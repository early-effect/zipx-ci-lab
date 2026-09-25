//> using scala 3.9.0
//> using dep dev.zio::zio:2.1.26
//> using dep dev.zio::zio-json:1.1.0

package lab.measure

import zio.*
import zio.json.*

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.{Duration as JDuration, Instant}

opaque type RunId = Long
object RunId:
  def parse(raw: String): Either[MeasureError, RunId] =
    raw.toLongOption.filter(_ > 0).toRight(MeasureError.BadArgs(s"not a workflow run id: $raw"))
  extension (id: RunId) def value: Long = id

opaque type Repo = String
object Repo:
  val Lab: Repo                                      = "early-effect/zipx-ci-lab"
  def parse(raw: String): Either[MeasureError, Repo] =
    Either.cond(raw.matches("[\\w.-]+/[\\w.-]+"), raw, MeasureError.BadArgs(s"not an owner/name repo: $raw"))
  extension (repo: Repo) def value: String = repo

final case class Args(run: RunId, repo: Repo)
object Args:
  val usage = "usage: scala-cli run lab/Measure.scala -- <run-id> [--repo owner/name]"

  def parse(args: List[String]): Either[MeasureError, Args] = args match
    case run :: Nil                     => RunId.parse(run).map(Args(_, Repo.Lab))
    case run :: "--repo" :: repo :: Nil => RunId.parse(run).flatMap(id => Repo.parse(repo).map(Args(id, _)))
    case _                              => Left(MeasureError.BadArgs(usage))

enum MeasureError:
  case BadArgs(usage: String)
  case GhUnavailable(reason: String)
  case GhFailed(args: List[String], exit: Int, stderr: String)
  case Decode(path: String, reason: String)
  case NotCompleted(run: Long, status: String)
  case TooManyJobs(total: Int)

  def message: String = this match
    case BadArgs(usage)               => usage
    case GhUnavailable(reason)        => s"could not start gh: $reason"
    case GhFailed(args, exit, stderr) => s"${args.mkString(" ")} exited $exit: ${stderr.trim}"
    case Decode(path, reason)         => s"unexpected JSON from $path: $reason"
    case NotCompleted(run, status)    => s"run $run is $status; measure it once it completes"
    case TooManyJobs(total)           => s"run has $total jobs; this tool reads one page of 100"
end MeasureError

// GitHub REST shapes, reduced to the fields a report reads.

@jsonMemberNames(SnakeCase)
final case class ApiRun(
    id: Long,
    event: String,
    headBranch: Option[String],
    headSha: String,
    status: String,
    conclusion: Option[String],
    createdAt: Instant,
) derives JsonDecoder

@jsonMemberNames(SnakeCase)
final case class ApiStep(name: String, conclusion: Option[String]) derives JsonDecoder

@jsonMemberNames(SnakeCase)
final case class ApiJob(
    id: Long,
    name: String,
    conclusion: Option[String],
    createdAt: Instant,
    steps: List[ApiStep] = Nil,
) derives JsonDecoder

@jsonMemberNames(SnakeCase)
final case class ApiJobs(totalCount: Int, jobs: List[ApiJob]) derives JsonDecoder

@jsonMemberNames(SnakeCase)
final case class ApiCache(key: String, ref: String, sizeInBytes: Long) derives JsonDecoder

@jsonMemberNames(SnakeCase)
final case class ApiCaches(totalCount: Int, actionsCaches: List[ApiCache]) derives JsonDecoder

/** What one job's log says about cache traffic, compilation, and tests. */
/** What one job's log says about the build cache, compilation, and tests. */
final case class LogFacts(
    restoredKey: Option[String],
    cacheMiss: Boolean,
    savedKeys: List[String],
    compiled: List[String],
    metaBuildCompiled: Boolean,
    suites: List[String],
    printedModules: Option[List[String]],
)

object LogFacts:
  private val Ansi      = "\u001b\\[[0-9;?]*[A-Za-z]"
  private val Timestamp = """^\d{4}-\d\d-\d\dT[\d:.]+Z ?"""
  private val Restored  = """Cache restored from key: (\S+)""".r.unanchored
  private val Saved     = """Cache saved with key: (\S+)""".r.unanchored
  private val Missed    = """Cache not found for input keys: ([^,\s]+)""".r.unanchored
  private val Compiling = """\[info\] \[([^\]]+)\] compiling \d+ .* to (\S+)""".r.unanchored
  // ZIO Test prints a top-level suite unindented as `+ <label>`; its tests are indented under it.
  private val Suite = """^\+ (.+)$""".r

  /** zipx's LocalDir build cache. setup-sbt keeps its own launcher cache (`…-sbt-runner-…`), which is not the build. */
  def isBuildCache(key: String): Boolean = key.contains("-sbt-") && !key.contains("-sbt-runner-")

  /** `[models] … to …/models/test-classes` is `models/test`; `…/classes` is plain `models`. */
  private def target(module: String, dir: String): String =
    if dir.endsWith("test-classes") then s"$module/test" else module

  def from(log: String): LogFacts =
    val lines    = log.linesIterator.map(_.replaceAll(Ansi, "").replaceFirst(Timestamp, "")).toList
    val compiles = lines.collect { case Compiling(module, dir) => module -> dir }
    LogFacts(
      restoredKey = lines.collectFirst { case Restored(key) if isBuildCache(key) => key },
      cacheMiss = lines.exists { case Missed(key) => isBuildCache(key); case _ => false },
      savedKeys = lines.collect { case Saved(key) if isBuildCache(key) => key },
      compiled = compiles.collect { case (m, dir) if !m.endsWith("-build") => target(m, dir) }.distinct.sorted,
      metaBuildCompiled = compiles.exists((m, _) => m.endsWith("-build")),
      suites = lines.collect { case Suite(label) => label.trim }.distinct.sorted,
      // The `affected` job echoes its module set as one JSON array line: `["svcA","svcAJS"]`, `["all"]`, or `[]`.
      printedModules = lines.iterator
        .filter(l => l == "[]" || l.startsWith("[\""))
        .flatMap(_.fromJson[List[String]].toOption)
        .nextOption(),
    )
  end from
end LogFacts

final case class JobReport(
    name: String,
    conclusion: String,
    didWork: Boolean,
    restoredKey: Option[String],
    cacheMiss: Boolean,
    savedKeys: List[String],
    compiled: List[String],
    metaBuildCompiled: Boolean,
    suites: List[String],
) derives JsonEncoder

final case class CacheEntry(ref: String, key: String, megabytes: Long) derives JsonEncoder

final case class CacheReport(entries: Int, totalBytes: Long, mainPresent: Boolean, build: List[CacheEntry])
    derives JsonEncoder

final case class RunReport(
    runId: Long,
    event: String,
    branch: Option[String],
    sha: String,
    conclusion: Option[String],
    pendingSeconds: Long,
    affected: Option[List[String]],
    jobsRan: List[String],
    jobsWorked: List[String],
    cacheSaves: Int,
    jobs: List[JobReport],
    cache: CacheReport,
) derives JsonEncoder

object Jobs:
  private val Infrastructure = Set("Set up job", "Complete job", "zipx sbt setup", "Log in to GHCR")

  def ran(job: ApiJob): Boolean = !job.conclusion.contains("skipped") && job.steps.nonEmpty

  /** A job worked when a step of its own ran: checkout, toolchain setup, and post-steps do not count. */
  def didWork(job: ApiJob): Boolean =
    job.steps.exists { step =>
      val infra = Infrastructure(step.name) || step.name.startsWith("Post ") || step.name.startsWith("Run actions/")
      !infra && step.conclusion.exists(c => c == "success" || c == "failure")
    }

  def report(job: ApiJob, facts: LogFacts): JobReport =
    JobReport(
      name = job.name,
      conclusion = job.conclusion.getOrElse("none"),
      didWork = didWork(job),
      restoredKey = facts.restoredKey,
      cacheMiss = facts.cacheMiss,
      savedKeys = facts.savedKeys,
      compiled = facts.compiled,
      metaBuildCompiled = facts.metaBuildCompiled,
      suites = facts.suites,
    )
end Jobs

final case class Gh(repo: Repo):
  def json[A: JsonDecoder](path: String): IO[MeasureError, A] =
    text(path).flatMap(body => ZIO.fromEither(body.fromJson[A]).mapError(MeasureError.Decode(path, _)))

  def text(path: String): IO[MeasureError, String] = Gh.exec(List("gh", "api", s"repos/${repo.value}/$path"))

  /** Job logs carry ANSI colour codes, which `gh api` refuses to print without the flag. [[LogFacts]] strips them. */
  def jobLog(jobId: Long): IO[MeasureError, String] =
    Gh.exec(List("gh", "api", "--allow-escape-sequences", s"repos/${repo.value}/actions/jobs/$jobId/logs"))

object Gh:
  def layer(repo: Repo): ULayer[Gh] = ZLayer.succeed(Gh(repo))

  /** Process I/O is the impure edge; every failure becomes a [[MeasureError]] here. */
  private def exec(args: List[String]): IO[MeasureError, String] =
    ZIO.scoped {
      for
        process <- ZIO
          .acquireRelease(ZIO.attemptBlocking(new ProcessBuilder(args*).start()))(p => ZIO.succeed(p.destroy()))
          .mapError(e => MeasureError.GhUnavailable(e.getMessage))
        output <- readAll(process.getInputStream) <&> readAll(process.getErrorStream)
        exit   <- ZIO.attemptBlocking(process.waitFor()).mapError(e => MeasureError.GhUnavailable(e.getMessage))
        _      <- ZIO.fail(MeasureError.GhFailed(args, exit, output._2)).when(exit != 0)
      yield output._1
    }

  private def readAll(stream: InputStream): IO[MeasureError, String] =
    ZIO
      .attemptBlocking(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
      .mapError(e => MeasureError.GhUnavailable(e.getMessage))
end Gh

object Report:
  def of(run: RunId): ZIO[Gh, MeasureError, RunReport] =
    ZIO.serviceWithZIO[Gh] { gh =>
      for
        apiRun <- gh.json[ApiRun](s"actions/runs/${run.value}")
        _      <- ZIO.fail(MeasureError.NotCompleted(apiRun.id, apiRun.status)).when(apiRun.status != "completed")
        page   <- gh.json[ApiJobs](s"actions/runs/${run.value}/jobs?per_page=100&filter=latest")
        _      <- ZIO.fail(MeasureError.TooManyJobs(page.totalCount)).when(page.totalCount > page.jobs.size)
        ran = page.jobs.filter(Jobs.ran)
        facts <- ZIO
          .foreachPar(ran)(job => gh.jobLog(job.id).map(log => job -> LogFacts.from(log)))
          .withParallelism(8)
        jobs = facts.map((job, fact) => Jobs.report(job, fact))
        caches <- gh.json[ApiCaches]("actions/caches?per_page=100")
      yield RunReport(
        runId = apiRun.id,
        event = apiRun.event,
        branch = apiRun.headBranch,
        sha = apiRun.headSha,
        conclusion = apiRun.conclusion,
        // A run held by its concurrency group has `run_started_at` set already; its jobs appear when it is released.
        pendingSeconds = page.jobs
          .map(_.createdAt)
          .minOption
          .fold(0L)(firstJob => JDuration.between(apiRun.createdAt, firstJob).toSeconds),
        affected = facts.collectFirst { case (job, fact) if job.name == "affected" => fact.printedModules }.flatten,
        jobsRan = jobs.map(_.name),
        jobsWorked = jobs.filter(_.didWork).map(_.name),
        cacheSaves = jobs.map(_.savedKeys.size).sum,
        jobs = jobs,
        cache = CacheReport(
          entries = caches.totalCount,
          totalBytes = caches.actionsCaches.map(_.sizeInBytes).sum,
          mainPresent = caches.actionsCaches.exists(c => c.ref == "refs/heads/main" && LogFacts.isBuildCache(c.key)),
          build = caches.actionsCaches
            .filter(c => LogFacts.isBuildCache(c.key))
            .map(c => CacheEntry(c.ref, c.key, c.sizeInBytes / (1024 * 1024))),
        ),
      )
    }
end Report

object Measure extends ZIOAppDefault:
  def run =
    getArgs
      .flatMap(args => ZIO.fromEither(Args.parse(args.toList)))
      .flatMap(args => Report.of(args.run).provide(Gh.layer(args.repo)))
      .flatMap(report => Console.printLine(report.toJsonPretty).orDie)
      .catchAll(error => Console.printLineError(error.message).orDie *> exit(ExitCode.failure))
