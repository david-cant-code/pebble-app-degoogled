# Gravel (de-Googled CoreApp fork)

Gravel is an **unofficial, Android-only fork** of the Core Devices Pebble
mobile app (upstream name: CoreApp), not affiliated with or endorsed by
Core Devices, distributed under GPLv3 (see `LICENSE`). What the fork is,
its goals, status, and scope live in `README.md`; this file is the rules
for changing it. How the de-Googling is implemented (the DI seams, the
Firebase stubs, the unplugged Ring module) lives in `DESIGN_NOTES.md`.

## Fork rules (these override the upstream rules below where they conflict)

- **Android-only.** iOS sources remain in-tree but are unmaintained. Do not
  write, fix, or build iOS code. This supersedes upstream's "all features
  must work on both Android and iOS" rule.
- **De-Google at the DI seam, not by mass deletion.** Swap Firebase/GMS-backed
  implementations for no-op or GMS-free ones at the Koin module level, keeping
  upstream call sites intact so upstream merges stay cheap. `DESIGN_NOTES.md`
  maps the existing seams (the `:firebase-stubs` and `:haversine-stubs`
  modules, the unplugged `experimental` module, the no-op `LibIndex`);
  extend those seams rather than deleting upstream code. The FCM push stack
  is the one deliberate exception to the seam rule: push either registers a
  device token with Google or does not exist, so `PushMessaging`,
  `PushService`, and their call sites were deleted outright rather than
  no-opped; do not read that deletion as precedent for other strips.
- **The speech engine is whisper.cpp, built from source.** Upstream's
  proprietary Cactus engine modules are replaced by the fork's
  `:whisper`/`:whisper-native` pair; the engine is a pinned git submodule
  at `whisper-native/src/main/cpp/whisper.cpp`, so clone with
  `--recursive` (or run `git submodule update --init`) before building.
  Model weights are integrity-pinned runtime downloads, never committed;
  the re-pin procedure lives in `WhisperModelCatalog`. Upstream mentions
  of `:cactus`/`:cactus-native` in the module list below predate the
  swap.
- **No `google-services.json`.** The google-services plugin and the Firebase
  SDKs are gone, so no config file (dummy or real) is needed or consumed;
  this supersedes the upstream build instruction below that still asks for
  `androidApp/src/google-services.json`.
- **Test source sets under AGP 9.** Android unit tests live in
  `src/androidHostTest` in the KMP modules and instrumented tests in
  `androidApp/src/androidTest`; upstream's "General editing info" line
  below still naming `androidUnitTest` / `androidInstrumentedTest`
  predates its own AGP 9 migration. Do not add tests under the old names:
  the new plugins silently ignore those directories.
- **Any JDK 17 or newer builds the tree; there is no toolchain pin.**
  F-Droid's buildserver ships a single JDK (21) with Gradle toolchain
  provisioning switched off, so upstream's `jvmToolchain(17)` calls are
  removed and every JVM-flavoured target (android and `jvm()` alike) sets
  its bytecode target explicitly (`jvmTarget` / `compileOptions`, 17); a
  target without one follows the JDK running Gradle and emits different
  class files on 21 than on 17. `FdroidGuardrailsTest` fails on any
  toolchain pin (`jvmToolchain(` or `jvmToolchain {`, or a Java
  `toolchain {` block), which is what an upstream sync would bring back.
  CI builds on both 17 and 21; this supersedes upstream's "JDK 17
  required" below.
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
- **Distinct branding: the fork is Gravel.** applicationId is
  `com.anopticlabs.gravel`; the Kotlin `namespace` and source packages
  deliberately stay `coredevices.coreapp` so upstream merges stay cheap;
  only the applicationId and user-facing branding are renamed (asset and
  color details in `DESIGN_NOTES.md`). Where docs reference
  `coredevices.coreapp` as the installed package (for example the adb
  launch and verify commands, and the adb instrumentation component in
  test KDocs), substitute `com.anopticlabs.gravel`. Nominative references
  to Pebble watches and Core Devices stay (they state compatibility and
  attribution); using their branding as ours does not.
- **Verify against the artifact.** De-Google changes are confirmed against the
  built APK (no `com.google.firebase`/`com.google.android.gms` classes, no
  unexpected network endpoints), not just against the source tree.
- **Minimal new dependencies.** Every new or upgraded dependency is vetted
  before code is written against it: actively maintained, current stable
  version, no known advisories, non-deprecated API surface, and a real need
  that the standard library or existing deps cannot meet.
- **Stay F-Droid deliverable.** The fork targets inclusion in F-Droid's
  main repository, so a change must not add non-free dependencies,
  dependencies from repositories F-Droid does not allow, or prebuilt
  binaries in the tree. `FdroidGuardrailsTest` in `:androidApp` replicates
  F-Droid's source scanner over the tracked tree, the whisper.cpp submodule
  included, minus the paths the F-Droid recipe removes (dependency lines in
  every gradle file, iOS-only and unplugged modules included; maven URLs;
  checked-in binaries; lockfile-less dependency manifests), and defines the
  envelope: it must stay green, and a change that needs it loosened is a
  change that breaks the target. The recipe contract the tree assumes is in
  `DESIGN_NOTES.md` (F-Droid section).
- **Security first.** No known security flaw ships, however small; genuinely
  deferred issues are documented in `KNOWN_ISSUES.md` with rationale. Any
  cross-app interface (e.g. the third-party mic API) must be explicitly
  authorization-gated (signature/knownSigner-level permissions or equivalent),
  never openly exported.
- **Comment density: this fork comments far more heavily than upstream, by
  choice.** This overrides the upstream comment guidance below on density
  only; upstream's other comment rules (no ticket references, no comments
  defending why a change is correct) still apply. New and changed code gets
  comments stating intent, constraints, and trade-offs, even where the why
  seems obvious at the time of writing. The reason is working conditions:
  this fork is one person's free-time project, worked on AI-assisted, mostly
  at weekends around a demanding day job, so whoever picks the code up next
  (the maintainer after a week of unrelated work, or an agent with no
  session history) starts cold, and most fork changes remove or substitute
  code the fork did not write.
- **License compliance.** Keep `LICENSE` and copyright notices intact; changes
  are tracked through git history per GPLv3 §5.
- **Branch discipline.** Feature work happens on branches, each reviewed
  before merging to master. Commits are logical units that build and pass
  tests, with an imperative subject and a body explaining what and why.

---

# Upstream documentation

Everything below is upstream's CLAUDE.md, kept intact so upstream merges stay
cheap. Where it conflicts with the fork rules above, the fork rules win
(notably: iOS instructions are unmaintained here, and the Ring/Index feature
module is unplugged from the build).

# CoreApp

Kotlin Multiplatform / Compose Multiplatform app targeting Android and iOS.

## Overview

- App supports modern Pebble/Core watches over BLE, exposing features like notifications, health data, watchfaces, and more.
- 'Index 01' AKA 'Ring' is a new ring device to record notes and ingest into the 'Index AI'. The 'experimental' module is dedicated to ring device features.
- The ring is not 'always-on' like a watch so is scanned for continuously.

## Platform Rules

- **All features must work on both Android and iOS.** Write shared code in `commonMain` whenever possible. If a feature cannot be implemented on one platform, alert the user before proceeding.
- **Do NOT modify the Ring recording processing pipeline.** The Ring Bluetooth audio capture, preprocessing, transcription, and agent processing flow works correctly. New audio input sources (e.g., phone mic) must massage their output into the format the existing pipeline expects (16kHz, PCM_16BIT, mono, raw) and feed into `queueLocalAudioProcessing(fileId)`.

## Repository layout

- `:composeApp` — shared Compose UI / Firebase / Cocoapods / Koin DI, and the iOS entry point. KMP library.
- `:androidApp` — Android application shell: manifest, launcher resources, signing, R8, google-services. `applicationId` is `coredevices.coreapp`; the Activity and Service classes it declares live in `:composeApp`.
- `:libpebble3` — KMP library for talking to Pebble/Core watches (BLE, protocol, services, endpoint managers). Mirrored from a standalone repo.
- `:pebble` — Pebble-related shared code used by the app.
- `:experimental` — newer/experimental device features (e.g. ring); see `coredevices.ring`.
- `:util` — shared utilities (logging, IO, etc.).
- `:mcp`, `:index-ai`, `:libindex` — AI/MCP-related modules.
- `:cactus`, `:resampler`, `:krisp-stubs` — audio/ML support modules. `:cactus-native` is a plain Android library holding cactus' CMake build and prebuilt `.so` (the KMP Android library plugin has no NDK support).
- `:blobannotations`, `:blobdbgen` — KSP annotations + code generator for Pebble blobdb.

iOS app project: `iosApp/iosApp.xcworkspace` (always open the `.xcworkspace`, not `.xcodeproj`).

## General editing info

- Source layout per module follows standard KMP: `src/commonMain/kotlin`, `src/androidMain/kotlin`, `src/iosMain/kotlin`, plus `commonTest` / `androidUnitTest` / `androidInstrumentedTest`.
- Compose resources are generated under the package `coreapp.composeapp.generated.resources`.
- `versionCode` is derived from git commit count; `versionName` requires a git tag (e.g. `git tag 1.0.0`) or falls back to `"unknown"`. Both are set lazily in `androidComponents.onVariants` so the git commands don't run during configuration.
- DI is Koin (`koin-core`, `koin-compose`, `koin-compose-viewmodel`); navigation uses `androidx.navigation.compose`; logging uses Kermit; HTTP is Ktor (OkHttp on Android, Darwin on iOS).
- Some dependencies are internally developed and published. You can still ask for the source code of these dependencies to be included in the session if you need more context but don't try looking for it in the filesystem.

## Guidance

- Project follows DRY principles; attempt to use shared code and split out to platform-specific when required or more performant. The `util` module can be used for potentially reusable generic utilities, even if it isn't reused now.
- Changes should reuse existing code where possible and loosely follow clean coding principles, this is a big project which requires production-level maintainable code and good separation of concerns to stay manageable.
- Write tests for new logic, and consider test coverage when modifying existing code. Try to stick to unit tests where possible.
- Never use kotlin class `init()` blocks. These are invoked on class creation (happens during DI graph init and could block main thread/happen at an unexpected time). Use an explicit initialization method if needed, called from somewhere sensible.
- We don't need every method documents with comments describing exactly what it does in detail. Just comment if something is interesting/not obvious.
- More on comments: you write comments which are far too verbose - please don't. Don't write a comment against code unless it's really required for someone to understand the code. Specifically banned:
  - "X, not Y" comments naming a rejected alternative — X is already in the code; nobody cares about Y.
  - Ticket/issue references (MOB-1234) in comments — the commit message carries that.
  - Comments defending why the change is correct — that's for the reviewer, and it's noise after merge.
  - Default to no comment on a fix. If the code has a genuine landmine someone would reintroduce later, one line stating the constraint itself; the full story goes in the commit message.

## Code Guidelines

### 1. Minimal fixes only — no scope creep

Make the smallest change that fixes the problem. Do not bundle extra "improvements," retry logic, or defensive code alongside a root-cause fix. If a bug is a one-line fix, the PR should be a one-line fix. Reviewers will extract the minimal fix and discard the rest.

- **Bad:** Fixing a stale token bug (`authStateChanged` → `idTokenChanged`) + adding retry loops + adding token refresh in UI layer. Reviewer merged only the one-line fix.
- **Bad:** Fixing a dispatcher issue (`Dispatchers.IO` → `Dispatchers.Default`) + restructuring file writes to be async fire-and-forget. Reviewer took only the dispatcher change.

### 2. Use existing abstractions at the correct scope — don't reinvent

Before writing new code, search for existing services, injectables, and utilities that already do what you need. This codebase uses dependency injection extensively. If something feels like it should exist, it probably does. When adding state, put it at the correct DI scope — per-watch state belongs in per-watch services, not in singletons.

- **Bad:** Manually querying DAOs and iterating trace sessions to find the right one to append to. The injectable `RingTraceSession` already tracks the current session.
- **Bad:** Writing temp files for audio playback. In-memory `MediaDataSource` (Android) avoids temp file management entirely.
- **Bad:** Putting per-watch timezone tracking state in `LibPebble3` (singleton). It belongs in `SystemService` (per-watch). Reviewer moved it.

### 3. Understand root causes before fixing

Do not apply brute-force workarounds to symptoms. Understand why something is happening before changing it.

- **Bad:** Removing `LookaheadScope` to "fix" an animation. The actual bug was a Compose recomposition where bounds changed from 0 to full width on first compose. The "fix" just made the UI blink jarringly instead.
- **Bad:** Setting `disableNextTransitionAnimation = true` by default. This disabled ALL page transitions, not just the problematic one.

### 4. Distinguish transient vs. deterministic failures

Do not mark deterministic failures as recoverable/retryable. If a transcription model can't handle audio, retrying won't change the outcome — it just holds up the processing queue for other recordings.

- **Rule:** Network errors = transient (retry). Model/processing errors = deterministic (fail permanently).

### 5. Never blur state boundaries for marginal performance

If the system defines clear states (transferring → complete), do not introduce async operations that make a recording appear "complete" while files are still being written. "It'll be fast enough" is not a correctness argument.

- **Bad:** Fire-and-forget async file write after marking recording complete. A user could see a transcription-failed file with no playable audio, or a bug report could be missing its audio attachment.

### 6. Use the existing permission-nag UI — don't add manual permission requests

Most code runs in background services where requesting permissions isn't possible. The app already has a UI that nags users about missing permissions. Don't add manual `requestPermission()` calls in feature code — rely on the existing permission flow and handle the denied case gracefully.

### 7. Keep PRs single-purpose

Each PR should address one concern. Do not bundle animation fixes + trace timeline + retry UI + error handling changes into one PR. Multi-concern PRs are harder to review and get closed as "rewritten" when any part is wrong.

### 8. Include tests for new logic

Non-trivial new logic (parsers, encoders, state machines) must have unit tests. When Alice rewrote the M4A feature, she included 324 lines of tests for the M4A parser. The original PR had zero.

### 9. Prefer simple architectures over clever ones

When adding a new format/encoding, prefer keeping the local storage format unchanged and converting at the boundary (upload/download). Don't introduce MIME-type dispatch, dual playback paths, or multi-step file replacement schemes when you can encode on upload and decode on download.

## Useful references

- Public-facing setup steps (iOS prerequisites, Firebase, signing): `README.md`.
- Contribution / licensing: `CONTRIBUTING.md`, `LICENSE`, `LICENSE-COMMERCIAL`.

## Building/installing

### Basics

- Gradle wrapper at the root: `./gradlew`.
- JDK 17 required. JVM target is 17 across modules.
- Version catalog: `gradle/libs.versions.toml`.
- Android: `./gradlew :androidApp:assembleDebug` / `assembleRelease`. Needs `androidApp/src/google-services.json` (a dummy is committed alongside).
- iOS: `./gradlew podInstall`, then build from Xcode against `iosApp/iosApp.xcworkspace`.
- The iOS framework is named `ComposeApp` and is wired up via the Kotlin Cocoapods plugin in `composeApp/build.gradle.kts`.

### iOS

- **iOS simulator linker fix:** `composeApp/build.gradle.kts` hardcodes the Swift compatibility lib search path to `.../usr/lib/swift/iphoneos`, which breaks simulator links (`ld: building for 'iOS-simulator', but linking in object file ... libswiftCompatibility*.a ... built for 'iOS'`). For local simulator builds, change that path's `iphoneos` to `$osName` (the `osName` val already computed just above resolves to `iphonesimulator` for the sim targets). Device builds are unaffected either way.
- **Iterating on iOS — swap the framework binary, don't rebuild Pebble.app.** A full xcodebuild from scratch takes ~30 min. After the first successful build of `Pebble.app`, every subsequent Kotlin source change only needs:
    1. `./gradlew :composeApp:linkPodDebugFrameworkIosSimulatorArm64` — relinks the K/N framework into `composeApp/build/bin/iosSimulatorArm64/podDebugFramework/ComposeApp.framework/`
    2. `cp` the new `ComposeApp` binary over `iosApp/build/Build/Products/Debug-iphonesimulator/Pebble.app/Frameworks/ComposeApp.framework/ComposeApp`
    3. `codesign --force --sign - --preserve-metadata=identifier,entitlements,flags --timestamp=none` on the framework, then on `Pebble.app` itself
    4. `xcrun simctl uninstall` + `install` + `launch` — no xcodebuild needed
       Only run a full `xcodebuild` again when you change Swift code, Info.plist, resources, or pod dependencies.

### Android local release install

When asked to make a release build and install it on a local device, follow the GitHub Actions release build shape instead of inventing a shortcut:

1. Add or confirm `LOCAL_RELEASE_BUILD=true` in the root `local.properties`. This makes the release variant use the debug signing config, so it can install over an existing local/debug app without uninstalling.
2. Build from the repo root with `./gradlew :androidApp:assembleRelease --stacktrace --no-daemon`. Do not skip release lint unless the user explicitly asks.
3. Install over the existing app with `adb -s <device-id> install -r androidApp/build/outputs/apk/release/androidApp-release.apk`. Do not uninstall first unless explicitly requested.
4. Launch and verify with logcat:
    - `adb -s <device-id> logcat -c`
    - `adb -s <device-id> shell monkey -p coredevices.coreapp -c android.intent.category.LAUNCHER 1`
    - wait long enough for `PebbleService` / Ring BLE scanning to start, then check for `FATAL EXCEPTION`, `ClassNotFoundException`, `Room cannot verify`, and `Process: coredevices.coreapp`.

Release builds are minified. If a release-only crash appears in Haversine/native BLE code, check R8 keep rules before changing app logic. In particular, the Haversine native library resolves `com.wtlp.haversinesatellitelibrary.logging.HaversineLog` by exact JVM class name, so the app proguard rules must keep that class.
