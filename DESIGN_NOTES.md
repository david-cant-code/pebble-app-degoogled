# Design notes

How the de-Googling is implemented. `README.md` says what Gravel is and
does; `CLAUDE.md` carries the rules for changing it; this file records the
architecture behind those rules, so that a change touching one of these
seams starts from the actual wiring rather than a guess.

## The DI-seam strategy

Gravel removes Google Play services, Firebase, and the cloud-backed
Ring/Index AI features mostly by swapping implementations at the Koin
dependency injection seam rather than deleting upstream code. Upstream
call sites stay intact, which keeps upstream commits cheap to
cherry-pick: a pick brings in new call sites against the same
interfaces, and Gravel's bindings decide what those calls actually do.
Each seam below is additionally built so that
accidentally re-enabling the removed functionality fails loudly at build
time instead of quietly coming back.

The same idea applies to the manifest. Upstream declares four adb-driven
QA receivers (settings write, dev connection server, QEMU watch attach,
firmware sideload; all gated on `android.permission.DUMP`, which only
the system, privileged apps, and the adb shell can hold) for every build
type. Gravel keeps them out of release with
`androidApp/src/release/AndroidManifest.xml`, a release-only overlay
whose `tools:node="remove"` entries drop the four elements from the
merged manifest; upstream's declarations and classes stay verbatim,
debug builds keep the receivers for driving an emulator from adb, and
the release exported-component allowlist is unchanged. Gravel adds one
receiver of the same kind, declared only in the debug overlay
`androidApp/src/debug/AndroidManifest.xml`: `SttDebugReceiver`, gated on
the same permission, sets the four dictation debug hooks and the local
model from adb and can post a reply-capable test notification for
driving an emulated watch; release builds never declare it, so there is
nothing to remove.
Two things in
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

Upstream's Ring/Index AI feature module (`experimental`) is
unplugged from the build (`settings.gradle.kts` + DI wiring) with sources
left in place so upstream commits stay cheap to cherry-pick.

`libindex`, `index-ai`, and `mcp` stay compiled: the watch UI in `pebble`
compiles against `libindex`, whose Room schema references `index-ai`
entities, which need `mcp`; and `util`'s transcription exception
hierarchy types its error kind against an `index-ai` enum
(`api(project(":index-ai"))` in `util/build.gradle.kts`). Their runtime
is dead:

- a no-op `LibIndex` is bound at the Koin seam;
- Gravel's stubs in `composeApp` replace the `experimental` types the app
  wiring touches, under the same fully-qualified names, so re-plugging
  the `experimental` module trips duplicate-class errors;
- `CoreConfig.enableIndex` has no reachable set-point in a release build
  (debug builds' adb-driven settings receiver can write it, and a flag
  flipped that way reaches only the empty stubs).

The Ring satellite library `libindex` compiles against
(`io.github.coredevices.haversine`, a prebuilt AAR with no public source
that bundles two native libraries per ABI) is replaced by Gravel's module
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
Gravel's module `:firebase-stubs` shadows the gitlive `dev.gitlive.firebase.*`
artifacts with inert same-FQN stand-ins (permanently signed out, empty
never-authoritative store). Consequences:

- upstream call sites compile unchanged;
- any new upstream use of gitlive API fails the build until the stub
  surface is extended;
- re-adding the real artifacts trips duplicate classes.

`UsersDao` is additionally rebound at the Koin seam to Gravel's
`SignedOutUsersDao`.

The bug report screen's upload path needed both a Core account ID token
and the bug-reports backend (`bugUrl`, empty in this build), so it is
dead twice over. The screen stays and gates every backend control on
`BugApi.canUseService()`: with no backend it is the local log export
reached from Settings > Get Help > Export logs (zip to the share sheet,
no upload path), and upstream's screen keeps its behavior across
cherry-picks.

## The FCM push stack

Push either registers a device token with Google or does not exist, so
`PushMessaging`, `PushService`, and their call sites were deleted outright
rather than no-opped.

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
ever showed "location". Gravel adds a per-app, tri-state permission
system over those two capabilities, denied by default.

**Scope.** The grants gate the app's PebbleKit JS. A watchapp's other
phone-side path is a companion app (classic PebbleKit or PebbleKit 2, see
the classic PebbleKit entries in `KNOWN_ISSUES.md`), a separate Android app
with its own permissions that these grants do not cover, so the user text
is worded as "code the app runs inside Gravel" and never as "stops the app
sending your data".

**What a granted watchapp can reach.** A Network grant lets the app's JS
talk to whatever its WebView can, and that JS is less confined than a web
page: it loads from a `file://` page with universal access, so the
same-origin rules that stop a website reading another origin's responses do
not apply to it. Beyond the internet, two kinds of destination are in
range: services other apps run on the phone's loopback interface, and
devices on the user's network. Both are commonly plain HTTP with no
authentication, on the assumption that only the local user can reach them.
The app-wide cleartext denial covers the plain-HTTP ones, and it is kept
whole for that reason: Android 17 permits cleartext to loopback unless the
app's network security config names a localhost domain, so the config names
one (platform source in that file's comment; `CleartextPolicyTest` asks the
platform on a device).

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

**Network enforcement is layered** (per the defense-in-depth rule; where
the UDP filter below is installed and the WebView supports proxy override,
with at least one deterministic cover for every socket type;
`KNOWN_ISSUES.md` records what a WebView without proxy override, and a
phone where the filter has never installed, leave open). Three layers act on
the WebView's requests; the UDP socket filter and the denied-session page's
response header, both below, are two more:

1. `WebViewJsRunner.shouldInterceptRequest` returns a 403 for every
   non-`file://` request when the app is network-denied (in a
   denied-session page, below, for `file://` as well). Deterministic for
   http/https (XHR, fetch, subresources, navigations). WebSocket
   handshakes do not pass through this callback (a documented WebView
   limitation), which is why layer 3 exists.
2. `startup.js` wraps `XMLHttpRequest`/`fetch`/`WebSocket`/
   `EventSource`/`sendBeacon` in failing guards when the app loads while
   denied, gated on a synchronous `_Pebble.isNetworkAllowed()` bridge
   call. The guards re-read the live grant on every use and restore the
   original entry points once access is granted; they do not reinstall
   on a later revoke. A grant change also requests the session restart
   described below, which loads the page again. Best-effort (same-realm
   JS a hostile bundle could try to work around), so it never stands
   alone.
3. `ProxyController` (androidx.webkit) sets a process-wide WebView proxy
   override that black-holes all egress (every scheme, including ws/wss)
   to an unroutable address while a network-denied app runs, and clears it
   otherwise. Chromium's implicit proxy-bypass rules are removed so
   localhost and link-local destinations are black-holed too; both are
   real egress (other apps' local socket servers, hosts on the same
   network segment). Applied and awaited before the app page loads and
   kept in sync with live toggles; on stop, the live-toggle collector is
   canceled first and the override is cleared only after the WebView is
   destroyed (the reasons are at `WebViewJsRunner.stop`).
   The override is process-wide, so while it is set it also covers any
   other WebView in the app, such as a page left open for another app. Only
   one PKJS WebView runs at a time, and the config page (below) is gated
   for denied apps. Requires the
   `PROXY_OVERRIDE` feature; the degraded case is in `KNOWN_ISSUES.md`.

A change of the app's Network grant while it is running, in either
direction, also restarts its PKJS session once the grant watcher sees it
(`launchNetworkGrantWatcher`, counting from the lifecycle's own grant
read, which decides whether the session gets a PebbleKit JS side;
`CompanionAppLifecycleManager` funnels the restart through the same
serially processed stream as watch-side app switches). What a session sets
up for the grant is fixed when it starts, and an app that fetches only at
launch never touches the network again after its first attempt fails. The
runner also ends a session built as granted once its live collector sees
the grant denied (`endGrantedSessionOnDenial`), after running the
`localStorage` save that `stop()` runs, which gives up after three seconds
so a page that keeps its main thread busy cannot hold off the end. That
also covers a denial the grant watcher never sees as a change from the
value it started from. A session built as denied whose grant turns allowed
without the watcher seeing a change keeps the denied construction until it
is rebuilt (`KNOWN_ISSUES.md`).

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
(`WatchappPermissionControls`, reused by the list). Where the switch for
Network-denied sessions (below) applies, the screen holds it too. Where
the primary layer is not active, an app's per-app controls say, while its
Network grant is off, what applies to its code (`deniedPkjsNotice`) and link
to the KNOWN_ISSUES.md entry at the build's release tag, or at master for any
other build (`knownIssuesUrl`, `KnownIssuesLinksTest`). Store listings gain an
honest disclosure that code the app runs inside Gravel can reach the
internet, and the location capability description states the real "may send
it to outside servers" flow. A one-time "What's New" dialog announces the
deny-by-default change to existing users (`WhatsNewDialog`).

**UDP socket filter.** Under the three layers above sits a process-wide
one where the platform allows it: `:socketfilter` installs a seccomp
filter in `MainApplication.attachBaseContext` that refuses the creation of
`AF_INET` and `AF_INET6` datagram sockets in the app's process for as long
as it runs, whatever a watchapp's grant says (costs and limits in
`KNOWN_ISSUES.md`). The install first attaches the filter to a thread of
its own and checks from there that the platform's DNS client still
reaches the system resolver (`resolver_check` in `socket_filter.c`); where
it does not, as on LineageOS and CalyxOS (`KNOWN_ISSUES.md`, "The UDP
filter is off on LineageOS and CalyxOS"), the filter is not installed and
the layer reports "not active".
libpebble3 does not depend on the module: the app reports the install
result through `NetworkDenyEnforcement`, a `LibPebble3.create` parameter
whose default is "not active", with the switch below not applying.
`MainApplication.onCreate` records each install in a marker file in
`noBackupFilesDir` (`recordUdpFilterInstall`), and the app's binding
(`udpFilterEnforcement`) lets the switch apply only where the layer is not
active, no install has been recorded, and the WebView's major version is
at least `HEADER_MIN_WEBVIEW_MAJOR`, from which the header below is on by
default. Where the
layer is not active,
`CompanionAppLifecycleManager.createCompanionApps` builds a PebbleKit JS
session for an app whose Network grant is denied only where the switch
applies and `WatchConfig.deniedPkjsWithoutPrimaryLayer` is on
(`shouldRunPkjs`; a switch in Watch App Permissions, on by default, shown
only where it applies). The grant watcher restarts the session once it
sees the grant change (above). For a Network-denied session,
`launchDeniedPkjsSwitchWatcher` restarts it once its first read, or a
config change, answers differently from the build whether it would get its
PebbleKit JS side; a change in whether the switch applies, such as a
WebView update, is read only then (`KNOWN_ISSUES.md`). `WebViewJsRunner.start` makes the same check on the
grant and the switch it reads itself, and loads nothing when it fails.

**Denied-session page.** A session that starts with the Network grant
denied is built differently from a granted one (`denyConstruction`, fixed
in `WebViewJsRunner.start`; the grant watcher and the runner's end of a
granted session, above, are what let it be fixed). Its top document is a
host page served by the runner's own interceptor at
`https://pkjs.gravel.invalid/host.html`, the only URL that interceptor
serves while the grant stays denied (`planPkjsRequest`), and
that response and every 403 of
the session carry a `Connection-Allowlist` header with an empty allowlist
and WebRTC blocked (source cited at the constant in `PkjsRequestPlan.kt`;
WebView version caveat in `KNOWN_ISSUES.md`). `DeniedSessionWebRtcTest`
checks on a device, in a process without the UDP filter and on WebView 152
or newer, that a WebRTC offer in the frame gathers no candidate (a renderer
exit counts as refused). The host page holds no
watchapp script. It creates one `sandbox="allow-scripts"` `srcdoc` frame,
and `startup.js` and the app's script run there, read as text through
`_Pebble` bridge methods that take no argument. Native calls reach the
frame as strings the host page forwards with `postMessage`
(`evaluateInPage`), and `localStorage`, which a sandboxed frame lacks, is
a stand-in that writes through to the same native store a granted session
uses. File access is off in the session's WebView, and the view is given a
laid-out size, without which the frame's timers fall behind their interval
(`frameTimersRunAtTheirInterval`).
The frame has to keep the document it was created with, and two controls
hold that: the navigation lock (`refusePageNavigation`, which refuses every
navigation the WebView consults the app for, in granted sessions too) and
the document-commit guard (`onFrameDocument`), which ends the session,
the same way a renderer exit does, when the frame's bootstrap runs a second
time or the host page sees the frame load a second document.
`PKJSDenyConstructionTest` covers the construction and both controls on a
device; a granted session is built as before.

**Renderer exit.** `WebViewJsRunner`, `PebbleWebview` and the watchapp
settings page each handle `onRenderProcessGone`; left unhandled, a WebView
renderer's exit ends the app process (source cited at `WebViewJsRunner`'s
override). The runner does not restart a PebbleKit JS session whose
renderer exits (`KNOWN_ISSUES.md`), `PebbleWebview` shows a message in
place of the page, and the settings page closes with a message. A
session ended this way, by the document-commit guard, or by
`endGrantedSessionOnDenial` also stops its location watches
(`GeolocationInterface.endWatches`).

**Dependency.** `androidx.webkit` (1.16.0) is added for `ProxyController`,
and the runner also logs its multi-process query at session start: current
stable, Apache-2.0, on Google's Maven (F-Droid
deliverable), no known advisories, non-deprecated API surface.

## PebbleKit exposure toggles

Two `WatchConfig` booleans, `classicPebbleKitEnabled` (default off) and
`pebbleKit2Enabled` (default on), decide whether each PebbleKit surface
exists at all. Both live under Settings > Apps > Watch App Permissions,
beside the JS grants above, with an info dialog that explains the classic
default.

**Why classic is off by default.** Every non-system watchapp that declares
no PebbleKit 2 companion package gets a classic PebbleKit session when the
watch runs it, JS-only watchfaces included, because upstream's
`appMessageToMultipleCompanions` default creates the platform session next
to PKJS. A classic session broadcasts what the watch sends to any installed
app and accepts a `SEND` from any app (KNOWN_ISSUES, "Classic PebbleKit
broadcasts cannot be restricted to authorized callers"), a path the per-app
Internet and Location grants never touched. Classic therefore ships off,
for upgrading installs too, the same way the network default shipped. A
classic companion registers runtime receivers with no manifest trace and
cannot be reliably detected, so the mitigation for a user whose companion
stops working is text: the toggle's description, the info dialog, the
What's New entry and the release changelog. PebbleKit 2 travels over a
bound service and a ContentProvider, where the caller is authoritative and
checked against the companions installed watchapps declare, so its toggle
reduces surface for a user with no PebbleKit 2 companion and stays on.

**Routing and no fallback.** `PebbleKitSurface.kt` (common code) holds the
routing rule: a declared companion package means PebbleKit 2, anything else
classic, and both the Android session factory and the session gate read it.
A disabled surface yields no platform session; a PebbleKit 2 watchapp is not
given a classic session, which would turn a caller-gated surface into an
ungated broadcast. `appMessageToMultipleCompanions` is left as upstream has
it.

**Layers per surface.**

1. *Session creation.* `CompanionAppLifecycleManager.createCompanionApps`
   consults `WatchConfig.allowsPlatformCompanionSession`, and each session
   class checks its toggle again at `start()`; the classic session also
   checks it at every broadcast, for the moment between a toggle-off and the
   restart. A toggle flipped while an app runs restarts its session through
   the coordinator, pinned to the session generation like the permission
   restart (`launchPlatformSessionGateWatcher`). The watcher compares the
   live decision with the one the session was built from, so a flip while
   the session starts still counts; a flip off and back on across a
   session's own `start()` does not (KNOWN_ISSUES).
2. *Manifest component state.* `PebbleKitComponentState` disables the
   basalt provider, the sender service and the `.pebblekit` provider through
   `PackageManager` while their toggle is off, and applies a component
   whose call threw again at the next config change. The platform behavior
   this layer rests on is listed, with AOSP symbols, in that class's KDoc.
3. *Runtime entry points.* Classic START/STOP receivers are registered only
   while classic is on. The basalt provider's `query` returns null while off
   and before the setting is readable. The PebbleKit 2 sender hands out a
   binder that refuses every request while off (`pebbleKitRequestDecision`),
   before it reads anything the caller sent, and checked per request because
   the system reuses that binder for later binds
   (`PebbleSenderReceiver.onBind`). The PebbleKit 2 provider returns null
   while off (`pebbleKitQueryDecision`, which reaches the companion registry
   only once the toggle admits the query), and the state it serves
   (`PebbleKit2ProviderState`, owned by the Koin graph rather than the
   provider) tracks watches only while on and announces changes per
   collection, never per watch. The basalt change
   notifier fires only while classic is on, and once when it turns on.

Runtime-registered receivers never appear in a manifest, so the release
exported-component allowlist (`VerifyExportedComponents`) cannot see them;
the runtime entry points govern those.

**Accepted costs.** Flipping a toggle restarts the running watchapp's whole
session, PKJS included, when that watchapp uses the flipped surface, so a JS
watchface reloads once on a classic flip. Turning a surface off unpublishes
its provider, which kills a companion process that still holds a stable
connection to it when the deferred package-changed broadcast lands, and
turning PebbleKit 2 off also drops the bindings companions hold to the
sender service (`PebbleKitComponentState` KDoc).

**Verification shape.** Host tests pin the routing, defaults and no
fallback (`PebbleKitSurfaceTest`); the restart trigger and the watcher's
restart request (`PlatformSessionGateChangesTest`); the listener
registration, the notifier, the sender's and the provider's admission
decisions, and that the sender's refusal on the toggle or the caller reads
nothing from the request; the component map
and its retry after a failed call; and which surface each factory's helper
follows (`PebbleKitSurfaceWiringTest`). A source sentinel in `:androidApp`
(`CompanionSessionGateSentinelTest`) checks that the upstream-owned manager,
which no unit test can construct, still calls the gate and launches the
watcher. On the device, `ClassicPebbleKitSessionTest` and
`PebbleKit2SessionTest` drive real sessions across the toggles,
`PebbleKitToggleGateTest` flips the real config against the real
components, and `PebbleKitComponentStateTest` drives the real
PackageManager.

## The whisper speech engine

Upstream's on-device dictation ran on the proprietary Cactus engine: a
prebuilt 57.7 MB `libcactus_engine.so` checked into git with no source
in the tree, under a source-available license with commercial revenue
caps. That is not free software, which blocked the F-Droid goal, and it
conflicts with distributing Gravel's GPLv3 APKs, so the speech stack
is replaced outright rather than no-opped at a seam: dictation
is a core watch
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
  is mapped until it passes. `CpuFloorProbeSentinelTest` (app unit
  tests) holds each engine's probe to the hwcaps its compile floor
  implies (`whisper_cpu.c` states the mapping and its sources).
- `:whisper` holds the Kotlin bindings: a seven-function expect/actual
  surface whose iOS actuals are unsupported stubs, keeping commonMain
  compiling for the unmaintained iOS targets. Engine strings cross JNI
  as UTF-8 byte arrays: engine output can be byte sequences that are
  invalid modified UTF-8, which NewStringUTF aborts on under CheckJNI.
- The engine runs in an isolated process, `WhisperEngineService`,
  declared in the `:whisper` module manifest with `android:isolatedProcess`
  (pinned at the source by `WhisperEngineManifestTest` and in every
  variant's merged manifest by the app build's `VerifyIsolatedServices`
  task; what the isolation buys is stated on the service), so a
  memory-safety bug in
  the model parser or the decoder, reached through a model file, is
  contained to that process. This layer is independent of the pinned
  catalog below: the pin covers that the bytes that arrived are the
  bytes that were meant to arrive; it does not cover a parser bug
  reached through a correctly pinned model, nor a file replaced on disk
  after verification. The app-process side is `WhisperEngineClient`,
  behind the `:whisper` actuals, so the common surface stays path-based:
  it opens the verified model path and passes the descriptor (the engine
  process never opens a path; from its domain the app's files do not
  resolve), and passes each dictation's audio as a read-only
  shared-memory region, since a Binder transaction cannot carry a full
  window of float PCM. The transactions are a hand-written Binder
  protocol, `WhisperEngineProtocol`, because the KMP Android library
  plugin has no AIDL support, and everything read back from the engine
  process is bounded before use. Every transaction carries a deadline
  (`transactionDeadline`, set well above the slowest legitimate call of
  its kind), on whose expiry the client drops the binding: the platform
  then ends the isolated process (`WhisperEngineClient` states the
  source) and the blocked call returns as a dead-object failure, so an
  engine process that answers nothing, wedged or hostile, is handled as
  one that died; a decode that ignores its abort past the service's
  unwind bound ends its process the same way, and so does a reply whose
  header is not the plain no-exception marker, which the client reads
  itself rather than through the framework's exception reader, one with
  an unknown status, a payload past its bound, or a long-form reply the
  parser refuses. The
  engine side keeps a live-handle set
  and a per-handle busy flag, so a handle from an earlier engine process
  or one inside another call is refused rather than dereferenced,
  independently of the service's mutexes, and re-checks the CPU floor
  before loading the engine library. The one binding is held for the
  life of the app process, which keeps the model resident as the
  in-process engine was; a released binding would end the engine
  process, and the next dictation would pay a cold load with nothing in
  front of it. The same holds when the engine process dies (a crash in
  the engine, or the platform reclaiming it): `WhisperTranscriptionService`
  forgets the handle that process issued and reloads the model at once,
  at most three times in a row without a successful decode in between,
  so a process that keeps dying is not reloaded in a loop; a death
  during a load is that load's failure and is not retried on its own,
  and every dictation still makes its own load attempt, except as
  `KNOWN_ISSUES.md` records ("A dictation that meets an engine restart
  can fail"). A dictation inside the process when it dies is lost
  and reported as the engine unavailable, which the hybrid fallback
  routes to remote. Handles are
  matched to the process that issued them by a per-bind generation from
  the client, so a late report of an earlier death never drops a fresh
  handle. The engine thread count and the placement facts on the
  diagnostics lines (`proc=engine`) come from the engine process once it
  is bound, since `/proc/self` in the app process describes the wrong
  process. The transport's API floor (`isWhisperSupported` in
  `Whisper.android.kt`) puts Android 8.0 on the remote path
  (KNOWN_ISSUES), and `MainApplication.onCreate` returns at once in the
  engine process (`runningInIsolatedProcess` states why).
- `whisperBenchmark` is the model-free speed probe: the shim times one
  encoder block of the base model's shape, built on ggml with random
  weights, on the thread count a dictation would get. `DeviceSpeedEstimator`
  (util) measures once per install and caches the score; a measurement
  is the lower of two probes, since the first probe in a freshly spawned
  engine process runs slow, and callers arriving during a measurement
  wait for it and take its score. `WhisperSpeedCalibration` turns the
  score into "seconds for a full 15 s dictation" per catalog tier from
  constants measured on the reference phone (the calibration procedure
  is in its KDoc; the instrumented `WhisperSpeedCalibrationBenchmark`
  produces the numbers, discarding its first probe for the same
  reason). The model
  picker shows the estimate on every row, and the default pick steps
  down a tier while its estimate exceeds the watch's window, to the tiny
  floor at most.
- The probe is a forecast; real dictations are the record. The engine
  reports how many samples it was given (`TranscribeStats`), the service
  records the time to a result per
  second of engine input after each successful dictation, under the
  model that ran the decode, smoothed per model in settings
  (`DictationSpeedTracker`), and `DictationSpeedPolicy` predicts a full
  window from it. Engine input counts the encoder's fixed floor (the
  shim's 64 extra context positions, 1.28 s of audio) on top of the
  audio, since every call pays it and a short reply would otherwise
  read as a slow model; dictations under two seconds of audio are not
  recorded at all. When that prediction misses the session coordinator's
  deadline and a cheaper tier exists, `SttSpeedNudgePrompt` offers the
  switch, naming the watch's error text and where the model can be
  changed later; keeping the current model is remembered per model, so
  each model asks at most once, and the pending offer is held while the
  prompt downloads its target, so a dictation finishing mid-download
  neither replaces nor withdraws it.
- A self-hosted transcription server is Gravel's remote backend, at
  the seam where upstream's cloud pair sits: `HybridTranscriptionService`
  keeps upstream's three remote modes and their fallback timing, and
  when a server URL is configured `SelfHostedTranscriptionService` takes
  the remote slot (the cloud pair stays for cherry-pick parity and can never
  sign in here). One request shape serves whisper.cpp's own server and
  OpenAI-style servers: a multipart POST of the session audio as a
  16 kHz mono WAV with `response_format=json`, the model name when set
  and the spoken language when known, answered with JSON `text`; the
  URL is used as entered, path included. The bearer token is a secret
  and goes through the keystore-backed encrypted setting
  (`SelfHostedServerStore`) in one value with the host and port it was
  saved for, and a dictation, a Notebook request and the dialog's test
  read it for the address the request goes to (`tokenFor`); a URL edited
  to another server is tested and saved without it unless a new one is
  typed. A token an earlier build stored without an address is given
  the configured server's at app start or when the dialog opens
  (`adoptUnboundServerToken`), or removed when no valid server is
  configured; one that cannot be read then stays for the next time.
  Transport is https only, like
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
  with the JDK's keytool, so no key material is tracked. The one piece
  no host test reaches is `AndroidServerTrust`, the adapter over the
  platform's hostname-aware check (the SDK stub of
  `X509TrustManagerExtensions` throws from its constructor); a change to
  it needs a device pass against a self-hosted server.
- The server's Wi-Fi-only rule, while "Use the Server on Mobile Data"
  is off: a request may go out while the app's default network is
  Wi-Fi not marked metered, or a VPN running over such Wi-Fi as far as
  Android shows (`AndroidUnmeteredWifi`, which cites the platform
  behavior it relies on; its limits are in `KNOWN_ISSUES.md`).
  `SelfHostedTranscriptionService` checks the rule once the audio is
  collected and sends through a client that refuses sockets and name
  lookups while the rule allows no network, binds them (from Android 9)
  to the network it allows when each is made, keeps no idle connection,
  and closes a socket whose network stops being allowed. `HybridTranscriptionService` routes first: Server Only
  refuses the watch session at setup, and the two fallback modes keep
  dictation on the phone, as they do with Flaky Network Mode on, or a long
  capture on a server without a dictation lane (below), while the phone
  has a model. `settleServerOnMobileData` turns the switch on
  once, at the first start with it, when a server was already set up;
  its marker is a separate setting, so a rebuilt config cannot
  re-trigger it.
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
- Notebook recordings, made in the app and capped at 5 minutes, take a
  second decode path on the same model and handle,
  `whisperTranscribeLongForm`, with the opposite trade: timestamps kept,
  whole 30-second windows, no token cap, and without dictation's extra
  no-speech filter, which judges a whole window and would drop a sentence
  that shares one with a pause. Whisper's own no-speech check still drops
  a window it judges silent; a silent stretch it lets through can come
  back as a short filler line. A recording is one engine call on a whisper
  state of the call's own (the shim says why); it crosses as one
  shared-memory region the engine reads in place, and the result comes
  back as one packed reply of at most 256 KiB, bounded and parsed in the
  app process (`WhisperLongForm`). Dictation keeps priority: a dictation
  transcribed on the phone, and a transcription opening (`earlyInit`)
  where the phone answers first (LocalOnly, LocalFirst, and the fallback
  modes while they keep dictation on the phone), raises a
  `DictationHold`, which
  stops a long-form call at the engine's next abort poll (the shim cites
  where whisper polls and what runs before the first poll); once the hold
  ends (the dictation is done, or the watch's own clock has run out) the
  recording is transcribed again from the beginning, which on device gave
  the uninterrupted call's transcript at the same thread count
  (`WhisperLongFormDeviceTest`; `KNOWN_ISSUES.md`). A stop nothing asked
  for is refused like a malformed reply, and a call past its transaction
  deadline fails the transcription without a retry.
  `WhisperLongFormServiceTest` pins the rules against a fake engine.
- Nothing CI runs reaches `whisper_jni.cpp`: the host suites stop at the
  binding's Kotlin side, so the shim's semantics (cancellation,
  cold-start init) are guarded only by the instrumented
  `WhisperLocalCancellationTest` and `WhisperColdStartRaceTest` under
  `androidApp/src/androidTest`, run one class at a time on a device with
  the model installed (each KDoc carries the command); the process
  boundary (an isolated uid, a dictation and a full window across it, a
  refused stale handle, the app outliving a crashed engine process) by
  `WhisperEngineIsolationTest` beside them, which also has the model
  loader refuse a tensor type outside ggml's range while the engine
  process lives on; the long-form path (a recording across the boundary,
  a cancel and the call made again, a deadline timeout, a dictation
  answered mid-recording) by `WhisperLongFormDeviceTest`. An engine
  bump, a shim edit or a protocol change gets a device run of those four
  before it merges.
- The watch's dictation deadline is owned by `VoiceSessionCoordinator`
  in libpebble3, not by the provider. The firmware records for at most
  15 seconds, gives the phone 15 seconds from the end of the recording,
  drops a later result, and after its error dialog starts a new session
  on its own. So each setup is its own job and a new setup supersedes
  the session in flight: the watch has moved on, so the earlier decode
  is canceled and nothing more is sent for it (the engine runs one
  decode at a time, and a decode left running would fail the new
  session's own the moment its recording ended; the cancel lands
  between engine passes, so a decode deep in one can still hold the
  engine for up to its unwind bound). Frames are buffered from the
  moment a setup is accepted, and an overrun is reported as a
  recognizer error one second before the watch's own clock runs out
  while the decode runs on until the next session supersedes it, so the
  speed record (below) still sees how long it really took; a decode
  canceled past the deadline records its elapsed time as a lower bound.
  The late transcript itself goes nowhere and the watch's retry runs as
  a fresh session. A recording the watch has not ended
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
- Nothing gates on level before the decode: the engine receives each
  dictation exactly as the watch sent it. A Silero detector once cut
  the audio to its detected speech; on Speex watch audio with speech
  near -25 dBFS it found either nothing or a fragment and cut the rest,
  and cost half a second per dictation. A level-based edge trim was
  tried in its place and dropped: on the Core Time 2 the microphone
  opens with a 60 to 120 ms burst and lifts post-speech ambient to
  -43 dBFS, so no real capture had a tail it could cut, and removing
  half a second of non-speech before the first word changed marginal
  decodes, once from a correct sentence to nothing. The decode cost of
  a full 15 second window is bounded instead by the thread policy below
  and by the model choice the picker's estimate and the nudge steer.
- The engine thread count follows the cores the process can actually
  run on, not the phone's CPU count: ggml's workers synchronize on
  spinning barriers (sourced at `MAX_ENGINE_THREADS` in
  `TranscriptionThreads`), so a count above the usable cores stalls
  every barrier for a scheduler slice and a decode that takes a second
  takes half a minute (measured on two chips; `TranscriptionThreads`
  holds the numbers' conclusions). The count is read at call time from
  the affinity mask of the process the decode runs in (the engine
  process once one is bound, this process before that; see the
  isolated-process bullet above), since a process that leaves the
  screen lands in a smaller cpuset on every phone tried, and sized by the
  fastest frequency tier in that mask (`tieredThreadCount`), capped at
  four. The engine binding carries an `EnginePlacement` (affinity mask,
  nice value) that the shim applies to the calling thread for one call;
  it exists for the on-device placement benchmark and probe under
  `androidApp/src/androidTest`, and the service always passes the
  default: pinning gained ten percent at best and can drop below the
  thread count when the OS moves the allowed set, and a raised priority
  changed nothing.
- Four debug-only hooks live behind `isDebugBuild()` (util), which reads
  the application's debuggable flag and fails closed: a single-thread
  override that slows a fast phone's decode, a capture dump that writes
  each dictation's engine input as WAV under the app's private files
  (last 20 kept, excluded from backups and device transfer, deleted when
  the hook goes off or at start in a build that cannot honor it), a
  substitute-audio hook that stands the bundled test clip (debug assets
  only) in for the watch's audio so an emulated watch, whose microphone
  is silence, still yields a transcript, and a slow-decode hook that
  holds every result for 20 seconds so the deadline report runs on any
  phone. The settings toggles are offered only in debug builds and the
  code re-checks the build before honoring any flag, because debug and
  release installs share an application id.

Model weights are never checked in. `ModelCatalog` (util) is every
pinned model: `WhisperModelCatalog` pins six speech models (small, base
and tiny, each as the multilingual and the English-only conversion)
from the whisper.cpp author's Hugging Face conversions, and
`LlmModelCatalog` pins the Notebook's two text models, unsloth's GGUF
quantizations of Qwen3 1.7B and Qwen3.5 0.8B. Each entry has an
immutable-commit URL, exact byte size, SHA-256, and an MIT or
Apache-2.0 license, the only two a test allows; the whisper catalog
KDoc records the three-source re-pin procedure. Verification is
layered, each layer defensible alone: the commit-pinned URL (a
retargeted branch or re-uploaded file cannot swap bytes), the
download-time streaming digest gate in `ModelFileInstaller` (fail
closed, mid-stream size abort; interrupted transfers keep a resumable
partial, completed-but-wrong bytes delete it), and a load-time re-hash
in `WhisperModelProvider` that quarantines a mismatch before the native
parser ever sees the file.

The installer retries a dropped or stalled transfer, or an HTTP 408,
425, 429 or 5xx, inside one install, resuming where the partial file
ends, until five attempts in a row each add less than 1 MiB. Other
failures end it at once, among them a failed write, too little free
space for the remaining bytes and a rejected certificate; a server that
keeps ignoring resumes ends it after three restarts. The partial's name
carries the pin, and staging files other than the current pins'
partials and quarantined files are deleted at startup. Speech and text
models download in separate lanes, one download per role at a time: a
new request replaces the pending download of its own role only, and the
dictation screens follow the speech lane's status. A download still
scheduled when a process builds the download manager stays scheduled;
where its job names a catalog model, its lane starts out showing it as
downloading, whether or not the job has run there. A cancel, or a request
that replaces the job with another model's, takes a download it ends off
what the lane shows. A download job's
time limit scales with the file's size (`DownloadJobLiveness.timeoutFor`).
The startup sweep deletes every model directory that is not an
installed catalog model, a text model's included, but resets dictation
only when one of them has a name outside the catalogs, as a previous
engine's installs have, and the selected speech model is not installed.
A torn catalog install keeps the chosen mode and announces nothing; when
it is the selected model's, the selection moves as after a delete
(`WhisperModelCatalog.selectionAmong`). A re-pin that keeps a model's id
reads as a torn install, so it is deleted without the announcement.
The model picker, dictation's installed check and the sweep's restore
see speech models only.

Deleting a speech model in the picker keeps the chosen mode
(`deleteModelKeepingMode`), where upstream switches to Server Only once
none is left. Deleting the selected model selects an installed one, the
recommended ahead of the catalog's order, or clears the selection; in
the modes that use a model on this phone the picker says which model is
now used, or that none is installed, naming the mode's other
destination for watch dictation in a mode that has one
(`noSpeechModelMessage`: Rebble, signed in or not; the server while a
valid address is set, "on Wi-Fi" while the server is kept off mobile
data). `ModelManager.deleteModel` tells
`WhisperTranscriptionService.modelDeleted`, which frees a loaded copy of
that model behind a call that is inside the engine; `isLocalAvailable`
counts a loaded model only while it is still installed. The view
model's call and that wiring are checked as source text
(`SpeechModelDeleteSentinelTest`).

Naming: `CactusSTTMode` and `CactusModelPathProvider` keep their
upstream names. The enum's entry names are persisted inside the
serialized config on every existing install (a rename is a data
migration), and both types are referenced across upstream-owned files
where a rename would cost churn in every future cherry-pick. Migration of
existing installs rides the incompatible-model sweep in
`CommonAppDelegate`: previous-engine model directories are deleted, the
user's local mode is stashed when the selected model is not installed,
and `SttModelUpdatePrompt` restores it once a catalog model is
downloaded, while the mode is still the one the sweep left
(`sttUpdatePromptApplies`). The sweep's recommendation wiring and the
dialog's calls to its two host-tested functions are checked as source
text (`SpeechModelStartupSentinelTest`).

## Notebook

Gravel's Notebook feature (voice and typed notes, and reminders) lives in the
`:notebook` module. The
stores, the reminder alarm stack, the capture pipeline and the tab's screen
are registered in the app's Koin graph (`notebookModule`) and the alarm
receivers are in the merged manifest. Notes and reminders come from the tab's
record button, typed notes, the reminder editor and the Notebook watch app
(below). Pure
logic goes in `commonMain` on the standard library and kotlinx.serialization;
`androidMain` holds only what the platform must supply (Unicode NFKD
decomposition for note filenames, the Keystore-backed storage below, the
alarm stack and the Compose screens).

The text model behind the Notebook's titles, tags and reminder messages is
llama.cpp (MIT), compiled from source like the speech engine and run the
same way: `:llm-native` builds the pinned submodule at
`llm-native/src/main/cpp/llama.cpp` into one JNI shim, `libllmjni.so`, with
the server, tools, examples, web UI and network surfaces off, plus the
baseline-arch CPU probe `libllmcpu.so` (the same armv8.2 dotprod + fp16
floor, checked before any engine code is mapped); `:llm` holds the Kotlin
side. Each engine links its own static copy of ggml behind its own version
script, so the whisper.cpp and llama.cpp pins move independently and a
security bump of one never moves the ggml under the other. The engine runs
in an isolated process of its own, `LlmEngineService` in `:llm` (pinned at
the source by `LlmEngineManifestTest` and in every merged manifest by
`VerifyIsolatedServices`), separate from the speech engine's so a
generation that runs for minutes never sits in front of a watch dictation.
The app side is `LlmEngineClient`, a hand-written Binder protocol in the
shape of the speech engine's: the model crosses as a descriptor, the
prompt and the rendered chat template as bytes, every reply is bounded
(`LlmEngineLimits`) and every transaction carries a deadline whose expiry
ends the engine process. Constraints the code relies on:

- A conversation is rendered by llama.cpp's built-in formatter for the
  template family it detects on the model's template text (a substring
  match, not an execution of the Jinja; `llm_jni.cpp` cites the source),
  with a model detected as no family refused. Which models render
  faithfully is the catalog's business: `LlmModelCatalog` records each
  model's chat format and its reasoning suppression suffix, which the
  caller appends after the render, and `LlmCatalogModelsDeviceTest`
  checks on a device that each catalog model renders a short
  conversation in that format and, with the suffix, answers a note
  prompt without reasoning tags. The Qwen3.5 file's embedded template
  trims message contents, so a caller must trim them before the render. The
  formatter renders roles and contents verbatim and the tokenizer parses
  special-token text anywhere in the prompt, so the roles are limited to
  system, user and assistant, and a content carrying a template marker
  (`<|`, `|>`) is refused on the app side and one carrying any of the
  loaded model's special-token texts is refused by the engine. The device
  suite pins the render for a ChatML model and both refusals.
- A prompt the context cannot hold is refused with its token count in the
  stats, never cut; the device suite pins the refusal.
- Cancellation targets a call by its id, checked inside every decode
  through the engine's abort callback and between tokens, so a cancel
  lands during the prompt's prefill as well. A cancel for an id with no
  call in flight is kept for that id's call, for the 64 most recent such
  ids, so a cancel that reaches the engine just before its call still
  stops it. The device suite pins a cancel during the prefill, one between
  tokens, one before its call, and the bound.
- Each generation runs on a ggml threadpool with the requested thread
  count, kept by its model handle until the handle is freed (`llm_jni.cpp`
  cites why a pool cannot end with its call); the count comes from the
  caller. The device suite pins that the worker threads a handle's
  generations start, at two thread counts, are there after the last
  generation and gone after its free, that a generation asked to run at a
  nice value or on a CPU mask the kernel accepts gets workers of its own
  under it and leaves no earlier thread held to that mask, and that one
  whose mask the kernel refuses starts no workers of its own. Not pinned
  by a test: an access to a freed pool, and the calling thread's nice value
  being restored. `textGenerationThreadCount`
  (dictation's rule over the CPUs the text engine process may run on) is
  the count the speed calibration below was measured at, and the count
  `TextGenerator` generates at.
- `recommendedTextModel` (`ModelManager.getRecommendedTextModel`, marked
  on Settings > AI > LLM) walks `LlmModelCatalog` from the largest model down
  to the first whose RAM floor the phone meets and whose note from a
  one-minute recording is estimated within 45 seconds, else offers the
  smallest; nothing is
  recommended where the engine cannot run. The text engine has no probe of
  its own: `LlmSpeedCalibration` scales reference times measured on the
  speech engine's reference phone by the speech probe's score, with the
  speech estimate's background margin, and states the calibration
  procedure. The speech engine process, which parses model files, thereby
  steers the text recommendation too, within the probe's accepted range.
  Host tests pin the walk, the scaling, and the text times' match to the
  current speech reference.
- `TextGenerator` (`:util`) generates the Notebook's text. It picks the
  Notebook's chosen text model if installed, else the recommended one if
  installed, else the largest installed; resolves it through the model
  provider without downloading, so a missing or failed file means no
  model rather than a fetch; loads it with an 8192-token context; and
  frees it five minutes after the end of its last use, or once a use
  holding the engine ends when its file is deleted. An engine process
  death while a model is loaded or loading drops the handle and that
  file's verification memo, so the next load hashes the file again. A load the
  engine refuses or that runs past its deadline, or a use whose four
  counted events were all engine losses during a load, marks the model
  unusable; the mark counts while that model is the pick, until a load
  succeeds, the model is deleted or the app restarts. A use that gives up
  on an engine loss with a loss during a generation or a hold wait that
  ran out among its four leaves the model usable; a generation past its
  deadline is not retried. Loads and generations give way to dictation
  as long-form transcription does: each waits while a dictation hold is
  up, a hold raised mid-call cancels the call and it starts again, a
  restart does not count against the retries, and a load already under
  way finishes. The generator and the speech probe each take an
  `InferenceExclusion`, and the app passes the generator the probe's Koin
  single, so the probe does not time the CPU under a use (that wiring is
  not pinned by a test). `TextGeneratorTest` and `DeviceSpeedTest` pin
  the rest on fakes.

Storage is a directory of encrypted files under `files/notebook`. Each write
draws a fresh AES-256 data key, seals the file with it in software
(AES-GCM), and seals the data key with one Keystore key (alias
`gravel_notebook_kek_v1`, in StrongBox, unless StrongBox is unavailable
or five attempts there fail with a provider error,
in which case it is generated without the StrongBox request;
no user authentication so background captures and reminders can read and
write). The `GNF1` envelope, version 1, holds magic, version byte, the
key-wrap nonce, the sealed data key with its tag, the data nonce, then the
ciphertext and its tag. The Keystore key seals and opens only the 32-byte
data key, so its cost does not grow with the file. The existing `KeystoreSecretCipher` in `:util` is a string codec for
single secrets without location binding, so the Notebook has its own codec
rather than a second contract on that class. The Notebook needs API 28, the
level that adds the StrongBox key generation API and the exception that
sends a generation to the fallback at once; below it the tab is absent and
the key path throws. The
directory is excluded from Android backup and device transfer in every rule
set because the key never leaves the device, so a restored copy could only be
quarantined; the Notebook's own backup, below, is how data moves. Gaps of
this design are recorded in `KNOWN_ISSUES.md` (Notebook).

Constraints the code relies on, each pinned by a test in the module or
in the app's device suite:

- Three front matter readers exist on purpose. `Frontmatter` is strict and
  line-anchored, because the editor splits and rejoins notes through it;
  `FrontmatterTags` walks the block for the `tags:` field; `BodyTokenizer`
  has its own lenient strip, which defines the token vocabulary stored notes
  are indexed under, so changing it means re-indexing every note.
- Stems are derived from display text and never re-stemmed: the Porter
  stemmer is not idempotent. The note store therefore stems a note's tags
  from the `tags:` field of its front matter at every write and move,
  never from the stored stems.
- Model output is shaped through `NoteSanitizers` from `JsonElement`s, so
  the type gates run against what the JSON parser actually produced.
- Folder routing (`routedFolder`) is a deterministic stemmed-overlap score
  with no model call, and `archive` is never a default destination.
- Model replies are read by `LlmJson`: strict JSON once the reasoning block
  and fences are stripped, bare `NaN` and `Infinity` as null, and a reply
  over 256 KB or not a JSON object is a failed classification (fallback
  title).
- A voice reminder's time is read by fixed English rules
  (`SpokenTimeReader`), never by the model, which writes only the message;
  the time words the rules read are removed from it when
  `withoutTimeWords` finds a clean cut. A time they read is armed when
  strictly in the future and at most one year ahead. When they are unsure
  (time words they cannot read; a relation word such as "before", "by" or
  "ahead of" earlier in a read time's clause; a correction after one, as
  in "at 3, no, 4"; a time that describes something else, as in "the 5:15
  train" or "the meeting is on Friday"; "early" or "late" naming part of
  a day or of next week; a stray number, "last" or "end of" beside one),
  and when a read time is already past as the capture is processed, the
  reminder is armed at the quick-remind default and the user is asked to
  check it (a mark until the editor is saved or the time kept, and the
  notice below); with no time words they
  recognize, or only vague ones, it is armed at that default without
  asking. A voice reminder, its time read or defaulted, posts one notice
  of when it is set (where notifications are allowed, and not when
  Android refuses its alarm), a tap on which opens its editor and
  dismisses it: "Reminder set" on its own channel, or
  the notice asking to check the time in its place; a reminder made in the
  editor posts none. Among the paths that take the notice down are the
  reminder going off, being completed, marked missed or deleted, a re-arm
  dropping it or finding it gone, and an editor save or a Keep; of the
  "Reminder set" notices, only the newest ten (`MAX_SET_NOTICES`) stay up.
  Recurrence advances in local
  calendar units counted from the rule's anchor, so a 31st clamped to a
  shorter month's last day does not move the occurrences after it. An
  occurrence's status is decided as completed, then canceled, then by its
  latest fire against now.
- A spoken completion is never resolved on its own: the matcher only ranks
  candidates, every write follows a tap through `voiceCompletionWriteTarget`,
  and an upcoming occurrence has no single-occurrence delete.
- A capture's queue entry is written before any processing and
  removed after it, so a capture survives process death; the in-memory
  capture rows are a projection of that entry.
- The data key's seal is bound to the file's path relative to the Notebook
  root, and the data's seal to that path and the envelope header (under the
  prefixes `gn1:key:` and `gn1:data:`), so an envelope copied to another path
  or folder fails to open, as does a data key moved between envelopes;
  renaming or moving a file means re-sealing it.
- A read separates four outcomes: only an authentication failure (in
  either layer) or a malformed envelope is corruption, moved to `.quarantine` (named
  after the file's path and the time, and deleted by the cleanup below
  once that time is 30 days behind the phone's clock; the failed list's
  Delete removes a recording's copies sooner); a Keystore or I/O failure
  that may
  heal is transient and leaves the file in place; a missing key or a failure
  the key store calls permanent is `KeyUnavailableException` and also leaves
  the file in place (the key store's own verdict, read from Android 13 on,
  is not pinned by a test); and a strict read (the read half of a
  read-modify-write) rethrows both of the latter so nothing is written
  back. Opening never
  creates a key, and sealing creates one only while no file sealed under a
  key remains in the Notebook root (outside `.staging`, `.quarantine`,
  `pending_captures` and `backup_staging`), so a lost key stays reported as
  missing instead of a new key turning every old file into corruption.
  Authentic plaintext that fails to parse is the store's
  `PersistedFormatException`, which a strict read throws and a display read
  degrades to empty; a storage failure raised inside the parse keeps its
  class. A failure of the software layer other than authentication is not
  classified and reaches the caller as it is.
- A write seals for the destination's path, lands in a staging file of its
  own that is synced before the move, and replaces the target in one move,
  atomic where the file system supports it.
- Keystore cipher operations run at most three at a time process-wide; a
  Keystore-layer failure is retried a few times before it surfaces as
  transient, each retry of a seal or open looking the key up again and
  using the handle it finds, if any; an authentication failure is never
  retried.
- The note store keeps each note as `notes/<id>`, the id 32 lowercase hex
  characters from 128 random bits, so file and directory names carry no
  folder, name or title. A note file holds a versioned record of its
  folder, name, title, date, modified time and content; the `notes_meta`
  index lists the same fields by id plus the content hash, beside
  `folders_list` and `folder_tags`. A folder name must be valid and a name
  plain ASCII, and a name is unique within its folder.
- A note's search stems (its body words and front matter tags, stemmed) are
  kept out of the index, in one of 16 files under `note_stems/` picked by
  the id's first hex digit, each entry carrying the content hash it was
  computed from. They are written after the index; a stems file that cannot
  be read or written that time is left as it is, and a corrupt or
  unparseable one is quarantined and started again, without failing the note
  write. `NoteStore.repairStems`, which a reader of the stems runs first,
  recomputes entries that are missing or carry another hash than the index.
  A note in an unindexed folder has no stems.
- The parsed index is held in memory, process-wide per Notebook root, once a
  read has succeeded or a write has landed, and every index write replaces
  it; a read that degrades to empty is not held. Folder lists and note
  writes use it instead of opening the index file again.
- Each store file has one process-wide lock, keyed by file name, that every
  store instance shares; the locks are not reentrant, and each file's
  read-modify-write reads strictly under its lock before it writes, so a
  read that fails (transient, missing key, corrupt, unparseable) changes
  nothing on disk. Mutators run on the I/O dispatcher; the non-suspend
  reads block on file and Keystore I/O, so callers run them there too.
- No read quarantines the index, folder list or folder tags: a corrupt one
  stays in place, display reads degrade to empty, and every mutator fails
  with `CorruptFileException`; for the index this holds from a process
  start until `reconcile` rebuilds it, and once the index is held in memory
  the next index write replaces a bad file. An absent index while note
  files exist is a transient failure for a strict read. A corrupt note file
  is quarantined on its own; its index entry stays, moving with its folder
  as an entry alone, until the note is deleted or the index is rebuilt.
- A note file is written before the index entry that lists it, and deleted
  before its entry is removed. `NoteStore.reconcile`, run under the index
  lock at each process start, repairs an interrupted create, restore or
  rebuild: an index that fails to open or to parse is rebuilt from the note
  files, which are all read before the old index is moved to `.quarantine`,
  and an absent index while note files exist is rebuilt the same way; a
  note file the index does not list is deleted when a listed note with the
  same folder, name and content has a file that opens with that content,
  and listed otherwise (under a free suffixed name if its own is taken). A
  note file that fails to open is quarantined, and one that opens but does
  not parse, or finds no free name, is left in place; both stay unlisted.
  A transient or key failure leaves the index file as it was. A move
  rewrites the note file, then the index, so a move cut short between the
  two leaves the note listed in its old folder; a move to that folder
  rewrites the file to match. A delete cut short leaves an entry without
  its file.
- A new note goes through `createNote`, which takes the first `-n`
  suffixed name its folder does not hold, and reuses the note holding a
  name on the way when its content is identical, rewritten when its file
  is missing or does not open; a suffixed name the name
  rule would refuse is never taken. `updateNote` replaces a note's content;
  the note page's edits go through `editNote`, which times an edit at least
  a millisecond past the note's listed time while that stays within the
  stored window, so a restore of an archive made before such an edit keeps
  the edit while its file opens with its content.
  Deleting a folder lists it again while a note it could not move remains
  under it; a process end before the move finishes, or a relist that
  fails, leaves it unlisted with the notes not yet moved still shown under
  it, and deleting it again moves them; a process end after the move
  leaves the folder's tags behind. Not pinned by a test: what a process
  end leaves. Folders are listed
  from `folders_list` plus any folder that holds an indexed note, so every
  listed note shows under a folder.
- `EncryptedFiles.sweepStaging` deletes staging files older than two
  minutes; `NotebookHousekeeping` calls it at each process start.
- `NotebookHousekeeping` cleans `.quarantine` on each capture run, which
  includes a process start and the app coming to the front with no run
  under way. `EncryptedFiles.sweepQuarantine` deletes a file directly
  under it once the time in its name is 30 days or more behind the phone's
  clock; a time ahead of the clock keeps the file, and a name without a
  time goes by the file's modification time. A copy that a retried replace
  uses again is renamed to the time of that retry, unless that name is
  taken. `NotebookRecovery.removeStartOverLeftovers` deletes the `start-over-*`
  folders a Start over left there, and does nothing while a recovery step
  holds the lock. The Start fresh, Start over and restore dialogs say
  beforehand what they delete; a file set aside by a read or by Set Up
  Again goes the same way with no message. Not pinned by a test: the
  module handing the leftover step to the housekeeping (device runs only).
- The queue store keeps `queue_meta`, the list of `QueuedCapture` entries,
  and one blob per entry at `queue/<id>.bin`; an id is `[A-Za-z0-9_-]`, up
  to 128 characters, and a list with any other id, or a duplicated one, is
  refused on write and unparseable on read. `enqueue` writes the blob and
  then the entry under the one lock and deletes the blob again if the entry
  write fails, so it never writes an entry before the blob it names and a
  crash inside it leaves at most a blob without an entry; `remove` drops
  the entry, then the blob. An entry can still name a missing blob (a blob
  quarantined by a read, or deleted on disk), so a reader handles absent.
  Reading a blob, also under the lock, separates present, absent and
  corrupt: a blob that fails to open, or whose file is over the cap, is
  quarantined on that read and reported as corrupt, so the caller can flag
  the entry rather than take it for stale; `queue_meta` follows the index
  rule above and is never quarantined by a read. `sweepOrphanBlobs` runs under the
  lock and deletes only `.bin` files at least two minutes old that have no
  entry; it skips when the queue file is absent or cannot be read strictly.
  A blob is sealed and opened in memory as a whole, so it is capped at
  32 MiB on write and a longer file on disk is corrupt.
- A capture the store could not seal is moved as plaintext to
  `pending_captures/<id>~<name>`, one file per id (a recording held in memory
  is written to a partial file first, synced, then renamed, and the directory
  synced; a partial file at least two minutes old is deleted by the next
  salvage), and `salvagePending` queues each such file before every capture
  run, deleting the plaintext only once the queued blob holds its bytes; a
  file that still fails, is over the cap, or differs from the blob already
  queued under its id is kept and counted, an empty one is deleted, and a
  symlink or a file not named that way is left alone. A file queued this way
  has no zone of its own. This is the Notebook's one window of plaintext at
  rest, recorded in `KNOWN_ISSUES.md`.
- The reminder stores keep `reminders` (the active reminders and the next
  id to hand out, taken and advanced under the lock, so a deleted id is
  not reused in that store and every restored id ends up below it; a random store
  identity, set by the first write, naming that id space; and the ids it
  gave reminders restored from another id space, by origin), `reminder_events` (one event per occurrence, keyed by the
  reminder id and the scheduled fire instant) and `voice_completions`
  (completion claims waiting for the user to pick their reminder, oldest
  first, capped at 50: a save past
  the cap drops the oldest by insertion, so the entry just captured
  survives whatever the clock did). All three are
  encrypted JSON values read and written through one helper that follows
  the index rule above (never quarantined by a read, strict read under the file's
  lock before every write, a validator on read and on write, a write over
  the file's size cap refused before it lands), so a failed read changes
  nothing on disk and a file the reader would refuse is never written. A
  value this build cannot parse, such as a recurrence frequency written by
  a later build, fails closed the same way as a damaged one. The event
  log's transitions are pure functions in the reminders package: a
  replayed fire never resets an occurrence, a completed or canceled stamp
  is kept once written (the calendar's switch replaces one), the two do not
  exclude each other, a resolved occurrence is neither snoozed nor marked
  delivered, and retention keeps an event until its scheduled fire, its
  actual fire, any pending snooze and its completion all lie behind the
  cutoff.
- The alarm stack (the `alarms` package) keys one fire alarm per reminder
  and one snooze alarm and one notification per occurrence, the pending
  intents told apart by the occurrence in their data URI and the
  notifications by a tag carrying the fire instant, so each occurrence is
  acted on separately; every
  Notebook notification is tagged, and the rest of the app posts untagged
  ids today. An open occurrence counts as missed at its cutoff (`statusFor`:
  midnight after its latest fire, or 02:00 for a fire at or after 22:00) or
  once a later logged occurrence of its reminder comes due, whichever is
  first; the log keeps no stamp for it. A fire posts its notification, logs the occurrence under its
  scheduled instant, then moves the item under the file's lock, and only
  while the item still sits on that occurrence: a one-shot is removed with
  its alarm, a recurring item advances from the later of now and that
  instant and is armed once stored (a series whose next occurrence falls
  past the stores' window ends instead), and a late fire of an occurrence
  the user edited away leaves the item alone. A fire whose log write fails
  leaves an item it would remove stored (listed as waiting to go off), for
  a re-arm to log and remove as it does any past-due item, and
  still advances a recurring item. The fire then takes down the
  notification and snooze alarm of its reminder's open occurrences that are
  missed by the log, and a fire already missed when it lands moves from its
  notification to a notice without buttons. Complete and Mark missed dismiss the
  occurrence, drop its snooze, move the item past it the same way, and
  stamp it, creating the event when it was never logged; when that move
  fails, only an occurrence the log already holds is stamped. The
  notification and the snooze go once the stamp has landed, and each
  reports whether the occurrence was recorded, whether only the item
  moved, or neither. The two buttons
  are marked as showing no UI, which is what the watch mirror keeps by
  default; with the watch setting for such actions on, Snooze is mirrored
  too (libpebble3 `LibPebbleNotificationAction.fromNotificationAction`,
  `NotificationConfig.addShowsUserInterfaceActions`). A swipe snoozes for the
  configured minutes until the occurrence's chain reaches eight (an
  unreadable log arms the re-fire uncounted), the picker snoozes
  regardless and does not count and clears the notification only once the
  snooze is recorded and armed, and a re-fire is posted for an open
  occurrence that is not missed, or for any occurrence when the log cannot
  be read, and marked delivered only when the user could see it. A re-fire
  the log holds no snooze awaiting delivery for (after the log was set aside
  or restored, for one) counts as a snooze to
  that moment, and an occurrence the log does not hold is logged with it;
  it is dropped like any other when its occurrence is resolved or a later
  logged occurrence of its reminder has come due. The picker,
  the swipe and the re-arm each refuse a missed occurrence. One sweep alarm,
  set at the earliest time an open occurrence turns missed (its cutoff, or a
  later logged occurrence of its reminder coming due), takes down the
  notifications of logged occurrences that are missed or completed (one
  not logged yet stays, because a fire posts before it logs) and sets
  itself again; a fire, a re-fire, a Complete or Mark missed and `rearmAll`
  set it. The alarms are absolute instants, so after a time zone change a
  cutoff moves only at the next set. Deleting a reminder (`delete`) clears
  its pending snoozes in the log with their alarms, then removes the item
  with its fire alarm and notifications; a failed write leaves the
  reminder stored. Deleting one time (`deleteOccurrence`) first moves a
  reminder still sitting on it past it, so no re-arm logs it again (a
  failed move changes nothing), then removes its record, notification and
  snooze alarm and runs the sweep; an earlier open time of the same
  reminder that was missed only because the deleted one came due is
  recorded as missed. A reminder's write and the alarm set for it happen
  under one lock (`arming`), in the fire path, the re-arm sweep and the
  Reminders section's edits alike; a new reminder is armed once it is
  stored, outside that lock. `rearmAll` re-arms from
  the stores and is idempotent; it runs at app start, at boot, after a
  package update, when exact alarms are allowed, after a restore and after
  a recovery step: a future reminder
  is armed when it still reads as the sweep read it or cannot be read
  again, a past-due one-shot is removed and a recurring one advances
  from now, every fire that passed unlogged is logged at its scheduled time
  before its item moves (one per cycle of a recurring reminder, none before
  the retention cutoff, at most 366, newest first; a log write that fails
  keeps the item for the next re-arm, a full log drops the oldest and the
  item still moves; a recurring reminder's fire whose own log write
  failed already moved its item on, and the re-arm does not log it, which
  no test pins), a logged occurrence still due is posted as a fire and
  the missed ones share one notice without buttons, each notice beside any
  earlier one and opening the calendar on the latest one's due day, an
  unseen snooze of an occurrence still open is re-posted once and one
  already missed is skipped, events past the retention setting are
  pruned, and the lapse sweep runs. Exact alarms need the user's grant from
  Android 12; the app lists it among its required permissions while a
  reminder exists, without waiting on or failing with the Notebook's
  storage, and arms inexact alarms until it is granted. The receivers run
  in the main process and tell a live screen to re-read through the
  in-process `ReminderRefreshSignal`. A stored reminder's message is capped
  and its recurrence rule must be usable, so what reaches an alarm intent
  or a notification is bounded.
- The backup (the `backup` package) is one password-sealed archive of the
  notes under their ids with their folder, name, title, date and time, the
  folder list, the folder tags, the capture queue with its recordings, the
  reminders and the event log; parked completions, pending
  plaintext captures and settings are not in it. The archive
  (`ArchiveWriter`, version 1) is a header of magic, version, key derivation id, salt
  and nonce prefix, then a record stream sealed in 64 KiB segments with
  AES-256-GCM: the STREAM construction with the nonce laid out as in Tink
  (tink-java main, `subtle/AesGcmHkdfStreaming.paramsForSegment`), the
  header bound as AAD on each segment. The key comes from PBKDF2-HMAC-SHA256
  at 600,000 rounds over the NFC-normalized password and the archive's own
  salt; a password that is not well-formed UTF-16 is refused. An automatic
  backup's archive (key derivation id 2) takes its key from HKDF-SHA256
  (RFC 5869) over a recovery key's ASCII characters, the archive's salt and
  a fixed label instead. A record is a
  type, a length and a JSON body (a capture's body is its entry, then its
  recording); a closing record carries the count per type and the SHA-256 of
  every record before it.
- An archive is built in and read from a staging file under the Notebook
  directory (`BackupStaging`), sealed a second time under a random key held
  only in memory by its `StagedFile`, so a copy of the file, or one a killed
  process left, does not open even with the password; a write fails once
  it finds under 64 MiB free, checked when it opens and again each time a
  MiB or more has gone in since the last check. The first
  capture-coordinator run of each
  process, which the app's start requests, deletes what earlier processes
  left. A backup first runs the store's repair (`NoteStore.reconcile`), then
  writes the captures, then reads and writes the notes through the index,
  then the reminders and events, so a capture the drain publishes meanwhile
  is in the archive as a capture, as the note or reminder it became, or
  both. Every read that feeds it is strict, so a store that cannot be read
  this time fails the run, while an indexed note that will not open or
  parse and is still listed at the end, a note too large for a restore, and
  a queued capture without a readable recording that is still queued at the
  end, are left out and counted, as are note files no index read during the
  run listed. A note file that fails to open during a backup is quarantined
  by that read. A backup holds one segment and one record at a time besides
  the lists it reads. A wrong password and damage in the first segment are
  the same failure.
- A picked archive is copied into staging only after its header passes the
  checks that need no key. A restore reads it twice: the first pass
  authenticates all of it, checks its structure and counts what the
  per-entry decisions (`RestorePlan`, `ArchiveContents`) would take and
  skip, writing nothing, which is also the inspection; then the store's
  repair runs, and the second pass requires the same header and merges
  record by record, holding at most a batch of 64 notes (or 4 Mi
  characters of them) and 64 captures (or 32 MiB of recordings) besides the
  reminder and event lists. The merge is never a replacement and nothing is
  rolled back. Notes match by id and land under the index lock in batches:
  identical content is kept, rewritten when its file is missing or does
  not open; an entry
  modified later is kept while its file opens with its content; anything
  else takes the archived content, title, date and time in its own folder
  under its own name; an id not listed becomes a note in the archived
  folder under its name or a suffixed one, unless a file the index does not
  list holds that id, which is left alone. Folders are unioned up to 1,000,
  a folder's tags are added only when it has none, captures are queued in
  batches under the queue lock, then the reminder ids are adopted and the
  event log and the reminders are each appended in one write that skips
  keys and ids already present, in that order so a re-arm never meets a
  restored reminder without the occurrences it fired. Archived reminder ids
  count in the id space the archive's META names, or the one an item names
  as its origin; adoption gives each accepted item's id the id it has in
  this store: its own id when the space is this store's and no adoption
  holds that id, else the id an earlier restore gave that origin, else a
  new one from the counter past every own id in the archive up to a tenth
  of the store's ceiling, recorded,
  while the file keeps a sixteenth of its cap free. A backup reads the
  event log before the reminders file and writes the origin of every
  reminder and event under an adopted id. Once the re-arm has pruned, the
  adoptions of ids no reminder or event uses any more are dropped. Each store stops taking entries where its file
  would pass the cap its reader enforces, the index measured in the bytes
  it is sealed as. A storage failure part way leaves each store in a state
  its readers accept, and running the same archive again converges, except
  for recordings processed since the archive was made (`KNOWN_ISSUES.md`,
  a capture published twice). The
  per-entry decisions skip and count a note
  whose id, folder or name the store does not spell, whose content is over
  a bound or whose time lies outside the stored window, with its title and
  date cleaned and capped; a capture with no recording or one over the blob
  cap, or with an id, name or time the queue refuses; a reminder or event
  the validators refuse, which also bound every instant to the stored
  window and a recurrence interval to a thousand; an archived reminder id
  over a tenth of the store's ceiling (an archive of this store keeps its
  ids, so this leaves room for the device's own). What the stores themselves refuse (a full file, an
  id already present, no free name, an id in this store's own space that
  an adoption holds or that is over a tenth of the ceiling) is counted by
  the restore only. A
  restored event keeps the snooze it carries, also when its reminder is
  not known here, as after a one-shot's fire. The alarms are re-armed from
  the stores after the repair and
  merge, finished or not, and the report counts what the stores hold after
  that: a restored one-shot already due is logged and shown (in the missed
  notice once missed) unless it is older than the retention cutoff, and
  dropped, and
  restored history past the retention setting is pruned.
- The publisher copies a finished archive into the document the system
  save picker created, under the name it has (`BackupPublisher`, generic
  over the document handle, and `BackupDocuments`, the Storage Access
  Framework binding; `NotebookBackup.publish` binds it to the archive's own
  check). The picker creates the file empty the moment the user confirms,
  so the archive is built before the picker opens and opened before the
  document is touched. A document the provider reports as holding data, or
  whose first byte reads, is refused, as is one whose emptiness cannot be
  read, before any byte is written, so a failed save destroys no earlier
  file. The archive is then written in place and read back through
  `NotebookBackup.verify` (the restore's first pass, with the key derived
  again from the password, which must find the records, hash and item
  counts the writer wrote); a document written but not verified is removed,
  whatever failed, as is an empty one that would not open for writing. On a
  Pixel running GrapheneOS (Android 17) the internal storage provider left
  the app no access to a document after renaming it, so no rename is used.
  The host suite pins the sequence on a fake store and the device suite
  pins the binding against a real cross-process DocumentsProvider.
- Settings > Notebook runs a manual backup, restore or check through one
  process-wide `BackupFlow`, so a run outlives the screen and one runs at a
  time; its dialogs and pickers are composed by the home screen on every
  tab, so a picker's answer after the process restarted still arrives. A
  backup asks for a password of at least 15 Unicode code points after NFC
  normalization (the archive reader refuses a shorter one too), typed
  twice, then builds the archive into staging before the save picker
  opens, so a backup that cannot read the Notebook fails without creating a
  file. The picker suggests `gravel-notebook-<date>-<time>.gnbk` with the
  type `application/octet-stream`, which keeps an extension `MimeTypeMap`
  does not know (android16-release, `FileUtils.splitFileName`). The flow
  holds the password array until the publish ends and then zeroes it, and
  wipes its copy of a picked archive's key when the run ends; the text
  field, the password checks and the key derivation make copies it does not
  reach. A save under way is noted in the no-backup directory
  (`FileRunRecord`) by stage, picked, writing (noted before the first byte,
  once the document is known to be empty, and required to be noted) and
  verified, with a persistable grant on the document (DocumentsUI grants one
  with a save result: android17-release, `ActionHandler.finishPicking`). The
  next process tries to remove the document of a save that reached writing,
  and a picked one only when it reads empty, keeps a verified one, says what
  it did, and reports a restore that was under way; it clears the note only
  while the note is still the one it read. A picker answer that arrives with
  no run waiting deletes the document only when it reads empty, and one the
  activity lost while the process lived ends its run as backed out when the
  host comes back to the front. A picked archive is copied into staging
  after its header check; its key is derived once from the password
  (`NotebookBackup.unlock`) and serves the check and both restore passes,
  and a reader given that key for an archive with another salt reports
  damage; the publish read-back derives its own. A check shows what the
  archive holds and what a restore would skip; a restore asks first, cannot
  be canceled once it starts, and asks for a capture drain unless it
  finished having added none. The backup dialogs open as secure windows
  (`notebookDialogProperties`). An archive with a recovery key asks for the
  key instead of a password, in either of its forms.
- Automatic backups hold the notes and reminders a manual backup holds,
  without the captures, sealed under a recovery key: 64 characters from
  `0-9a-z` drawn with `SecureRandom`, shown uppercase in groups of four
  with `O` as `#` and `0` as `=` (`RecoveryKey`). Setup
  (`AutoBackupSetup`) explains them, opens the folder picker and keeps a
  persistable grant on the folder, shows the key in a secure dialog,
  copies it only after a warning (marked sensitive, with an inexact alarm
  set to clear the clipboard a minute later, `copySensitive`; the alarm is
  set first, and when Android refuses it nothing is copied and an earlier
  copy is taken off),
  asks for it typed back, and writes a first backup; it stores nothing
  until that backup is written and checked, and a failed one keeps the key
  and folder for Try again or another folder. Where the grant on the picked
  folder could not be kept, Try again opens the picker again. A restore that opened with a
  recovery key offers it to setup while automatic backups have neither a
  key nor a folder; a first backup that would store a new or restored key
  is refused while they are set up, and one storing a replacement key
  unless they are. On a phone with a screen lock, setup (the restore's
  offer included), a new key and a folder change start by confirming the
  lock, or a biometric the phone accepts for it, through Android's own
  prompt (`rememberScreenLockPrompt`, which needs `USE_BIOMETRIC`), and go
  no further without that; only the answer to the prompt shown now counts,
  a cancel ends setup and a prompt that fails says so. A lock that cannot
  be read counts as one. A new folder uses
  the stored key, keeps the switch as it was, and starts the record of
  backups over, leaving the files in the old folder and releasing its
  grant. A folder setup picked is listed in the no-backup directory until
  it is kept or let go of, and the next process start lets go of any still
  listed that is not the stored folder. Settings offers setup only once it
  has read that nothing is set up, or, as Set Up Automatic Backups Again
  (below), that the state or key file is damaged or the key file gone. The key (its 64 ASCII bytes and an
  8-byte stamp of when setup made or took it) and the
  state (`AutoBackupStore`) are two sealed files under the Notebook root;
  setup alone writes the key file. Off keeps the key, the grant and the
  record.
- An automatic run (`AutoBackupRunner`, from `AutoBackupWorker` on
  WorkManager) comes about an hour after the first change to the notes,
  folders, folder tags, reminders or their history (the stores' revision
  counters), as unique work kept rather than replaced, so later changes do
  not move it; a change while that work runs asks the second slot, and a
  run that saw the stores change asks for one more. The watch starts
  before the start-up re-arm. A periodic run every 12 hours backs up what
  a change run missed. A run compares `NotebookBackup.fingerprint` (the
  index, the note files' sizes and times, folders, tags, reminders and
  history, but not the next reminder id or the store id) with the newest
  recorded backup's: when they match it reads that file whole with the key
  and checks its SHA-256, and a pass confirms the backup. The fingerprint
  is taken again once the archive is read, and a run that saw it move
  records none, so the next run writes.
  Otherwise, or for Back up now, it writes a new archive to a `.part`
  document through the publisher, which reads it back; the run then
  renames it to `gravel-notebook-auto-<date>-<time>.gnbk` (with a number
  added when the folder holds that name), hashes the renamed document
  against the bytes written, and records it, even when the run is being
  stopped. Each step that touches the folder is noted in the no-backup
  directory first. The next run, or setup, removes a document a run ended
  by its process left under its `.part` name, or one it could not delete,
  and a run waits until that document is gone; a file that already has its
  final name is left unrecorded. `BackupGate` keeps one run
  over the Notebook at a time: a manual backup's build and a manual restore
  wait for an automatic run, and an automatic run that finds it held is
  retried. Lost folder access, a missing folder (a folder app that answers
  but no longer has it), a key that cannot be read and damaged storage are
  notified at once on the Notebook backups channel (a tap opens Settings >
  Notebook) and shown under Automatic Backups; other failures, a folder app
  that cannot be reached or will not make the file among them, are retried
  with backoff from 30 minutes and notified after the fourth failed run in
  a row. A failure the stored count cannot take, the state being unreadable
  or unwritable, is also notified once the work has made three earlier
  attempts, and Back up now notifies a failure when the state reads but
  cannot be written. A
  backup that left out notes (unreadable, too large to restore, or not
  listed) is kept and notified, and the next backup without gaps takes the
  notice down.
- After a run that wrote or confirmed a backup, the runner reads back the
  newest earlier one and every other one not read for a week (or stamped
  later than the clock), by hash, key and counts
  (`AutoBackupRunner.upkeep`). A file that reads to anything else is
  flagged damaged, notified once, kept and read again a week later; one the
  folder app no longer knows and its listing no longer shows (while it
  shows another recorded file) leaves the record, notified once. A read
  without an answer is stamped, and one still without an answer a week
  after the first counts as damaged. Damage and a gone file make the next
  run write even when nothing changed. Thinning (`backupsToThin`) runs only
  when the newest earlier backup read back and every read gave an answer:
  every backup of the last 48 hours stays, then the newest of each day for
  two weeks, of each week from Monday for three months, and of each month
  after that, in the phone's zone; the newest undamaged backup, the run's
  own, damaged ones, one kept before a drop and one whose last read had no
  answer always stay. Thinned entries leave the record into a deletion
  list first, and a file is deleted only while its name, and its size
  where the folder app reports one, are those recorded; a renamed file,
  or one whose size changed, is let go of, and a failed delete is retried
  by a later run that thins.
- The newest recorded backup carries the ids of the upcoming reminders it
  holds. A backup holding far fewer notes than the one before, or one
  after which far fewer of the earlier backup's upcoming reminders are
  upcoming or in the history log (none left, or a quarter gone and at
  least five), is notified, naming the earlier file; that file is marked
  to keep for good in the same write that records the new backup. The
  backup a folder change writes into the stored folder is compared the same
  way. Damage,
  gone files and a drop are told before the write that records them, so a
  failed write tells them again.
- The key file's stamp is recorded with each backup: a run, and a key
  check, drop from the record backups sealed with another key, which
  completes a key change whose process ended between storing the key and
  the record.
- `AutoBackupChecks` runs at process start, when the app comes to the
  front, after a boot, when a reminder goes off, after an automatic run's
  work and on its own inexact alarm, an hour later when it could not use
  the state. While automatic backups are on, a check that finds no backup
  confirmed for 24 hours asks for a run at once and warns an hour later if
  none came, then once a day while none does, each repeat taking the
  earlier notice down first (Settings shows the warned stretch). A check
  that cannot write a stretch's first mark still asks for the run, and
  warns at each check once the last confirmed backup is 25 hours old,
  which Settings does not show. Not pinned by a test: that Settings does
  not show that warning. While they are set
  up, recovery key reminders come 14 days
  after setup, three months after that and every six months after,
  counted again from each successful check; each point posts a
  notification on its own channel, Not now on it brings one more a week
  later, and Settings says a check is due until one passes. A notice is
  posted before the state that records it is written, so a failed write
  posts it again. The last confirmed backup, the stale marks and the last
  key check, when stored later than the clock, are moved back to it; the
  time the key was made is read so and stays stored as setup wrote it. Backup and
  key notices are marked private, with a public version that has a title
  only.
- Recovery Key in Settings > Notebook checks a typed key by opening the
  newest backup with it, or, when that does not open, by comparing it with
  the stored key, which tells a wrong key from a damaged or gone backup. A
  check that opens the newest backup records that read, clearing a stamp
  left by a read without an answer.
  Turning automatic backups on with the key a restore opened with counts
  as a check. "I don't have my key" runs setup with a new key, made once
  the screen lock is confirmed, into the same folder; the backups made
  before it stay in the folder, out of the record and off the deletion
  list, and need the old key.
- The Notebook tab's recovery card reads every store part strictly
  (`NotebookRecovery.diagnose`): the key counts as gone when a read
  reports it missing or unusable and no file read from disk opened, or,
  with no file opened, when Android reports no key while files the key
  guard counts remain;
  otherwise each part whose file fails to open or parse is named, and a
  failure that may pass offers Try again, which also runs the rebuilds.
  The note list and the recordings list are rebuilt (both also at each
  process start) from what remains: each recording that opens gets
  the name `recovered`, its file's time (now when that is unusable), no
  zone, no attempts, no flag and no watch mark (so a watch recording is
  handled as a phone recording, for example under Pause all and in the
  limit on held watch recordings); what the rebuild processes again is in
  `KNOWN_ISSUES.md` (a capture published twice). For any other damaged part the card offers
  Restore and Start fresh side by side, or Start fresh alone for the parked
  completions, which backups do not hold. Start fresh sets the damaged
  file aside in `.quarantine`: the recordings list, folder list and folder
  tags are copied there, the copy synced, and replaced in one move (the
  folder list with Unclassified plus every folder holding a note, the tags
  from each listed folder's name, each built only from a note index or
  folder list that reads); the note index and every other part are moved
  there, and the reminders go with their history, which goes first.
  Fire alarms already set for reminders set aside still go off: each posts
  the text it carries and is logged in the new history, and so does a
  snooze alarm already set (the re-fire above for an occurrence the log
  does not hold). The new stores hold no record of such an alarm, so
  `rearmAll` does not set it again after an event that drops alarms. Not
  pinned by a test: that it is not set again.
  A reminders store not yet written,
  a replaced one included, takes a new identity and a first id at a random
  point in a window below the highest id a backup may carry; the old
  store's ids are unknown, so a new id may still match one whose alarm is
  set, though the window makes that unlikely. With the key gone, Start over
  diagnoses again and clears the key on its own only when Android reports
  no key under the alias, from Android 12 (`NotebookKeystore.resetKey`
  cites why earlier versions do not count); a key that fails or cannot be
  looked up is kept
  (one that passes an encrypt and decrypt stops the start over), and the
  card then says a restart may help and offers Start over anyway, which
  deletes it, except as `KNOWN_ISSUES.md` records ("Start over anyway can
  leave the Notebook key in place"); a delete that fails stops it
  unfinished. It then moves every file
  outside the unsealed
  directories to `.quarantine/start-over-<millis>/`, cancels the automatic
  backup runs and lets go of every folder grant the app holds that no
  setup in progress uses, and last deletes that folder, and any an
  earlier Start over left, without following a link in them (the cleanup
  above deletes what a Start over did not get to); the next write makes a
  new key. Restore carries
  the same step and runs it after the archive's first pass, before
  anything is added: from the newest automatic backup made with the stored
  key and not found damaged, after the screen lock on a phone that has one,
  when a part backups hold is damaged and such a backup exists in a folder
  the app still holds, else from a picked file; the automatic backup's file
  must have the SHA-256 recorded when it was written, and one that does not
  or cannot be opened, or a lock prompt that fails, offers a file with the
  same step. A restore also drops the alarms and notifications under
  archive ids it gave new ids, and of their logged occurrences the snooze
  alarm where the archive holds a snooze still to come (the re-arm then
  treats that snooze under the new id as any other; where the archive
  holds none still to come, a snooze alarm already set stays), so that
  what the archive holds is not armed under both ids, leaving any id a
  reminder here holds to that reminder; history the old alarms above
  logged stays beside the restored history.
  Settings > Notebook offers Set Up Automatic Backups Again when their
  state file is damaged, or once a run has failed on a damaged or gone key
  file: setup starts as a new one, and its save, after the screen lock and
  the new key typed back, deletes the key file and then sets the state
  aside, stops
  the runs and lets go of unused folder grants; the earlier setup's
  backups stay in the folder, outside the new record. It also shows where
  the Notebook key is kept (`NotebookKeystore.placement`). Not pinned by a
  test: the card and its dialogs (device runs only), `resetKey`'s Keystore
  calls beyond a key that works and one that is absent,
  `BackupFolderDocuments.heldFolders`, `placement` on a real key, the sync
  of a kept copy, a failed restore's state written once with its file
  plan, and the
  module passing an automatic backup's recorded SHA-256 to the restore.
- The capture pipeline (the `pipeline` package) works through the queue
  one capture at a time, one drain at a time (`CaptureDrain`), on the I/O
  dispatcher. Before each capture it reads the queue again and takes the
  first of `processingOrder`: short captures before long ones (long is
  over 30 seconds or of unknown length, and never from the watch), each
  group oldest first, under the user's pauses and settings
  (`ProcessingControls`). Pause all holds every capture but those from the
  watch, Pause long recordings holds the long ones, and a pause stays
  until Resume, across restarts, in a no-backup file. A pause, and a short
  capture queued while a long one that may start again is still
  transcribing (Settings > Notebook, on by default), stops the matching
  capture through `CaptureDrain.interrupt`, even one picked but not yet
  started: it stays queued with its attempt given back and starts over
  from the beginning later; once its outcome is decided it is not
  stopped. With Long Recordings Wait for Power on (the default), a long
  capture the phone transcribes starts only while the phone is plugged in
  or after Process now;
  one already running finishes when the phone is unplugged. Plugging in
  asks for a run through a receiver while the process runs, and a
  WorkManager job with the charging condition is scheduled after a pass
  when long captures still wait for power; its worker asks to run again
  while they still do (`LongCaptureCharger`). The watch mark is set by the
  watch source (below). Before the first capture a
  drain reads every store file a publish writes, strictly, so one that
  will not open holds the drain before any engine runs. A capture is
  decoded through the platform's media extractor and codecs, called from
  the app process and reading the recording from memory; a recording
  sampled above 48 kHz or longer than five minutes is refused, never cut,
  which also bounds what the decode holds in memory. It is downmixed,
  resampled to 16 kHz by the shared `:resampler`, and transcribed by the
  long-form path or the server (below); a capture that opens with the
  trigger word is parked as a completion claim before the text model is
  loaded. Otherwise the
  cue detector picks the reminder or the note prompt for the text model.
  A voice note's body is its full transcript: the note prompt asks the
  model for the title and tags only, and routing reads the transcript.
  The time of the recording, not of the processing, dates the
  note and is the "now" the rules read a reminder's time from. A capture
  is published by storing what it asked for (a
  claim, a reminder or a note); during processing it leaves the
  queue only after that write, or when its recording is gone; the alarm,
  notifications and
  refresh after the write are best effort. A capture's attempt is counted
  before its engines run, so one that ends the process still moves toward
  its flag, and given back when the drain holds or is canceled, or the
  capture waits, is rerouted or is stopped. A failure
  is transient (the attempt stands; flagged at ten), holding (no model or
  one that will not load, a speech setting the Notebook cannot use, too
  little memory on the phone route, dictation keeping the engines, no key,
  a store file that
  will not open, the server cases below: the drain stops), waiting (the
  server cases below where another route takes the other captures, and too
  little memory for a capture the server passed to the phone: only that
  capture waits, attempt given back), rerouted (the cases below:
  attempt given back, through the processing rules again) or one that
  would repeat (no speech,
  audio the platform cannot decode or that the rules above refuse, an
  engine deadline, a prompt too long for the context, a value a store
  refuses: flagged at once). A flagged capture keeps its recording,
  except one whose recording failed to open, which the store has moved to
  quarantine. The flag stores a reason code (`CaptureFailureReason`, a
  stored format) in the entry, and in manual backups for a capture whose
  recording still reads; an entry flagged without one reads as having run
  out of attempts, and one whose recording is gone reads as damaged. The failed list (the flagged count) leaves out the capture
  the drain is running and offers Try again, which gives fresh attempts,
  and Delete, which removes the entry, its recording and any quarantined
  copies of it; one whose recording is gone has no Try again. A note routed to a
  folder it was not offered, or to an unindexed one, is written in
  `unclassified`, and a note's file name is
  cut so it and its largest suffix fit the store's limit. Host tests
  cover these on fakes and temporary stores, except the strict folder
  reads behind the store check (which reads those files first), the
  readiness check's failed-load branch, `isPluggedIn`, the power receiver,
  the charging work and its worker, the pause file's sync, the failed
  list's buttons and notices, and the Koin
  wiring; `CapturePipelineDeviceTest` runs
  WAV and AAC captures through the real engines and the Keystore. The
  engines reach the pipeline through interfaces the app binds
  (`NotebookEngines` in `:composeApp`). A capture is dated in the time zone
  its entry carries; one without a zone, or with one the runtime does not
  know, uses the current zone.
- The speech setting routes captures (`NotebookSpeech` in `:composeApp`,
  `SpeechRoute`): Local Only and the Rebble modes with a phone fallback
  use the phone; Server Only, Server (with Local Fallback), and Local
  (with Server Fallback) while the phone has no model, use the self-hosted
  server; Local (with Server Fallback) with a model tries the phone first
  and sends the server a capture whose model will not load, that it has
  too little memory for, runs out of time on, or hears no speech in. It
  marks the entry (`QueuedCapture.phoneTried`) when the phone ran or its
  model would not load, so a later attempt skips the phone; a memory
  refusal is not marked. When the setting has no server step by the end
  of the phone step (another mode chosen, or the server URL removed), the
  capture is not sent and is rerouted as below. A fallback mode
  without a valid server URL uses
  the phone; Rebble Only, the platform engine, and Server Only without a
  URL hold with their own line. Server (with Local Fallback) with a phone
  model (`SERVER_THEN_PHONE`) sends a short capture to the phone when the
  server step fails for any reason but the server hearing no speech. A
  long one waits while the Wi-Fi rule refuses or the app's default network
  goes out over nothing but a VPN or not at all (`hasUplinkNetwork`);
  each transport failure on an allowed network is a server try
  (`QueuedCapture.serverTries`, with its time), at least 15 minutes apart,
  each recorded try replacing a WorkManager job due when the next one is,
  and after the third, or after a server error, the capture goes back
  through the processing rules (`CaptureFailureKind.REROUTE`, once per
  pass), which send it to the phone, where the charger rule applies,
  unless the server settings changed meanwhile. A server error there
  starts the server's hold with no capture to flag; while it lasts the
  mode routes to the phone and the screen shows a notice
  (`CaptureSpeech.notice`) with Try again. Try again on the failed list
  resets the server tries. In both fallback modes with a phone model,
  Flaky Network Mode sends captures made on the watch
  (`QueuedCapture.fromWatch`) to the phone alone. A capture goes to the
  server as one WAV built in
  memory (`SelfHostedTranscriptionService.transcribeRecording`), on a
  client of its own whose read timeout is 10 minutes, under dictation's
  Wi-Fi-only rule, and that function ends the whole request at 15 minutes
  (`RECORDING_REQUEST_LIMIT`); a capture known to go to the server
  (`ProcessingRules.toServer`) never waits for the charger. A server step
  that cannot run holds the drain on the server route and makes only its
  capture wait where the phone takes the rest. The Wi-Fi rule refusing, or
  a server whose name does not resolve, that refuses the connection or
  that does not answer the connect (asked once per pass), waits for the
  network: `ServerNetworkWatch` asks for a run when the rule starts
  allowing a request, and after a pass that held so schedules a
  WorkManager job on any connected network, whose drain applies the rule
  and which retries with a linear backoff from 15 minutes while the hold
  stays. On the other server routes, any other transport failure (a
  connection lost once made, a timeout before the 10 minutes are up, a
  socket the system refuses) is a transient failure (a counted attempt;
  `TransportFailure` in `:util`
  tells them apart by exception class). A server error (an HTTP error, a
  reply without text or too large, a refused certificate, a timeout after
  the 10 minutes, a request still open at 15 minutes) keeps captures off
  the server (`ServerCheck`, in memory, so a restart tries again) until
  Try again or a change of the server URL, model, token or pinned
  certificate; Try again lets the capture whose
  error started the hold through for its next request, and a server error
  there flags it. Host tests cover the routing, the failure kinds, the
  hold, the fallback mode's tries, reroute and notice, the watches, the
  startup order and the client's read timeout on fakes or a local server;
  the network and server-try jobs, their worker, the network check, the
  Koin wiring of the route, the notice and Flaky Network Mode, the network
  watch, the speech-setting watch and Try
  again, and the rendering of the hold and notice lines are not
  host-tested; the download job's reports of a finished download are
  checked as source text.
- The watch source is the Notebook watch app (its own repository,
  `gravel-watchapp`; UUID, message keys and digests in `NotebookWatchApp.kt`).
  libpebble3 takes an embedder handler for one app UUID
  (`WatchAppCaptures`, passed to `LibPebble3.create`):
  `VoiceSessionCoordinator` gives that app's sessions to it instead of the
  transcription provider, without `canServeSession` (which raises the
  dictation holds), under the same bound, deadline and supersede rules,
  and holds the setup reply up to `FAST_LINK_WAIT` (6 s) until the BLE
  link has no peripheral latency, refusing the setup if it still has;
  the pace (`WatchLinkPace`) comes from a hidden GATT server callback, a
  link whose pace is unknown is not waited on, also when a GATT server
  change forgets the pace during the wait, and while it waits
  Gravel asks for the fast link (Kable `requestConnectionPriority`);
  the app gets no companion app (`startAppSession`) and its AppMessages go
  to the handler, each ACKed after it; classic PebbleKit START and STOP
  ignore its UUID. The UUID is self-declared, so `NotebookWatchApp`
  accepts a recording only when the SHA-256 of the PBW the phone caches
  for the locker entry (`LockerApi.cachedPbwSha256`, the file the watch is
  served its binary from), read at the start and the end of the
  recording, is the same both times and either a shipped release's
  (`SHIPPED_WATCH_APP_RELEASES`) or the one the user marked as trusted
  (next item); what that leaves open is in `KNOWN_ISSUES.md`. The
  handler answers only after the recording ends, as text the watch shows
  verbatim (an empty transcription is rejected by the watch: PebbleOS
  70ed290, `services/voice/transcription.c`, `transcription_validate`).
  It refuses, storing nothing: audio over 256 KiB (an empty frame counting
  as one byte), an untrusted copy, a
  Notebook that is off, not set up or unable to record on this phone, a
  declared Speex rate or frame size other than the decoder's (16 kHz, 320
  samples, `WATCH_SPEEX_FRAME_SAMPLES`), over five minutes of frames,
  under a second, 50 watch recordings held (queued or kept), 60 stored in
  the past hour (counted in memory on a monotonic clock). Once the audio
  has ended the rest runs even if the session is canceled: a wait of
  `WATCH_CANCEL_WAIT` from the recording's end for the watch's cancel
  message, the Speex decode, and the ingest of a WAV with `fromWatch`,
  dated at the session start;
  an error before the store answers "Couldn't save", and a stored
  recording answers "Saved", or that it waits for Wi-Fi or for a setting
  or model (`NotebookSpeech.watchRecordingWait`, the drain's own holds
  for engines that succeed, without their side effects; a missing text
  model counts as a setting), with "Saved" when that lookup fails or takes
  over 2 s. The watch app cannot tell BACK during recording from BACK
  after SELECT (`applib/voice/voice_window.c`, `prv_mic_click_handler`
  reports `DictationSessionStatusFailureTranscriptionRejected` in both
  states), so a cancel applies to the recording in progress and only its
  wait reads it. A recording ingest could not seal is kept under a
  `watch_<start>` name, which `QueueStore.salvagePending` queues as a
  watch recording dated at that start. Host tests cover the routing, the
  hold, the link pace and its requests, the
  relay, the session-start choice, the digest, the handler's refusals,
  limits, cancel window, errors and answers, and the wait against the
  drain for every setting; the Speex decode, the Koin wiring, the
  lifecycle manager's and locker's calls into these, the hidden callback,
  its keep rule, the BLE transport's attach call and the priority request
  are checked on a device only; following a reopened GATT server is not
  tested.
- Gravel installs the watch app itself. `SHIPPED_WATCH_APP_RELEASES`
  (`NotebookWatchApp.kt`) lists each release Gravel has shipped, with
  its version, SHA-256, size and release file address on GitHub, oldest
  first; the last entry is the one Gravel installs. `WatchAppInstaller`
  downloads that file with the app's shared HTTP client on a tap, stops
  reading once the body is longer than the release, and calls
  `LockerApi.sideloadAppWithoutLaunch` only when the download's length
  and SHA-256 are the release's, so nothing parses the file before that.
  That route stores a sideload without opening the app on a watch and
  refuses a PBW that is another app's. It keeps the order of
  `Locker.sideloadApp`, which deletes an entry's cached PBW before it
  stores the new one and gives the entry a new sideload's place: a store
  that fails leaves the entry without its PBW, shown as a copy that
  could not be checked, and an update moves the app in the watch's app
  list. `NotebookWatchAppControls` follows the app's locker entry and
  derives the status from the digest of the copy the phone caches
  (`watchAppStatus`): not installed, the newest release, an older
  release, a copy marked as trusted, or unverified. It reads the status
  again when Settings or the guide's watch page opens, because the
  cached copy can change with no change to the entry. Settings >
  Notebook shows the status with Install, Update or Replace (on phones
  that record), Trust This Copy (the question shows the copy's whole
  digest, and the mark, that digest, is saved only while the locker
  still holds that copy; it stays on this phone, in the no-backup
  directory), Stop Trusting This Copy, Record with a Watch Button (the
  four hold buttons with what each opens now, offered under the
  conditions the watch's own Quick Launch settings are shown under,
  `watchButtonsCanBeSet`) and Remove Watch App, which first turns off
  each Quick Launch button set to the app. An install or a removal from
  these screens ends the mark. The setup guide has an optional watch
  page while a release is listed, on phones that record, and the
  Notebook tab names a newer release until its OK is tapped. Host tests
  cover the installer against a mock engine, the status and trust rules,
  the trust file, the controls, the removal's order, the update line's
  put-away state, the guide's page list and the screens' text rules. No
  host test covers `sideloadAppWithoutLaunch`, the Koin wiring or the
  screens themselves: those are checked on a device.
- Watch dictation and captures share the server through
  `ServerDictationHold` (`:util`), whose `sendRecording` every server
  request of a capture goes through. `HybridTranscriptionService` reads a
  long capture's request under way as a long recording on the server; in
  the two fallback modes with a phone model, a dictation made meanwhile
  stays on the phone alone, the server fallback included. Server Only,
  and a fallback mode without a phone model, raise the hold as the watch
  session opens (`earlyInit`); the dictation's `transcribe` replaces those
  holds with its own and releases it when it ends, so only a session that
  never reaches `transcribe` keeps one for its full `DICTATION_HOLD`.
  While a hold is up, captures wait before their request, and a long
  capture's request under way is canceled and sent again from the start
  inside the same speech step, so nothing is counted and the queue is
  untouched; a capture whose route or server settings changed while it
  waited goes back through the processing rules (`REROUTE`). A server that
  sends `Gravel-Dictation-Lane: separate` on a successful reply
  transcribes dictation apart from long audio: `SelfHostedServerStore`
  keeps the host and port of the last successful reply when that reply
  carried it, the settings dialog forgets it when it saves another
  address, and for that server dictation routes as if no capture were on
  it. Host tests cover the hold, the dictation routing with and without a
  lane, the header and its memory, and the Notebook's requests through
  the hold; among what is not host-tested are the Koin wiring, the
  dialog's forget on save, a hang-up reaching a real server, the stop
  racing a request that has just ended, and Local (with Server
  Fallback)'s check after a timed-out phone attempt.
- Before a transcription on the phone the pipeline compares the phone's
  available memory with what a transcription on the dictation model adds
  (`WhisperTier.longFormBytes`: whisper's own count of the call's state
  and a five-minute recording's buffers, plus a quarter, measured on
  device; plus the model's file size when that model is not loaded, since
  the call loads it first), against the system's low-memory threshold.
  When that is short it frees the text model and compares again, and a
  second shortfall holds the drain, or, for a capture another route passed
  to the phone (Server (with Local Fallback), or Flaky Network Mode for
  one made on the watch), makes only that capture wait; otherwise the text
  model stays
  loaded.
  Host tests pin the order and the need; the estimates are measurements to
  repeat when the engine or the long-form limits change.
- `CaptureCoordinator` runs the drain on a scope of its own, so no screen's
  lifetime bounds it, one run at a time: at process start, where
  `NotebookHousekeeping` sweeps stale staging and backup staging files,
  reconciles the note index, lists a missing Unclassified, rebuilds a
  damaged recordings list, queues recordings held for a later attempt and
  only then sweeps orphan blobs; whenever an
  activity resumes after none was; when the Notebook screen starts; and
  when a text download finishes, a text model is chosen or an installed one
  is deleted (Settings > AI below); and on Resume, Process now, a change of
  Long Recordings Wait for Power, the phone being plugged in or unplugged
  while that setting is on, the charging job, the Wi-Fi rule starting to
  allow a request while the drain waits for the network, the network
  job, a change of the speech settings in the app's config
  (`SpeechSettingWatch`, on `STTConfig`; a change of the server's token or
  pinned certificate alone starts no run), the download job finishing a
  speech model's download (the same watch, on `SpeechModelInstalls`, which
  the job counts up on its success path), and Try again on the server's
  hold or notice. A request during a
  run is taken by that run, while a capture queued during one, a text model
  change, a speech settings change, a finished speech model download, or
  one of those processing changes
  makes it go round once more. Each run first queues the recordings kept for
  a later attempt. A capture that failed transiently waits for the next run;
  nothing retries on a timer. From the moment a run has a capture in flight,
  or a capture is queued, with no activity resumed until the run ends or an
  activity resumes, the coordinator holds: a partial wake lock (30 minutes,
  extended at each row change) and `NotebookProcessingService`, a
  `specialUse` foreground service with a content-free notification that does
  no work and stops itself when the hold ends (its KDoc states why nothing
  stops it from outside). From the background, Android 12 and later refuse
  that start without an exemption (see `InferenceBoostService`); the refusal
  is logged, the wake lock is still taken, and a process the system then
  freezes or ends leaves its capture queued for the next run. The phone
  recorder takes the same service and wake lock under a hold of its own from
  the record tap until its recording is queued (`PhoneRecorder` states why),
  and the queue call takes the processing hold before the recorder lets go of
  its own; the notification, when shown, says a note is being recorded while
  the recorder holds it. Host tests pin the hold rules, the hand-over, the
  run coalescing, the startup wiring and the housekeeping steps;
  `NotebookProcessingServiceTest` pins on a device that the service and the
  wake lock follow each hold and its release.
- The phone recorder (the `sources` package) records the microphone through
  AudioRecord as 16 kHz mono PCM16 held in memory, one recording at a time,
  and only while an activity is started. A recording ends at Stop, at five
  minutes (the long-form limit), when the microphone is lost, when the last
  resumed activity pauses or no activity is left started, or when the
  Notebook tab is left or a note is opened, and keeps what it had; one
  under a second is discarded. The screen stays on while the tab
  shows a recording. The recording is dated with the time and zone its
  microphone started in. The text model starts loading when the recording
  starts, unless a capture run is under way. `CaptureIngest` stores a
  finished recording as a WAV and asks the coordinator for a run; it runs to
  its end if its caller is canceled, and a recording the queue refuses for
  any reason goes to `pending_captures/` (above). A process death during a
  recording loses it (`KNOWN_ISSUES.md`). The microphone permission is asked
  for at the record tap through `PermissionRequester` and is not in
  `RequiredPermissions`, so the missing-permission prompt does not ask for
  it; a refusal says so, and one reported as permanent links to the app's
  settings. Host tests pin the recorder on a fake microphone and the ingest
  on a temporary store; `PhoneRecordingDeviceTest` checks on a device that
  the platform decoder returns the WAV's samples, that a five-minute WAV is
  not refused, that the microphone opens in the capture format, and that a
  directory opens for a sync.
- A typed note (`TypedNotes`) is written as typed, with no model and no tags,
  in the chosen folder when a capture's note could be routed there and in
  `unclassified` otherwise. Its title is cleaned and cut to what a restore
  keeps, never inside a surrogate pair, and a note longer than a restore
  takes (`RestorePlan.MAX_NOTE_CHARS`) is refused, so a typed note fits what
  a backup keeps. The editor is a full-screen dialog; with text in it, it
  closes on a save or a confirmed discard. The draft is held in the
  ViewModel, not in saved screen state, so a process death discards it.
  Dialogs, sheets and menus are windows of their own, so each one the
  Notebook tab opens sets `FLAG_SECURE` itself (`notebookDialogProperties`,
  `notebookSheetProperties`, `notebookMenuProperties`); a popup inside one
  without properties of its own takes the flag from it (androidx-main,
  `AndroidPopup.android.kt`, `isFlagSecureEnabled`). Every Notebook text
  field asks the keyboard not to learn from it
  (`IME_FLAG_NO_PERSONALIZED_LEARNING`, set in `NoKeyboardLearning`):
  Compose builds the field's `EditorInfo` itself and its `KeyboardOptions`
  carry no such flag, so an interceptor adds it (`InterceptPlatformTextInput`,
  ui-android 1.11.1, `PlatformTextInputModifierNode.kt`, marked
  experimental).
- The tab (the `ui` package) sits second in the bottom bar, shown until the
  user turns off "Show Notebook Tab" in Settings > Notebook and absent below
  API 28. That Settings category (after General, hidden below API 28) also
  holds quick reminder completion (its trigger word set through
  `TriggerWordDialog`), the default time for a voice reminder with no clear
  time, the swipe-away snooze and how long reminder history is kept; the
  last three are picked from a short list that also shows a stored value
  outside it (`NotebookSettingsChoices`). `WatchSettingsScreen` reads and
  writes them through `NotebookTab.settings`, which `AndroidNotebookTab`
  keeps over `NotebookPreferences`. The page and its dialog show no note or
  reminder content and keep the Settings window's screenshot policy.
  While shown it takes the slot upstream gives the Index tab, so Health
  and Notifications share one: the one the bar leaves out gets a row in
  Settings (`WatchHomeNavTab.displacedTabs`). The tab bar in `:pebble`
  compiles for iOS too, so it reaches the screen through the `NotebookTab`
  interface, which only the Android app binds (`notebookTabModule`) and
  common code looks up with `getOrNull`. The screen puts the capture area
  (the record button and New note, the drain's progress and hold reason,
  the waiting and flagged counts with the failed list, the
  parked-completion banner and the recent capture rows) above the Notes,
  Reminders and Calendar sections.
  Its counts come from strict reads of every store part
  (`NotebookRecovery.diagnose`), the queue and the parked claims, taken one
  at a time when the screen starts, after each drain pass, after each write
  to the parked claims (`VoiceCompletionStore.revision`), after each
  recovery card action, pause, resume or failed-list action, and when a
  capture settles or joins the batch; a
  missing or unusable key or a damaged file replaces them with the recovery
  card (above), and an empty claim list clears the "to review" notification, one at a
  time with a park's save and notice. While any Notebook screen is composed,
  its window holds `FLAG_SECURE`: the screen shows its content two frames
  after setting it, and the flag is cleared two frames after the last
  Notebook screen goes unless one came back or the window already had it
  (`NotebookSecureContent`, `SecureHolds`), so the other tabs stay
  capturable; the debug build's adb receiver can lift it for screenshots.
  The notification taps' OPEN_* intents carry a random token kept in the
  app's no-backup directory (`NotebookLinkToken`). `MainActivity` hands
  every such intent to `NotebookIntents` first, which ends its handling
  there and opens the tab only for one with that token, not relaunched
  from Recents, while the tab is shown; it reads only ids from the intent,
  never the text in it.
- The setup guide (`NotebookSetupGuide`, `:pebble`) takes the tab's place
  until it is finished on this phone; the tab shows nothing until the
  guide's state is read (`setupSlot`). Its place is saved at each page in
  the no-backup directory (`DeviceStateFile`, which cites the platform
  rule), so it resumes there, or back at a model page that would hold
  Next, and a phone set up by Android's transfer runs it again.
  The pages (`setupSteps`): early beta notice; welcome, with Restore from
  a backup; speech, only while the Notebook cannot transcribe under the
  app's speech setting (`NotebookTab.canTranscribe`), whose download sets
  the speech model watch dictation uses too; text model; long recordings;
  quick reminder completion; default reminder time; automatic backups; the
  watch page, while one is offered (above); done. A phone that cannot run
  the speech or text engine gets one typed-only page in place of the
  recording pages, and its capture area offers New note only. The speech
  page goes on once a download is scheduled or running, under Rebble Only,
  or when no model can be offered; the text page once a model is there or
  a download is scheduled or running; the backups page once backups are on
  or after Not now. Settings > Notebook > Notebook Setup Guide reopens a
  finished guide, which then has a close button and saves no place; a
  Notebook notification that opens the tab closes it.
- The tab's backup reminder (`BackupReminder`) shows while automatic
  backups are off, never set up, or need to be set up again (not while
  their state is unread or unreadable), with Set up, Turn on or Set up
  again, Not now (7
  days, the guide's Not now included, which also undoes Never) and Never
  remind me again. The answers stay on this phone (`BackupReminderFile`,
  no-backup directory). A snooze reaching more than 7 days ahead counts as
  run out; the tab reads the time again whenever it is shown.
- Upstream's Settings > Calendar is named Phone Calendar and also holds the
  Notebook's "Show in Notebook" switch and its own pick of calendars
  (`PhoneCalendarsDialog`, opened when the switch is turned on with none
  picked), whatever the watch's calendar settings are. The switch shows off
  while Gravel lacks calendar access, so a tap asks again after access was
  taken away, and a refused ask turns it off. Granting the access also
  lets libpebble3 put the phone's calendar on the watch while the watch's
  Enable Calendar is on, so with access missing and Enable Calendar on the
  switch asks first (`phoneCalendarFirstStep`): "Notebook only" turns
  Enable Calendar off before the access prompt. The picker lists calendar and account names in
  the Settings window.
- Settings > AI takes Speech Recognition's place in the list and opens two
  pages: Speech Recognition, upstream's category, now kept out of the
  top-level list (`Section.parent`), and, where the Notebook runs, LLM
  (`TextModelsScreen`), which reaches the app through `TextModels` (bound
  by `AndroidTextModels`). LLM lists the catalog's text models with their
  size, the estimate of the text model's part of a note and the
  recommendation, refuses a second text download while one is shown as
  started, and offers no download where the text engine cannot run. A
  download shows as started when requested, since the job reports itself
  only when it runs. A text download that finishes with its model
  installed, unless the user canceled it, becomes the Notebook's choice;
  that, a choice and a delete of an installed model make the capture
  drain run, or go round once more. The download job reports the finish
  to `AndroidTextModels` on its success path (`TextDownloadFollower`) and
  waits for that follow-up, in whichever process it runs. Deleting the
  chosen model clears the
  choice; deleting a part an interrupted download kept also cancels that
  model's scheduled download. The Notebook's paused line offers the LLM
  page when the text model is what holds processing
  (`CaptureRemedy.TEXT_MODEL`). The device suite's `TextModelsContentTest`
  pins the page's questions: a download of a model whose memory floor is
  more than 10% above the phone's memory, and a delete. Among what no test
  pins: the Settings entries, the view model, the paused-line button
  (device runs only), the per-model job cancel, and the provider holding
  the model's install lock while it deletes a part.
- The Notes section (`NotesSection`, `NotesViewModel`) goes from the folder
  grid to a folder's notes to one note, with Back stepping out; a selection
  belongs to the folder page, and nothing goes into saved screen state.
  While a note is open it takes the whole tab area, so the capture area is
  hidden and a recording in progress stops and is kept, as when the tab is
  left. The screen reads the page it shows when it opens and again after
  each write to the note index, the folder list or the folder tags
  (`NoteStore.revision`, one count per directory), so a capture, a typed
  note or a restore shows without a refresh; a read that finishes after its
  page was left is dropped. A failed read shows why instead of an empty
  list; the folder grid needs only the index, and without the folder list
  it shows Unclassified and the folders holding notes. A note is read for
  display without quarantining a damaged file. Actions and edit saves run
  in the ViewModel's scope, actions one at a time; a batch's selection and
  a move dialog land only on the page they started from, and messages wait
  in the state until shown, without folder names (a snackbar can outlast
  the tab's `FLAG_SECURE`). Batches go to the store as one call
  (`moveNotes`, `deleteArchivedNotes`), which writes the index once per 64
  changed notes and goes on past a note it fails on. The section's Back
  handler is added again each time it becomes enabled, so it runs ahead of
  the home screen's (`NotesSection`).
  A note is edited from what the editor opened, against the hash the index
  lists: `NoteStore.editNote` refuses the save when the note changed since
  (a restore, for example), was archived, or is gone, and the editor keeps
  the text. The note page and the editor show the body after the front
  matter as it is (`Frontmatter.editableBody`), an unchanged draft closes
  without a write, and a title left as it was keeps the note's title and
  name; a new title is
  written into the front matter when the note has one and into the index,
  and renames the note (the new title's slug, the old date stamp kept).
  Archived notes are read-only; they can be restored to a folder or
  deleted, and deleting asks first and deletes only notes still in the
  archive. The note page renders the body as markdown (`NoteBody`), parsed
  off the main thread and laid out in a lazy list one top-level block at a
  time. It is shown as plain text in pieces of up to 4 Ki characters
  (`NoteDisplay.chunks`) instead when `NoteRenderLimits` refuses it: before
  parsing, a line starting with more than `MAX_DEPTH` levels as
  `NoteRenderLimits.mayParse` counts them (a task-list box adding none) or
  a run of lines
  holding more than `MAX_RUN_BRACKETS` `[`; during it, a parse still running
  after `PARSE_BUDGET_MILLIS` or once the page is left (through the parser's
  cancellation token; where the parser consults it is cited there); after it,
  a failed parse or a tree past the depth, block-size or item limits, or
  with two top-level nodes at one offset (the lazy list keys items by
  offset). Single line breaks are kept. Inline HTML, HTML blocks,
  `==highlight==`, `=` signs, link definitions, links written with an
  angle-bracketed destination and images show as the text they are written
  as, and no image is loaded (the renderer loads images only through its
  image transformer, whose default returns none: renderer 0.45.0,
  `NoOpImageTransformerImpl`). A tapped link goes to the page's `UriHandler`
  (`NoteBody` cites where the renderer reads it) and opens its own
  destination: the page keeps no link table (`NoteLinks`), so
  reference-style links do not resolve. Only printable-ASCII https links
  with a host and no user part, and mailto and tel links (`NoteLinkSafety`),
  are offered, after a confirmation showing the site and the whole link;
  they open with `ACTION_VIEW` and `CATEGORY_BROWSABLE` on the trimmed text,
  scheme lowercased (`linkIntent`), and any other link is refused with a
  message. The renderer is `com.mikepenz:multiplatform-markdown-renderer`
  0.45.0 on JetBrains' `org.jetbrains:markdown` 0.7.15, declared above the
  0.7.9 the renderer asks for. The renderer ships Java 21 class files, which
  a JDK 17 host test cannot load, so host tests cover `NoteRenderLimits`
  (parser types only, including a check that the running text of sample
  notes holds only tokens the renderer draws or Gravel appends), and the
  renderer is exercised on a device: `NoteMarkdownDeviceTest` and the
  Compose UI test `NoteBodyComposeTest` (androidx `ui-test-junit4`, kept at
  the app's Compose UI version, with `ui-test-manifest` in debug builds;
  the debug overlay unexports the test activity that library exports). A note longer than `NoteDisplay.MAX_EDIT_CHARS`
  (32 Ki characters) opens read-only with a line saying so, and the editor,
  typed notes included, takes no text past that length: typing slows with the field's length (about 50 ms per
  keystroke at 32 Ki characters on a Pixel 6 Pro, seconds past 128 Ki).
  Tapping a capture's "note saved" row opens that note.
- The Reminders section (`RemindersSection`, `RemindersViewModel`) lists
  fired occurrences still due (not missed), occurrences waiting on a
  snooze, and every stored
  reminder by its next fire, one whose time passed without a fire marked
  as waiting to go off (`reminderListing`). It reads the reminders and
  the event log strictly when it starts, on each
  `ReminderRefreshSignal` tick (subscribed before the first read) and on
  "Try again", and works the list out again each minute from that read, so
  a due row lapses on time; a reminder still sitting on an occurrence the
  log holds is shown as gone off. Completing, marking missed and deleting
  a reminder ask first (there is no
  undo), and the outcome is reported; deleting a single time is left to the
  calendar. The editor (`planReminderEdit`)
  takes a message up to the stores' cap, a date and a time in the phone's
  12- or 24-hour setting (formatted by ICU from skeletons), and a repeat
  rule: a one-shot must be in the future, a repeating reminder may start
  in the past and first fires at its next occurrence, an edit that keeps
  the time and the repeat changes only the message (re-armed at the
  stored time, so a late reminder still fires), and one that keeps the
  repeat and the shown day keeps the rule's anchor date. A reminder marked
  to be checked shows "check this time" beside its time while that time
  is ahead, a note in its editor and "Keep this time" in its row menu; a
  save or a Keep clears the mark (not pinned by a test: the hint, the menu
  item and the note). The date and
  time pickers' states are not kept in saved state. A reminder is stored,
  then armed. The notification's Snooze button
  opens a picker for its occurrence, read from the event log rather than
  from the intent; a set notice opens its reminder's editor, and a capture
  row opens its reminder, neither replacing an editor already
  open. The Reminders ViewModel takes those links; the tab's ViewModel
  only shows the section: Calendar for the missed notice's link, which
  waits until that section shows, and for the other links, the banner and
  the capture rows, Reminders from Notes, leaving Reminders or Calendar
  as shown; their editors and the review open over either.
  The Calendar section, served by the same ViewModel and opened on
  today's month with today listed by a tap on its tab, shows a
  month calendar (`ReminderCalendar`, `buildCalendarOccurrences`, worked
  out only while shown): six weeks starting on the phone's first day of
  the week (`firstDayOfWeek`), a dot per time on each day (pending,
  completed, missed; three at most, then a count), and the selected day's
  times under the grid with Add reminder (09:00 on a later day, an hour
  from now otherwise). A logged time shows on its due day, except a
  completion not switched in the calendar, which shows on the day it was
  done; one done before 02:00 counts on the day before when the time went
  off at or after 22:00 that day, a snooze re-fire that late being kept on the
  record when a later snooze replaces it (`lateNightFireAt`). A stored
  reminder's next time always shows, a repeating one's later times up to a
  month from today, and the calendar pages from the month history is kept
  from to the furthest time it shows. Tapping a time opens its details
  (words, status, first due, when completed or marked, the last snooze)
  and what can be done with it, each asking first: a completed or missed
  time switches to the other, which then shows on its due day
  (`setInCalendar`), or is deleted; a pending one is completed, marked
  missed or deleted; a next time is completed or marked missed ahead,
  deleted (a repeating reminder moves on, a one-time one is removed) or
  its reminder edited; a later time offers only the edit. A switch in the
  calendar replaces a stamp; completing or marking missed elsewhere keeps
  the first one an occurrence gets.
  With "Show in Notebook" on in Settings > Phone Calendar and a calendar
  picked there, the section also shows the phone's own calendar events
  (`PhoneCalendar`, read through `AndroidPhoneCalendar` for each month the
  calendar shows, again on a zone change, on Try again and each time the
  section starts): a neutral dot per event after the reminders' dots
  (three dots at most), and the day's events under its reminders, all-day
  ones first. Canceled events and ones the user declined are left out; of
  each event the section keeps its title, times, all-day flag and calendar
  name, and a tap on one does nothing. Picks are
  stored as a calendar's identity (`calendarKey`: account, name, sync id),
  not the provider's row id, so they follow the calendar through a restore
  and do not pass to another calendar given the same row id. While shown,
  the calendar pages at least a year either side of today, and a line under
  the grid says when no calendar is picked, calendar access is off or the
  read failed.
  The completion review, opened from the banner or the "to review"
  notification (which opening it clears), lists the parked claims, read
  strictly with the section's reads while it is open, each with up to four
  candidates (`completionCandidates`, worked out off the main thread with
  each read): every stored reminder, one whose time passed included, and
  the logged occurrences, a reminder still on a logged occurrence offered
  as that occurrence. Candidates are ranked by distance from when the
  claim was spoken, and a logged occurrence nobody completed or marked missed
  stays completable after it lapses. The notification link waits while an
  editor, a confirmation or the snooze picker is open, so the review does
  not open over them, and while it is open the section's messages show
  inside it. Every action but "New reminder" asks first, and that one
  writes only when its editor is saved. An action that writes a reminder
  or a note for a claim reads the claim again first and does nothing when
  it is gone. A completion confirmed there is stamped at the time the
  claim was spoken. A candidate's action goes through `ReminderAlarms` and takes
  the claim once its write lands, a reminder moved on without its history
  record included; "Was a note" and a new reminder made from the claim
  take it once the note or the reminder is stored; "Already complete" and
  "Disregard" only take it. A claim whose write landed but which could not
  be taken is marked in the ViewModel and offers only "Disregard".
- Captures and typed notes are written with `NoteStore.createListedNote`,
  which puts a note in `unclassified` when its folder is no longer listed,
  the list read under the index lock. `deleteFolder` unlists the folder
  first, moves its notes under one hold of that lock, then drops its tags,
  so a capture during a delete goes to `unclassified`; when a move fails
  the folder is listed again where it was. A canceled caller does not stop
  a folder delete or creation once it has started.

## In-app links and the What's-new popup

Upstream's Settings > About linked "What's new in the app" to its hosted
changelog in an embedded WebView. That page describes upstream's
releases, not Gravel's, and its host (Notion) refuses to render
inside a WebView at all, so Gravel points the item at its own
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
notes share the Notion-in-WebView problem, and the help center is
Intercom-backed, so loading it in an embedded WebView makes the app's
own network traffic include Intercom's endpoints, which the app's
listing does not (and should not need to) disclose. The upstream
WebView routes (`RoadmapChangelogRoute`, `PebbleOsChangelogRoute`,
`TroubleshootingRoute`) stay registered in `AppNavHost` but are
unreachable, keeping the upstream cherry-pick surface small.

## App store search

Both app store feeds (Core Devices and Rebble) keep their search index on
Algolia, and upstream queries it straight from the phone with per-feed
search-only keys (`AppstoreSources.kt`), so every store search is a
request to a third party, one per enabled feed. Gravel keeps the
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
  asks on every edit (upstream's behavior, and what an upstream pick
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

## PebbleOS changelog list

Core publishes PebbleOS builds as GitHub releases
(https://github.com/coredevices/PebbleOS/releases) and announces releases
on its public changelog (https://ndocs.repebble.com/pebbleos-changelog).
The "PebbleOS changelog list" workflow copies the changelog's version
numbers once a day into `pebbleos-changelog.json` on the `firmware-list`
branch; the workflow file and `.github/changelog/pebbleos_changelog.py`
hold its rules.

- Repository rulesets restrict creating, updating and deleting tags and
  every branch other than `firmware-list` to the admin role, and block
  force pushes to and deletion of `firmware-list` for everyone but that
  role.
- GitHub runs scheduled workflows from the default branch and disables
  them in a public repository after 60 days without repository activity
  (GitHub docs, "Events that trigger workflows"). A disabled or skipped
  run raises no failure; the list's `checkedAt` shows its age.
- The update notification's tap opens the build picker only when its
  intent carries this process's `FirmwareNotificationToken` and names a
  connected Core watch (`firmwarePickerRouteForNotification`, called from
  `MainActivity.handleIntent`). Any app can send the activity the
  `pebble://show-watches` link the notification also carries, so that
  link opens only the Devices tab.

## F-Droid

Gravel is in F-Droid's main repository, as `com.anopticlabs.gravel`.
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
  the tracked tree, the whisper.cpp and llama.cpp submodules included,
  minus the paths the recipe removes before scanning. Its `recipeRemovedPaths` list is the
  tree's copy of the recipe's `rm:` field. F-Droid's inclusion policy is
  about the tree and about dependency provenance, not about native code as
  such: free-software Maven artifacts from allowed repositories may carry
  compiled native libraries, and the APK does still ship such libraries
  (the Speex codec from `io.github.coredevices.speex`, Apache-2.0 with
  public source; SQLite from `androidx.sqlite:sqlite-bundled`; androidx
  graphics-path) next to the two engines built from source. What
  Gravel removed was prebuilt native code with no public source (the Cactus
  engine, the Ring satellite AAR), which the policy does reject.
- The native toolchain is pinned in `whisper-native/build.gradle.kts`,
  `llm-native/build.gradle.kts` and `socketfilter/build.gradle.kts`
  (`ndkVersion` 28.2.13676358, that is NDK r28c, and CMake 3.22.1), so
  every build uses the same NDK and CMake and the recipe has exact values
  to provision. `FdroidGuardrailsTest` fails when the modules name different
  values.
- Native libraries built from source name neither the checkout nor the
  SDK by absolute path: each native module's `CMakeLists.txt` maps its
  source, build and NDK directories to relative names
  (`-ffile-prefix-map`). `VerifyNativeLibPaths` in
  `androidApp/build.gradle.kts` fails a build whose merged native
  libraries, read before stripping, name the checkout as a whole path,
  whose libraries built here name the SDK or a home directory, or that
  hold a library listed neither as built here nor as a dependency's.
  `NativeLibPathsSentinelTest` checks the mappings in the CMake builds
  the Gradle files declare, and the check's registration and its list
  of libraries built here.
- `versionName` is `git describe --tags --first-parent HEAD` and
  `versionCode` the commit count, both functions of the built commit
  (`androidApp/build.gradle.kts`), so a tag checkout reports exactly the
  tag name and the values the recipe declares for that tag can be checked
  against the built APK. The first-parent walk keeps upstream's tags,
  reachable through every sync merge's second parent, from describing a
  Gravel commit (git describe stops after ten candidate tags, so a non-tag
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
  the replaced native libraries are absent from every APK and the debug
  variant's dictation test clip from the release one. CI builds the
  release variant on a keystore-less checkout and compares the built
  `versionName` with `git describe` of the built commit.

What the recipe has to supply (field names as in the fdroiddata format):

- `submodules: yes`: the engines are compiled from the whisper.cpp and
  llama.cpp submodules.
- `rm:` exactly the directories `FdroidGuardrailsTest.recipeRemovedPaths`
  lists under `whisper-native/src/main/cpp/whisper.cpp/` (bindings,
  examples, models, samples, tests) and under
  `llm-native/src/main/cpp/llama.cpp/` (examples). They hold language
  bindings and an example Android app with their own gradle builds and
  toolchain pins, a lockfile-less `package.json`, example apps naming a
  maven host off the allowlist, binary test-fixture models, and sample
  audio, none of it part of Gravel's build; the guardrail test requires
  each entry to remove a scanner hit. `rm:`, not
  `scandelete:`: the scanner counts a `scandelete` entry that removed
  nothing as an error, while `rm:` tolerates a path that is already gone.
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
- Anti-features: the on-device dictation models and the Notebook's text
  models are runtime downloads from Hugging Face (`ModelCatalog`), which
  is expected to draw the NonFreeNet anti-feature flag; that documents
  but does not block inclusion. The Notebook watch app is a runtime
  download from its GitHub releases (`SHIPPED_WATCH_APP_RELEASES`), as
  PebbleOS firmware is.

## Branding assets

applicationId is `com.anopticlabs.gravel`; the Kotlin `namespace` and
source packages stay `coredevices.coreapp` (see the branding rule in
`CLAUDE.md`). The launcher icon, in-app wordmark, and fastlane graphics
come from `art/gravel-logo.png`; the brand color is `gravelPurple`
(0xFF9129DE in `util` `theme/Theme.kt`), while the adaptive-icon
background (`util .../res/values/ic_launcher_background.xml`) is a
neutral dark so the purple mark stays visible against it.
