package lab.publish

import heddle.http.{Request, Response, Status}

import java.nio.charset.StandardCharsets
import java.util.Base64

/** What the two `ZipxMaven` constructors put on the wire. Upload of a bearer registry is Basic with username `token`.
  * The metadata GET is `Authorization: Bearer`.
  */
final case class Credentials(user: String, password: String, token: String):
  def accept(header: Option[String]): Either[Response, String] =
    header match
      case None => Left(Credentials.challenge)
      case Some(raw) =>
        Credentials.bearer(raw) match
          case Some(got) if got == token => Right("bearer")
          case Some(_)                   => Left(Credentials.challenge)
          case None =>
            Credentials.basic(raw) match
              case Some((user, pass)) if user == this.user && pass == password => Right(s"basic:$user")
              case Some(("token", pass)) if pass == token                      => Right("basic:token")
              case Some(_)                                                     => Left(Credentials.challenge)
              case None                                                        => Left(Credentials.challenge)

object Credentials:
  val Lab: Credentials = Credentials(user = "zipx", password = "zipx-proof", token = "zipx-proof-token")

  val Realm = "127.0.0.1"

  def challenge: Response =
    Response
      .empty(Status.Unauthorized)
      .withHeader("WWW-Authenticate", s"""Basic realm="$Realm"""")
      .withHeader("Docker-Distribution-Api-Version", "registry/2.0")

  def basic(header: String): Option[(String, String)] =
    val prefix = "Basic "
    if header.regionMatches(true, 0, prefix, 0, prefix.length) then
      val encoded = header.substring(prefix.length).trim
      scala.util.Try(Base64.getDecoder.decode(encoded)).toOption.flatMap { raw =>
        val text = String(raw, StandardCharsets.UTF_8)
        text.split(":", 2) match
          case Array(user, password) => Some(user -> password)
          case _                     => None
      }
    else None

  def bearer(header: String): Option[String] =
    val prefix = "Bearer "
    Option.when(header.regionMatches(true, 0, prefix, 0, prefix.length))(header.substring(prefix.length).trim)
      .filter(_.nonEmpty)
end Credentials
