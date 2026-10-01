import com.typesafe.sbt.packager.PluginCompat
import com.typesafe.sbt.packager.docker.{Cmd, DockerPermissionStrategy}

ProofVersions.settings

organization   := "rocks.earlyeffect.lab"
publish / skip := true

def published: Seq[Setting[?]] = Seq(publish / skip := false)

lazy val models = (projectMatrix in file("modules/models"))
  .settings(published)
  .jvmPlatform(scalaVersions = Seq(ProofVersions.scala))
  .jsPlatform(scalaVersions = Seq(ProofVersions.scala))

lazy val lib = (project in file("modules/lib"))
  .dependsOn(models.jvm(ProofVersions.scala))
  .settings(published)

// Outside the root aggregate, published only by `zipxRelease legacy`.
lazy val legacy = (project in file("modules/legacy"))
  .settings(
    published,
    scalaVersion       := ProofVersions.scala2,
    crossScalaVersions := Seq(ProofVersions.scala2),
  )

// Maven publish stays off. Docker / publish pushes the two proof registries named by the environment.
lazy val image = (project in file("modules/image"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    publish / skip           := true,
    Compile / mainClass      := Some("lab.proof.ImageMain"),
    dockerBaseImage          := sys.env.get("ZIPX_PROOF_BASE").filter(_.nonEmpty).getOrElse("zipx-lab-base:local"),
    dockerPermissionStrategy := DockerPermissionStrategy.None,
    daemonUserUid            := None,
    dockerUpdateLatest       := false,
    Docker / packageName     := "lab-proof",
    Docker / version         := sys.env.get("ZIPX_PROOF_IMAGE_TAG").filter(_.nonEmpty).getOrElse("proof"),
    dockerAliases            := {
      val imageName = (Docker / packageName).value
      val tag       = (Docker / version).value
      List("ZIPX_PROOF_IMAGE_A", "ZIPX_PROOF_IMAGE_B").flatMap(sys.env.get).filter(_.nonEmpty).map { host =>
        DockerAlias(Some(host), None, imageName, Some(tag))
      }
    },
    Docker / mappings := PluginCompat.toFileRefsMapping(Seq((baseDirectory.value / "marker") -> "marker"))(using
      fileConverter.value
    ),
    dockerCommands := Seq(
      Cmd("FROM", dockerBaseImage.value),
      Cmd("COPY", "marker", "/marker"),
    ),
  )

lazy val root = (project in file("."))
  .aggregate(models.projectRefs*)
  .aggregate(lib)
  .settings(
    LocalRootProject / zipxReleaseWorkflow := {
      val snapshots = sys.env.getOrElse("ZIPX_PROOF_SNAPSHOTS", "https://127.0.0.1:5443/snapshots")
      val releases  = sys.env.getOrElse("ZIPX_PROOF_RELEASES", "https://127.0.0.1:5443/releases")
      val workflow  = sys.env.get("ZIPX_PROOF_AUTH") match
        case Some("bearer") =>
          ZipxMaven.releases(snapshots, releases, token = secret"ZIPX_PROOF_TOKEN")
        case _ =>
          ZipxMaven.releases(
            snapshots,
            releases,
            username = EnvValue.plain("zipx"),
            password = secret"ZIPX_PROOF_PASSWORD",
          )
      Some(workflow)
    }
  )
