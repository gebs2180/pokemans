# Dependency triage, 2026-10-26

The ten open Dependabot PRs, reviewed before the closed beta. Nothing has been merged or closed.

**How it was checked.** Every PR was 42–51 commits behind `main`, so its commits were cherry-picked onto `main` at `15a85f2` and checked there, not on the Dependabot branch.

- **Backend:** `./gradlew check` with Docker Desktop running and `POKEMANS_REQUIRE_DOCKER=true`. The last run, with #4, #5 and #6 stacked, used `--rerun-tasks` so nothing came from the build cache.
- **Web:** `npm ci`, `npm run lint`, `npx tsc -b`, `npm test` and `npm run build`. With TypeScript 7 I also added a deliberate type error to confirm `tsc -b` still fails on it. It does: TS2322, exit 1.
- **GitHub Actions bumps:** these can only be tested by CI on the PR itself. `ci.yml` hasn't changed on `main` since those PRs were opened, so their CI runs still apply. Each pinned SHA was checked against its upstream tag, and they all match.

## Verdicts

| PR | Bump | Verdict | Checks run (rebased on main) | Breaking changes found | CI on the PR |
|---|---|---|---|---|---|
| [#4](https://github.com/gebs2180/pokemans/pull/4) | JUnit BOM 5.13.4 → 6.1.3 (valuation-core) | **Merge as is** | `gradlew check`: valuation-core 128/128. Also passes stacked with #5 and #6: valuation-core 128/128, app 165/166 with 1 skipped (`RealPhotoCalibrationTest`, which needs `POKEMANS_REAL_PHOTOS`), coverage gate passes | 6.0 requires Java 17+ and Kotlin 2.2+, and removes `junit-platform-runner`, `MethodOrderer.Alphanumeric`, `junit.jupiter.tempdir.scope` and some deprecated Platform APIs. We use none of them. `@CsvSource` parsing still passes (`CardValuatorTest`, `StalenessTest`). `app` already gets JUnit 6 from the Spring Boot 4.1 BOM, so this aligns the two modules | backend ✅ web ✅ |
| [#5](https://github.com/gebs2180/pokemans/pull/5) | AssertJ 3.27.4 → 3.27.7 | **Merge as is** | `gradlew check`: valuation-core 128/128 | None. 3.27.5 fixes Byte Buddy on Java 25, which is our JDK. 3.27.7 fixes an XXE in `isXmlEqualTo` (CVE-2026-24400) and deprecates `XmlStringPrettyFormatter` | backend ✅ web ✅ |
| [#6](https://github.com/gebs2180/pokemans/pull/6) | jqwik 1.9.3 → 1.10.2 | **Hold (your call)** | `gradlew check`: valuation-core 128/128, including the 9 `ValuationPropertiesTest` properties. Also passes alongside JUnit 6 (#4) | No API breaks for us, and the license is still EPL-2.0. But upstream lists this as a breaking change in 1.10.1: *"Use of jqwik >= 1.10 with coding agents is strongly discouraged"*. The user guide now has an "Anti-AI Usage Clause", and this repo is built with Claude Code. 1.10.1 also says it is probably the last release on JUnit Platform 1.x, and any later release will be on Platform 6 | backend ✅ web ✅ |
| [#8](https://github.com/gebs2180/pokemans/pull/8) | TypeScript 6.0.3 → 7.0.2 | **Merge as is** | lint ✅ `tsc -b` ✅ test ✅ build ✅. Also passes with #9 | 7.0 is the native (Go) compiler. It ships no compiler API until 7.1, so tools that need it (typescript-eslint, some IDE setups) must keep 6.0 alongside it. Nothing in `web/` needs it: oxlint, Vite and Vitest don't call it. 6.0's deprecated options are now errors, but our tsconfigs use none (es2023, `bundler` and `nodenext`). Editor note: VS Code's "use workspace TypeScript" needs the TypeScript native preview extension with 7.0 | backend ✅ web ✅ |
| [#9](https://github.com/gebs2180/pokemans/pull/9) | @types/node 24 → 26 | **Hold** | lint ✅ `tsc -b` ✅ test ✅ build ✅ | Nothing breaks, but the types would no longer match the runtime. CI (`node-version: '24'`), `web/Dockerfile` (`node:24-bookworm-slim`) and local dev all run Node 24. Node 26 types would let `vite.config.ts` use Node 26-only APIs that then fail at build time. Take this when the runtime moves to Node 26 | backend ✅ web ✅ |
| [#2](https://github.com/gebs2180/pokemans/pull/2) | actions/checkout v5.1.0 → v7.0.1 | **Merge as is** | SHA `3d3c42e` = v7.0.1 ✅ | v6 stores persisted credentials in a separate file. v7 (and v6.1.0) block checking out fork PRs under `pull_request_target`/`workflow_run` unless `allow-unsafe-pr-checkout` is set. We only use `pull_request` and `push`, so neither affects us | backend ✅ web ✅ |
| [#7](https://github.com/gebs2180/pokemans/pull/7) | actions/setup-java v5.7.0 → v6.1.0 | **Merge as is** | SHA `b843ebf` = v6.1.0 ✅ | v6 checks JDK downloads against vendor checksums and turns on signature verification by default for supported distributions (Temurin included). It also removes the legacy AdoptOpenJDK distributions (we use `temurin`) and renames `jdkFile` to `jdk-file` (unused here) | backend ✅ web ✅ |
| [#3](https://github.com/gebs2180/pokemans/pull/3) | actions/setup-node v5.0.0 → v7.1.0 | **Merge as is** | SHA `949feb2` = v7.1.0 ✅ | v6 limits automatic caching to npm, and we set `cache: npm` explicitly anyway. v7 is ESM and no longer exports a dummy `NODE_AUTH_TOKEN`. We don't publish to npm | backend ✅ web ✅ |
| [#1](https://github.com/gebs2180/pokemans/pull/1) | actions/upload-artifact v5.0.0 → v7.0.2 | **Merge as is** | SHA `cf430e0` = v7.0.2 ✅ | v6 runs on Node 24 and needs runner 2.327.1+, which GitHub-hosted runners have. v7 is ESM and adds an opt-in `archive: false`. Our step uploads a directory with the default zip behaviour, so nothing changes | backend ✅ web ✅ (the upload step runs `if: always()`) |
| [#14](https://github.com/gebs2180/pokemans/pull/14) | gradle/actions/setup-gradle v5.0.2 → v6.4.0 | **Merge my replacement, [#32](https://github.com/gebs2180/pokemans/pull/32)** (or #14 if you accept Gradle's terms) | SHA `3f5f9ad` = v6.4.0 ✅ | Nothing breaks: #14's CI passes. The issue is the license. In v6 the default caching moved into `gradle-actions-caching`, a closed-source component under [Gradle's Terms of Use](https://gradle.com/legal/terms-of-use/), and upgrading means accepting those terms. Gradle says it stays free for public repos and may add restrictions later. #32 sets `cache-provider: basic`, the MIT-licensed provider added in v6.1.0, which has no cleanup or deduplication. v6 also drops the experimental configuration-cache support, which we don't use | #14: backend ✅ web ✅. #32: backend ✅ web ✅ CodeQL ✅. The log confirms "basic open-source caching provider". The first run had a cold cache (backend 4m14s vs ~2m40s); later runs restore it |

## Recommended merge order

Every bump in one group touches the same file, and the GitHub Actions ones touch adjacent lines of `ci.yml`. I applied each group in the order below to check for conflicts.

1. **Backend: #4, then #5.** They change neighbouring lines of `backend/valuation-core/build.gradle.kts` but stack without conflicts. JUnit goes first so AssertJ is tested on the final platform. If you decide to take #6, it goes last and stacks cleanly too.
2. **Web: #8.** It touches only `web/package.json` and `package-lock.json`. #9 also stacks on it cleanly if you take it later.
3. **CI, in this order:**
   1. **#2** (checkout). It changes both jobs' first step.
   2. **#7** (setup-java) and **#3** (setup-node). Each **conflicts with #2** because they change the line right after the checkout step. After #2 is merged, Dependabot normally rebases them by itself; if not, comment `@dependabot rebase` on each. Let CI run on each, then merge.
   3. **#1** (upload-artifact) and **#32** (setup-gradle). Neither conflicts with the others, so merge them in any order.
   4. Close #14 once #32 is in, or the other way round.

The three groups don't share any files, so you can merge them in any order between groups.

## Not done here (follow-ups)

- `.github/dependabot.yml` doesn't ignore `@types/node` majors, so Dependabot will keep proposing the next major ahead of the runtime. Adding an `ignore` for `version-update:semver-major` on `@types/node` until Node moves would stop that.
- `npm ci` warns about the deprecated `glob@11.1.0` (a transitive dependency) and an unapproved `browser-tabs-lock` postinstall script. The same warnings appear on `main`, so neither comes from these PRs.
