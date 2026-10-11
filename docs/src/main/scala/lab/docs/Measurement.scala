package lab.docs

import lab.measure.{Report, RunId}
import specular.*

/** What `lab/Measure.scala` computes for one Actions run. */
object Measurement extends DocSpec:

  def doc = page("Measurement")(
    md"""
This repository is a CI lab. It is not a library people depend on. There is no
published tag and no install coordinate. The `libs` ship group publishes `models`
and `lib` to this repo's GitHub Packages. That coordinate is those two modules.

`Report.of` measures one completed GitHub Actions run. It asks `gh` for the run,
its jobs, those jobs' logs, and the repo's Actions cache, then returns one
`RunReport`. The program is `lab/Measure.scala`, a scala-cli script. It is not an
sbt project.
""",
    section("Running it")(
      md"""
```text
scala-cli run lab/Measure.scala -- <run-id>
scala-cli run lab/Measure.scala -- <run-id> --repo owner/name
```

The default repo is `early-effect/zipx-ci-lab`. `gh` must already be authenticated.
Stdout is the report as pretty JSON. A `MeasureError` goes to stderr and the
process exits non-zero.

`MeasureError` is bad arguments, `gh` could not be started, `gh` exited non-zero,
JSON the decoder does not know, a run whose status is not `completed`, or a jobs
page larger than the tool reads. It does not retry, and it does not follow a
second page.
"""
    ),
    section("Report.of")(
      md"""
This is the measurement. The footer link is that definition at this commit.
""",
      cite[Report.type](_.of(_: RunId)),
    ),
    section("Which jobs count")(
      md"""
Jobs come from `actions/runs/<id>/jobs?per_page=100&filter=latest`. If
`total_count` is greater than the number of jobs in that page, the tool stops
with `TooManyJobs`. One hundred jobs is the whole page it reads.

A job ran when its conclusion is not `skipped` and it has at least one step.

A job did work when one of its own steps concluded `success` or `failure`. These
steps do not count: `Set up job`, `Complete job`, `zipx sbt setup`,
`Log in to GHCR`, any name that starts with `Post `, and any name that starts
with `Run actions/`. A missing job conclusion is stored as `none`.
"""
    ),
    section("What a log contributes")(
      md"""
Each job that ran has its log fetched, eight at a time. The request passes
`--allow-escape-sequences` because the log contains ANSI colour. Before matching,
ANSI CSI sequences and a leading timestamp ending in `Z` are stripped.

A build-cache key contains `-sbt-` and does not contain `-sbt-runner-`. The
setup-sbt launcher cache is not the build.

- `restoredKey` is the first `Cache restored from key:` whose key is a build cache.
- `cacheMiss` is true when some `Cache not found for input keys:` names a build cache.
- `savedKeys` is every `Cache saved with key:` that is a build cache.
- `compiled` comes from `[info] [module] compiling N ... to DIR`. A directory
  ending in `test-classes` is recorded as `module/test`. Any other directory is
  `module`. Names ending in `-build` are left out of this list. If any such name
  was compiled, `metaBuildCompiled` is true. The list is distinct and sorted.
- `suites` is unindented ZIO Test lines `+ label`, trimmed, distinct, and sorted.
  Indented lines are tests under a suite, not suites.
- The first line that is `[]` or starts with `["` and parses as a JSON array of
  strings is the module list that job printed. `RunReport.affected` is that list
  from the job named `affected`. It is absent when that job printed none.
"""
    ),
    section("Pending seconds")(
      md"""
`pendingSeconds` is the whole seconds between the run's `created_at` and the
earliest `created_at` among the jobs on the page, skipped jobs included. It is 0
when the page has no jobs. A run held by its concurrency group already has
`created_at` set. Its jobs appear when the group releases it, so the gap is how
long it sat before the first job existed.
"""
    ),
    section("The repo cache")(
      md"""
The cache listing is `actions/caches?per_page=100` for the repository, not for
the run.

- `entries` is the API's `total_count`. That can be larger than the page returned.
- `totalBytes` is the sum of `size_in_bytes` on the returned page only.
- `mainPresent` is true when a returned entry has ref `refs/heads/main` and a
  build-cache key.
- `build` lists those build-cache entries. `megabytes` is
  `size_in_bytes / (1024 * 1024)`, integer division, so a remainder under 1 MiB
  is dropped.
"""
    ),
    section("The report")(
      md"""
| Field | What it is |
| --- | --- |
| `runId`, `event`, `branch`, `sha`, `conclusion` | The run. `branch` and `conclusion` are absent when the API omits them. |
| `pendingSeconds` | Seconds from run creation to the first job on the page, or 0. |
| `affected` | The JSON array printed by the job named `affected`. |
| `jobsRan` | Names of jobs that ran, in API order. |
| `jobsWorked` | Names of those that did work, in API order. |
| `cacheSaves` | How many build-cache keys the ran jobs saved. |
| `jobs` | One object per ran job: conclusion, whether it did work, restore, miss, saves, compiled modules, meta-build compile, suites. |
| `cache` | The repo cache fields above. |
"""
    ),
  )
end Measurement
