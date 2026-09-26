// A small build shaped like the monorepos zipx has to gate well:
//
//   models (JVM + JS) ──▶ lib ──▶ svcA (JVM + JS, image) ┐
//                          ├───▶ svcB (image)           ├─▶ imageIt (Docker/publishLocal edges, not aggregated)
//                          ├───▶ workerA (image, deploy)│
//                          └───▶ workerB (image, deploy)┘
//   legacy (Scala 2.13 only, not aggregated)
//
// The zipx block at the bottom is the configuration under test. See README.md for the scenarios.

LabVersions.settings
organization   := "rocks.earlyeffect.lab"
publish / skip := true

val labPackages = "GitHub Package Registry" at "https://maven.pkg.github.com/early-effect/zipx-ci-lab"

def publishedLibrary: Seq[Setting[?]] = Seq(
  publish / skip := false,
  publishTo      := Some(labPackages),
  credentials ++= sys.env
    .get("GITHUB_TOKEN")
    .map(Credentials("GitHub Package Registry", "maven.pkg.github.com", "_", _)),
)

val promote = taskKey[Unit]("Move the tier's image tag to this build's image. Reads TIER from the deploy target.")

def promoteTask = Def.task {
  val tier  = sys.env.getOrElse("TIER", "local")
  val image = dockerAlias.value
  streams.value.log.info(
    s"promote ${image.withTag(Some(tier))} -> ${image.withTag(Some(LabImages.tag(version.value)))}"
  )
}

lazy val models = (projectMatrix in file("modules/models"))
  .settings(publishedLibrary)
  .jvmPlatform(scalaVersions = Seq(LabVersions.scala), settings = LabVersions.jvmTests)
  .jsPlatform(scalaVersions = Seq(LabVersions.scala), settings = Seq(coverageEnabled := false))

lazy val lib = (project in file("modules/lib"))
  .dependsOn(models.jvm(LabVersions.scala))
  .settings(LabVersions.lib, LabVersions.jvmTests, publishedLibrary)

lazy val svcA = (projectMatrix in file("modules/svc-a"))
  .dependsOn(models)
  .jvmPlatform(
    Seq(LabVersions.scala),
    Nil,
    (p: Project) =>
      p.dependsOn(lib)
        .enablePlugins(JavaAppPackaging, DockerPlugin)
        .settings(LabVersions.svcA, LabVersions.jvmTests)
        .settings(LabImages.settings("svcA", "lab.svca.Main")),
  )
  .jsPlatform(scalaVersions = Seq(LabVersions.scala), settings = Seq(coverageEnabled := false))

lazy val svcB = (project in file("modules/svc-b"))
  .dependsOn(lib)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(LabVersions.svcB, LabVersions.jvmTests)
  .settings(LabImages.settings("svcB", "lab.svcb.Main"))

lazy val workerA = (project in file("modules/worker-a"))
  .dependsOn(lib)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(LabVersions.jvmTests)
  .settings(LabImages.settings("workerA", "lab.workera.Main"))
  .settings(promote := Def.uncached(promoteTask.value))

lazy val workerB = (project in file("modules/worker-b"))
  .dependsOn(lib)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(LabVersions.jvmTests)
  .settings(LabImages.settings("workerB", "lab.workerb.Main"))
  .settings(promote := Def.uncached(promoteTask.value))

// A `svcA.jvm(...)` finder here resolves the matrix during parallel settings evaluation and can see `models` half-built.
val svcAJvm = LocalProject("svcA")

// Not aggregated: root testFull must not need a Docker daemon. CI runs it as its own job.
lazy val imageIt = (project in file("modules/image-it"))
  .settings(
    LabVersions.jvmTests,
    Test / fork := true,
    Test / javaOptions += Def.uncached {
      val images = Seq(
        (svcAJvm / dockerAlias).value,
        (svcB / dockerAlias).value,
        (workerA / dockerAlias).value,
        (workerB / dockerAlias).value,
      )
      s"-Dlab.images=${images.mkString(",")}"
    },
    // `.value` dependencies run in parallel in sbt 2, so `dependsOn` is what orders publishLocal before the tests.
    Test / testFull := Def.uncached {
      val results = (Test / executeTests)
        .dependsOn(
          svcAJvm / Docker / publishLocal,
          svcB / Docker / publishLocal,
          workerA / Docker / publishLocal,
          workerB / Docker / publishLocal,
        )
        .value
      val overall = results.overall
      if (overall != TestResult.Passed && overall != TestResult.Empty) throw new TestsFailedException
      overall
    },
  )

// Scala 2.13 only and outside the root aggregate, so root `++` never touches it. CI runs it under `++2.13.18`.
lazy val legacy = (project in file("modules/legacy"))
  .settings(
    scalaVersion       := LabVersions.scala2,
    crossScalaVersions := Seq(LabVersions.scala2),
    LabVersions.legacy,
  )

lazy val root = (project in file("."))
  .aggregate(models.projectRefs *)
  .aggregate(svcA.projectRefs *)
  .aggregate(lib, svcB, workerA, workerB)
  .settings(
    Global / excludeLintKeys ++= Set(
      Debian / executableScriptName,
      Debian / sourceDirectory,
      Rpm / daemonStdoutLogFile,
      Rpm / executableScriptName,
      Rpm / name,
      Rpm / sourceDirectory,
      Universal / executableScriptName,
      UniversalDocs / name,
      UniversalSrc / name,
      rpmScriptsDirectory,
    )
  )

// --- zipx --------------------------------------------------------------------
// Mirrors a production monorepo's configuration on purpose, pathologies included: Once Verify jobs with no affected
// gate. Coverage as the required `test` and images and deploys on every merge were the 0.11.0 baseline (L2, L3).

zipxJavaVersion      := JdkVersion("25")
zipxCacheEpoch       := CacheEpoch.ShipCatalog
zipxWorkflowDispatch := true
zipxAffectedOnPush   := true
zipxAffectedPublish  := true
zipxAffectedDeploy   := true
zipxVersionUpdates   := false
// Images and deploys run from a dispatched zipx-deploy.yml, never on a merge.
zipxDeployTrigger := DeployTrigger.Manual()
// Package publish and the modver registry lookup both read GITHUB_TOKEN.
zipxEnv += ("GITHUB_TOKEN" -> EnvValue.githubToken)

val onMainPush = JobCondition.eventIs("push") && JobCondition.refIs("refs/heads/main")

// The builtin test owns the LocalDir build snapshot. Coverage runs in zipx-coverage.yml, restores that snapshot, and
// never saves one.
zipxCoverageWorkflow := Some(
  Coverage.workflow(
    CoverageTrigger.Scheduled(Cron.daily(hour = 3)),
    CoverageTrigger.Dispatch,
    CoverageTrigger.prLabel("coverage"),
  )
)

zipxCapabilities += ZipxModver
  .publish(
    command = zipxTasks.of(publish),
    registry = ModverRegistry.GitHubPackages("early-effect", "zipx-ci-lab"),
  )
  .copy(permissions = ZipxGitHubPackages.packagesPermissions)
  .andCondition(onMainPush)

zipxCapabilities += Capability.dockerGraph
  .copy(
    gate = Gate.Always,
    permissions = Map("contents" -> "read", "packages" -> "write"),
    extraSteps = LabDeploy.ghcrLogin,
  )

zipxCapabilities += Capability
  .steps(
    name = CapabilityName("registry"),
    steps = LabDeploy.registry,
    phase = Phase.Deploy,
    gate = Gate.Always,
    needsCapabilities = List(Capability.DockerName),
    permissions = Map("contents" -> "read"),
  )
  .copy(
    scope = CapabilityScope.Graph,
    ordering = Ordering.Independent,
    participates = n => LabImages.Services.contains(n.id),
    matrixCollapse = Some(MatrixCollapse.Off),
  )

zipxCapabilities += zipxTasks
  .custom(
    name = CapabilityName("image"),
    command = Docker / publishLocal,
    participates = n => LabImages.All.contains(n.id),
    phase = Phase.Verify,
    gate = Gate.Always,
  )
  .withPostSteps(LabChecks.uninstrumented)

zipxCapabilities += zipxTasks.once(
  name = CapabilityName("image-it"),
  command = imageIt / testFull,
  phase = Phase.Verify,
  gate = Gate.Always,
)

zipxCapabilities += Capability.once(
  name = CapabilityName("legacy"),
  command = SbtCommand.underScalaVersion(Expr.lit("2.13.18"), zipxTasks.of(legacy / testFull)),
  phase = Phase.Verify,
  gate = Gate.Always,
)

zipxCapabilities += zipxTasks.deployGraph(
  participates = n => LabImages.Workers.contains(n.id),
  command = promote,
  targets = _ => LabTier.targets,
  name = CapabilityName("deploy-workers"),
  needsCapabilities = List(Capability.DockerName),
  permissions = Map("contents" -> "read"),
  gate = Gate.Always,
)
