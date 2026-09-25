# zipx-ci-lab

A proving ground for [zipx](https://github.com/early-effect/zipx) CI claims. Each claim about affected gating, LocalDir
cache reuse, manual deploys, or coverage isolation is reproduced here on the released sbt-zipx first, then shown fixed
on a snapshot of the zipx branch that changes it. Results are numbers from `lab/Measure.scala`, not screenshots.

## The build

```
models (JVM + JS) ──▶ lib ──▶ svcA (JVM + JS, image) ┐
                       ├───▶ svcB (image)           ├─▶ imageIt (Docker/publishLocal edges, not aggregated)
                       ├───▶ workerA (image, deploy)│
                       └───▶ workerB (image, deploy)┘
legacy (Scala 2.13 only, not aggregated)
```

The zipx configuration at the bottom of `build.sbt` is the subject under test. It deliberately reproduces the settings
that make gating ineffective in a real monorepo:

| Setting | Effect today |
| --- | --- |
| `Coverage.once(name = test)` | Coverage is the required `test` job, runs over the whole build, and saves its instrumented snapshot under the shared cache prefix. |
| `docker`, `registry`, `deploy-workers` on every push to `main` | Every merge builds and pushes images and deploys to `lab-stg` and `lab-prd`. |
| `lab-prd` requires a reviewer | A waiting approval holds the `CI-refs/heads/main` concurrency group. |
| `ShipGroup libs` (`models`, `lib`) | Library publish to this repo's GitHub Packages. Ship rows also turn off cancel-in-progress on `main`. |
| `image-it`, `legacy` are Once jobs | They run on every PR regardless of what changed. |
| Catalog in `project/ZipxVersions.scala` | Any change there forces `all`, even a row only `svcB` uses. |

Images go to GHCR as `ghcr.io/early-effect/zipx-ci-lab-<image>:main-<sha>`. The `registry` job stands in for an
external service catalog that must hear about each pushed service image.

## Measuring a run

```
scala-cli run lab/Measure.scala -- <run-id>
```

It prints one JSON report per run: the jobs that ran, the jobs that did real work, the cache key each job restored and
saved, the number of `compiling` lines per job, the suites that ran, the repo's cache usage, and how long the run sat
pending. It needs an authenticated `gh`.

## Scenarios

Each baseline must fail as stated on sbt-zipx 0.11.0. A baseline that does not fail disproves its claim.

| # | Scenario | 0.11.0 baseline (expected failure) | Pass criterion after the fix |
| --- | --- | --- | --- |
| L1 | Four PRs, each touching only `svcA` sources | main's entry is evicted by the third run; `test` restores a non-test entry; all modules recompile | one save per run; `test` restores the build entry from main; recompiles only `svcA`; main's entry survives 10 runs |
| L2 | Coverage on a labeled PR, then an image build | `image` restores the instrumented entry and recompiles | the coverage workflow saves nothing; the staged `svcA` jar has no scoverage reference; `image` recompiles nothing |
| L3 | Merge A, deploy waits on `lab-prd`; merge B; merge C | B stays pending behind A's approval; C cancels pending B | merges run no image or deploy jobs; a dispatched `prd` deploy waits while B's and C's CI completes |
| L4 | Deploy `changed` to `stg` twice, one `svcB` merge between | no such workflow | the second plan is `svcB` only, resolved from the Deployments API; an existing image tag is not rebuilt |
| L5 | A `lib`-only change, and an `svcA`-only change | `test` runs every suite; `image-it` and `legacy` run regardless | `test` runs only the affected closure; `image-it` runs for `svcA` and skips for a worker-only change; `legacy` skips |
| L6 | Bump `fansi`, a row only `svcB` uses | affected is `all` | affected is `svcB` |
| L7 | Edit one setting in `svcA`'s block; separately edit a shared `val` | both give `all` | the first gives `svcA`; the second stays `all` |
| L8 | A per-commit value (`BuildInfo` git hash) in `lib`, then an unrelated `svcB` change | every module downstream of `lib` misses the compile cache on every commit | only `svcB` compiles; the per-commit value no longer reaches a compiled source |

## Baseline results (sbt-zipx 0.11.0)

Every claim measured so far reproduced. Run ids are in `early-effect/zipx-ci-lab` Actions.

| # | Runs | Measured |
| --- | --- | --- |
| L1 | 36153148986, 36153672453, 36153673948, 36153673364 | Each `svcA`-only PR saved 9 build-cache entries (296 to 389 MB each). `test` restored `main`'s `docker` entry, never a test entry, and recompiled 13 module configurations; every run ran all 6 suites. After one wave of four PRs only 1 of `main`'s 6 entries survived; the repo held 11.6 to 14.7 GB against the 10 GB quota. |
| L2 | 36150056478 onward | The coverage `test` job saves under the same prefix as every other job. Locally, an instrumented `svcA` compile followed by `reload; svcA/Docker/stage` staged a jar with no scoverage reference: sbt 2 keys the compile on scalac options, so isolation holds today only through that keying. The labeled-PR run is not recorded yet. |
| L3 | 36152262310 (A), 36152583774 (B), 36152610608 (C) | A waited on `lab-prd`. B sat pending behind it, then was cancelled when C was merged, so B's merge never got a `main` run. C sat pending 55 s. C was `svcB`-only (`affected` = `["svcB"]`), yet it still ran 4 `docker` jobs, both `lab-stg` deploys, and asked for `lab-prd` approval for both workers: `deploy-workers` gates on "affected is non-empty", not on its own module. |
| L4 | none | The workflow does not exist on 0.11.0. |
| L5 | 36154112865 (`lib`), 36154113117 (`workerB`) | `lib`: `affected` = lib plus its 4 dependents. `workerB`: `affected` = `["workerB"]`. Both ran all 6 suites, `image-it`, and `legacy`. |
| L6 | 36154111518 | `affected` = every module, from a catalog row only `svcB` selects. |
| L7 | 36154114240 (`svcA` setting), 36154113879 (shared `val`) | Both `affected` = every module. |
| L8 | not run | Added after the first baselines: a production build's `core` embeds `gitCommitHash` through BuildInfo. |

One run outside the scenario list: 36153908391 changed only `lab/`, which no module owns. `affected` was `[]`, and `test` still ran all 6 suites while `image-it` and `legacy` both ran.

## Environments

- `lab-stg`: open.
- `lab-prd`: requires a reviewer.
