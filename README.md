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

## Baseline results (sbt-zipx 0.11.0)

Not yet recorded.

## Environments

- `lab-stg`: open.
- `lab-prd`: requires a reviewer.
