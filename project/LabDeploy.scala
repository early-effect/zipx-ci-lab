import zipx.core.*
import zipx.shell.{Exec, Script, Word}
import zipx.workflow.{Expr, Step}

import scala.collection.immutable.ListMap

/** Deploy tiers. `lab-prd` requires a reviewer, `lab-stg` is open. */
enum LabTier(val target: TargetName, val environment: String, val tier: String):
  case Stg extends LabTier(TargetName("stg"), "lab-stg", "stg")
  case Prd extends LabTier(TargetName("prd"), "lab-prd", "prd")

  def toTarget: Target =
    Target(name = target, environment = Some(environment), env = Map("TIER" -> EnvValue.plain(tier)))

object LabTier:
  val targets: List[Target] = values.toList.map(_.toTarget)

object LabDeploy:

  /** `docker/login-action` v4.6.0. GHCR accepts the job's own token once the job holds `packages: write`. */
  val ghcrLogin: Steps = Steps("ghcr-login") { _ =>
    List(
      Step
        .uses("docker/login-action@dbcb813823bdd20940b903addbd779551569679f")
        .named("Log in to GHCR")
        .withInputs(
          ListMap(
            "registry" -> "ghcr.io",
            "username" -> Expr.github("actor").render,
            "password" -> Expr.githubToken.render,
          )
        )
        .build
    )
  }

  /** Stand-in for an external image registry (a service catalog) that must hear about every pushed service image. It
    * only needs the pushed tag, which is why it depends on `docker` and on nothing that sbt can see.
    */
  val registry: Steps = Steps("registry") { ctx =>
    LabImages.Services.get(ctx.node.id).toList.map { image =>
      Step
        .run(Script(Exec("echo", Word.lit("registered"), Word.vq("LAB_IMAGE"))))
        .named("Register image")
        .withEnvs(Map("LAB_IMAGE" -> s"${LabImages.repository(image)}:main-${Expr.github("sha").render}"))
        .build
    }
  }
end LabDeploy
