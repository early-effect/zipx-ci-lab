package lab.publish

import heddle.client.Client
import heddle.http.Method
import zio.*

import java.nio.charset.StandardCharsets

/** The wires a publish uses, checked with heddle's own client before sbt or Docker run. */
object Protocol:
  private val Jar = "rocks/earlyeffect/lab/probe/0.0.1/probe-0.0.1.jar"
  private val Snap = "rocks/earlyeffect/lab/probe/0.0.1-SNAPSHOT/probe-0.0.1-SNAPSHOT.jar"
  private val Image = "lab-proof"

  def run(world: World, checks: Ref[List[Check]]): ZIO[Client, ProofError, Unit] =
    val maven = world.bound.maven.base
    val auth  = Http.labBasic
    for
      _ <- anonymous(world, checks, maven)
      _ <- rejected(world, checks, maven)
      _ <- checksumAndImmutable(world, checks, maven, auth)
      _ <- snapshotReplaces(world, checks, maven, auth)
      _ <- bearerReads(world, checks, maven)
      _ <- docker(world, checks)
      _ <- Http.send(Method.POST, s"$maven/zipx/reset", Chunk.empty).flatMap(Http.status(_, 204, "reset"))
      _ <- gate(checks, "ledger-reset", true, "cleared")
    yield ()

  private def anonymous(world: World, checks: Ref[List[Check]], maven: String): ZIO[Client, ProofError, Unit] =
    for
      res <- Http.send(Method.PUT, s"$maven/releases/$Jar", bytes("nope"))
      _   <- Http.status(res, 401, "anonymous put")
      key <- world.ledger.file(s"releases/$Jar")
      _   <- gate(checks, "maven-anonymous", key.isEmpty, "401 and no stored coordinate")
    yield ()

  private def rejected(world: World, checks: Ref[List[Check]], maven: String): ZIO[Client, ProofError, Unit] =
    for
      res <- Http.send(Method.PUT, s"$maven/releases/$Jar", bytes("nope"), "Authorization" -> Http.basic("zipx", "wrong"))
      _   <- Http.status(res, 401, "rejected put")
      key <- world.ledger.file(s"releases/$Jar")
      _   <- gate(checks, "maven-rejected", key.isEmpty, "wrong password is 401 and stores nothing")
    yield ()

  private def checksumAndImmutable(
      world: World,
      checks: Ref[List[Check]],
      maven: String,
      auth: String,
  ): ZIO[Client, ProofError, Unit] =
    val body = bytes("first-release")
    val sha  = Ledger.sha1(body)
    val url  = s"$maven/releases/$Jar"
    for
      put <- Http.send(Method.PUT, url, body, "Authorization" -> auth).flatMap(Http.status(_, 201, "release put"))
      _   <- Http
        .send(Method.PUT, s"$url.sha1", bytes(s"$sha\n"), "Authorization" -> auth)
        .flatMap(Http.status(_, 201, "sha1 put"))
      got <- Http.send(Method.GET, url, Chunk.empty, "Authorization" -> auth).flatMap(Http.status(_, 200, "release get"))
      _   <- gate(checks, "maven-checksum", got.body.strict.contains(body), "sha1 sidecar accepted and the jar reads back")
      _   <- Http
        .send(Method.PUT, url, bytes("second-release"), "Authorization" -> auth)
        .flatMap(Http.status(_, 409, "release overwrite"))
      again <- Http.send(Method.GET, url, Chunk.empty, "Authorization" -> auth).flatMap(Http.status(_, 200, "release get after 409"))
      _     <- gate(checks, "maven-release-409", again.body.strict.contains(body) && put.status.code == 201, "second put is 409 and the first digest stays")
    yield ()

  private def snapshotReplaces(
      world: World,
      checks: Ref[List[Check]],
      maven: String,
      auth: String,
  ): ZIO[Client, ProofError, Unit] =
    val url = s"$maven/snapshots/$Snap"
    val next = bytes("snapshot-two")
    for
      _   <- Http.send(Method.PUT, url, bytes("snapshot-one"), "Authorization" -> auth).flatMap(Http.status(_, 201, "snapshot put"))
      _   <- Http.send(Method.PUT, url, next, "Authorization" -> auth).flatMap(Http.status(_, 201, "snapshot replace"))
      got <- Http.send(Method.GET, url, Chunk.empty, "Authorization" -> auth).flatMap(Http.status(_, 200, "snapshot get"))
      _   <- gate(checks, "maven-snapshot-replace", got.body.strict.contains(next), "a second snapshot put replaces the bytes")
    yield ()

  private def bearerReads(world: World, checks: Ref[List[Check]], maven: String): ZIO[Client, ProofError, Unit] =
    val url = s"$maven/snapshots/$Snap"
    for
      got <- Http.send(Method.GET, url, Chunk.empty, "Authorization" -> Http.labBearer).flatMap(Http.status(_, 200, "bearer get"))
      _   <- gate(checks, "maven-bearer-get", got.body.strict.contains(bytes("snapshot-two")), "metadata read accepts Bearer")
    yield ()

  private def docker(world: World, checks: Ref[List[Check]]): ZIO[Client, ProofError, Unit] =
    val base = world.bound.imageA.base
    val auth = "Authorization" -> Http.labBasic
    val body = bytes("layer")
    val hex  = Ledger.sha256(body)
    val digest = s"sha256:$hex"
    for
      anon <- Http.send(Method.GET, s"$base/v2/", Chunk.empty).flatMap(Http.status(_, 401, "docker ping"))
      ping <- Http.send(Method.GET, s"$base/v2/", Chunk.empty, auth).flatMap(Http.status(_, 200, "docker ping authed"))
      ver  <- Http.header(ping, "Docker-Distribution-Api-Version", "docker ping")
      _    <- gate(checks, "docker-ping", anon.status.code == 401 && ver == "registry/2.0", "challenge then registry/2.0")
      open <- Http
        .send(Method.POST, s"$base/v2/$Image/blobs/uploads/", Chunk.empty, auth)
        .flatMap(Http.status(_, 202, "open upload"))
      loc  <- Http.header(open, "Location", "open upload")
      _    <- Http.send(Method.PATCH, loc, body, auth).flatMap(Http.status(_, 202, "patch upload"))
      done <- Http
        .send(Method.PUT, s"$loc?digest=$digest", Chunk.empty, auth)
        .flatMap(Http.status(_, 201, "finish upload"))
      head <- Http
        .send(Method.HEAD, s"$base/v2/$Image/blobs/$digest", Chunk.empty, auth)
        .flatMap(Http.status(_, 200, "blob head"))
      len  <- Http.header(head, "Content-Length", "blob head")
      got  <- Http.header(head, "Docker-Content-Digest", "blob head")
      missing <- Http
        .send(Method.GET, s"$base/v2/$Image/blobs/$digest", Chunk.empty, auth)
        .flatMap(Http.status(_, 404, "blob get"))
      _ <- gate(
        checks,
        "docker-upload",
        len == body.length.toString && got == digest && missing.status.code == 404 && done.status.code == 201,
        "chunked upload, head keeps the size, get of a discarded blob is 404",
      )
      mono <- Http
        .send(Method.POST, s"$base/v2/$Image/blobs/uploads/?digest=$digest", body, auth)
        .flatMap(Http.status(_, 201, "monolithic"))
      mount <- Http
        .send(Method.POST, s"$base/v2/$Image/blobs/uploads/?mount=sha256:${"ab" * 32}", Chunk.empty, auth)
        .flatMap(Http.status(_, 202, "missing mount"))
      _ <- gate(checks, "docker-mount-miss", mono.status.code == 201 && mount.status.code == 202, "a missing mount starts an upload")
      manifest = bytes("""{"schemaVersion":2,"mediaType":"application/vnd.docker.distribution.manifest.v2+json","layers":[]}""")
      put <- Http
        .send(
          Method.PUT,
          s"$base/v2/$Image/manifests/proof",
          manifest,
          auth,
          "Content-Type" -> "application/vnd.docker.distribution.manifest.v2+json",
        )
        .flatMap(Http.status(_, 201, "manifest put"))
      man <- Http.header(put, "Docker-Content-Digest", "manifest put")
      listed <- Http
        .send(Method.GET, s"$base/v2/$Image/tags/list", Chunk.empty, auth)
        .flatMap(Http.status(_, 200, "tags"))
      _ <- gate(checks, "docker-manifest", listed.body.text.exists(_.contains("proof")) && man.startsWith("sha256:"), "tag list names the pushed tag")
      other <- Http
        .send(Method.GET, s"${world.bound.imageC.base}/v2/$Image/tags/list", Chunk.empty, auth)
        .flatMap(Http.status(_, 200, "unlisted tags"))
      _ <- gate(checks, "docker-unlisted", other.body.text.exists(_.contains(""""tags":[]""")), "an authority with no push has an empty tag list")
    yield ()

  private def gate(checks: Ref[List[Check]], name: String, ok: Boolean, detail: String): IO[ProofError, Unit] =
    checks.update(_ :+ Check(name, ok, detail)) *> ZIO.unlessDiscard(ok)(ZIO.fail(ProofError.Failed(name, detail)))

  private def bytes(text: String): Chunk[Byte] = Chunk.fromArray(text.getBytes(StandardCharsets.UTF_8))
end Protocol
