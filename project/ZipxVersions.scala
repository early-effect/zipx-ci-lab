import zipx.*

/** Catalog. `fansi` and `scodecBits` are each selected by one service only, so bumping either row is the probe for
  * catalog-aware affected gating (scenario L6).
  */
object LabVersions extends ZipxVersions:
  val sbt: SbtVersion      = SbtVersion("2.1.0-M2")
  val scala: ScalaVersion  = ScalaVersion("3.9.0")
  val scala2: ScalaVersion = ScalaVersion("2.13.18")

  val zio        = Lib("dev.zio", "zio", "2.1.26")
  val zioTestSbt = zio.mod("zio-test-sbt").test
  val scodecBits = Lib("org.scodec", "scodec-bits", "1.2.5")
  val fansi      = Lib("com.lihaoyi", "fansi", "0.5.0")

  val nativePackager = Plugin("com.github.sbt", "sbt-native-packager", "1.11.7")
  val scalafmt       = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
  val scoverage      = Plugin("org.scoverage", "sbt-scoverage", "2.4.4")
  val scalajs        = Plugin("org.scala-js", "sbt-scalajs", "1.22.0")

  /** Published to the lab's GitHub Packages. Ship rows are also what keep zipx from cancelling runs on main. */
  val libs = ShipGroup("libs", "0.1.0")("models", "lib")

  def jvmTests = library(zioTestSbt)
  def lib      = library(zio)
  def svcA     = library(scodecBits)
  def svcB     = library(fansi)
  def legacy   = library(zio, zioTestSbt)
end LabVersions
