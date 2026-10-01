package lab.publish

import java.nio.file.Path

final case class World(root: Path, proof: Path, ledger: Ledger, bound: Registry.Bound, trustStore: Path):
  def env(auth: String): Map[String, String] =
    val opts =
      s"-Djavax.net.ssl.trustStore=$trustStore -Djavax.net.ssl.trustStorePassword=${Certs.StorePass} -Djavax.net.ssl.trustStoreType=PKCS12"
    Map(
      "ZIPX_PROOF_SNAPSHOTS" -> bound.maven.snapshots,
      "ZIPX_PROOF_RELEASES"  -> bound.maven.releases,
      "ZIPX_PROOF_PASSWORD"  -> Credentials.Lab.password,
      "ZIPX_PROOF_TOKEN"     -> Credentials.Lab.token,
      "ZIPX_PROOF_AUTH"      -> auth,
      "ZIPX_PROOF_IMAGE_A"   -> s"${Registry.DockerRegistryHost}:${bound.imageA.port}",
      "ZIPX_PROOF_IMAGE_B"   -> s"${Registry.DockerRegistryHost}:${bound.imageB.port}",
      "ZIPX_PROOF_IMAGE_TAG" -> "proof",
      "ZIPX_PROOF_BASE"      -> "zipx-lab-base:local",
      "COURSIER_TTL"         -> "0s",
      "JAVA_OPTS"            -> opts,
      "SBT_OPTS"             -> opts,
    )

  def trustOpts: String =
    env("basic")("SBT_OPTS")
end World
