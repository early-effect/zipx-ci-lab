package lab.publish

import heddle.client.Client
import heddle.http.Method
import zio.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The publish claims, each read back from the ledger or from a second process. */
object Scenarios:
  private val Tag = "proof"
  private val Image = "lab-proof"

  def run(world: World, checks: Ref[List[Check]]): ZIO[Client, ProofError, Unit] =
    val lib = world.proof.resolve("modules/lib/src/main/scala/lab/proof/Lib.scala")
    for
      _ <- credentials(world, checks)
      _ <- world.ledger.reset
      _ <- SnapshotId.compileAndDirty(world, checks)
      _ <- publishSnapshots(world, checks)
      first <- ProofClone.head(world.proof)
      _ <- SnapshotId.shaAndPointer(world, checks, first)
      _ <- SnapshotId.immutableAndPointer(world, checks, first.take(12))
      _ <- SnapshotId.plainReleaseRejected(world, checks)
      _ <- SnapshotId.ivyDoesNotWin(world, checks, first.take(12))
      _ <- republishLib(world, checks, lib)
      second <- ProofClone.head(world.proof)
      _ <- SnapshotId.newer(world, checks, first, second)
      _ <- SnapshotId.pullRequest(world, checks, first, second)
      _ <- ProofClone.resetHard(world.proof)
      _ <- SnapshotId.releaseOrder(world, checks, first.take(12), released = false)
      _ <- publishLibs(world, checks)
      _ <- SnapshotId.releaseOrder(world, checks, first.take(12), released = true)
      _ <- shadowRefuses(world, checks)
      _ <- refusedRefs(world, checks)
      _ <- consumers(world, checks, "consumer-release", world.bound.maven.releases, "0.1.0", 9)
      _ <- consumers(world, checks, "consumer-snapshot", world.bound.maven.snapshots, SnapshotId.stored(first.take(12)), 8)
      models <- jarDigest(world, "snapshots", "models_3")
      release <- jarDigest(world, "releases", "lib_3")
      _ <- consumers(world, checks, "consumer-snapshot-replaced", world.bound.maven.snapshots, SnapshotId.stored(first.take(12)), 8)
      _ <- consumers(world, checks, "consumer-snapshot-new-sha", world.bound.maven.snapshots, SnapshotId.stored(second.take(12)), 9)
      _ <- consumers(world, checks, "consumer-release-stable", world.bound.maven.releases, "0.1.0", 9)
      modelsAfter <- jarDigest(world, "snapshots", "models_3")
      _ <- gate(
        checks,
        "unaffected-models",
        models.isDefined && models == modelsAfter,
        models.fold("models snapshot jar was not stored")(d => s"digest $d"),
      )
      _ <- releaseAgain(world, checks, release)
      _ <- publishLegacy(world, checks, release)
      _ <- images(world, checks)
      _ <- promote(world, checks)
    yield ()

  private def credentials(world: World, checks: Ref[List[Check]]): ZIO[Any, ProofError, Unit] =
    val proof = world.proof
    for
      before <- world.ledger.all
      unset <- Machine.sbt(
        proof,
        List("zipxRelease", "libs"),
        world.env("basic"),
        unset = List("ZIPX_PROOF_PASSWORD", "ZIPX_PROOF_TOKEN"),
      )
      mid <- world.ledger.all
      _ <- gate(
        checks,
        "credentials-missing",
        unset.exit != 0 && noNewCoordinates(before, mid) && credentialFailure(unset.tail),
        unset.tail,
      )
      wrong <- Machine.sbt(
        proof,
        List("zipxRelease", "libs"),
        world.env("basic").updated("ZIPX_PROOF_PASSWORD", "wrong").updated("ZIPX_PROOF_TOKEN", "wrong"),
      )
      after <- world.ledger.all
      _ <- gate(
        checks,
        "credentials-rejected",
        wrong.exit != 0 && noNewCoordinates(mid, after) && credentialFailure(wrong.tail),
        wrong.tail,
      )
    yield ()

  private def publishSnapshots(world: World, checks: Ref[List[Check]]): ZIO[Any, ProofError, Unit] =
    for
      ran <- Machine.sbt(world.proof, List("zipxSnapshotPublish"), world.env("bearer"))
      _   <- gate(checks, "snapshot-publish", ran.exit == 0, if ran.exit == 0 then "exit 0" else ran.tail)
      keys <- world.ledger.fileKeys
      facts <- world.ledger.all
      snaps = mainJars(keys, "snapshots")
      releases = mainJars(keys, "releases")
      _ <- gate(
        checks,
        "snapshot-repo-only",
        snaps.exists(_.contains("sjs")) && snaps.exists(_.contains("/models_3/")) && snaps.exists(_.contains("/lib_3/")) &&
          releases.isEmpty,
        s"snapshots ${snaps.mkString(", ")}",
      )
      _ <- gate(
        checks,
        "bearer-wires",
        facts.exists(f => f.auth == "basic:token" && f.method == "PUT" && f.status == 201) &&
          facts.exists(f => f.auth == "bearer" && f.method == "GET"),
        "upload is Basic user token, metadata read is Bearer",
      )
    yield ()

  private def publishLibs(world: World, checks: Ref[List[Check]]): ZIO[Any, ProofError, Unit] =
    for
      ran <- Machine.sbt(world.proof, List("zipxRelease", "libs"), world.env("basic"))
      _   <- gate(checks, "release-libs", ran.exit == 0, if ran.exit == 0 then "exit 0" else ran.tail)
      keys <- world.ledger.fileKeys
      facts <- world.ledger.all
      released = mainJars(keys, "releases")
      _ <- gate(
        checks,
        "cross-build",
        released.exists(_.contains("sjs")) && released.exists(_.contains("/models_3/")) && released.exists(_.contains("/lib_3/")),
        released.mkString(", "),
      )
      _ <- gate(checks, "release-omits-legacy", !released.exists(_.contains("/legacy_")), released.mkString(", "))
      _ <- gate(
        checks,
        "basic-wires",
        facts.exists(f => f.repository == "releases" && f.auth == "basic:zipx" && f.method == "PUT" && f.status == 201),
        "release upload is Basic user zipx",
      )
    yield ()

  /** After libs 0.1.0 is on the release repository, another snapshot of that number must not upload. The proof clone
    * has no `libs/v0.1.0` tag, so the publish cannot prove the tree is clean. A missing token must fail the same way,
    * before any PUT: an anonymous 401 is not "unreleased".
    */
  private def shadowRefuses(world: World, checks: Ref[List[Check]]): ZIO[Any, ProofError, Unit] =
    for
      before <- jarDigest(world, "snapshots", "lib_3")
      ran    <- Machine.sbt(world.proof, List("zipxSnapshotPublish"), world.env("basic"))
      after  <- jarDigest(world, "snapshots", "lib_3")
      text = ran.tail
      refused = ran.exit != 0 && text.contains("sbt zipxModverBump") &&
        (text.contains("is not in this clone") || text.contains("shadowed"))
      stable = before.isDefined && before == after
      _ <- gate(
        checks,
        "shadow-refuses",
        refused && stable,
        if refused && stable then "released snapshot unchanged" else text,
      )
      putsBefore <- world.ledger.all
      unset <- Machine.sbt(
        world.proof,
        List("zipxSnapshotPublish"),
        world.env("basic"),
        unset = List("ZIPX_PROOF_PASSWORD", "ZIPX_PROOF_TOKEN"),
      )
      putsAfter <- world.ledger.all
      _ <- gate(
        checks,
        "shadow-no-credentials",
        unset.exit != 0 && noNewCoordinates(putsBefore, putsAfter) && credentialFailure(unset.tail),
        unset.tail,
      )
    yield ()

  private def refusedRefs(world: World, checks: Ref[List[Check]]): ZIO[Any, ProofError, Unit] =
    for
      before <- world.ledger.all
      empty <- Machine.sbt(world.proof, List("zipxRelease"), world.env("basic"))
      unknown <- Machine.sbt(world.proof, List("zipxRelease", "nosuch"), world.env("basic"))
      after <- world.ledger.all
      _ <- gate(checks, "release-empty", empty.exit != 0 && empty.tail.contains("is not a tag"), empty.tail)
      _ <- gate(
        checks,
        "release-unknown",
        unknown.exit != 0 && unknown.tail.contains("is not in the catalog"),
        unknown.tail,
      )
      _ <- gate(checks, "refused-refs-write-nothing", noNewCoordinates(before, after), "no new coordinate")
    yield ()

  private def republishLib(world: World, checks: Ref[List[Check]], lib: Path): ZIO[Any, ProofError, Unit] =
    ZIO.acquireReleaseWith(rewrite(lib))(original => ZIO.attemptBlocking(Files.writeString(lib, original)).orDie) { _ =>
      for
        before <- jarDigest(world, "snapshots", "lib_3")
        _ <- ProofClone.commitAll(world.proof, "change lib")
        ran <- Machine.sbt(world.proof, List("zipxSnapshotPublish"), world.env("basic"))
        keys <- world.ledger.fileKeys
        digests <- ZIO.foreach(libJars(keys))(key => world.ledger.file(key).map(_.map(Ledger.sha256)))
        moved = ran.exit == 0 && before.isDefined && digests.flatten.exists(digest => !before.contains(digest))
        _ <- gate(
          checks,
          "snapshot-selective",
          moved,
          if moved then s"new lib bytes besides ${before.getOrElse("missing")}"
          else s"exit ${ran.exit} before ${before.getOrElse("missing")} digests ${digests.flatten.mkString(",")}\n${ran.tail}",
        )
      yield ()
    }

  private def releaseAgain(world: World, checks: Ref[List[Check]], before: Option[String]): ZIO[Any, ProofError, Unit] =
    for
      ran <- Machine.sbt(world.proof, List("zipxRelease", "libs"), world.env("basic"))
      after <- jarDigest(world, "releases", "lib_3")
      _ <- gate(
        checks,
        "release-twice",
        ran.exit != 0 && ran.tail.toLowerCase.contains("already released") && before.isDefined && before == after,
        ran.tail,
      )
    yield ()

  private def publishLegacy(world: World, checks: Ref[List[Check]], libsDigest: Option[String]): ZIO[Any, ProofError, Unit] =
    for
      ran <- Machine.sbt(world.proof, List("zipxRelease", "legacy"), world.env("basic"))
      keys <- world.ledger.fileKeys
      after <- jarDigest(world, "releases", "lib_3")
      legacy = mainJars(keys, "releases").filter(_.contains("/legacy_"))
      _ <- gate(checks, "release-legacy", ran.exit == 0, if ran.exit == 0 then "exit 0" else ran.tail)
      _ <- gate(
        checks,
        "legacy-binary",
        legacy.nonEmpty && legacy.forall(_.contains("_2.13")) && !legacy.exists(_.contains("_3")),
        legacy.mkString(", "),
      )
      _ <- gate(checks, "legacy-leaves-libs", libsDigest.isDefined && libsDigest == after, libsDigest.getOrElse("missing"))
    yield ()

  private def images(world: World, checks: Ref[List[Check]]): ZIO[Any, ProofError, Unit] =
    val hostA = s"${Registry.DockerRegistryHost}:${world.bound.imageA.port}"
    val hostB = s"${Registry.DockerRegistryHost}:${world.bound.imageB.port}"
    for
      _ <- baseImage(checks)
      _ <- dockerLogin(checks, hostA)
      _ <- dockerLogin(checks, hostB)
      ran <- Machine.sbt(world.proof, List("image/Docker/publish"), world.env("basic"))
      _ <- gate(checks, "image-publish", ran.exit == 0 && !untrusted(ran.tail), if ran.exit == 0 then "exit 0" else trustHint(ran.tail))
      keys <- world.ledger.fileKeys
      _ <- gate(checks, "image-not-maven", !keys.exists(_.contains("/image_")), "no Maven coordinate for the image module")
      a <- world.ledger.tagList(s"image-a/$Image")
      b <- world.ledger.tagList(s"image-b/$Image")
      c <- world.ledger.tagList(s"image-c/$Image")
      _ <- gate(
        checks,
        "image-tags",
        a.get(Tag).isDefined && a.get(Tag) == b.get(Tag) && c.isEmpty,
        s"A ${a.get(Tag).getOrElse("missing")} B ${b.get(Tag).getOrElse("missing")}",
      )
    yield ()

  private def promote(world: World, checks: Ref[List[Check]]): ZIO[Client, ProofError, Unit] =
    val auth = "Authorization" -> Http.labBasic
    val from = s"${world.bound.imageA.base}/v2/$Image/manifests/$Tag"
    val to   = s"${world.bound.imageB.base}/v2/$Image/manifests/promoted"
    for
      got <- Http.send(Method.GET, from, Chunk.empty, auth).flatMap(Http.status(_, 200, "manifest get"))
      body <- ZIO.fromOption(got.body.strict).orElseFail(ProofError.Protocol("manifest get returned no body"))
      digest <- Http.header(got, "Docker-Content-Digest", "manifest get")
      media = got.header("Content-Type").getOrElse("application/vnd.docker.distribution.manifest.v2+json")
      before <- world.ledger.all
      _ <- Http
        .send(Method.PUT, to, body, auth, "Content-Type" -> media)
        .flatMap(Http.status(_, 201, "manifest promote"))
      after <- world.ledger.all
      tags <- world.ledger.tagList(s"image-b/$Image")
      _ <- gate(
        checks,
        "promote-retag",
        blobUploads(before) == blobUploads(after) && tags.get("promoted").contains(digest) && tags.get(Tag).contains(digest),
        s"digest $digest",
      )
    yield ()

  private def consumers(
      world: World,
      checks: Ref[List[Check]],
      name: String,
      repo: String,
      version: String,
      expected: Int,
  ): ZIO[Any, ProofError, Unit] =
    ZIO.scoped(ZIO.acquireRelease(
      ZIO
        .attemptBlocking(Files.createTempDirectory("zipx-proof-consumer"))
        .mapError(err => ProofError.Failed(name, String.valueOf(err.getMessage)))
    )(dir => ZIO.attemptBlocking(deleteTree(dir)).ignore).flatMap { dir =>
      for
        _ <- ZIO.attemptBlocking(writeConsumer(dir, slash(repo), version)).mapError(e => ProofError.Failed(name, String.valueOf(e.getMessage)))
        ivy = dir.resolve("ivy")
        cache = dir.resolve("cache")
        opts = world.trustOpts + s" -Dsbt.ivy.home=$ivy"
        env = world.env("basic").updated("SBT_OPTS", opts).updated("JAVA_OPTS", opts).updated("COURSIER_CACHE", cache.toString)
        ran <- Machine.sbt(dir, List("run"), env)
        _ <- gate(checks, name, ran.exit == 0 && ran.tail.contains(s"viaModels=$expected"), ran.tail)
      yield ()
    })

  private def baseImage(checks: Ref[List[Check]]): IO[ProofError, Unit] =
    ZIO.scoped(ZIO.acquireRelease(
      ZIO
        .attemptBlocking(Files.createTempDirectory("zipx-proof-base"))
        .mapError(err => ProofError.Failed("base-image", String.valueOf(err.getMessage)))
    )(dir => ZIO.attemptBlocking(deleteTree(dir)).ignore).flatMap { dir =>
      for
        _ <- ZIO.attemptBlocking {
          Files.writeString(dir.resolve("Dockerfile"), "FROM scratch\nCOPY marker /marker\n")
          Files.writeString(dir.resolve("marker"), "zipx-proof\n")
        }.mapError(e => ProofError.Failed("base-image", String.valueOf(e.getMessage)))
        ran <- Machine.run(dir, List("docker", "build", "-t", "zipx-lab-base:local", "."))
        _ <- gate(checks, "base-image", ran.exit == 0, if ran.exit == 0 then "zipx-lab-base:local" else ran.tail)
      yield ()
    })

  private def dockerLogin(checks: Ref[List[Check]], host: String): IO[ProofError, Unit] =
    Machine
      .run(
        Path.of(sys.props("user.dir")),
        List("docker", "login", host, "-u", Credentials.Lab.user, "--password-stdin"),
        stdin = Some(Credentials.Lab.password),
      )
      .flatMap { ran =>
        gate(checks, s"docker-login-$host", ran.exit == 0, if ran.exit == 0 then "ok" else trustHint(ran.tail))
      }

  private def rewrite(lib: Path): IO[ProofError, String] =
    ZIO.attemptBlocking {
      val original = Files.readString(lib)
      val next     = original.replace("Models.answer + 1", "Models.answer + 2")
      if next == original then throw new RuntimeException(s"$lib has no Models.answer + 1")
      Files.writeString(lib, next)
      original
    }.mapError(e => ProofError.Failed("snapshot-selective", String.valueOf(e.getMessage)))

  private def writeConsumer(dir: Path, repo: String, version: String): Unit =
    Files.createDirectories(dir.resolve("project"))
    Files.createDirectories(dir.resolve("src/main/scala/check"))
    Files.writeString(dir.resolve("project/build.properties"), "sbt.version=2.1.0-M3\n")
    Files.writeString(
      dir.resolve("build.sbt"),
      s"""ThisBuild / scalaVersion := "3.9.0"
         |ThisBuild / resolvers += "proof" at "$repo"
         |ThisBuild / credentials += Credentials("127.0.0.1", "127.0.0.1", "zipx", "${Credentials.Lab.password}")
         |ThisBuild / libraryDependencies += "rocks.earlyeffect.lab" %% "lib" % "$version"
         |fork := true
         |""".stripMargin,
    )
    Files.writeString(
      dir.resolve("src/main/scala/check/Check.scala"),
      """package check
        |
        |@main def check(): Unit =
        |  println("viaModels=" + lab.proof.Lib.viaModels)
        |""".stripMargin,
    )

  private def gate(checks: Ref[List[Check]], name: String, ok: Boolean, detail: String): IO[ProofError, Unit] =
    checks.update(_ :+ Check(name, ok, detail)) *> ZIO.unlessDiscard(ok)(ZIO.fail(ProofError.Failed(name, detail)))

  private def credentialFailure(tail: String): Boolean =
    val text = tail.toLowerCase
    text.contains("no credentials") || text.contains("cannot tell whether") || text.contains("http 401")

  private def noNewCoordinates(before: Chunk[Fact], after: Chunk[Fact]): Boolean =
    coordinates(after) == coordinates(before)

  private def coordinates(facts: Chunk[Fact]): Int =
    facts.count(f => f.method == "PUT" && (f.status == 201 || f.status == 200))

  private def blobUploads(facts: Chunk[Fact]): Int =
    facts.count(f => f.path.contains("/blobs/uploads/") && f.status == 201 && (f.method == "PUT" || f.method == "POST"))

  private def libJars(keys: List[String]): List[String] =
    mainJars(keys, "snapshots").filter(_.contains("/lib_3/"))

  private def mainJars(keys: List[String], repo: String): List[String] =
    keys.filter { key =>
      key.startsWith(s"$repo/") && key.endsWith(".jar") && !key.endsWith("-sources.jar") && !key.endsWith("-javadoc.jar")
    }

  /** A snapshot publish keeps every timestamped jar. The coordinate a resolver reads is the newest unique version. */
  private def jarDigest(world: World, repo: String, artifact: String): UIO[Option[String]] =
    world.ledger.fileKeys.flatMap { keys =>
      newest(mainJars(keys, repo).filter(_.contains(s"/$artifact/"))) match
        case None      => ZIO.succeed(None)
        case Some(key) => world.ledger.file(key).map(_.map(Ledger.sha256))
    }

  private val UniqueSnapshot = """(\d{8}\.\d{6})-(\d+)\.jar$""".r

  private def newest(keys: List[String]): Option[String] =
    keys.maxByOption(snapshotRank)

  private def snapshotRank(key: String): (String, Int) =
    val name = key.split('/').lastOption.getOrElse(key)
    UniqueSnapshot.findFirstMatchIn(name) match
      case Some(hit) => (hit.group(1), hit.group(2).toIntOption.getOrElse(0))
      case None      => ("", 0)

  private def untrusted(text: String): Boolean =
    val lower = text.toLowerCase
    lower.contains("x509") || lower.contains("unknown authority")

  private def trustHint(tail: String): String =
    if untrusted(tail) then
      "Docker does not trust the lab CA yet. Restart Docker Desktop once so it loads ~/.docker/certs.d, then re-run this proof.\n" + tail
    else tail

  private def slash(url: String): String = if url.endsWith("/") then url else s"$url/"

  private def deleteTree(root: Path): Unit =
    if Files.exists(root) then
      val stream = Files.walk(root)
      try stream.iterator.asScala.toList.reverse.foreach(Files.deleteIfExists)
      finally stream.close()
end Scenarios
