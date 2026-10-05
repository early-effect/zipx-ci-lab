package lab.publish

import zio.*

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The sbt-zipx build this proof resolves: a Maven directory, and the one version published into it. */
final case class PluginUnderTest(repo: Path, version: String)

/** Publishes the zipx checkout named by `ZIPX_UNDER_TEST` into a Maven directory. Not ivy, and not committed. */
object ZipxUnderTest:
  def publish: IO[ProofError, PluginUnderTest] =
    val root = sys.env.get("ZIPX_UNDER_TEST").filter(_.nonEmpty).map(Path.of(_))
    root match
      case None =>
        ZIO.fail(ProofError.Failed("zipx-under-test", "set ZIPX_UNDER_TEST to the zipx checkout this proof resolves"))
      case Some(checkout) if !Files.isDirectory(checkout.resolve("modules/sbt-plugin")) =>
        ZIO.fail(ProofError.Failed("zipx-under-test", s"$checkout has no modules/sbt-plugin"))
      case Some(checkout) =>
        ZIO.attemptBlocking(Files.createTempDirectory("zipx-under-test")).mapError { err =>
          ProofError.Failed("zipx-under-test", String.valueOf(err.getMessage))
        }.flatMap { dest =>
          val set =
            s"""set ThisBuild / publishTo := Some(Resolver.file("zipx-under-test", new java.io.File("${dest.toAbsolutePath}")))"""
          val publish =
            "shell/publish; workflow/publish; core/publish; syntax/publish; central/publish; aws/publish; plugin/publish"
          Machine.sbt(checkout, List(s"$set; $publish"), Map.empty).flatMap { ran =>
            if ran.exit == 0 then versionIn(dest).map(PluginUnderTest(dest, _))
            else ZIO.fail(ProofError.Command(ran.command, ran.exit, ran.tail))
          }
        }

  /** The version directory `plugin/publish` wrote, so the proof never names a zipx version itself. */
  private def versionIn(repo: Path): IO[ProofError, String] =
    val plugin = repo.resolve("rocks/earlyeffect/sbt-zipx_sbt2_3")
    ZIO
      .attemptBlocking {
        val stream = Files.list(plugin)
        try stream.iterator.asScala.filter(Files.isDirectory(_)).map(_.getFileName.toString).toList
        finally stream.close()
      }
      .mapError(err => ProofError.Failed("zipx-under-test", String.valueOf(err.getMessage)))
      .flatMap {
        case version :: Nil => ZIO.succeed(version)
        case found          => ZIO.fail(ProofError.Failed("zipx-under-test", s"$plugin holds ${found.mkString(", ")}"))
      }
end ZipxUnderTest
