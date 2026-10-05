package lab.publish

import heddle.http.{Body, Method, Request, Response, Status}
import zio.*

import java.nio.charset.StandardCharsets

/** One host, two repositories: `/snapshots` and `/releases`. Release artifacts are immutable. `maven-metadata.xml` is
  * not an artifact and may be replaced. Snapshot artifacts may be replaced.
  */
object MavenRepo:
  private val Repos = Set("snapshots", "releases")

  def handle(ledger: Ledger, authority: String, req: Request): UIO[Response] =
    req.path.segments.toList match
      case "zipx" :: "ledger" :: Nil if req.method == Method.GET =>
        ledger.json.map(json => Response.json(json))
      case "zipx" :: "reset" :: Nil if req.method == Method.POST =>
        ledger.reset.as(Response.empty(Status.NoContent))
      case repo :: rest if Repos.contains(repo) =>
        req.body.collect.orDie.flatMap { body =>
          Credentials.Lab.accept(req.header("Authorization")) match
            case Left(denied) =>
              val who = req.header("Authorization").fold("anonymous")(_ => "rejected")
              note(ledger, authority, repo, req.method.render, rest.mkString("/"), None, 0L, who, 401).as(denied)
            case Right(auth) =>
              dispatch(ledger, authority, repo, rest, req.method, body, auth)
        }
      case _ =>
        ZIO.succeed(missing(req.method))

  private def dispatch(
      ledger: Ledger,
      authority: String,
      repo: String,
      rest: List[String],
      method: Method,
      body: Chunk[Byte],
      auth: String,
  ): UIO[Response] =
    val path = rest.mkString("/")
    val key  = s"$repo/$path"
    method match
      case Method.GET | Method.HEAD =>
        ledger.file(key).flatMap {
          case None =>
            note(ledger, authority, repo, method.render, path, None, 0L, auth, 404).as(missing(method))
          case Some(stored) =>
            note(
              ledger,
              authority,
              repo,
              method.render,
              path,
              Some(Ledger.sha256(stored)),
              stored.length.toLong,
              auth,
              200,
            ).as(served(stored, method == Method.HEAD))
        }
      case Method.PUT if repo == "snapshots" && !snapshotCoordinate(path) =>
        note(ledger, authority, repo, "PUT", path, Some(Ledger.sha256(body)), body.length.toLong, auth, 400)
          .as(Response.badRequest(s"$path is not a snapshot coordinate"))
      case Method.PUT =>
        val digest    = Ledger.sha256(body)
        val immutable = repo == "releases" && !metadata(path)
        ledger.file(key).flatMap {
          case Some(_) if immutable =>
            note(ledger, authority, repo, "PUT", path, Some(digest), body.length.toLong, auth, 409)
              .as(Response.empty(Status.Conflict))
          case existing =>
            checksumAgrees(ledger, key, path, body, replacing = existing.isDefined).flatMap {
              case Some(problem) =>
                note(ledger, authority, repo, "PUT", path, Some(digest), body.length.toLong, auth, 400)
                  .as(Response.badRequest(problem))
              case None =>
                ledger.putFile(key, body, immutable).flatMap {
                  case None =>
                    note(ledger, authority, repo, "PUT", path, Some(digest), body.length.toLong, auth, 409)
                      .as(Response.empty(Status.Conflict))
                  case Some(_) =>
                    note(ledger, authority, repo, "PUT", path, Some(digest), body.length.toLong, auth, 201)
                      .as(Response.empty(Status.Created))
                }
            }
        }
      case other =>
        note(ledger, authority, repo, other.render, path, None, 0L, auth, 405)
          .as(Response.methodNotAllowed("GET, HEAD, PUT"))

  /** A checksum file must describe the stored sibling. A checksum uploaded before its artifact must describe that
    * artifact when it arrives. A snapshot replace skips that check: the previous sidecar still describes the previous
    * bytes, and the next checksum PUT is checked against the new bytes. Absent siblings wait, so either upload order
    * of one publish is accepted.
    */
  private def checksumAgrees(
      ledger: Ledger,
      key: String,
      path: String,
      body: Chunk[Byte],
      replacing: Boolean,
  ): UIO[Option[String]] =
    algorithmOf(path) match
      case Some(algorithm) =>
        ledger.file(key.stripSuffix(suffix(algorithm))).map {
          case None         => None
          case Some(stored) => agree(algorithm, body, stored, path)
        }
      case None if replacing =>
        ZIO.succeed(None)
      case None =>
        ZIO.foreach(List("sha1", "sha256", "md5")) { algorithm =>
          ledger.file(key + suffix(algorithm)).map {
            case None       => None
            case Some(side) => agree(algorithm, side, body, path)
          }
        }.map(_.flatten.headOption)

  private def agree(
      algorithm: String,
      checksumFile: Chunk[Byte],
      artifact: Chunk[Byte],
      path: String,
  ): Option[String] =
    token(checksumFile) match
      case None => Some(s"$path checksum is not a hex digest")
      case Some(got) =>
        val expect = algorithm match
          case "sha1"   => Ledger.sha1(artifact)
          case "sha256" => Ledger.sha256(artifact)
          case _        => Ledger.md5(artifact)
        Option.when(got != expect)(s"$path $algorithm was $got, artifact is $expect")

  /** A snapshot repository takes `-SNAPSHOT` versions, as Nexus and Artifactory do: a commit pin is
    * `<line>-<sha>-SNAPSHOT` and the pointer is `<line>-SNAPSHOT`. A release number or a bare commit id is neither.
    */
  private def snapshotCoordinate(path: String): Boolean =
    val parts = path.split('/').toList.filter(_.nonEmpty)
    parts match
      case _ :+ version :+ name =>
        metadataName(name) || version.endsWith("-SNAPSHOT")
      case _ :+ name =>
        metadataName(name)
      case _ =>
        false

  private def metadataName(name: String): Boolean =
    name == "maven-metadata.xml" || name.startsWith("maven-metadata.xml.")

  private def metadata(path: String): Boolean =
    path.split('/').lastOption.exists(metadataName)

  private def algorithmOf(path: String): Option[String] =
    if path.endsWith(".sha1") then Some("sha1")
    else if path.endsWith(".sha256") then Some("sha256")
    else if path.endsWith(".md5") then Some("md5")
    else None

  private def suffix(algorithm: String): String = "." + algorithm

  private def token(body: Chunk[Byte]): Option[String] =
    val text  = String(body.toArray, StandardCharsets.UTF_8).trim
    val word  = text.takeWhile(!_.isWhitespace)
    val lower = word.toLowerCase
    val hex   = lower.forall(c => c.isDigit || (c >= 'a' && c <= 'f'))
    Option.when(hex && (lower.length == 32 || lower.length == 40 || lower.length == 64))(lower)

  /** A HEAD client does not read a body. Bytes written after a HEAD status line prefix the next response on the same
    * connection.
    */
  private def missing(method: Method): Response =
    if method == Method.HEAD then Response.empty(Status.NotFound) else Response.notFound()

  private def served(stored: Chunk[Byte], head: Boolean): Response =
    val body = if head then Body.empty else Body.fromBytes(stored)
    Response(Status.Ok, body).withHeader("Content-Length", stored.length.toString)

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
end MavenRepo
