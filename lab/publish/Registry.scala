package lab.publish

import heddle.http.{Request, Response}
import heddle.route.{Handler, Routes}
import heddle.server.Tls
import heddle.{BytesLength, Server}
import zio.*

object Registry:
  val MavenPort  = 5443
  val ImageAPort = 5444
  val ImageBPort = 5445
  val ImageCPort = 5446

  /** The daemon's name for these listeners. `127.0.0.1` is an insecure HTTP registry, and a VM daemon's loopback is
    * not this process. Colima forwards this name to the host loopback.
    */
  val DockerRegistryHost = "host.docker.internal"

  final case class Authority(name: String, base: String, port: Int):
    def snapshots: String = s"$base/snapshots"
    def releases: String  = s"$base/releases"

  final case class Bound(maven: Authority, imageA: Authority, imageB: Authority, imageC: Authority):
    def dockerHosts: List[String] =
      List(imageA, imageB, imageC).flatMap { authority =>
        List(s"127.0.0.1:${authority.port}", s"$DockerRegistryHost:${authority.port}")
      }

  def bind(tls: Tls, ledger: Ledger): ZIO[Scope, ProofError, Bound] =
    for
      maven <- listen("maven", MavenPort, tls, req => MavenRepo.handle(ledger, "maven", req))
      imageA <- listen(
        "image-a",
        ImageAPort,
        tls,
        req => ImageRegistry.handle(ledger, "image-a", s"https://127.0.0.1:$ImageAPort", req),
      )
      imageB <- listen(
        "image-b",
        ImageBPort,
        tls,
        req => ImageRegistry.handle(ledger, "image-b", s"https://127.0.0.1:$ImageBPort", req),
      )
      imageC <- listen(
        "image-c",
        ImageCPort,
        tls,
        req => ImageRegistry.handle(ledger, "image-c", s"https://127.0.0.1:$ImageCPort", req),
      )
    yield Bound(maven, imageA, imageB, imageC)

  private def listen(
      name: String,
      port: Int,
      tls: Tls,
      handler: Request => UIO[Response],
  ): ZIO[Scope, ProofError, Authority] =
    val routes = Routes.fromHandler(Handler.fromFunctionZIO(handler))
    for
      server <- Server.install(routes, config(port), tls).mapError(e => ProofError.Bind(s"$name: ${e.message}"))
      bound  <- server.port
      _      <- ZIO.when(bound != port)(ZIO.fail(ProofError.Bind(s"$name bound $bound, wanted $port")))
    yield Authority(name, s"https://127.0.0.1:$bound", bound)

  private def config(port: Int): Server.Config =
    Server.Config.default.copy(
      host = "127.0.0.1",
      port = port,
      http2 = false,
      maxBodyBytes = BytesLength(2L * 1024L * 1024L),
    )
end Registry
