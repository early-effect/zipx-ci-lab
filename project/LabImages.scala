import com.typesafe.sbt.SbtNativePackager.autoImport.packageName
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.*
import sbt.*
import sbt.Keys.*

/** Images for the two services and two workers, pushed to GHCR under the lab repo. */
object LabImages:

  val Registry = "ghcr.io/early-effect"
  val Source   = "https://github.com/early-effect/zipx-ci-lab"

  /** sbt module id to image suffix. The registry step and deploy jobs look modules up here. */
  val Services: Map[String, String] = Map("svcA" -> "svc-a", "svcB" -> "svc-b")
  val Workers: Map[String, String]  = Map("workerA" -> "worker-a", "workerB" -> "worker-b")
  val All: Map[String, String]      = Services ++ Workers

  def repository(image: String): String = s"$Registry/zipx-ci-lab-$image"

  /** `main-<sha>` in CI, the same immutable tag shape a production build pushes; the sbt version locally. */
  def tag(localVersion: String): String =
    sys.env.get("GITHUB_SHA").fold(localVersion.replace('+', '-'))(sha => s"main-$sha")

  def settings(moduleId: String, mainClassName: String): Seq[Setting[?]] = Seq(
    Compile / mainClass  := Some(mainClassName),
    publish / skip       := true,
    dockerBaseImage      := "eclipse-temurin:25-jre",
    dockerRepository     := Some(Registry),
    Docker / packageName := s"zipx-ci-lab-${All(moduleId)}",
    Docker / version     := tag(version.value),
    dockerLabels         := Map("org.opencontainers.image.source" -> Source),
    dockerUpdateLatest   := false,
  )
end LabImages
