import zipx.*

/** Catalog. `fansi` and `scodecBits` are each selected by one service only, so bumping either row is the probe for
  * catalog-aware affected gating (scenario L6).
  */
object LabVersions extends ZipxVersions:
  val sbt: SbtVersion      = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion  = ScalaVersion("3.9.0")
  val scala2: ScalaVersion = ScalaVersion("2.13.18")

  val zio        = Lib("dev.zio", "zio", "2.1.26")
  val zioTestSbt = zio.mod("zio-test-sbt").test
  val zioJson    = Lib("dev.zio", "zio-json", "1.1.0")
  val scodecBits = Lib("org.scodec", "scodec-bits", "1.2.5")
  val fansi      = Lib("com.lihaoyi", "fansi", "0.5.1")

  /** Docs only. The lab is not a library, and these rows are not an install coordinate for it. */
  val specular        = Lib("rocks.earlyeffect", "specular-core", "0.20.0")
  val specularSite    = specular.mod("specular-site")
  val specularTheme   = specular.mod("early-effect-docs-theme")
  val specularZioTest = specular.mod("specular-zio-test").test

  val nativePackager = Plugin("com.github.sbt", "sbt-native-packager", "1.11.7")
  val scalafmt       = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
  val scoverage      = Plugin("org.scoverage", "sbt-scoverage", "2.4.4")
  val scalajs        = Plugin("org.scala-js", "sbt-scalajs", "1.22.0")
  val specularPlugin = Plugin("rocks.earlyeffect", "sbt-specular", "0.20.0")

  /** Published to the lab's GitHub Packages. `0.1.0` is already there, so this row is the next number: a later change
    * to `lib` or `models` is already past that release.
    */
  val libs = ShipGroup("libs", "0.1.1")("models", "lib")

  def jvmTests = library(zioTestSbt)
  def lib      = library(zio)
  def svcA     = library(scodecBits)
  def svcB     = library(fansi)
  def legacy   = library(zio, zioTestSbt)
  def docs     = library(zio, zioJson, specular, specularSite, specularTheme, specularZioTest, zioTestSbt)
end LabVersions
