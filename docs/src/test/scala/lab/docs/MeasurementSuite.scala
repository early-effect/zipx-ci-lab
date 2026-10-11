package lab.docs

import specular.{CiteFormat, CiteResolver, DocNode, Section, SourceCite}
import specular.site.ProjectMeta
import specular.ziotest.DocSpecSuite
import zio.*
import zio.test.*

import java.nio.file.Files

object MeasurementSuite extends DocSpecSuite:

  def doc = Measurement.doc

  override def spec =
    suite("Measurement")(
      super.spec,
      test("the citation is Report.of in lab/Measure.scala") {
        cites(doc.children) match
          case head +: rest =>
            CiteResolver.resolve(head, CiteFormat.AsWritten).map { source =>
              assertTrue(
                rest.isEmpty,
                source.path == "lab/Measure.scala",
                source.text.contains("def of"),
                source.text.contains("pendingSeconds"),
                source.startLine >= 1,
                source.endLine >= source.startLine,
              )
            }
          case _ =>
            ZIO.succeed(assertTrue(cites(doc.children).nonEmpty))
      },
      test("an unpublished meta has no version badge") {
        val raw = ProjectMeta(
          name = "zipx-ci-lab",
          organization = "rocks.earlyeffect.lab",
          version = "0.1.0-SNAPSHOT",
          scalaVersion = "3.9.0",
          displayVersion = Some("0.1.0"),
        )
        val shown = BuildSite.unpublished(raw)
        val line  = shown.sbtDependency()
        assertTrue(
          shown.versionBadge.isEmpty,
          shown.docsVersion.isEmpty,
          !line.contains("0.1.0"),
          !line.contains("SNAPSHOT"),
        )
      },
      test("the built index is the measurement page and names no coordinate") {
        for
          tmp <- ZIO.attempt(Files.createTempDirectory("zipx-ci-lab-docs"))
          props = Map(
            "specular.meta.name"           -> "zipx-ci-lab",
            "specular.meta.organization"   -> "rocks.earlyeffect.lab",
            "specular.meta.version"        -> "0.1.0-SNAPSHOT",
            "specular.meta.scalaVersion"   -> "3.9.0",
            "specular.meta.displayVersion" -> "0.1.0",
            "specular.meta.description"    -> "Measures one GitHub Actions run. Not a library.",
            "specular.cite.sourceBase"     -> "https://github.com/early-effect/zipx-ci-lab/blob/0123456789abcdef",
            "specular.site.dir"            -> tmp.toString,
          )
          _     <- ZIO.withConfigProvider(ConfigProvider.fromMap(props))(BuildSite.build)
          index <- ZIO.attempt(Files.readString(tmp.resolve("index.html")))
          front <- ZIO.attempt(Files.readString(tmp.resolve("measurement.html")))
          meta  <- ZIO.attempt(Files.readString(tmp.resolve("metadata.json")))
        yield assertTrue(
          index == front,
          index.contains("not a library"),
          index.contains("cite-lab.measure.Report.of"),
          index.contains("pendingSeconds"),
          index.contains("per_page=100"),
          index.contains("github.com/early-effect/zipx-ci-lab/blob/0123456789abcdef/lab/Measure.scala#L"),
          !index.contains("libraryDependencies"),
          !index.contains("addSbtPlugin"),
          !index.contains("0.1.0"),
          !index.contains("SNAPSHOT"),
          !meta.contains("0.1.0"),
          !meta.contains("SNAPSHOT"),
        )
      },
    )

  private def cites(nodes: Vector[DocNode]): Vector[SourceCite] =
    nodes.flatMap {
      case cite: SourceCite     => Vector(cite)
      case Section(_, children) => cites(children)
      case _                    => Vector.empty
    }
end MeasurementSuite
