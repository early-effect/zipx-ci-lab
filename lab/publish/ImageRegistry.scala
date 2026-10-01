package lab.publish

import heddle.http.{Body, Method, Request, Response, Status}
import zio.*
import zio.json.*

import java.util.UUID

/** Docker Registry HTTP API v2, push side. Blob bodies are hashed and discarded. A manifest and its tags stay, which
  * is the location a push claims. GET of a blob is 404: the proof does not pull layers back.
  */
object ImageRegistry:
  private val Markers = Set("blobs", "manifests", "tags")

  def handle(ledger: Ledger, authority: String, fallback: String, req: Request): UIO[Response] =
    val base = req.header("Host").filter(_.nonEmpty) match
      case Some(host) => s"https://$host"
      case None       => fallback
    val segments = req.path.segments
    val pinging  =
      segments.length == 1 && segments.headOption.contains("v2") &&
        (req.method == Method.GET || req.method == Method.HEAD)
    if pinging then ping(ledger, authority, req)
    else
      route(segments) match
        case None =>
          ZIO.succeed(miss(req.method))
        case Some(call) =>
          req.body.collect.orDie.flatMap { body =>
            Credentials.Lab.accept(req.header("Authorization")) match
              case Left(denied) =>
                val who = req.header("Authorization").fold("anonymous")(_ => "rejected")
                note(ledger, authority, call.repository, req.method.render, call.path, None, 0L, who, 401).as(denied)
              case Right(auth) =>
                call.run(ledger, authority, base, req, body, auth)
          }

  private def ping(ledger: Ledger, authority: String, req: Request): UIO[Response] =
    Credentials.Lab.accept(req.header("Authorization")) match
      case Left(denied) =>
        val who = req.header("Authorization").fold("anonymous")(_ => "rejected")
        note(ledger, authority, "", req.method.render, "/v2/", None, 0L, who, 401).as(denied)
      case Right(auth) =>
        note(ledger, authority, "", req.method.render, "/v2/", None, 0L, auth, 200).as(distribution(Response.ok))

  private def route(segments: Chunk[String]): Option[Call] =
    if segments.headOption.contains("v2") then
      val rest = segments.drop(1)
      rest.zipWithIndex.collectFirst { case (segment, i) if Markers.contains(segment) => i } match
        case None => None
        case Some(at) =>
          val name = rest.take(at).mkString("/")
          val tail = rest.drop(at).toList
          if name.isEmpty then None
          else
            tail match
              case "blobs" :: "uploads" :: Nil              => Some(Call.Start(name))
              case "blobs" :: "uploads" :: id :: Nil        => Some(Call.Continue(name, id))
              case "blobs" :: "sha256" :: hex :: Nil        => Some(Call.Blob(name, s"sha256:$hex"))
              case "blobs" :: digest :: Nil                 => Some(Call.Blob(name, digest))
              case "manifests" :: ref :: Nil                => Some(Call.Manifests(name, ref))
              case "tags" :: "list" :: Nil                  => Some(Call.Tags(name))
              case _                                        => None
    else None

  private enum Call(val repository: String, val path: String):
    case Start(name: String) extends Call(name, s"/v2/$name/blobs/uploads/")
    case Continue(name: String, id: String) extends Call(name, s"/v2/$name/blobs/uploads/$id")
    case Blob(name: String, digest: String) extends Call(name, s"/v2/$name/blobs/$digest")
    case Manifests(name: String, ref: String) extends Call(name, s"/v2/$name/manifests/$ref")
    case Tags(name: String) extends Call(name, s"/v2/$name/tags/list")

    def run(
        ledger: Ledger,
        authority: String,
        base: String,
        req: Request,
        body: Chunk[Byte],
        auth: String,
    ): UIO[Response] = this match
      case Start(name) =>
        req.query.get("mount") match
          case Some(digest) =>
            ledger.blobSize(digest).flatMap {
              case Some(_) =>
                val location = s"$base/v2/$name/blobs/$digest"
                note(ledger, authority, name, "POST", path, Some(digest), 0L, auth, 201).as(
                  distribution(Response.empty(Status.Created))
                    .withHeader("Location", location)
                    .withHeader("Docker-Content-Digest", digest)
                )
              case None =>
                req.query.get("digest") match
                  case Some(expected) => monolithic(ledger, authority, base, name, body, expected, auth)
                  case None           => open(ledger, authority, base, name, auth)
            }
          case None =>
            req.query.get("digest") match
              case Some(expected) => monolithic(ledger, authority, base, name, body, expected, auth)
              case None           => open(ledger, authority, base, name, auth)
      case Continue(name, id) =>
        req.method match
          case Method.PATCH =>
            ledger.appendUpload(id, body).flatMap {
              case None =>
                note(ledger, authority, name, "PATCH", path, None, body.length.toLong, auth, 404)
                  .as(Response.notFound())
              case Some(size) =>
                val end = if size == 0L then 0L else size - 1L
                note(ledger, authority, name, "PATCH", path, None, body.length.toLong, auth, 202).as(
                  distribution(Response.empty(Status.Accepted))
                    .withHeader("Location", s"$base/v2/$name/blobs/uploads/$id")
                    .withHeader("Range", s"0-$end")
                    .withHeader("Docker-Upload-UUID", id)
                )
            }
          case Method.PUT =>
            val expected = req.query.get("digest").getOrElse("")
            ledger.finishUpload(id, body, expected.stripPrefix("sha256:")).flatMap {
              case Left(problem) =>
                val status = if problem == "unknown upload" then 404 else 400
                note(ledger, authority, name, "PUT", path, req.query.get("digest"), body.length.toLong, auth, status)
                  .as(if status == 404 then Response.notFound() else Response.badRequest(problem))
              case Right((hex, size)) =>
                val digest = s"sha256:$hex"
                ledger.putBlob(digest, size) *>
                  note(ledger, authority, name, "PUT", path, Some(digest), size, auth, 201).as(
                    distribution(Response.empty(Status.Created))
                      .withHeader("Location", s"$base/v2/$name/blobs/$digest")
                      .withHeader("Docker-Content-Digest", digest)
                  )
            }
          case other =>
            note(ledger, authority, name, other.render, path, None, 0L, auth, 405)
              .as(Response.methodNotAllowed("PATCH, PUT"))
      case Blob(name, digest) =>
        req.method match
          case Method.HEAD =>
            ledger.blobSize(digest).flatMap {
              case None =>
                note(ledger, authority, name, "HEAD", path, Some(digest), 0L, auth, 404).as(headMissing)
              case Some(size) =>
                note(ledger, authority, name, "HEAD", path, Some(digest), size, auth, 200).as(
                  distribution(Response.empty(Status.Ok))
                    .withHeader("Docker-Content-Digest", digest)
                    .withHeader("Content-Length", size.toString)
                )
            }
          case Method.GET =>
            note(ledger, authority, name, "GET", path, Some(digest), 0L, auth, 404)
              .as(Response.notFound("blob bodies are not stored"))
          case other =>
            note(ledger, authority, name, other.render, path, Some(digest), 0L, auth, 405)
              .as(Response.methodNotAllowed("HEAD"))
      case Manifests(name, ref) =>
        val key = s"$authority/$name/$ref"
        req.method match
          case Method.PUT =>
            val digest    = s"sha256:${Ledger.sha256(body)}"
            val mediaType = req.header("Content-Type").getOrElse("application/vnd.docker.distribution.manifest.v2+json")
            val stored    = Ledger.Manifest(mediaType, body, digest)
            val tag       = Option.when(!ref.startsWith("sha256:"))(ref)
            ledger.putManifest(key, stored, None) *>
              ledger.putManifest(s"$authority/$name/$digest", stored, tag.map(s"$authority/$name" -> _)) *>
              note(ledger, authority, name, "PUT", path, Some(digest), body.length.toLong, auth, 201).as(
                distribution(Response.empty(Status.Created))
                  .withHeader("Location", s"$base/v2/$name/manifests/$digest")
                  .withHeader("Docker-Content-Digest", digest)
              )
          case Method.GET | Method.HEAD =>
            lookupManifest(ledger, authority, name, ref).flatMap {
              case None =>
                val absent = if req.method == Method.HEAD then headMissing else Response.notFound()
                note(ledger, authority, name, req.method.render, path, None, 0L, auth, 404).as(absent)
              case Some(stored) =>
                val payload = if req.method == Method.HEAD then Body.empty else Body.fromBytes(stored.body)
                note(
                  ledger,
                  authority,
                  name,
                  req.method.render,
                  path,
                  Some(stored.digest),
                  stored.body.length.toLong,
                  auth,
                  200,
                ).as(
                  distribution(Response(Status.Ok, payload))
                    .withHeader("Content-Type", stored.mediaType)
                    .withHeader("Docker-Content-Digest", stored.digest)
                    .withHeader("Content-Length", stored.body.length.toString)
                )
            }
          case other =>
            note(ledger, authority, name, other.render, path, None, 0L, auth, 405)
              .as(Response.methodNotAllowed("GET, HEAD, PUT"))
      case Tags(name) =>
        ledger.tagList(s"$authority/$name").flatMap { listed =>
          val json = TagList(name, listed.keys.toList.sorted).toJson
          note(ledger, authority, name, "GET", path, None, json.length.toLong, auth, 200)
            .as(distribution(Response.json(json)))
        }

  /** A POST that already carries `?digest=` is a monolithic upload. The body is hashed and dropped. */
  private def monolithic(
      ledger: Ledger,
      authority: String,
      base: String,
      name: String,
      body: Chunk[Byte],
      expected: String,
      auth: String,
  ): UIO[Response] =
    val got    = Ledger.sha256(body)
    val want   = expected.stripPrefix("sha256:")
    val path   = s"/v2/$name/blobs/uploads/"
    if got != want then
      note(ledger, authority, name, "POST", path, Some(expected), body.length.toLong, auth, 400)
        .as(Response.badRequest(s"digest sha256:$got did not match $expected"))
    else
      val digest = s"sha256:$got"
      ledger.putBlob(digest, body.length.toLong) *>
        note(ledger, authority, name, "POST", path, Some(digest), body.length.toLong, auth, 201).as(
          distribution(Response.empty(Status.Created))
            .withHeader("Location", s"$base/v2/$name/blobs/$digest")
            .withHeader("Docker-Content-Digest", digest)
        )

  private def open(ledger: Ledger, authority: String, base: String, name: String, auth: String): UIO[Response] =
    val id = UUID.randomUUID().toString
    ledger.openUpload(id) *>
      note(ledger, authority, name, "POST", s"/v2/$name/blobs/uploads/", None, 0L, auth, 202).as(
        distribution(Response.empty(Status.Accepted))
          .withHeader("Location", s"$base/v2/$name/blobs/uploads/$id")
          .withHeader("Range", "0-0")
          .withHeader("Docker-Upload-UUID", id)
      )

  private def lookupManifest(
      ledger: Ledger,
      authority: String,
      name: String,
      ref: String,
  ): UIO[Option[Ledger.Manifest]] =
    ledger.manifest(s"$authority/$name/$ref").flatMap {
      case some @ Some(_) => ZIO.succeed(some)
      case None =>
        ledger.tagList(s"$authority/$name").flatMap { tags =>
          tags.get(ref) match
            case None         => ZIO.succeed(None)
            case Some(digest) => ledger.manifest(s"$authority/$name/$digest")
        }
    }

  private def distribution(response: Response): Response =
    response.withHeader("Docker-Distribution-Api-Version", "registry/2.0")

  /** HEAD must not carry the "Not Found" bytes. A client that skips the HEAD body would parse them as the next status
    * line.
    */
  private def headMissing: Response =
    distribution(Response.empty(Status.NotFound))

  private def miss(method: Method): Response =
    if method == Method.HEAD then headMissing else distribution(Response.notFound())

  private def note(
      ledger: Ledger,
      authority: String,
      repository: String,
      method: String,
      path: String,
      digest: Option[String],
      bytes: Long,
      auth: String,
      status: Int,
  ): UIO[Unit] =
    ledger.record(Fact(authority, repository, method, path, digest, bytes, auth, status))

  final case class TagList(name: String, tags: List[String]) derives JsonEncoder
end ImageRegistry
