package lab.publish

import heddle.client.{Client, ClientTls}
import heddle.server.Tls
import zio.*
import zio.json.*

import java.nio.file.{Files, Path}

object PublishProof extends ZIOAppDefault:
  def run =
    ZIO.scoped {
      Ref.make(List.empty[Check]).flatMap { checks =>
        program(checks).catchAll { err =>
          checks.get.map { found =>
            if found.exists(check => !check.passed) then Report(passed = false, checks = found)
            else Report(passed = false, checks = found :+ Check("stopped", passed = false, detail = err.message))
          }
        }
      }
    }.flatMap { report =>
      Console.printLineError("publish-proof report follows") *>
        Console.printLine(report.toJsonPretty) *>
        exit(if report.passed then ExitCode.success else ExitCode.failure)
    }

  private def program(checks: Ref[List[Check]]): ZIO[Scope, ProofError, Report] =
    for
      located <- discover
      (root, source) = located
      plugin <- ZipxUnderTest.publish
      proof <- ProofClone.prepare(source, plugin)
      material <- Certs.generate
      tls <- ZIO
        .service[Tls]
        .provideLayer(Tls.pem(material.certPem, material.keyPem).mapError(err => ProofError.Cert(err.message)))
      ledger <- Ledger.make
      bound <- Registry.bind(tls, ledger)
      _ <- Certs.installDocker(material.certPem, bound.dockerHosts)
      world = World(root, proof, ledger, bound, material.trustStore)
      _ <- (Protocol.run(world, checks) *> Scenarios.run(world, checks)).provide(
        ZLayer.succeed(Client.Config.default),
        ClientTls.trusting(material.certPem).mapError(err => ProofError.Protocol(err.toString)),
      )
      found <- checks.get
    yield Report(passed = found.forall(_.passed), checks = found)

  private def discover: IO[ProofError, (Path, Path)] =
    ZIO.attempt {
      val cwd   = Path.of(sys.props("user.dir")).toAbsolutePath.normalize
      val proof = cwd.resolve("lab/proof")
      if Files.isDirectory(proof) then (cwd, proof)
      else throw new RuntimeException(s"run from the lab root so lab/proof exists; cwd is $cwd")
    }.mapError(err => ProofError.Failed("layout", String.valueOf(err.getMessage)))
end PublishProof
