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
| `Coverage.once(name = test)` (0.11.0 baseline) | Coverage is the required `test` job, runs over the whole build, and saves its instrumented snapshot under the shared cache prefix. The lab now uses `Coverage.workflow` (L2). |
| `docker`, `registry`, `deploy-workers` on every push to `main` (0.11.0 baseline) | Every merge builds and pushes images and deploys to `lab-stg` and `lab-prd`. The lab now dispatches them from `zipx-deploy.yml` (L3, L4). |
| `lab-prd` requires a reviewer | Under the baseline, a waiting approval holds the `CI-refs/heads/main` concurrency group. |
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

## Testing a zipx branch

A zipx branch under test is published into `project/zipx-snapshot/` as a Maven directory and committed, so CI resolves
it without a registry token. `project/resolvers.sbt` points the meta-build at it. From the zipx clone, on the branch:

```
sbt 'set ThisBuild / version := "<next>-<topic>-<sha8>"; set every publishTo := Some(MavenCache("zipx-snapshot", file("<lab>/project/zipx-snapshot"))); set every packageDoc / publishArtifact := false; set every packageSrc / publishArtifact := false; publish'
```

Then set that version in `project/plugins.sbt`, run `sbt zipxWorkflowGenerate` here, and open a PR. Delete the old
version's directory when you replace it.

## Scenarios

Each baseline must fail as stated on sbt-zipx 0.11.0. A baseline that does not fail disproves its claim.

| # | Scenario | 0.11.0 baseline (expected failure) | Pass criterion after the fix |
| --- | --- | --- | --- |
| L1 | Four PRs, each touching only `svcA` sources | main's entry is evicted by the third run; `test` restores a non-test entry; all modules recompile | one save per run; `test` restores the build entry from main; recompiles only `svcA`; main's entry survives 10 runs |
| L2 | Coverage on a labeled PR, then an image build | `image` restores the instrumented entry and recompiles | the coverage workflow saves nothing; no class the `image` job compiled or restored references `coverage/Invoker`; `image` recompiles nothing |
| L3 | Merge A, deploy waits on `lab-prd`; merge B; merge C | B stays pending behind A's approval; C cancels pending B | merges run no image or deploy jobs; a dispatched `prd` deploy waits while B's and C's CI completes |
| L4 | Deploy `changed` to `stg` twice, one `svcB` merge between | no such workflow | the second plan is `svcB` only, resolved from the Deployments API; an existing image tag is not rebuilt |
| L5 | A `lib`-only change, an `svcA`-only change, and a `legacy`-only change | `test` runs every suite; `image-it` and `legacy` run regardless | `test` runs only the affected closure; `image-it` runs when an image module changes and skips otherwise; `legacy` runs only for a `legacy` change |
| L6 | Bump `fansi`, a row only `svcB` uses | affected is `all` | affected is `svcB` |
| L7 | Edit one setting in `svcA`'s block; separately edit a shared `val` | both give `all` | the first gives `svcA`; the second stays `all` |
| L8 | A per-commit value (`BuildInfo` git hash) in `lib`, then a commit that changes nothing else | every module downstream of `lib` recompiles on every commit | disproven locally; see the results |

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
| L8 | local only | **Disproven.** With `target/` intact, as `zipx-sbt-setup` restores it, a hash-only commit recompiled one source in `lib` (the regenerated `BuildInfo.scala`) and nothing downstream: zinc sees no API change. A per-commit BuildInfo value costs one source per commit, not a rebuild of its dependents. |

One run outside the scenario list: 36153908391 changed only `lab/`, which no module owns. `affected` was `[]`, and `test` still ran all 6 suites while `image-it` and `legacy` both ran.

## Fix results

| # | zipx branch | Runs | Measured |
| --- | --- | --- | --- |
| L1 | [#160](https://github.com/early-effect/zipx/pull/160), `0.11.1-cachemodes-18852bcf` | 36174987364, 36174988651, 36174987715, 36174986740, then two re-push waves (36178784901 onward, 36180280788 onward) | **Pass.** 1 build-cache save per run in all 12 runs. First push: `test` restored `main`'s `build` rehydrate entry and compiled only `svcA` and `svcAJS`; `image` rows compiled nothing or `svcA` alone. Re-pushes: `test` restored the PR's own previous save and compiled nothing. `main`'s `build` entry was still present after 13 runs with the repo over quota. The lab ran the builtin `test`, with coverage on a label, since coverage recompiles everything whatever the cache holds (L2). |
| L2 | [#161](https://github.com/early-effect/zipx/pull/161), `0.11.2-coverage-b348d41` | coverage: 36195519814 (labeled PR #21), 36195830706 (re-push), 36195672471 (dispatch on `main`), 36196537090 (unrelated label); `ci.yml`: 36195518972, 36195830801 | **Pass.** Coverage runs in `zipx-coverage.yml`: 0 build-cache saves in all 3 coverage runs, each restoring a plain `build` entry, and no cache entry carries a coverage key. On both pushes `image (svcA)` compiled nothing, and a step found no class under `target/out/jvm` referencing `coverage/Invoker`. Adding `documentation` to the labeled PR started a coverage run whose job skipped; the `opened` run was cancelled by the `labeled` one. The baseline's local check looked for "scoverage", but Scala 3 instruments with `scala/runtime/coverage/Invoker`, so this check reads class files for `coverage/Invoker`. |
| L3 | [#162](https://github.com/early-effect/zipx/pull/162), `0.11.3-deploy-ebbd715` | setup merge 36206691241; `prd` dispatch 36207947133; B 36208158089 (#24); C 36208165265 (#25) | **Pass.** Each merge ran only `verify-gate`, `modver`, and `cache-rehydrate`: no image push and no deploy. The dispatched `prd` deploy pushed 4 images, skipped both `stg` jobs without asking for approval, and waited on `lab-prd` for its 2 worker jobs. B and C merged while it waited: B pending 1 s, C 63 s, which was C queuing behind B in `ci.yml`'s own group (created 01:21:48, started when B finished at 01:22:51). Neither was held or cancelled by the deploy. The `prd` wait was rejected after measuring. |
| L4 | [#162](https://github.com/early-effect/zipx/pull/162), `0.11.3-deploy-ebbd715` | `changed` to `stg`: 36208686659 (at C), then D (#26, `svcB` only), then 36209126670 (at D); `all` to `stg`: 36209461137 (at D) | **Pass.** The first plan read each image's last deploy from GitHub (the setup commit) and shipped `images ["svcB","workerA","workerB"]`, `stg ["workerA","workerB"]`; `svcA`, unchanged since, was skipped. The second plan was `images ["svcB"]`, targets `{}`: only `docker svcB` and `registry svcB` ran. With `all` at the same commit, `svcB`'s tag check found `main-d35400f…` and skipped the rebuild while the other three, with no tag at that commit, pushed. GitHub's `lab-stg` records carry `…/commit/<sha>#<module>` for exactly the four worker deploys; the rejected `prd` deploys are `FAILURE` with no url. |
| L5 | [#163](https://github.com/early-effect/zipx/pull/163), `0.11.4-affected-5633282`, then `0.11.4-affected-c9c4df0` | `lib` #29 (36212465567) and again as #34 (36214105521) on `c9c4df0`; `svcA` #30 (36212473044); `legacy` #31 (36212480078) | **Pass.** `test` ran `zipxTestAffected` against the PR base. `lib`: tested lib and its 4 dependents (5 suites; `models` untouched), `image-it` ran, `legacy` skipped, identically on both snapshots. `svcA`: tested `svcA` and `svcAJS`, the two rows of the cross-built module (1 suite), `image-it` ran, `legacy` skipped. `legacy`: tested nothing (0 suites; `legacy` is outside the root aggregate), `image-it` skipped, `legacy` ran. Two fixes came out of the lab: the first setup run tested `root` beside every module, which reran every suite, so aggregators are excluded; and `5633282` ran the modules as a sequential `;` session, so `c9c4df0` runs them as one parallel `all` command (lab #33: every CI-relevant module, 6 suites, in an 11 s test command). |
| L6 | [#166](https://github.com/early-effect/zipx/pull/166), `0.11.5-catalog-0972003` | #36 (36244089867), the same `fansi` 0.5.1 → 0.5.0 change as baseline #9 | **Pass.** `affected` published `["svcB"]`, where the baseline published every module. `test` logged `zipx: project/ZipxVersions.scala: com.lihaoyi:fansi moved`, then tested `svcB` alone (1 suite). zipx read the catalog at both commits and found one `Lib` version literal moved, with the rest of the file unchanged. |
| L7 | [#168](https://github.com/early-effect/zipx/pull/168), `0.11.6-buildsbt-cd2f9a2` | `svcA` setting: #39 (36247517227); shared `def`: #40 (36247538883), the same changes as baselines #13 and #12 | **Pass.** The `svcA` setting published `["imageIt","svcA","svcAJS"]`, where the baseline published every module: `test` logged `zipx: build.sbt: affects imageIt, svcA, svcAJS` and tested `svcA` alone (1 suite), and only `image (svcA)` and `image-it` built. `imageIt` is in the set because it names `svcA` through `val svcAJvm = LocalProject("svcA")`. The shared `publishedLibrary` change logged `zipx: build.sbt: build-wide` and stayed every module (6 suites). |

## Environments

- `lab-stg`: open.
- `lab-prd`: requires a reviewer.
