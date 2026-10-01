import zipx.*

/** Ships the publish proof releases. The lab's own catalog stays in `project/ZipxVersions.scala`. */
object ProofVersions extends ZipxVersions:
  val sbt: SbtVersion      = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion  = ScalaVersion("3.9.0")
  val scala2: ScalaVersion = ScalaVersion("2.13.18")

  val libs   = ShipGroup("libs", "0.1.0")("models", "lib")
  val legacy = Ship("legacy", "0.1.0")
end ProofVersions
