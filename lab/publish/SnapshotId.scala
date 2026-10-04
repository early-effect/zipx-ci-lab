package lab.publish

import heddle.client.Client
import heddle.http.Method
import zio.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The snapshot-id promises. Each one is a gate. A second process, or the ledger, reads the coordinate back. */
object SnapshotId:
  private val Line = "0.1.0"

  def compileAndDirty(world: World, checks: Ref[List[Check]]): ZIO[Any, ProofError, Unit] =
    val proof = world.proof
    for
      version <- Machine.sbt(proof, List("show lib/version"), world.env("basic"))
      snap <- Machine.sbt(proof, List("show lib/isSnapshot"), world.env("basic"))
      _ <- gate(checks, "compile-stable", version.exit == 0 && version.tail.contains(s"$Line-ci") && truthy(snap.tail), version.tail)
      _ <- ZIO.attemptBlocking(Files.writeString(proof.resolve("notes.txt"), "not a ship source\n")).mapError(boom("compile-stable", _))
      _ <- ProofClone.commitAll(proof, "notes")
      again <- Machine.sbt(proof, List("show lib/version"), world.env("basic"))
      _ <- gate(checks, "compile-stable-second-commit", again.exit == 0 && again.tail.contains(s"$Line-ci"), again.tail)
      released <- Machine.sbt(proof, List("-Dzipx.session=release", "show lib/version"), world.env("basic"))
      releasedSnap <- Machine.sbt(proof, List("-Dzipx.session=release", "show lib/isSnapshot"), world.env("basic"))
      _ <- gate(
        checks,
        "compile-release-session",
        released.exit == 0 && released.tail.contains(Line) && !released.tail.contains(s"$Line-ci") && falsy(releasedSnap.tail),
        released.tail + "\n" + releasedSnap.tail,
      )
      before <- world.ledger.all
      _ <- ZIO.attemptBlocking(Files.writeString(proof.resolve("modules/lib/src/main/scala/lab/proof/Lib.scala"), extra, java.nio.file.StandardOpenOption.APPEND)).mapError(boom("dirty-local", _))
      refused <- Machine.sbt(proof, List("zipxSnapshotPublish"), world.env("basic"))
      mid <- world.ledger.all
      _ <- gate(
        checks,
        "dirty-registry-refuses",
        refused.exit != 0 && refused.tail.contains("zipxSnapshotPublish local") && coordinates(mid) == coordinates(before),
        refused.tail,
      )
      ivy <- ZIO.attemptBlocking(Files.createTempDirectory("zipx-proof-ivy")).mapError(boom("dirty-local", _))
      opts = world.trustOpts + s" -Dsbt.ivy.home=$ivy"
      local <- Machine.sbt(proof, List("zipxSnapshotPublish", "local"), world.env("basic").updated("SBT_OPTS", opts).updated("JAVA_OPTS", opts))
      names <- ZIO.attemptBlocking(ivyNames(ivy)).mapError(boom("dirty-local", _))
      _ <- gate(
        checks,
        "dirty-local-publish",
        local.exit == 0 && names.exists(_.contains("+")) && names.forall(!commitDir.matches(_)),
        names.mkString(", "),
      )
      _ <- ProofClone.resetHard(proof)
    yield ()

  def publishedSha(world: World, artifact: String): UIO[Option[String]] =
    world.ledger.fileKeys.map { keys =>
      keys.collectFirst {
        case key if key.contains(s"/$artifact/") && commitDir.matches(versionOf(key)) => versionOf(key).stripPrefix(s"$Line-")
      }
    }

  def pointerNames(world: World, artifact: String, fullSha: String): UIO[Boolean] =
    world.ledger.fileKeys.flatMap { keys =>
      val poms = keys.filter(key => key.contains(s"/$artifact/$Line-SNAPSHOT/") && key.endsWith(".pom"))
      val unique = poms.filter(_.matches(""".*\d{8}\.\d{6}-\d+\.pom"""))
      val chosen = if unique.nonEmpty then Some(unique.max) else poms.maxOption
      chosen match
        case None => ZIO.succeed(false)
        case Some(key) =>
          world.ledger.file(key).map(_.exists { body =>
            String(body.toArray, StandardCharsets.UTF_8).contains(s"<zipx.snapshot.sha>$fullSha</zipx.snapshot.sha>")
          })
    }

  def shaAndPointer(world: World, checks: Ref[List[Check]], full: String): ZIO[Any, ProofError, Unit] =
    val abbrev = full.take(12)
    for
      keys <- world.ledger.fileKeys
      snaps = keys.filter(_.startsWith("snapshots/"))
      releases = keys.filter(_.startsWith("releases/"))
      hasSha = snaps.exists(key => versionOf(key) == s"$Line-$abbrev")
      _ <- gate(checks, "ci-sha-published", hasSha && releases.isEmpty, snaps.filter(_.endsWith(".jar")).mkString(", "))
      named <- pointerNames(world, "lib_3", full)
      _ <- gate(checks, "pointer-names-sha", named, s"pointer POM names $full")
      before = snaps.map(versionOf).toSet
      again <- Machine.sbt(world.proof, List("zipxSnapshotPublish"), world.env("basic"))
      afterKeys <- world.ledger.fileKeys
      after = afterKeys.filter(_.startsWith("snapshots/")).map(versionOf).toSet
      _ <- gate(checks, "republish-same-commit", again.exit == 0 && after == before, after.mkString(", "))
    yield ()

  def immutableAndPointer(world: World, checks: Ref[List[Check]], abbrev: String): ZIO[Client, ProofError, Unit] =
    val version = s"$Line-$abbrev"
    for
      first <- consumerRun(world, version)
      _ <- gate(
        checks,
        "immutable-resolve",
        first.exit == 0 && first.tail.contains("viaModels=8") && first.tail.contains("changing=false"),
        first.tail,
      )
      getsBefore <- world.ledger.all
      _ <- world.ledger.deleteContaining("maven-metadata.xml")
      _ <- consumers(world, checks, "immutable-resolve-again", version, 8)
      getsAfter <- world.ledger.all
      metadataGets = getsAfter.drop(getsBefore.length).exists(f => f.method == "GET" && f.path.contains("maven-metadata.xml"))
      _ <- gate(checks, "immutable-no-metadata", !metadataGets, "second resolve did not GET maven-metadata.xml")
      refused <- pointerUpdate(world)
      _ <- gate(checks, "pointer-not-a-dependency", refused.exit != 0 && refused.tail.contains("zipxSnapshotStatus"), refused.tail)
    yield ()

  def releaseOrder(world: World, checks: Ref[List[Check]], abbrev: String, released: Boolean): ZIO[Any, ProofError, Unit] =
    ZIO.scoped {
      ZIO.acquireRelease(downstream(world, abbrev))(dir => ZIO.attemptBlocking(delete(dir)).ignore).flatMap { dir =>
        for
          plan <- Machine.sbt(dir, List("zipxReleasePlan"), world.env("basic"))
          pin <- Machine.sbt(dir, List("zipxPinRelease", "lib"), world.env("basic"))
          text = Files.readString(dir.resolve("project/ZipxVersions.scala"))
          _ <-
            if !released then
              gate(
                checks,
                "release-plan-blocked",
                plan.exit == 0 && plan.tail.contains(s"$Line-$abbrev") && plan.tail.contains("all refuses"),
                plan.tail,
              ) *> gate(
                checks,
                "pin-release-missing",
                pin.exit != 0 && pin.tail.contains("not on the release repository"),
                pin.tail,
              )
            else
              gate(
                checks,
                "pin-release",
                pin.exit == 0 && text.contains(""""0.1.0"""") && !text.contains(abbrev),
                text,
              ) *> Machine.sbt(dir, List("reload"), world.env("basic")).flatMap { reloaded =>
                Machine.sbt(dir, List("zipxReleasePlan"), world.env("basic")).flatMap { ready =>
                  gate(checks, "release-plan-ready", reloaded.exit == 0 && ready.exit == 0 && ready.tail.contains("Ready."), ready.tail)
                }
              }
        yield ()
      }
    }

  def pullRequest(world: World, checks: Ref[List[Check]], first: String, pointer: String): ZIO[Any, ProofError, Unit] =
    ZIO.scoped {
      ZIO.acquireRelease(cloneAt(world.proof, "HEAD~1"))(dir => ZIO.attemptBlocking(delete(dir)).ignore).flatMap { dir =>
        for
          ran <- Machine.sbt(dir, List("zipxSnapshotPublish", "pr", "7"), world.env("basic"))
          named <- pointerNames(world, "lib_3", pointer)
          keys <- world.ledger.fileKeys
          uploaded = keys.exists(key => versionOf(key) == s"$Line-${first.take(12)}")
          _ <- gate(checks, "pr-does-not-move-pointer", ran.exit == 0 && uploaded && named, ran.tail)
        yield ()
      }
    }

  def ivyDoesNotWin(world: World, checks: Ref[List[Check]], abbrev: String): ZIO[Any, ProofError, Unit] =
    val version = s"$Line-$abbrev"
    for
      loopback <- jarDigest(world, "snapshots", "lib_3", version)
      ivy <- ZIO.attemptBlocking(Files.createTempDirectory("zipx-proof-ivy-ci")).mapError(boom("ivy-does-not-win", _))
      opts = world.trustOpts + s" -Dsbt.ivy.home=$ivy"
      local <- Machine.sbt(world.proof, List("lib/publishLocal"), world.env("basic").updated("SBT_OPTS", opts).updated("JAVA_OPTS", opts))
      ran <- consumerRun(world, version, Some(ivy))
      _ <- gate(
        checks,
        "ivy-does-not-win",
        local.exit == 0 && ran.exit == 0 && ran.tail.contains("viaModels=8") && loopback.isDefined,
        ran.tail,
      )
    yield ()

  def plainReleaseRejected(world: World, checks: Ref[List[Check]]): ZIO[Client, ProofError, Unit] =
    val url = s"${slash(world.bound.maven.snapshots)}rocks/earlyeffect/lab/lib_3/$Line/lib_3-$Line.jar"
    for
      before <- world.ledger.fileKeys
      res <- Http.send(Method.PUT, url, Chunk.fromArray("nope".getBytes(StandardCharsets.UTF_8)), "Authorization" -> Http.labBasic)
      after <- world.ledger.fileKeys
      _ <- gate(checks, "snapshot-repo-rejects-release", res.status.code == 400 && before == after, s"HTTP ${res.status.code}")
    yield ()

  private val extra = "\n// dirty local edit\n"

  private val commitDir = """\d+\.\d+\.\d+-[0-9a-f]{12}""".r

  private def truthy(tail: String): Boolean = tail.contains("true")
  private def falsy(tail: String): Boolean = tail.contains("false")

  private def versionOf(key: String): String =
    val parts = key.split('/').toList
    parts.lift(parts.length - 2).getOrElse("")

  private def ivyNames(root: Path): List[String] =
    if !Files.exists(root) then Nil
    else
      val stream = Files.walk(root)
      try stream.iterator.asScala.filter(path => Files.isDirectory(path)).map(_.getFileName.toString).toList
      finally stream.close()

  private def consumers(world: World, checks: Ref[List[Check]], name: String, version: String, expected: Int): ZIO[Any, ProofError, Unit] =
    consumerRun(world, version).flatMap { ran =>
      gate(checks, name, ran.exit == 0 && ran.tail.contains(s"viaModels=$expected"), ran.tail)
    }

  def newer(world: World, checks: Ref[List[Check]], previous: String, latest: String): ZIO[Any, ProofError, Unit] =
    ZIO.scoped {
      ZIO.acquireRelease(downstream(world, previous.take(12)))(dir => ZIO.attemptBlocking(delete(dir)).ignore).flatMap { dir =>
        val versions = dir.resolve("project/ZipxVersions.scala")
        for
          status <- Machine.sbt(dir, List("zipxSnapshotStatus", "lib"), world.env("basic"))
          before <- ZIO.attemptBlocking(Files.readString(versions)).mapError(boom("newer-snapshot", _))
          _ <- gate(
            checks,
            "status-names-newer",
            status.exit == 0 && status.tail.contains(latest.take(12)) && before.contains(previous.take(12)),
            status.tail,
          )
          advanced <- Machine.sbt(dir, List("zipxSnapshotAdvance", "lib"), world.env("basic"))
          after <- ZIO.attemptBlocking(Files.readString(versions)).mapError(boom("newer-snapshot", _))
          _ <- gate(
            checks,
            "advance-rewrites",
            advanced.exit == 0 && after.contains(latest.take(12)) && !after.contains(previous.take(12)),
            after,
          )
          ran <- consumerRun(world, s"$Line-${latest.take(12)}")
          _ <- gate(checks, "resolve-advanced-sha", ran.exit == 0 && ran.tail.contains("viaModels=9"), ran.tail)
        yield ()
      }
    }

  private def pointerUpdate(world: World): IO[ProofError, Machine.Ran] =
    ZIO.scoped {
      ZIO.acquireRelease(downstream(world, "SNAPSHOT"))(dir => ZIO.attemptBlocking(delete(dir)).ignore).flatMap { dir =>
        Machine.sbt(dir, List("update"), world.env("basic"))
      }
    }

  private def consumerRun(world: World, version: String, ivy: Option[Path] = None): IO[ProofError, Machine.Ran] =
    val made = ZIO.attemptBlocking(Files.createTempDirectory("zipx-proof-consumer")).mapError(boom("consumer", _))
    made.flatMap { dir =>
      ZIO.attemptBlocking(writeConsumer(dir, slash(world.bound.maven.snapshots), version)).mapError(boom("consumer", _)) *>
        {
          val home = ivy.getOrElse(dir.resolve("ivy"))
          val opts = world.trustOpts + s" -Dsbt.ivy.home=$home"
          val env  = world.env("basic").updated("SBT_OPTS", opts).updated("JAVA_OPTS", opts).updated("COURSIER_CACHE", dir.resolve("cache").toString)
          Machine.sbt(dir, List("pin; run"), env)
        }
    }

  private def downstream(world: World, abbrev: String): IO[ProofError, Path] =
    ZIO.attemptBlocking {
      val dir = Files.createTempDirectory("zipx-proof-downstream")
      val plugins = Files.readString(world.proof.resolve("project/plugins.sbt"))
      Files.createDirectories(dir.resolve("project"))
      Files.writeString(dir.resolve("project/plugins.sbt"), plugins)
      Files.writeString(dir.resolve("project/build.properties"), "sbt.version=2.1.0-M3\n")
      Files.writeString(
        dir.resolve("project/ZipxVersions.scala"),
        s"""import zipx.*
           |
           |object Downstream extends ZipxVersions:
           |  val sbt: SbtVersion = SbtVersion("2.1.0-M3")
           |  val scala: ScalaVersion = ScalaVersion("3.9.0")
           |  val upstream = Lib("rocks.earlyeffect.lab", "lib", "$Line-$abbrev")
           |  val client = Ship("client", "0.2.0")
           |""".stripMargin,
      )
      val snaps = slash(world.bound.maven.snapshots)
      val rels  = slash(world.bound.maven.releases)
      Files.writeString(
        dir.resolve("build.sbt"),
        s"""Downstream.settings
           |organization := "rocks.earlyeffect.lab"
           |lazy val client = project.settings(
           |  publish / skip := false,
           |  libraryDependencies ++= Downstream.deps(Downstream.upstream),
           |)
           |lazy val root = (project in file(".")).aggregate(client).settings(publish / skip := true)
           |LocalRootProject / zipxReleaseWorkflow := Some(ZipxMaven.releases(
           |  "$snaps",
           |  "$rels",
           |  username = EnvValue.plain("zipx"),
           |  password = secret"${"ZIPX_PROOF_PASSWORD"}",
           |))
           |""".stripMargin,
      )
      dir
    }.mapError(boom("downstream", _))

  private def cloneAt(source: Path, rev: String): IO[ProofError, Path] =
    ZIO.attemptBlocking(Files.createTempDirectory("zipx-proof-pr")).mapError(boom("pr-clone", _)).flatMap { dest =>
      Machine.run(source, List("git", "clone", "--local", source.toString, dest.toString)).flatMap { ran =>
        if ran.exit != 0 then ZIO.fail(ProofError.Command(ran.command, ran.exit, ran.tail))
        else Machine.run(dest, List("git", "checkout", rev)).flatMap { checked =>
          if checked.exit == 0 then ZIO.succeed(dest)
          else ZIO.fail(ProofError.Command(checked.command, checked.exit, checked.tail))
        }
      }
    }

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
         |lazy val pin = taskKey[Unit]("report whether the pin is changing")
         |pin := {
         |  val changing = libraryDependencies.value.exists(_.isChanging)
         |  streams.value.log.info(s"changing=$$changing")
         |}
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

  private def jarDigest(world: World, repo: String, artifact: String, version: String): UIO[Option[String]] =
    world.ledger.fileKeys.flatMap { keys =>
      keys.find(key => key.startsWith(s"$repo/") && key.contains(s"/$artifact/$version/") && key.endsWith(".jar") && !key.contains("-sources") && !key.contains("-javadoc")) match
        case None => ZIO.succeed(None)
        case Some(key) => world.ledger.file(key).map(_.map(Ledger.sha256))
    }

  private def gate(checks: Ref[List[Check]], name: String, ok: Boolean, detail: String): IO[ProofError, Unit] =
    checks.update(_ :+ Check(name, ok, detail)) *> ZIO.unlessDiscard(ok)(ZIO.fail(ProofError.Failed(name, detail)))

  private def coordinates(facts: Chunk[Fact]): Int =
    facts.count(f => f.method == "PUT" && (f.status == 201 || f.status == 200))

  private def slash(url: String): String = if url.endsWith("/") then url else s"$url/"

  private def boom(name: String, err: Throwable): ProofError =
    ProofError.Failed(name, String.valueOf(err.getMessage))

  private def delete(root: Path): Unit =
    if Files.exists(root) then
      val stream = Files.walk(root)
      try stream.iterator.asScala.toList.reverse.foreach(Files.deleteIfExists)
      finally stream.close()
end SnapshotId
