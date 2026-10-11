package lab.docs

import earlyeffect.docs.EarlyEffectTheme
import specular.site.*
import zio.*

import java.nio.file.{Files, Path, StandardCopyOption}

object BuildSite extends DocsSite:

  def pages: Vector[specular.DocPage] = Vector(Measurement.doc)

  /** Drop the build version. This lab has no published tag, so chrome must not advertise a dynver distance or a
    * placeholder coordinate.
    */
  def unpublished(meta: ProjectMeta): ProjectMeta =
    meta.copy(version = "", displayVersion = None)

  override def site(settings: DocsSettings): SiteModel =
    val cleared = super.site(settings).copy(meta = Some(unpublished(settings.meta)))
    val branded = EarlyEffectTheme.brand(cleared)
    branded.copy(
      installSnippets = Vector.empty,
      summaryMarkdown = None,
      clientScript = None,
      brand = Some(
        Brand(
          name = settings.meta.displayTitle,
          links = Vector(EarlyEffectTheme.github("https://github.com/early-effect/zipx-ci-lab")),
        )
      ),
    )
  end site

  override def layers: ZLayer[Any, Nothing, SiteBuilder] =
    EarlyEffectTheme.layers

  override def afterBuild(out: Path, result: SiteOutput): IO[SiteError, Unit] =
    val _       = result
    val front   = out.resolve(s"${Measurement.doc.slug}.html")
    val index   = out.resolve("index.html")
    val promote =
      ZIO.attemptBlockingIO(Files.copy(front, index, StandardCopyOption.REPLACE_EXISTING)).mapError { err =>
        SiteError.WriteFailed(index, err)
      }
    promote *> EarlyEffectTheme.writeLogo(out)
  end afterBuild
end BuildSite
