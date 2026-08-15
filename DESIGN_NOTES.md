# Design notes

How the de-Googling is implemented. `README.md` says what the fork is and
does; `CLAUDE.md` carries the rules for changing it; this file records the
architecture behind those rules, so that a change touching one of these
seams starts from the actual wiring rather than a guess.

## The DI-seam strategy

The fork removes Google Play services, Firebase, and the cloud-backed
Ring/Index AI features by swapping implementations at the Koin dependency
injection seam, not by deleting upstream code. Upstream call sites stay
intact, which keeps upstream merges cheap: a merge brings in new call
sites against the same interfaces, and the fork's bindings decide what
those calls actually do. Each seam below is additionally built so that
accidentally re-enabling the removed functionality fails loudly at build
time instead of quietly coming back.

## Ring / Index AI

The out-of-scope Ring/Index AI feature module (`experimental`) is
unplugged from the build (`settings.gradle.kts` + DI wiring) with sources
left in place so upstream merges stay cheap.

`libindex`, `index-ai`, and `mcp` stay compiled: the watch UI in `pebble`
compiles against `libindex`, whose Room schema references `index-ai`
entities, which need `mcp`. Their runtime is dead:

- a no-op `LibIndex` is bound at the Koin seam;
- fork stubs in `composeApp` replace the `experimental` types the app
  wiring touches, under the same fully-qualified names, so re-plugging
  the `experimental` module trips duplicate-class errors;
- `CoreConfig.enableIndex` has no reachable set-point.

The Ring satellite library `libindex` compiles against
(`io.github.coredevices.haversine`, a prebuilt AAR with no public source
that bundles two native libraries per ABI) is replaced by the fork module
`:haversine-stubs`: inert same-FQN stand-ins for the seven symbols
`libindex` references, whose satellite classes have private constructors
and no factory, so no ring object can exist anywhere in the app. The
wiring is a `dependencySubstitution` rule in `settings.gradle.kts` rather
than an edited dependency line, because the only consumer,
`libindex/build.gradle.kts`, is an upstream file the fork otherwise leaves
untouched; the rule matches the coordinate by group and name, so an
upstream version bump still lands on the stub. `AppClasspathSentinelTest`
in `:androidApp` pins the swap from the shipping runtime graph (the AAR's
wrapped `com.wtlp.*` vendor classes must be absent), and the release APK
carries no `libhaversinesatellitelibrary.so` / `libppcommon.so`. This was
the last prebuilt native code in the app and the last tree-level F-Droid
inclusion blocker.

## Firebase

The Firebase SDKs are gone via the same idea at a different seam: the
fork module `:firebase-stubs` shadows the gitlive `dev.gitlive.firebase.*`
artifacts with inert same-FQN stand-ins (permanently signed out, empty
never-authoritative store). Consequences:

- upstream call sites compile unchanged;
- any new upstream use of gitlive API fails the build until the stub
  surface is extended;
- re-adding the real artifacts trips duplicate classes.

`UsersDao` is additionally rebound at the Koin seam to the fork's
`SignedOutUsersDao`.

## The FCM exception

The FCM push stack is the one deliberate exception to the seam rule: push
either registers a device token with Google or does not exist, so
`PushMessaging`, `PushService`, and their call sites were deleted outright
rather than no-opped. That deletion is not precedent for other strips; the
seam rule stands everywhere else.

## Watchapp permissions (phone-side network and location)

Third-party watchapps and watchfaces ship companion JavaScript (PebbleKit
JS) that upstream runs on the phone inside a per-app `WebView`
(`WebViewJsRunner`, one at a time, started when the watch launches that
app). Through it the app's JS gets `navigator.geolocation` (bridged to real
phone GPS) and unrestricted network (XHR/fetch/WebSocket go straight out
Chromium's stack; the phone-side weather interceptors also egress on the
app's behalf). Upstream gated none of this and disclosed none of it: the
appstore capability vocabulary (`location`/`health`/`timeline`) has no
"network" entry, so an app that used location to call a weather API only
ever showed "location". This fork adds a per-app, tri-state permission
system over those two capabilities, denied by default.

**Decision authority.** `WatchappPermissionResolver`
(`locker/WatchappPermissions.kt`) is the single place that resolves a
grant. A capability is stored per app in the existing
`LockerAppPermission` Room table as tri-state: no row = FollowGlobal
(inherit the global default), `granted=true` = Allow, `granted=false` =
Deny. Per-app rows win over the global default in either direction.
"Reset to default" is a row delete, not a third stored state. The global
defaults are `WatchConfig.watchappDefaultNetworkAllowed` /
`watchappDefaultLocationAllowed`, both shipping `false`. An upgrading
install inherits deny because the fields deserialize to their defaults;
a fresh install is offered the choice during onboarding. The resolver is
exposed on `LibPebble` via the `WatchappPermissions` interface for the UI.

**Location enforcement** is a single deterministic gate resolved through
the resolver (upstream read a row nothing ever wrote and defaulted to
"granted", so the gate was inert). One-shot requests
(`getCurrentPosition`) check the grant per call; continuous
subscriptions (`watchPosition`) collect the resolved grant as a live
flow, so revoking Location mid-session cancels the running GPS stream
immediately (a watchface can hold a watch for days) and re-granting
restarts it. Denial is reported to the JS callback as a geolocation
error in both cases.

**Network enforcement is layered** (three independent layers, per the
defence-in-depth rule, with at least one deterministic cover for every
socket type):

1. `WebViewJsRunner.shouldInterceptRequest` returns a 403 for every
   non-`file://` request when the app is network-denied. Deterministic for
   http/https (XHR, fetch, subresources, navigations). WebSocket
   handshakes do not pass through this callback (a documented WebView
   limitation), which is why layer 3 exists.
2. `startup.js` wraps `XMLHttpRequest`/`fetch`/`WebSocket`/
   `EventSource`/`sendBeacon` in failing guards when the app loads while
   denied, gated on a synchronous `_Pebble.isNetworkAllowed()` bridge
   call. The guards re-read the live grant on every use and restore the
   original entry points the moment access is granted, so granting a
   running app takes effect without a session restart (the page loads
   only once per session, so a load-time-only stub would freeze the deny
   until the app restarts). They do not reinstall on a later revoke; the
   native layers enforce that direction live. Best-effort (same-realm JS
   a hostile bundle could try to work around), so it never stands alone.
3. `ProxyController` (androidx.webkit) sets a process-wide WebView proxy
   override that black-holes all egress (every scheme, including ws/wss)
   to an unroutable address while a network-denied app runs, and clears it
   otherwise. Chromium's implicit proxy-bypass rules are removed so
   localhost and link-local destinations are black-holed too; both are
   real egress (other apps' local socket servers, hosts on the same
   network segment). Applied and awaited before the app page loads and
   kept in sync with live toggles; on stop, the live-toggle collector is
   cancelled first and the override is cleared only after the WebView is
   destroyed, so a denied app's scripts never run without the black-hole
   and nothing can re-install it once teardown has cleared it.
   Process-global is inherent to the API, but only one PKJS WebView runs
   at a time and the config page (below) is gated for denied apps, so
   nothing legitimate needs the network while it is set. Requires the
   `PROXY_OVERRIDE` feature; the degraded case is in `KNOWN_ISSUES.md`.

A deny-to-allow flip while the app is running also restarts its PKJS
session (`CompanionAppLifecycleManager` funnels the restart through the
same serially processed stream as watch-side app switches): an app that
fetches only at launch never touches the network again after its first
attempt fails, so without the restart a mid-session grant would take
visible effect only at the next app switch. Allow-to-deny needs no
restart; the enforcement layers apply it live.

The phone-side interceptor path (`PrivatePKJSInterface.onIntercepted`,
which the weather interceptors use to fetch on the app's behalf) is gated
separately and deterministically: it is exposed directly on the `_Pebble`
bridge, so a hostile bundle could call it without going through the XHR
shim, and gating it in Kotlin, not only in JS, is what actually closes
the egress-on-behalf vector.

**Config page.** A `configurable` app's developer settings page URL is
built by the app's own JS and loaded in a WebView, so opening it is an
app-controlled network request to a developer-chosen server that can carry
anything the app gathered (location included). `CommonApp.showSettings`
therefore refuses to open it when the app is network-denied and points the
user at the settings screen, rather than leaving a hole the size of the
control.

**Surfaces.** Settings > Apps > Watch App Permissions
(`WatchappPermissionsScreen`) holds the global defaults and an app list;
the per-app tri-state controls live on each app's detail page
(`WatchappPermissionControls`, reused by the list). Store listings gain an
honest disclosure that phone-side code can reach the internet, and the
location capability description states the real "may send it to outside
servers" flow. A one-time "What's New" dialog announces the deny-by-default
change to existing users (`WhatsNewDialog`).

**Dependency.** `androidx.webkit` (1.16.0) is added for `ProxyController`
only: current stable, Apache-2.0, on Google's Maven (F-Droid
deliverable), no known advisories, non-deprecated API surface.

## The whisper speech engine

Upstream's on-device dictation ran on the proprietary Cactus engine: a
prebuilt 57.7 MB `libcactus_engine.so` checked into git with no source
in the tree, under a source-available license with commercial revenue
caps. That is not free software, which blocked the F-Droid goal, and it
conflicts with distributing this fork's GPLv3 APKs, so the speech stack
is the second place (after FCM) where the fork replaces upstream code
outright instead of no-opping it at a seam: dictation is a core watch
feature, and its only other backends are cloud services or the
GMS-backed platform recognizer, both dead ends here.

The replacement is whisper.cpp (MIT), compiled from source:

- `whisper-native/src/main/cpp/whisper.cpp` is a git submodule pinned to
  an upstream release tag (clone with `--recursive`). The CMake build in
  `:whisper-native` statically links the engine into one JNI shim,
  `libwhisperjni.so`, with the network, server, example, and dynamic
  backend surfaces disabled; a linker version script exports only the
  JNI entry points so the statically linked engine symbols cannot be
  interposed.
- CPU gating uses two libraries. The engine is compiled for
  armv8.2-a with dotprod and fp16 (ggml selects CPU features at compile
  time, with no runtime dispatch), so a tiny baseline-architecture
  probe, `libwhispercpu.so`, checks the hwcaps first, and no engine code
  is mapped until it passes.
- `:whisper` holds the Kotlin bindings: a six-function expect/actual
  surface whose iOS actuals are unsupported stubs, keeping commonMain
  compiling for the unmaintained iOS targets. Engine strings cross JNI
  as UTF-8 byte arrays: engine output can be byte sequences that are
  invalid modified UTF-8, which NewStringUTF aborts on under CheckJNI.
- `WhisperTranscriptionService` (util) keeps the Cactus-era service's
  proven shape: config-driven re-initialization, the two-mutex warm-up
  design, the memory guard, and the InferenceBoost foreground-priority
  seam. Unlike the Cactus-era service, the handle's init/free runs under
  the same mutex the transcriptions hold, so a config-driven model
  switch cannot free the native context under an in-flight engine call.
- Decode parameters trade offline-transcription accuracy for a bounded
  worst case, because the watch firmware cancels a dictation session
  after 15 seconds: the JNI shim trims the encoder context to the real
  audio length, disables whisper's temperature-fallback ladder, and
  caps tokens per segment (`whisper_jni.cpp` documents each choice).
  The ladder's job, retrying a decoder repetition loop away, moves to
  a text-level pass (`collapseRepeatedSentences` in the service) that
  folds three or more consecutive identical sentences into one; a
  doubled sentence is left alone because real captures contain genuine
  doubles. Measured on a degraded capture that ground for 38 to 89
  seconds under the default parameters, the bounded configuration
  returns the correct text in under 10 seconds on the slowest catalog
  model.

Model weights are never checked in. `WhisperModelCatalog` (util) pins
four models (small, small.en, base, base.en) from
the whisper.cpp author's Hugging Face conversions, each with an
immutable-commit URL, exact byte size, and SHA-256; the catalog KDoc
records the three-source re-pin procedure. Verification is layered,
each layer defensible alone: the commit-pinned URL (a retargeted branch
or re-uploaded file cannot swap bytes), the download-time streaming
digest gate in `ModelFileInstaller` (fail closed, mid-stream size
abort; interrupted transfers keep a resumable partial, completed-but-
wrong bytes delete it), and a load-time re-hash in
`WhisperModelProvider` that quarantines a mismatch before the native
parser ever sees the file.

Naming: `CactusSTTMode` and `CactusModelPathProvider` keep their
upstream names. The enum's entry names are persisted inside the
serialized config on every existing install (a rename is a data
migration), and both types are referenced across upstream-owned files
where a rename would cost churn in every future merge. Migration of
existing installs rides the incompatible-model sweep in
`CommonAppDelegate`: previous-engine model directories are deleted, the
user's local mode is stashed, and `SttModelUpdatePrompt` restores it
once a catalog model is downloaded.

## Branding assets

applicationId is `com.anopticlabs.gravel`; the Kotlin `namespace` and
source packages stay `coredevices.coreapp` (see the branding rule in
`CLAUDE.md`). The launcher icon, in-app wordmark, and fastlane graphics
come from `art/gravel-logo.png`; the brand color is `gravelPurple`
(0xFF9129DE in `util` `theme/Theme.kt`), while the adaptive-icon
background (`util .../res/values/ic_launcher_background.xml`) is a
neutral dark so the purple mark stays visible against it.
