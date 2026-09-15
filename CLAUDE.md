# Gravel

Gravel is an **unofficial, Android-only fork** of the Core Devices Pebble
mobile app (upstream name: CoreApp), not affiliated with or endorsed by
Core Devices, distributed under GPLv3 (see `LICENSE`). What Gravel is, its
goals, status, and scope live in `README.md`; this file is the rules for
changing it. How the de-Googling is implemented (the DI seams, the
Firebase stubs, the unplugged Ring module) lives in `DESIGN_NOTES.md`.

## Project rules

- **Android-only.** iOS sources remain in-tree but are unmaintained. Do not
  write, fix, or build iOS code.
- **De-Google at the DI seam, not by mass deletion.** Swap Firebase/GMS-backed
  implementations for no-op or GMS-free ones at the Koin module level, keeping
  upstream call sites intact so upstream merges stay cheap. `DESIGN_NOTES.md`
  maps the existing seams (the `:firebase-stubs` and `:haversine-stubs`
  modules, the unplugged `experimental` module, the no-op `LibIndex`);
  extend those seams rather than deleting upstream code. The FCM push stack
  is the one deliberate exception to the seam rule: push either registers a
  device token with Google or does not exist, so `PushMessaging`,
  `PushService`, and their call sites were deleted outright rather than
  no-opped; do not read that deletion as precedent for other strips. The
  google-services plugin and the Firebase SDKs are gone, so no
  `google-services.json` (dummy or real) is needed or consumed.
- **The speech engine is whisper.cpp, built from source.** Upstream's
  proprietary Cactus engine modules are replaced by Gravel's
  `:whisper`/`:whisper-native` pair; the engine is a pinned git submodule
  at `whisper-native/src/main/cpp/whisper.cpp`, so clone with
  `--recursive` (or run `git submodule update --init`) before building.
  Model weights are integrity-pinned runtime downloads, never committed;
  the re-pin procedure lives in `WhisperModelCatalog`.
- **Distinct branding: the app is Gravel.** applicationId is
  `com.anopticlabs.gravel`; the Kotlin `namespace` and source packages
  deliberately stay `coredevices.coreapp` so upstream merges stay cheap;
  only the applicationId and user-facing branding are renamed (asset and
  color details in `DESIGN_NOTES.md`). adb commands in upstream code
  comments (the debug receivers' KDocs, for example) still name
  `coredevices.coreapp` as the installed package; substitute
  `com.anopticlabs.gravel`. Nominative references to Pebble watches and
  Core Devices stay (they state compatibility and attribution); using their
  branding as ours does not.
- **Verify against the artifact.** De-Google changes are confirmed against the
  built APK (no `com.google.firebase`/`com.google.android.gms` classes, no
  unexpected network endpoints), not just against the source tree.
- **Minimal new dependencies.** Every new or upgraded dependency is vetted
  before code is written against it: actively maintained, current stable
  version, no known advisories, non-deprecated API surface, and a real need
  that the standard library or existing deps cannot meet.
- **Stay F-Droid deliverable.** Gravel is in F-Droid's main repository,
  so a change must not add non-free dependencies, dependencies from
  repositories F-Droid does not allow, or prebuilt binaries in the tree.
  `FdroidGuardrailsTest` in `:androidApp` replicates F-Droid's source
  scanner over the tracked tree, the whisper.cpp submodule included, minus
  the paths the F-Droid recipe removes (dependency lines in every gradle
  file, iOS-only and unplugged modules included; maven URLs; checked-in
  binaries; lockfile-less dependency manifests), and defines the envelope:
  it must stay green, and a change that needs it loosened is a change that
  breaks the listing. The recipe contract the tree assumes is in
  `DESIGN_NOTES.md` (F-Droid section).
- **Security first.** No known security flaw ships, however small; genuinely
  deferred issues are documented in `KNOWN_ISSUES.md` with rationale. Any
  cross-app interface (e.g. the third-party mic API) must be explicitly
  authorization-gated (signature/knownSigner-level permissions or equivalent),
  never openly exported. Vulnerability reports follow `SECURITY.md`.
- **Tests come with the change.** New logic (parsers, encoders, state
  machines, anything a later unrelated change could silently break) gets
  unit tests in the same change; prefer unit tests over instrumented ones.
  Android unit tests live in `src/androidHostTest` in the KMP modules and
  instrumented tests in `androidApp/src/androidTest`. Do not add tests under
  the pre-AGP 9 names `androidUnitTest` / `androidInstrumentedTest`: the
  current plugins silently ignore those directories.
- **Comments: intent and breakable constraints, up to a ceiling.** New and
  changed code gets a comment where its intent is not obvious from its name,
  and wherever it relies on a constraint someone could break (ordering,
  threading, a platform behaviour, a difference from upstream). It does not
  get comments that restate the code, repeat a fact stated elsewhere, or
  claim more than a test or a cited source backs. Also banned: comments
  naming a rejected alternative ("X, not Y"), ticket or issue references,
  and comments defending why a change is correct; that story goes in the
  commit message. Roughly one comment line per five lines of code across a
  change is a ceiling, not a target: fewer is fine, code that is clear from
  its names and structure needs no comment, and nothing is added to reach
  the ratio. The reason Gravel comments more than upstream is working
  conditions: this is one person's free-time project, worked on AI-assisted,
  mostly at weekends around a demanding day job, so whoever picks the code up
  next (the maintainer after a week of unrelated work, or an agent with no
  session history) starts cold, and most Gravel changes remove or substitute
  code Gravel did not write.
- **License compliance.** Keep `LICENSE` and copyright notices intact; changes
  are tracked through git history per GPLv3 §5. Gravel has no commercial
  license and no contributor license agreement.
- **Branch discipline.** Feature work happens on branches, each reviewed
  before merging to master. Commits are logical units that build and pass
  tests, with an imperative subject and a body explaining what and why.
- **Upstream syncs keep Gravel's side.** A merge or cherry-pick from upstream
  must not bring back what Gravel removed or replaced, including toolchain
  pins, the tag-packed versionCode (both below), Firebase/GMS dependencies,
  and upstream's `LICENSE-COMMERCIAL`. `README.md`, `CLAUDE.md`,
  `CONTRIBUTING.md`, and `SECURITY.md` are Gravel's own documents: on a
  conflict keep Gravel's version and carry over only upstream changes that
  apply to Gravel.

## Build and versioning

- **Any JDK 17 or newer builds the tree; there is no toolchain pin.**
  F-Droid's buildserver ships a single JDK (21) with Gradle toolchain
  provisioning switched off, so upstream's `jvmToolchain(17)` calls are
  removed and every JVM-flavoured target (android and `jvm()` alike) sets
  its bytecode target explicitly (`jvmTarget` / `compileOptions`, 17); a
  target without one follows the JDK running Gradle and emits different
  class files on 21 than on 17. `FdroidGuardrailsTest` fails on any
  toolchain pin (`jvmToolchain(` or `jvmToolchain {`, or a Java
  `toolchain {` block), which is what an upstream sync would bring back.
  CI builds on both 17 and 21.
- **versionCode is the commit count; versionName is `git describe --tags
  --first-parent HEAD`.** Upstream packs its newest reachable tag into
  versionCode; Gravel keeps the commit count because F-Droid's update
  checker and the version file below are built on it, and derives
  versionName from HEAD so a tag checkout reports exactly its own tag. The
  first-parent walk keeps upstream's tags, reachable through every sync
  merge, from describing Gravel commits, so release tags go on master's
  first-parent line (tag the release commit on master). Both are set lazily
  in `androidComponents.onVariants` so the git commands do not run during
  configuration.
- **A release commit bumps `androidApp/version.properties`.** F-Droid's
  update checker reads a tag's versionCode from that one-line file because
  it cannot count commits the way the build does. The commit that gets
  tagged (the same one that adds
  `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`) sets it
  to the commit count that commit will have, which is `git rev-list --count
  HEAD` plus one when committing on top of HEAD; between releases the file
  names the last release. The value checks fire only on a tag checkout,
  so after tagging and before pushing the tag run `./gradlew
  :androidApp:verifyReleaseVersionFile` (or the guardrail suite) on the
  tagged commit; otherwise a stale file is first rejected once the tag is
  public, by Gradle's `preBuild` (`VerifyReleaseVersionFile`),
  `FdroidGuardrailsTest`, and the tag-build step in CI alike. The test
  also requires `changelogs/<versionCode>.txt` to exist for the value the
  file names, on every commit.
- Gradle wrapper at the root (`./gradlew`); version catalog in
  `gradle/libs.versions.toml`. Debug build: `./gradlew
  :androidApp:assembleDebug`.

## Codebase

- Kotlin Multiplatform / Compose Multiplatform. Module source sets follow
  standard KMP (`src/commonMain/kotlin`, `src/androidMain/kotlin`); write
  shared code in `commonMain` and split out to `androidMain` only when the
  platform requires it.
- DI is Koin; navigation uses `androidx.navigation.compose`; logging uses
  Kermit; HTTP is Ktor (OkHttp engine); storage is Room.
- Compose resources are generated per module under
  `coreapp.<module>.generated.resources` (for example
  `coreapp.composeapp.generated.resources`).
- The module map is in `README.md` (Architecture).

## Code guidelines

- **Never use Kotlin `init` blocks.** They run on class creation, which
  happens during DI graph initialization and can block the main thread or
  run at an unexpected time. Use an explicit initialization method, called
  from somewhere sensible.
- **Minimal fixes.** Make the smallest change that fixes the problem. Do not
  bundle retry logic, defensive code, or other "improvements" with a
  root-cause fix.
- **Reuse existing abstractions at the correct scope.** Search for existing
  services, injectables, and utilities before writing new ones; `util` is
  the home for reusable generic utilities. Put state at the right DI scope:
  per-watch state belongs in per-watch services (for example
  `SystemService`), not in singletons such as `LibPebble3`.
- **Understand root causes before fixing.** No brute-force workarounds for
  symptoms, such as disabling every page transition to hide one broken
  animation.
- **Distinguish transient from deterministic failures.** Network errors are
  transient and may be retried; model or processing errors are
  deterministic and fail permanently, so they do not hold up a queue with
  retries.
- **Never blur state boundaries for marginal performance.** If the system
  defines states (transferring, complete), do not make something appear
  complete while work such as a file write is still running.
- **Use the existing permission flow.** Most code runs in background
  services where requesting permissions is not possible, and the app
  already prompts for missing permissions (see `RequiredPermissions` in
  `util`). Do not add manual `requestPermission()` calls in feature code;
  handle the denied case gracefully.
- **Keep changes single-purpose.** One concern per branch or PR.
- **Prefer simple architectures.** For example, keep a storage format
  unchanged and convert at the boundary rather than adding format dispatch
  or dual code paths.

## Android local release install

When asked to make a release build and install it on a local device:

1. Add or confirm `LOCAL_RELEASE_BUILD=true` in the root `local.properties`.
   This makes the release variant use the debug signing config, so it can
   install over an existing local/debug app without uninstalling.
2. Build from the repo root with `./gradlew :androidApp:assembleRelease
   --stacktrace --no-daemon`. Do not skip release lint unless the user
   explicitly asks.
3. Install over the existing app with `adb -s <device-id> install -r
   androidApp/build/outputs/apk/release/androidApp-release.apk`. Do not
   uninstall first unless explicitly requested.
4. Launch and verify with logcat:
    - `adb -s <device-id> logcat -c`
    - `adb -s <device-id> shell monkey -p com.anopticlabs.gravel -c
      android.intent.category.LAUNCHER 1`
    - wait long enough for `PebbleService` to start, then check for
      `FATAL EXCEPTION`, `ClassNotFoundException`, `Room cannot verify`, and
      `Process: com.anopticlabs.gravel`.

Release builds are minified. If a release-only crash appears, check the R8
keep rules before changing app logic.
