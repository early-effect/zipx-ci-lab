package lab.publish

import heddle.client.Client
import heddle.http.{Body, Method, Request, Response, Url}
import zio.*

import java.nio.charset.StandardCharsets
import java.util.Base64

object Http:
  def basic(user: String, password: String): String =
    val raw = Base64.getEncoder.encodeToString(s"$user:$password".getBytes(StandardCharsets.UTF_8))
    s"Basic $raw"

  val labBasic: String = basic(Credentials.Lab.user, Credentials.Lab.password)

  val labBearer: String = s"Bearer ${Credentials.Lab.token}"

  def send(
      method: Method,
      url: String,
      body: Chunk[Byte],
      headers: (String, String)*
  ): ZIO[Client, ProofError, Response] =
    val payload = if body.isEmpty then Body.empty else Body.fromBytes(body)
    val start   = Request(method, Url.parse(url), body = payload)
    val req     = headers.foldLeft(start)((acc, h) => acc.withHeader(h._1, h._2))
    Client.batched(req).mapError(e => ProofError.Protocol(e.toString))

  def status(res: Response, expect: Int, what: String): IO[ProofError, Response] =
    if res.status.code == expect then ZIO.succeed(res)
    else ZIO.fail(ProofError.Protocol(s"$what: HTTP ${res.status.code} ${res.body.text.getOrElse("")}"))

  def header(res: Response, name: String, what: String): IO[ProofError, String] =
    res.header(name) match
      case Some(value) => ZIO.succeed(value)
      case None        => ZIO.fail(ProofError.Protocol(s"$what: missing $name"))
end Http
