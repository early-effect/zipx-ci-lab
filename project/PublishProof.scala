import zipx.core.*
import zipx.shell.*
import zipx.workflow.Step

/** `publish-proof.yml`. Zipx writes the checkout and `zipx-sbt-setup`. These steps run after that. */
object PublishProof:

  val workflow: ShellWorkflow =
    ShellWorkflow(
      path = ".github/workflows/publish-proof.yml",
      name = "publish-proof",
      steps = pins =>
        List(
          Step.run(Script(Exec("bash", Word.lit("lab/publish/install-scala-cli.sh")))).named("Install scala-cli").build,
          Step
            .usesRef(pins.checkout)
            .named("Checkout zipx")
            .withInput("repository", "early-effect/zipx")
            .withInput("ref", "main")
            .withInput("path", "zipx")
            .build,
          Step.run(Script(Exec("bash", Word.lit("lab/publish/ci.sh")))).named("Prove publish").build,
        ),
    )
end PublishProof
