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

The same idea applies to the manifest. Upstream declares four adb-driven
QA receivers (settings write, dev connection server, QEMU watch attach,
firmware sideload; all gated on `android.permission.DUMP`, which only
the system, privileged apps, and the adb shell can hold) for every build
type. The fork keeps them out of release with
`androidApp/src/release/AndroidManifest.xml`, a release-only overlay
whose `tools:node="remove"` entries drop the four elements from the
merged manifest; upstream's declarations and classes stay verbatim,
debug builds keep the receivers for driving an emulator from adb, and
the release exported-component allowlist is unchanged. Two things in
that verbatim upstream text do not hold here: the manifest comment and
the receiver KDocs call the receivers safe to keep in release builds,
which describes upstream's build, and their `am broadcast` recipes
address `coredevices.coreapp`, which is the namespace, not the installed
package; on a Gravel debug build the package half of `-n` is the
applicationId, as in
`-n com.anopticlabs.gravel/coredevices.coreapp.debug.QemuSetupReceiver`. The
loud failure here is `VerifyExportedComponents`: a removal that misses
leaves the receiver exported in the merged manifest, which fails the
release build.

## Ring / Index AI

The out-of-scope Ring/Index AI feature module (`experimental`) is
unplugged from the build (`settings.gradle.kts` + DI wiring) with sources
left in place so upstream merges stay cheap.

`libindex`, `index-ai`, and `mcp` stay compiled: the watch UI in `pebble`
compiles against `libindex`, whose Room schema references `index-ai`
entities, which need `mcp`; and `util`'s transcription exception
hierarchy types its error kind against an `index-ai` enum
(`api(project(":index-ai"))` in `util/build.gradle.kts`). Their runtime
is dead:

- a no-op `LibIndex` is bound at the Koin seam;
- fork stubs in `composeApp` replace the `experimental` types the app
  wiring touches, under the same fully-qualified names, so re-plugging
  the `experimental` module trips duplicate-class errors;
- `CoreConfig.enableIndex` has no reachable set-point in a release build
  (debug builds' adb-driven settings receiver can write it, and a flag
  flipped that way reaches only the empty stubs).

The Ring satellite library `libindex` compiles against
(`io.github.coredevices.haversine`, a prebuilt AAR with no public source
that bundles two native libraries per ABI) is replaced by the fork module
`:haversine-stubs`: inert same-FQN stand-ins for the eight symbols
`libindex` references, whose satellite classes have private constructors
and no factory, so no ring object can exist anywhere in the app
(`HaversineStubShapeTest` pins that shape, `HaversineStubTest` the
static entry points). The wiring is a `dependencySubstitution` rule in
`settings.gradle.kts`, applied to every project, rather than an edited
dependency line: the artifact is a transitive runtime dependency of every
module that depends on `libindex`, which the rule covers and a single
edited line would not, and the rule matches the coordinate by group and
name, so an upstream version bump still lands on the stub. Nothing names
`:haversine-stubs` directly, so a lost rule would bring the AAR back
silently; `AppClasspathSentinelTest` in `:androidApp` pins the swap from
the shipping runtime graph (the AAR's wrapped `com.wtlp.*` vendor classes
must be absent), and `VerifyApkContents` asserts the release APK carries
no `libhaversinesatellitelibrary.so` / `libppcommon.so`. This was the
last dependency whose native code had no public source, and the last
tree-level F-Droid inclusion blocker (the F-Droid section below records
what the tree guarantees).

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

The bug report screen's upload path needed both a Core account ID token
and the bug-reports backend (`bugUrl`, empty in this build), so it is
dead twice over. The screen stays and gates every backend control on
`BugApi.canUseService()`: with no backend it is the local log export
reached from Settings > Export logs (zip to the share sheet, no upload
path), and upstream's screen keeps its behaviour across merges.

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
- `:whisper` holds the Kotlin bindings: a nine-function expect/actual
  surface whose iOS actuals are unsupported stubs, keeping commonMain
  compiling for the unmaintained iOS targets. Engine strings cross JNI
  as UTF-8 byte arrays: engine output can be byte sequences that are
  invalid modified UTF-8, which NewStringUTF aborts on under CheckJNI.
- The ninth function is a model-free speed probe: the shim times one
  encoder block of the base model's shape, built on ggml with random
  weights, on the thread count a dictation would get. `DeviceSpeedEstimator`
  (util) runs it once per install (callers arriving during a probe wait
  for it and take its score) and caches the score;
  `WhisperSpeedCalibration` turns the score into "seconds for a full 15 s
  dictation" per catalog tier from constants measured on the reference
  phone (the calibration procedure is in its KDoc, the instrumented
  `WhisperSpeedCalibrationBenchmark` produces the numbers). The model
  picker shows the estimate on every row, and the default pick steps
  down a tier while its estimate exceeds the watch's window, to the tiny
  floor at most.
- The probe is a forecast; real dictations are the record. The engine
  reports how many samples it decoded after the detector's cut
  (`TranscribeStats`), the service records the time to a result per
  second of engine input after each successful dictation, under the
  model that ran the decode, smoothed per model in settings
  (`DictationSpeedTracker`), and `DictationSpeedPolicy` predicts a full
  window from it. Engine input counts the encoder's fixed floor (the
  shim's 64 extra context positions, 1.28 s of audio) on top of the
  speech, since every call pays it and a short reply would otherwise
  read as a slow model; dictations under two seconds of speech are not
  recorded at all. When that prediction misses the session coordinator's
  deadline and a cheaper tier exists, `SttSpeedNudgePrompt` offers the
  switch, naming the watch's error text and where the model can be
  changed later; keeping the current model is remembered per model, so
  each model asks at most once, and the pending offer is held while the
  prompt downloads its target, so a dictation finishing mid-download
  neither replaces nor withdraws it.
- A self-hosted transcription server is the fork's remote backend, at
  the seam where upstream's cloud pair sits: `HybridTranscriptionService`
  keeps upstream's three remote modes and their fallback timing, and
  when a server URL is configured `SelfHostedTranscriptionService` takes
  the remote slot (the cloud pair stays for merge parity and can never
  sign in here). One request shape serves whisper.cpp's own server and
  OpenAI-style servers: a multipart POST of the session audio as a
  16 kHz mono WAV with `response_format=json`, the model name when set
  and the spoken language when known, answered with JSON `text`; the
  URL is used as entered, path included. The bearer token is a secret
  and goes through the keystore-backed encrypted setting
  (`SelfHostedServerStore`), and belongs to the host and port it was
  saved with: a URL edited to another server is tested and saved
  without it unless a new one is typed. Transport is https only, like
  every other connection in the app; the trust rule
  (`decideServerTrust`) is: a pin for the host and port, once one
  exists, decides alone, and any other certificate is refused as changed
  even when the platform trusts its chain, since a CA-issued certificate
  for the same name is what an interceptor would present; with no pin,
  platform trust (system and user-installed CAs, matching host name)
  passes and anything else is refused until the user pins it. The pin
  is the leaf certificate's SHA-256 fingerprint, confirmed in the
  settings dialog against what the server prints, and keyed by
  `host:port`; forgetting it in the dialog returns the host to platform
  trust, which is how a server moves to a CA-issued certificate. A
  pinned certificate also satisfies host-name verification, since a
  self-signed certificate is often issued to no name. Every platform
  check goes through Android's hostname-aware trust extension, the form
  the platform requires once a network security config carries
  per-domain entries. The dialog's connection test probes the
  certificate first and only then sends a one-second silent request on
  a client of its own, so a wrong token or path is found before saving
  and the dictation path's client is never closed under a request. A
  reply is read through a 64 KB bound, and every transcript, from the
  server or the Rebble path alike, passes a word-count and word-length
  bound in libpebble3 before it is encoded for the watch. The app log
  never carries the server's address: the config line prints the URL as
  set or unset, and transport failures reach the log by exception
  class, with the cause the router sees rebuilt without it. Everything
  above the TLS glue is pure and host-tested; the trust manager and
  verifier are tested against two self-signed fixtures, and the client
  and probe against a local TLS server whose key pair the test generates
  with the JDK's keytool, so no key material is tracked.
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
- The watch's dictation deadline is owned by `VoiceSessionCoordinator`
  in libpebble3, not by the provider. The firmware records for at most
  15 seconds, gives the phone 15 seconds from the end of the recording,
  drops a later result, and after its error dialog starts a new session
  on its own. So sessions run as independent jobs (the retry must not
  cancel the decode it replaces), frames are buffered from the moment a
  setup is accepted, and an overrun is reported as a recognizer error
  one second before the watch's own clock runs out while the decode
  runs on, so the speed record (below) still sees how long it really
  took; the late transcript itself goes nowhere and the watch's retry
  runs as a fresh session. A recording the watch has not ended
  30 seconds after the setup is abandoned from the phone side (its
  clock has run out by then for any recording), so a stop packet that
  never arrives cannot hold a session open for the life of the
  connection. The firmware's timing constants and the deadline derived
  from them live once, beside `TranscriptionProvider` in libpebble3,
  and the speed nudge and the model picker's fit classes are built on
  that deadline. `HybridTranscription` keeps only a 60 second backstop,
  mapped to a generic error; the connection-error code is never used
  for a local decode, since the watch renders it as "No internet
  connection".
- The shim owns a Silero voice activity detector context (the catalog's
  `VAD_MODEL`, loaded by the service when installed, or by the next
  dictation when the install finishes after the model init) and cuts
  each dictation to the span from its first speech segment to its last
  before `whisper_full`. The detector's verdict never decides a
  dictation: when it finds no speech, or fails, the untrimmed audio is
  decoded, and interior gaps are never cut, because watch microphone
  captures sit far below the levels the detector was trained on and it
  has missed whole dictations the engine transcribed in full. The shim
  runs the detector on one thread: its graph is tiny and ggml spawns
  workers per graph, so the engine's built-in path, which uses four,
  spends more on thread creation than on detection. Segmentation is
  tuned for dictation (500 ms of silence to end a segment, 200 ms of
  padding around speech) so word edges survive the cut. The warm-up
  bypasses the detector.
- The engine thread count follows the cores the process can actually
  run on, not the online count: ggml's workers synchronize on spinning
  barriers, so a count above the usable cores stalls every barrier for a
  scheduler slice and a decode that takes a second takes half a minute
  (measured on two chips; `TranscriptionThreads` holds the numbers'
  conclusions). The count is read at call time from the process affinity
  mask, since a process that leaves the screen lands in a smaller cpuset
  on every phone tried, and sized by the fastest frequency tier in that
  mask (`tieredThreadCount`), capped at four. The engine binding carries
  an `EnginePlacement` (affinity mask, nice value) that the shim applies
  to the calling thread for one call; it exists for the on-device
  placement benchmark and probe under `androidApp/src/androidTest`, and
  the service always passes the default: pinning gained ten percent at
  best and can drop below the thread count when the OS moves the allowed
  set, and a raised priority changed nothing.
- Four debug-only hooks live behind `isDebugBuild()` (util), which reads
  the application's debuggable flag and fails closed: a single-thread
  override that slows a fast phone's decode, a capture dump that writes
  each dictation's engine input as WAV under the app's private files
  (last 20 kept, excluded from backups and device transfer, deleted when
  the hook goes off or at start in a build that cannot honour it), a
  substitute-audio hook that stands the bundled test clip (debug assets
  only) in for the watch's audio so an emulated watch, whose microphone
  is silence, still yields a transcript, and a slow-decode hook that
  holds every result for 20 seconds so the deadline report runs on any
  phone. The settings toggles are offered only in debug builds and the
  code re-checks the build before honouring any flag, because debug and
  release installs share an application id.

Model weights are never checked in. `WhisperModelCatalog` (util) pins
six models (small, base and tiny, each as the multilingual and the
English-only conversion) from
the whisper.cpp author's Hugging Face conversions, each with an
immutable-commit URL, exact byte size, and SHA-256; the catalog KDoc
records the three-source re-pin procedure. The Silero voice activity
detector (whisper.cpp's ggml conversion, MIT) is pinned the same way
from its own repository commit as `VAD_MODEL`, outside the speech list:
it installs alongside any speech model download, existing installs
fetch it once in the background on an unmetered network, its directory
is excluded from the picker, the "usable model" checks and the
migration sweep, and its absence means the engine runs without silence
trimming rather than failing. Verification is layered,
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

## In-app links and the What's-new popup

Upstream's Settings > About linked "What's new in the app" to its hosted
changelog in an embedded WebView. That page describes upstream's
releases, not this fork's, and its host (Notion) refuses to render
inside a WebView at all, so the fork points the item at its own
What's-new popup instead. `WhatsNew.kt` in `:pebble` holds the revision
counter, the entry list, and the popup composable; the update-triggered
auto-show wrapper (`WhatsNewDialog`) stays in `:composeApp`. One entry
is prepended per revision bump, and `WhatsNewTest` pins that
convention. The About item's badge, the Settings tab badge slot, and
the auto-show all key on `lastSeenWhatsNewVersion`, so acknowledging
the popup anywhere clears all three; upstream's `AppUpdateTracker`
(which badged on every version change) is left intact but no longer
read.

"What's new in PebbleOS" and "Getting Started & Troubleshooting" keep
their upstream URLs but open in the external browser: the PebbleOS
notes share the Notion-in-WebView problem, and the help centre is
Intercom-backed, so loading it in an embedded WebView makes the app's
own network traffic include Intercom's endpoints, which the app's
listing does not (and should not need to) disclose. The upstream
WebView routes (`RoadmapChangelogRoute`, `PebbleOsChangelogRoute`,
`TroubleshootingRoute`) stay registered in `AppNavHost` but are
unreachable, keeping the upstream merge surface small.

## App store search

Both app store feeds (Core Devices and Rebble) keep their search index on
Algolia, and upstream queries it straight from the phone with per-feed
search-only keys (`AppstoreSources.kt`), so every store search is a
request to a third party, one per enabled feed. The fork keeps the
feature (neither store offers another search API) and narrows what the
requests carry:

- The store search runs only on the search action. `SearchState` (in
  `:util`) keeps the live field text apart from `submittedQuery`, which
  only `submit()` sets; the locker tab's store search reads the submitted
  query, the on-device locker filter reads the live text, and the results
  list shows a hint instead of stale results while the two differ.
  Emptying the field withdraws the submitted query. `SearchStateTest`
  pins that contract.
- `LockerViewModel` debounces search requests (`STORE_SEARCH_DEBOUNCE`,
  300 ms of quiet) regardless of the caller's cadence, so a caller that
  asks on every edit (upstream's behaviour, and what an upstream merge
  would bring back) still cannot stream prefixes, and emptying the field
  cancels a request still inside the window; `LockerViewModelTest`
  covers both.
- `algoliaSearchParams` in `:pebble` is the only place a request is
  assembled: query analytics and click analytics are off, personalization
  is off, and no user token is ever attached, so the index owner's
  Algolia Analytics does not retain the query and nothing in the request
  links one query to the next. `AppstoreSearchParamsTest` pins the flags
  on the parameter object and, by driving the real client into a mock
  engine, on the wire.

What remains is inherent to the feature: the submitted query text and
the phone's IP reach Algolia's servers, which keep request logs of their
own. The app listing (the fastlane description and the F-Droid recipe's
NonFreeNet note) names Algolia for that reason.

## F-Droid

The fork is in F-Droid's main repository, as `com.anopticlabs.gravel`.
Staying there rests on a split between what the tree guarantees on its
own and what the build recipe (the app's metadata file in F-Droid's
`fdroiddata` repository, maintained out of tree) has to supply. The two
must agree, so both halves are recorded here.

What the tree guarantees, and how it is pinned:

- Nothing the scanner rejects is tracked: no prebuilt library or binary
  dependency (the few compiled files in the tree are small `.pbw` and
  `.pbz` test fixtures under `libpebble3/src/jvmTest/resources`, inputs
  to JVM tests that never reach an APK and that the scanner does not
  flag), no dependency comes from a repository outside F-Droid's
  allowlist, and no dependency line matches F-Droid's "usual suspects"
  signature database. `FdroidGuardrailsTest` in `:androidApp`
  replicates the buildserver's source scanner (`FdroidScannerReplica`
  holds the checks, `FdroidScannerReplicaTest` proves each one fires) over
  the tracked tree, the whisper.cpp submodule included, minus the paths the
  recipe removes before scanning. Its `recipeRemovedPaths` list is the
  tree's copy of the recipe's `rm:` field. F-Droid's inclusion policy is
  about the tree and about dependency provenance, not about native code as
  such: free-software Maven artifacts from allowed repositories may carry
  compiled native libraries, and the APK does still ship such libraries
  (the Speex codec from `io.github.coredevices.speex`, Apache-2.0 with
  public source; SQLite from `androidx.sqlite:sqlite-bundled`; androidx
  graphics-path) next to the whisper engine built from source. What the
  fork removed was prebuilt native code with no public source (the Cactus
  engine, the Ring satellite AAR), which the policy does reject.
- The engine toolchain is pinned in `whisper-native/build.gradle.kts`
  (`ndkVersion` 28.2.13676358, that is NDK r28c, and CMake 3.22.1), so a
  build is the same on every machine and the recipe has exact values to
  provision.
- `versionName` is `git describe --tags --first-parent HEAD` and
  `versionCode` the commit count, both functions of the built commit
  (`androidApp/build.gradle.kts`), so a tag checkout reports exactly the
  tag name and the values the recipe declares for that tag can be checked
  against the built APK. The first-parent walk keeps upstream's tags,
  reachable through every sync merge's second parent, from describing a
  fork commit (git describe stops after ten candidate tags, so a non-tag
  build would otherwise report the newest upstream tag); a release tag
  has to sit on master's first-parent line, which tagging the release
  commit on master gives.
- `androidApp/version.properties` holds one line, `versionCode=<digits>`,
  the versionCode of the most recent tagged release. It exists for
  F-Droid's update checker, which reads it from a tag checkout (it cannot
  count commits) and takes the tag name as versionName. The release commit
  bumps it to the count that commit will have once tagged. Three checks
  guard it: the Gradle-time `VerifyReleaseVersionFile` task (wired before
  `preBuild`, so it runs on every build everywhere) checks the shape on
  every build and, on a tag checkout, that the value equals the commit
  count the build stamps into the APK; `FdroidGuardrailsTest` repeats both
  and also requires a `changelogs/<value>.txt` for the value the file
  names; a tag-only CI step compares the file, the commit count, and the
  versionCode read back from the built APK. All three read the file as
  exactly one LF-terminated `versionCode=<digits>` line.
- No module pins a Gradle JVM toolchain. The buildserver ships a single JDK
  (21) with toolchain provisioning disabled, so upstream's `jvmToolchain(17)`
  calls in `libpebble3`, `blobannotations`, and `blobdbgen` are removed and
  every JVM-flavoured target, android and `jvm()` alike, sets `jvmTarget`
  to 17 explicitly (`blobdbgen`, the one plain-JVM module, also sets Java
  source and target compatibility); a target without an explicit
  `jvmTarget` follows the JDK running Gradle, so this is what keeps the
  class files identical on any JDK 17+. `FdroidGuardrailsTest` fails on a
  toolchain pin in any spelling (`jvmToolchain(`, `jvmToolchain {`, or the
  Java plugin's `toolchain {` block) anywhere in the tracked gradle files,
  and CI builds on JDK 17 and 21.
- A checkout without `keystore.jks` packages release unsigned, and the
  APK carries no dependency-metadata signing block; both are checked by
  the packaging-time `VerifyApkContents` task in
  `androidApp/build.gradle.kts`, which also asserts the excluded assets and
  the replaced native libraries are absent from every APK. CI builds the
  release variant on a keystore-less checkout and compares the built
  `versionName` with `git describe` of the built commit.

What the recipe has to supply (field names as in the fdroiddata format):

- `submodules: yes`: the engine is compiled from the whisper.cpp submodule.
- `rm:` exactly the directories `FdroidGuardrailsTest.recipeRemovedPaths`
  lists under `whisper-native/src/main/cpp/whisper.cpp/` (bindings,
  examples, models, samples, tests). They hold language bindings with their
  own gradle builds and a lockfile-less `package.json`, example apps naming
  a maven host off the allowlist, binary test-fixture models, and sample
  audio, none of it part of the fork's build. `rm:`, not `scandelete:`: the
  scanner counts a `scandelete` entry that removed nothing as an error,
  while `rm:` tolerates a path that is already gone.
- `ndk:` r28c (28.2.13676358) and a `prebuild`/`sudo` step that installs the
  SDK package `cmake;3.22.1`, matching the pins above; the buildserver
  provisions neither by default and a mismatch fails configuration.
- No JDK step: the buildserver's own JDK builds the tree (see above).
- Per release, a build entry with the tag's literal `versionName` and
  `versionCode` and `commit:` the full hash of the tagged commit; the
  F-Droid build fails if the built values differ from the declared ones.
- Update checking: `UpdateCheckMode: Tags` with `UpdateCheckData` naming
  `androidApp/version.properties` and the regex `versionCode=(\d+)` for
  the versionCode, and no file or regex for the versionName so the tag name
  is used; `AutoUpdateMode: Version` then adds the build entry for a new tag
  without a manual recipe change (current fdroidserver resolves the entry's
  `commit:` to the tag's hash). The checker inspects only the newest few
  tags and errors if none of them carries the file, so the recipe can
  only enable update checking once a tag containing
  `androidApp/version.properties` exists.
- The build subdirectory is `androidApp`; the unsigned release APK is the
  output F-Droid signs.
- Anti-features: the on-device dictation models are runtime downloads from
  Hugging Face (`WhisperModelCatalog`), which is expected to draw the
  NonFreeNet anti-feature flag; that documents but does not block
  inclusion.

## Branding assets

applicationId is `com.anopticlabs.gravel`; the Kotlin `namespace` and
source packages stay `coredevices.coreapp` (see the branding rule in
`CLAUDE.md`). The launcher icon, in-app wordmark, and fastlane graphics
come from `art/gravel-logo.png`; the brand color is `gravelPurple`
(0xFF9129DE in `util` `theme/Theme.kt`), while the adaptive-icon
background (`util .../res/values/ic_launcher_background.xml`) is a
neutral dark so the purple mark stays visible against it.
