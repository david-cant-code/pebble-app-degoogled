# Known issues

Deliberately deferred issues, each with the rationale and threat-model
context, per the project rule that nothing is deferred silently. An entry
leaves this file when the fix lands.

## GitHub firmware update path not hardware-tested on single-slot watches

**Status: deferred until single-slot Core hardware is available.**

Core watch firmware updates are checked against the public PebbleOS GitHub
releases and verified before install (API-declared SHA-256 digest and
size, manifest hardware/type/version cross-checks, inner CRCs), then
handed to upstream's existing sideload flow. Unit tests cover both
firmware bundle layouts, including the single-slot shape, and the
dual-slot path gets a hardware end-to-end pass on a Core Time 2 as part
of this branch's verification. No single-slot (asterix-class) watch is
available, so that hardware pass is deferred. Risk is bounded: the
phone-side code path is identical for both layouts up to the sideload
handoff, past which transfer and install are upstream's unchanged flow,
and the watch's own bootloader validation with recovery fallback
backstops a bad install. This entry leaves the file when a single-slot
watch runs the end-to-end pass.

## Classic PebbleKit broadcasts cannot be restricted to authorized callers

**Status: accepted while the classic toggle is on; the surface is off by
default.**

Classic PebbleKit is governed by a toggle (Settings > Apps > Watch App
Permissions) that ships off, for upgrading installs too. While it is off
no classic session is created, the START and STOP receivers are not
registered, and the basalt provider is disabled; a session running when
the toggle goes off relays nothing from then on, though its SEND, ACK and
NACK receivers stay registered until its restart (`DESIGN_NOTES.md`,
"PebbleKit exposure toggles"). The rest of this entry describes the
surface while the toggle is on, for every non-system watchapp that
declares no PebbleKit 2 companion, JS-only watchfaces included.

Classic PebbleKit's cross-app surface is broadcasts, and a
`BroadcastReceiver` is given no caller identity at all: `onReceive` sees the
intent and nothing about who sent it, and no platform API recovers it after
the fact. There is nothing to check. The obvious alternative, requiring a
permission on the receivers, is not available either: classic PebbleKit has
never declared one, so every existing third-party watchapp companion would
break, and a custom permission at `normal` protection level is granted to
anything that asks for it, which would look protective while stopping
nobody.

That leaves the following classic entry points open to any installed
application, deliberately:

- `com.getpebble.action.app.START` and `.STOP` launch or close a watchapp
  on the connected watch. Nuisance only: they take a watchapp UUID and
  return nothing to the sender.
- `com.getpebble.action.app.SEND` injects an app message into a live
  classic session as if it came from the watchapp's real companion. The
  session relays a SEND only when it addresses the session's own watchapp,
  but that filter is routing, not authorization: it exists to stop one
  watchapp's session relaying another watchapp's messages (and two
  concurrent sessions each transmitting the same message), and watchapp
  UUIDs are public, so any installed app that names the running watchapp
  passes it. A mis-addressed SEND is dropped without a NACK broadcast, so
  a companion that sent one waits out its own ACK timeout.
- `com.getpebble.action.app.ACK` and `.NACK` forge acknowledgements for
  outbound messages; transaction ids are a single byte, so a hostile app
  can confuse a companion's in-flight sends by guessing.

Watch data flowing out of a classic session is broadcast untargeted in
practice. `broadcastToCompanions` narrows delivery with `setPackage` when
the watchapp declares Android companion packages, but a watchapp that
declares one is routed to PebbleKit 2 instead of a classic session, so a
classic session never has a declared companion to narrow to; the targeted
branch is kept as future-proofing should that routing change. Any installed
app can therefore read what a classic watchapp sends out, and combined with
SEND injection can prompt a running classic watchapp and read its reply.

The PebbleKit 2 surface does not share this hole: it travels over a bound
service and a ContentProvider, where the caller is authoritative and is
checked against the companions installed watchapps declare. Its change
notifications reach every app but name no watch; see "PebbleKit 2 change
notifications reach any observer".

This entry leaves the file if a future Android release attaches sender
identity to broadcasts, or if the classic surface is retired outright.

## Classic PebbleKit content provider stays exported without a caller gate

**Status: accepted for compatibility while the classic toggle is on; the
component is disabled while it is off.**

`content://com.getpebble.android.provider.basalt` serves whether a watch is
connected, whether it supports AppMessage, and the running firmware version
to any installed application, with no permission and no caller check. Unlike
the classic broadcasts above, a ContentProvider does receive the caller's
identity, so the companion-registry gate that protects the PebbleKit 2
provider is technically possible here. It is deliberately not applied:
classic-era companions predate companion declarations, so the registry would
have nothing to authorize most of them against, and classic clients poll
this provider before any watchapp relationship exists, typically to show
connection state up front. Gating it would break every classic companion
while protecting little: the provider serves no identifier of any kind, and
connection state already leaks through the untargeted classic broadcasts
described above. With the classic toggle off (the default) the provider is
disabled through `PackageManager` and its `query` returns null besides, so
the acceptance covers only installs where the user turned classic on. This
entry leaves the file if the provider ever grows a column beyond
connection state and firmware version, at which point it gets the registry
gate regardless of the compatibility cost.

## PebbleKit 2 companions receive the watch's real serial

**Status: open; upstream-inherited, and only companions that installed
watchapps name receive it.**

The `.pebblekit` provider's rows and the sender service's results give each
calling package its own pseudonymous watch identifier, and the name column
serves the advertised model name with its device-unique suffix stripped. The
session's calls into a companion's listener service carry the real serial
instead: when a watchapp opens, when it closes, and with each message from
the watch, `PebbleKit2` passes `device.watchInfo.serial`, and pebblekit2
1.1.0's `DefaultPebbleListenerConnector` takes a single watch identifier per
call, not one per package. Two installed companions whose watchapps have opened
while PebbleKit 2 was on therefore hold the same serial and can link the watch;
the per-caller identifier keeps the serial only from a companion that
receives no callback, which still shares the model-level columns (platform
codename, board revision, firmware version) with every caller. The callbacks
go only to the packages the running watchapp's appinfo names. Closing this
means one listener connection per companion package, each passed that
package's identifier; the sender already resolves both the identifier and
the real serial, so companions that stored either keep working.

## A toggle flipped off and on across a session's start leaves it inert

**Status: accepted; the session fails closed, and the second flip has about
one dispatch to land in.**

Each session class reads its toggle at `start()` and registers nothing
while it is off, and the mid-session watcher restarts a session when the
platform-session decision stops matching the one the session was built
from. If the toggle goes off after the build snapshot and before `start()`
reads it, then back on before the watcher's first collection, `start()`
has refused and the watcher's first value matches the snapshot, so nothing
restarts. The session stays inert until it is rebuilt, for example at the
next app switch, another flip of the toggle, or a reconnection of the
watch; while inert it registers no receivers and binds to nothing. Nothing
in `handleNewRunningApp` suspends between the start loop and the watcher
launch but the launch's own dispatch, which keeps the window that short as
long as no suspending call is added there. Restarting a session on any
config write during its start, or having sessions report their `start()`
decision, are possible changes; neither is made for a fail-closed state
with a window this short.

## The classic receivers deserialize what the sender puts in the extras

**Status: accepted; the protocol's own UUID is a Java Serializable.**

Reading an extra of a classic PebbleKit broadcast runs Java
deserialization of bytes the sending app wrote, with the app's class
loader. On Android 8 to 12L the first read of any extra deserializes
every entry, so all five exported receivers (START, STOP, SEND, ACK and
NACK) do it; from Android 13 START, STOP and SEND do it when they read
the watchapp UUID. The platform code involved is named at
`handleSenderInput`, which drops a broadcast whose handling throws,
`OutOfMemoryError` and `StackOverflowError` included. It catches throws
only:

- A crafted object graph whose deserialization never finishes and never
  throws blocks the collector that read it, and those collectors run on the
  shared `Dispatchers.Default` pool. A crafted START holds one of its
  workers and leaves classic app start dead until the process restarts, a
  crafted STOP does the same for app stop, each classic session's SEND
  holds another, and on Android 8 to 12L that session's ACK and NACK do
  too. Cancelling a session does not interrupt a read that is not
  suspending, so a held worker stays held while sessions are rebuilt around
  it, and libpebble's other work on that dispatcher waits behind whatever
  is still held.
- A stream that allocates an array just under the heap limit can make an
  allocation on another thread fail with `OutOfMemoryError`, outside any
  catch.
- `readObject` hooks of any loadable class run, and the typed
  `getSerializableExtra(name, Class)` does not bound what is deserialized.

SEND, ACK and NACK check the classic toggle before reading an extra, and
START and STOP are registered only while it is on.

## The PebbleKit 2 sender reads a caller's Bundle before it refuses anything

**Status: open; the request arrives as a Bundle, and the platform reads it
before the app sees a key.**

The sender service is exported with no permission, and the binder it hands
out (`PebbleSenderReceiver`) reads `ACTION` and `WATCHAPP_UUID` from the
caller's Bundle on the binder thread. That read unparcels the Bundle, and
what a parcel asks the platform to allocate is the caller's to choose, so a
crafted request can exhaust this process's memory. The `OutOfMemoryError`
that follows is an `Error`, which `Binder.execTransactInternal` does not
catch (android16-release `Binder.java` catches `RemoteException` and
`RuntimeException`), so the process is killed through the JNI error path
(`android_util_Binder.cpp`, `report_java_lang_error`). A read that never
finishes holds the binder thread instead.

Any installed app can do this while PebbleKit 2 is on, without a
permission, a companion relationship or a connected watch. While the toggle
is off the binder refuses before it reads the request, including on a binder
a caller held across the flip. The request never reaches the app's own
handling, so what it costs is the process, which restarts. Bounding it means
not unparcelling an untrusted Bundle on the binder thread, which is where
the request's own keys are read from: the read would have to move to a
thread where an `Error` can be caught, which is the handling the entry below
needs too.

## A PebbleKit 2 request's dictionary is read with no bound on what it allocates

**Status: open; the read is the library's, and the app-side fix is an
exception handler on the scope it runs in.**

While PebbleKit 2 is on, any installed app can bind the exported sender
service and send a request whose `DATA_DICTIONARY` bundle holds a value the
platform reads eagerly, at a size the caller chose. The library reads that
bundle in a coroutine on the
scope this app supplies (pebblekit2 1.1.0
`UniversalRequestResponseSuspending`), where the allocation raises
`OutOfMemoryError`. That coroutine catches `CancellationException` and
`Exception`, and the scope carries no `CoroutineExceptionHandler`, so the
error reaches the thread's default handler, which this app chains to the
platform's: the process dies, and the watch connection with it. The
platform's 1 MB allocation guard does not cover the read, because it applies
while a binder transaction is being handled and this read happens on a
worker thread (android16-release `Parcel.ensureWithinMemoryLimit`).

Nothing gates the request beforehand: the service is exported with no
permission, the fork's own decision refuses START and STOP without a
companion relationship but passes the rest, and the companion check for a
send sits in `sendDataToPebble`, which the library calls after it has read
the dictionary. No watch need be connected.

The toggle closes it, since the binder refuses a request before the library
sees it. Closing it while PebbleKit 2 is on means giving that scope a
`CoroutineExceptionHandler`, which changes how every failure on the
libpebble scope is handled, so it is recorded here instead.

## A PebbleKit 2 watchapp gets no NACK while PebbleKit 2 is off

**Status: accepted; a responder for a surface meant to have no session is a
design choice deferred.**

With PebbleKit 2 on, a watchapp that names an Android companion and has no
PebbleKit JS gets a PebbleKit 2 session, which NACKs an AppMessage its
companion cannot receive, so the watchapp fails fast. With the toggle off
no session exists and the watchapp waits for its AppMessage timeout
instead, as a classic watchapp without a replying companion always has.
The state follows from the user's own toggle, and the watchapp cannot
reach its companion either way. Restoring the NACK would mean a NACK-only
responder for a surface the toggle says does not exist; that is recorded
here rather than added.

## PebbleKit 2 change notifications reach any observer

**Status: accepted; a notification names the collection and nothing else.**

Any app can register a `ContentObserver` on the `.pebblekit` authority,
which is exported with no permission as the PebbleKit 2 protocol requires,
and learn when the connected-watch list or some watch's running app
changes. Gravel's provider announces those two collection URIs rather than
the library's per-watch URI, which would carry the watch's real serial to
every observer (the library and platform behaviour is cited at
`PebbleKit2Change`). The timing signal is accepted: suppressing it would
break companions that observe the provider to refresh, and the same timing
reaches apps through the classic basalt notifier and broadcasts when that
surface is on. Nothing is announced while PebbleKit 2 is off.

## No backups at all on Android 8.0 and 8.1

**Status: accepted; these API levels cannot encrypt backups client-side.**

The backup policy is that no copy of app data leaves the device unless it
can be client-side encrypted (`backup_rules.xml` on API 31 and above,
`requireFlags` in `res/xml-v28/full_backup_content.xml` on API 28 to 30).
Android 8.x has no client-side backup encryption, and its rule parser has no
`requireFlags` to express the condition (it rejects the attribute outright),
so the only policy-compliant behaviour there is no backup at all:
`res/xml/full_backup_content.xml` deliberately allowlists a single path that
never exists, which disables Auto Backup, the O-era device-to-device
transfer path, and `adb backup` alike on those devices. `BackupRulesTest`
pins the shape of all three rule files. This entry leaves the file when
minSdk reaches 28.

## Watchapp WebSocket deny is best-effort on WebViews without proxy override

**Status: accepted; degrades safely and is rare in practice.**

Leaving aside the `Connection-Allowlist` header, which has its own entry
below, three of the layers the watchapp network gate puts under a denied app
(see `DESIGN_NOTES.md`) act on web requests. Two of them, the `shouldInterceptRequest`
403 and the `startup.js` API stubs, always apply, but only the third, the
`ProxyController` black-hole, deterministically covers WebSocket, because
`ws`/`wss` handshakes never reach `shouldInterceptRequest` (a documented
WebView limitation) and the JS stub is same-realm best-effort a hostile
bundle could try to bypass. `ProxyController` needs the `PROXY_OVERRIDE`
WebView feature, which is present on the updatable WebView shipped by every
current Android version but can be absent on very old or stripped WebView
builds. Where it is absent, a network-denied app's http/https egress is
still deterministically blocked (layer 1) and its JS network APIs are
stubbed (layer 2), but a hostile bundle that recovers a fresh `WebSocket`
constructor could open a WebSocket. The exposure is narrow: it needs a
`PROXY_OVERRIDE`-less WebView and a deliberately hostile watchapp. It is
not limited to WebSocket; WebRTC over TCP is reachable the same way, since
the UDP filter covers only UDP (see the WebRTC header entry below).
`WebViewJsRunner.applyNetworkProxy` logs a
warning when the feature is unavailable. This entry leaves the file if
minSdk/WebView baseline guarantees `PROXY_OVERRIDE`, or if a WebView-level
WebSocket intercept becomes available.

## No UDP for web content inside Gravel

**Status: deliberate.**

Gravel installs a filter at process start that refuses the creation of UDP
sockets in its own process, for as long as it runs. On devices where the
filter installs, web content inside Gravel (PebbleKit JS with internet
access on, configuration pages, other in-app web pages) therefore has no
UDP, which WebRTC over UDP, WebTransport and HTTP/3 need. This holds
because Android System WebView runs its network service in the app process,
so the WebView's own UDP sockets are created where the filter acts (Chromium
M153, `refs/branch-heads/8010`, `aw_main_delegate.cc`,
`AwMainDelegate::BasicStartupComplete` calling
`content::ForceInProcessNetworkService`); a WebView that moved its network
service out of the app process would need this re-checked at the sync that
brought it in. Building a release APK fails if its dex names
one of the types that `VerifyApkContents` lists, three Java UDP socket
types and `DatagramPacket`, which opens no socket; an app bundle build does
not run that check.

## The UDP filter starts with the app process, not before it

**Status: accepted.**

The filter is installed in `MainApplication.attachBaseContext`, ahead of
the app's content providers, library initializers and `onCreate` (platform
source cited there). A socket
opened earlier than that by platform code would be outside the filter; none
is known. The speech engine's isolated process gets no filter. A process
that Android starts for a full backup or restore uses the base
`Application` class (android16-release,
`ActivityThread.handleBindApplication`, `LoadedApk.makeApplicationInner`),
so neither the filter nor the rest of Gravel's startup runs in it.

## If Android refuses the UDP filter

**Status: accepted; no affected device is known.**

If the platform does not let Gravel install the filter, Gravel logs the
result at startup and does not run a watchapp's phone-side script while
that watchapp's internet access is off. The watchapp's permission controls
say so.

## The UDP filter is untested on Android versions before 17

**Status: accepted.**

The filter has been run on hardware on Android 17. On older versions, down
to Android 8, it has not been tested. If the platform there refuses the
install, Gravel behaves as described under "If Android refuses the UDP
filter". On a device that is not ARM, such as an x86 device that runs ARM
code under translation, Gravel does not install the filter and behaves the
same way.

## A watchapp whose WebView renderer exits stays stopped until relaunched

**Status: accepted.**

Gravel keeps running when a PebbleKit JS session's WebView renderer exits,
and does not restart that session on its own; opening the app on the watch
again starts a new one. With Network on, `localStorage` values the script
set by property assignment rather than by `setItem` are lost in that case;
with Network off, every change is written through as it happens, so none
are.

## Changing the Network permission restarts the watchapp's phone-side script

**Status: deliberate.**

A change to a watchapp's Network permission, in either direction, stops its
PebbleKit JS session and starts a new one. Whether a connection opened
while Network was on is cut in the moment before that restart completes has
not been measured.

## The WebRTC response-header layer needs WebView 152 or newer

**Status: accepted; improves as WebView updates.**

With Network off, Gravel serves the watchapp's page with a
`Connection-Allowlist` header that allows no connections and switches
WebRTC off for it. Android System WebView honors the header from version
152, and Gravel cannot read back whether it is honored. Below version 152
the header does nothing: the UDP filter still stops WebRTC over UDP, WebRTC
over TCP is outside the filter and is stopped by the black-hole proxy where
the WebView supports proxy override (Chromium M153, `refs/branch-heads/8010`,
`services/network/p2p/socket_tcp.cc`, `P2PSocketTcpBase::Init`, which opens
the socket through the proxy-resolving socket factory), and on a WebView
without proxy override (see the WebSocket entry above) WebRTC over TCP has
no deterministic cover.
The header is never relied on alone.

## With Network off, PebbleKit JS runs without cookies, IndexedDB or the Cache API

**Status: deliberate.**

With Network off, a watchapp's script runs in a sandboxed frame that has
none of the browser's origin-bound storage. `localStorage` is provided by
Gravel there and persists as before. If the script navigates or reloads
its own frame, Gravel stops that session; opening the app on the watch
again starts a new one.

## Name lookups are not covered by the watchapp Network permission

**Status: open.**

The Network permission's layers act on connections, not on name lookups,
which go through the system resolver. A lookup is itself outbound data: the
queried name reaches the domain's nameserver, so an uncovered lookup is a
low-bandwidth channel out. Whether a watchapp with Network off can cause a
lookup has not been measured; measuring it comes first, then a decision.

## Cleartext HTTP is blocked app-wide, breaking http-only watchapps

**Status: deliberate.**

Watchapps whose developer config pages or PebbleKit JS requests use plain
`http://` fail even when the app's Network permission is granted: the
config page shows a load error and JS requests fail with
`ERR_CLEARTEXT_NOT_PERMITTED`. `https://` is unaffected. The same block
covers user-added appstore sources: the add-source dialog and the
`pebble://add-store-feed` deep link accept an `http://` feed URL, the
source is saved, and every fetch from it then fails with nothing shown
in the source list beyond the empty entry.

The block began as an upstream accident, and is now an active fork
divergence held on purpose. Upstream's manifest declares
`android:usesCleartextTraffic="true"`, but Android ignores that attribute
whenever an `android:networkSecurityConfig` is declared, which upstream
added to trust user-installed CAs (upstream commit `7549c661`); with
targetSdk 28+ the config's base default is cleartext off, so the config
silently blocked cleartext for upstream too. Upstream then restored
cleartext by setting `cleartextTrafficPermitted="true"` in that config
(upstream commit `44a15ce5`, taken up in the 2026-08 sync). The fork sets
`cleartextTrafficPermitted="false"` on the config's base-config instead
and drops the manifest's `usesCleartextTraffic` attribute, so the built
manifest states what is true rather than relying on the precedence rule;
both are re-asserted against upstream at every merge and pinned by
`NetworkSecurityConfigTest` (source) and the CI check on the built
manifest (artifact). The config also names `localhost` in a
cleartext-denying domain-config, because Android 17 otherwise permits
cleartext to loopback hosts (platform source in that file's comment);
`CleartextPolicyTest` asks the platform on a device, and is not part of
CI. The fork keeps the block because a config
page is remote code executed in a WebView on the phone: fetched over
cleartext, it hands any network-position attacker script injection into
that WebView, plus whatever app state rides in the config URL. Legacy
http-only watchapps break, and that is the accepted cost.

No opt-in is planned. This entry leaves the file if the ecosystem's
http-only apps age out.

## Language pack downloads are not digest-pinned

**Status: deferred; upstream-inherited, integrity rests on transport security.**

Watch language packs come from a catalog compiled into
`LanguagePackRepository`: 137 entries, 125 of them on binaries.rebble.io
and 12 on four individual contributors' GitHub repositories; of those 12,
two reference a mutable branch, eight are release assets addressed by a
tag name (replaceable by the repository owner without a tag change), and
two are pinned to a commit. A pack is downloaded and sent to the watch
with no digest or size check. Firmware bundles and speech models in this
fork are integrity-pinned; language packs are the one remaining install
path where a substituted or compromised host, a re-uploaded release asset,
or a force-pushed branch delivers whatever bytes it likes to the watch,
bounded only by TLS to the host. The fix is per-file digests in the
catalog, checked before install, in the style of `WhisperModelCatalog`;
that needs the catalog re-derived with hashes and a decision on the two
branch-referenced entries (pin to a commit or drop; release assets have no
commit-addressed form, so the digest is the whole fix there), so it is
deferred as its own change. This entry leaves the file when the catalog
carries verified digests.

## Auto-resume of interrupted firmware updates is inert in this fork

**Status: accepted; the fork's own update path never arms it.**

Upstream (2026-08 sync) records an in-progress firmware update and
resumes it on reconnection, controlled by a user-facing "Auto-Resume
Firmware Updates" toggle. The record that arms the resume is written only
inside upstream's own `updateFirmware()` entry point, which this fork
does not call: fork firmware updates run through the verified
GitHub-release flow and enter libpebble3 as a sideload, so no interrupted
update is ever recorded and the resume machinery never triggers. The
toggle still renders and saves its preference, advertising behavior the
fork does not deliver. Hiding it means diverging in upstream settings UI
for cosmetic gain, so the mismatch is recorded here instead. This entry
leaves the file if the fork adopts the resume machinery for its verified
flow (plausible follow-up: writing the interrupted-update record at the
sideload boundary) or upstream's toggle becomes conditional on the
feature being armable.

## The "Use Core OTA service" debug toggle is inert in fork builds

**Status: accepted; this documented awareness is the resolution.**

Upstream's watch-settings debug section (2026-08 sync) gained a "Use Core
OTA service" toggle that routes firmware checks to a Core Devices OTA
service. The routing requires a bug-endpoint build config value that this
fork never sets, a fact the fork's own routing test asserts, so the
toggle changes nothing on any fork build while its description names a
firmware source the fork does not use. The toggle stays as upstream
ships it, except that the setting behind it keeps the fork's off
default where upstream now defaults it on, so an inert control is not
shown enabled; hiding it behind the same predicate that makes it functional
remains open as a follow-up, and renaming it would keep an inert control
under a different label. This entry leaves the file if the toggle is
hidden behind that predicate or the fork ever sets the endpoint that
makes it functional.

## Hardware BLE scan filter can hide nonstandard watch advertisements

**Status: accepted; upstream product decision, escape hatch exists.**

Upstream (2026-08 sync) attaches an OS-level scan filter on the Pebble
pairing service UUID (0xFED9) to every watch scan, on by default. A watch
or clone that advertises Pebble manufacturer data without listing that
service UUID in its advertisement is no longer surfaced by the hardware
scan, where the previous software-only filtering would have found it. The
only escape hatch is the scan-filter toggle in the debug options. No
affected device is currently known; if pairing reports surface for older
or third-party hardware, the toggle default is the first thing to
revisit. This entry leaves the file if the default changes or the filter
gains a fallback pass.

## Companion Device Manager association never completes for a BLE watch

**Status: deferred; upstream-inherited, root cause not yet found.**

Tapping Connect on a BLE watch first asks Android's Companion Device
Manager to associate it (a scan filter on the watch's address, watch
device profile, single device) and waits up to 30 seconds before
connecting. The system picker ("Looking for a watch") never lists the
watch, the wait expires, the app logs `CompanionDeviceManager
succeeded=false` and connects anyway, and the picker stays on screen
until dismissed; every later Connect asks again, because no association
was recorded. Observed on a Pebble Time 2 with this fork and with the
upstream app before the fork; the association code is upstream's,
untouched here. The picker scans for an advertisement from the address
the app itself scanned, so why it never matches is the open question.

Without the association the app does not hold Android's companion role
for the watch: the role's notification-access prompt never appears, so
notification access is granted from the system settings page the
permission warnings link to instead, and the app has none of the
background-start allowances the role carries. Connecting, syncing,
notifications, and everything else work once the watch is connected.
The Connectivity settings carry upstream's "Disable Companion Device
Manager" toggle, which skips the request entirely. This entry leaves the
file when the association completes on a BLE watch, or the request is
dropped or reworked.

## Notification mute carry-over can mismatch duplicate channel names

**Status: accepted; upstream-inherited, narrow trigger, worth upstreaming.**

Upstream's channel-ID-change handling (2026-08 sync) carries a channel's
mute state over to its replacement by matching group name plus channel
name when the ID changed. Android does not require channel names to be
unique within a group, and the match takes the first same-named channel,
so an app that recreates channels with duplicate names inside one group
can have a mute state land on the wrong channel. The trigger is narrow
(an app must both rotate channel IDs and hold duplicate names in one
group) and the damage is a misplaced mute, fixable in the notification
settings UI. Inherited unmodified from upstream and a candidate to fix
there rather than diverge here. This entry leaves the file when upstream
disambiguates the match (channel id first, then position-stable matching)
and the fork syncs it.

## gradle-wrapper.jar lags the declared Gradle version

**Status: accepted; cosmetic, self-correcting at the next upstream bump.**

Upstream's wrapper bump to Gradle 9.6.1 updated `distributionUrl` but
committed the wrapper jar regenerated by the still-running 8.14.4
distribution (the classic single-run wrapper update). The jar is only the
launcher that downloads the declared distribution, so builds correctly
run 9.6.1; the stale jar costs nothing at runtime. Regenerating locally
would diverge a binary file from upstream for zero functional gain, so
this waits for upstream's next wrapper update (or any local wrapper task
run that lands with other build changes). This entry leaves the file when
the committed jar matches the declared distribution.

## Large whisper models are absent from the catalog

**Status: accepted; blocked on the pinned upstream engine revision.**

The catalog originally carried ggml-large-v3-turbo-q5_0. Two findings
removed it. First, transcribing with it on the test platform (a Pixel
running GrapheneOS) crashed the app with a native out-of-bounds read
inside ggml's quantized matrix-multiply path at the pinned whisper.cpp
revision; the hardened system allocator surfaces the stray read as a
fault, and a model whose inference can fault the process cannot ship
for any consumer. Second, whisper.cpp applies the audio-context trim
only to the main transcription pass, while language auto-detection
(reached whenever a multilingual model runs with no spoken language
pinned) encodes at the full 1500-frame context; for a 32-encoder-layer
model on phone-class CPUs that single pass alone exceeds the watch
dictation window, so the model could never serve dictation even without
the crash. Both point at the engine revision rather than the catalog
design. This entry leaves the file when a large tier is reinstated
after an upstream fix or a re-pin that passes the same on-device checks
the smaller models passed.

## Watch dictation accuracy varies sharply between sessions

**Status: open; the variance is in the watch's microphone capture.**

On-device testing found dictation accuracy varying between
back-to-back sessions under identical conditions: the same sentence
dictated twice minutes apart produced a word-perfect transcript and
then a transcript with most words wrong or missing, on both catalog
model tiers. Replaying a captured session's audio through the engine
reproduces that session's result deterministically, and clean
microphone captures transcribe correctly, so the variance enters
between the watch microphone and the phone-side decode of the
speex-encoded BLE audio stream, not in the engine. A frame-level scan
of degraded captures found no lost-frame or codec-state signature, so
the root cause is unconfirmed. The decode path stays within the
dictation deadline on degraded audio (see the decode-parameter notes
in `DESIGN_NOTES.md`), so the user-visible cost is wrong words rather
than hangs or session failures.

A later on-device pass, on watch firmware v4.35.0 with both catalog
tiers, differed across the three watchapps tried: one app's sessions
returned single wrong words for a run and then three or four correct
words, another's returned most of the phrase with the last or a middle
word dropped, and a third's one session returned a different pattern
again, all from two to six seconds of audio arriving at the expected
16 kHz byte rate, with the phone-side decode and transcription path
unchanged since the earlier pass. Three apps is too few to generalize
from, so the requesting watchapp joins the firmware revision as a
variable a capture comparison should hold fixed.

A capture pass with the phone-side path held fixed placed the variance
ahead of the phone. In one afternoon, on one watch and one build, the
share of captures the engine hears as noise before the app touches them
swung between rounds minutes apart, from one in five to eleven in
fifteen; the failing captures are as loud as the good ones or louder,
but carry little energy in the speech band and no voiced pitch, the
low-frequency signature the earlier pass found, and a standalone decode
labels them as scraping, footsteps or crickets while the good captures
from the same minutes transcribe correctly. Nothing on the phone can
restore speech the capture never carried. The candidates are on the
watch: the microphone port covered while the watch is held to the
mouth, debris or moisture in the port, and the firmware's microphone
or gain state.

## A silent dictation can transcribe as a stray word

**Status: open; engine behavior.**

The engine decodes ten seconds of digital silence to " The" on the
base English model, and a near-silent watch recording can come back as
a single short word instead of the "Missed that" the watch shows for
an empty result. Nothing gates on level before the engine, so a silent
recording reaches it whole. A gate on the audio itself (an absolute
level floor measured after the microphone's start-up transient, or the
engine's own per-segment no-speech probability) needs captures from
more than one watch before its threshold can be set.

## A provider that fails before the recording ends is answered only after it

**Status: open; reachable only through a codec frame the decoder rejects,
which the firmware has not been observed to send.**

The dictation session coordinator waits for the watch to end the
recording before it looks at the provider's result, because the
watch's result clock starts at that moment. A provider that returns or
throws before it has read the recording is therefore answered only
when the recording ends, up to the firmware's recording cap later,
while the user keeps speaking into a session that has already failed.
The local provider decodes each codec frame as the watch sends it and
throws on a frame the decoder rejects, so a corrupt frame would reach
this case; no dictation has produced one, and the only encoder the
firmware sends is the one every provider accepts. If a rejected frame,
a second encoder or a provider that fails before reading ever shows up,
the coordinator needs to race the provider against the end of the
recording and stop the watch's transfer on an early failure, with a
coordinator test for it.

## Watch-side dictation endpointing misfires in both directions

**Status: open; firmware behavior, app-side mitigation only.**

The watch's end-of-speech detection sometimes streams the entire 15
second firmware window although speech ended seconds earlier, and
sometimes ends the session after one to two seconds while the user is
still speaking. Both directions were observed repeatedly during
on-device dictation testing; a fully silent session reliably streams
the whole window. The endpointer runs in the watch firmware, so the
app can only shape what it does with the audio it receives: a
full-window session costs a full-window decode, and a truncated session
transcribes as the fragment that was actually captured. Recorded here so
short or slow dictation results are attributed correctly during testing.

## A stale model directory resets local dictation for one launch

**Status: accepted; no catalog change that reaches it is planned.**

The model directory is swept at every launch, and any directory the
sweep cannot match to an installed catalog model is treated as a
leftover of the previous speech engine: the directory is deleted, the
selected model is cleared, and a local mode is stashed and replaced by
cloud-only until the next launch, when the sweep finds the stash and a
model it knows, restores the mode, and selects the first installed
model the directory listing returns, which may not be the one selected
before. With a self-hosted server configured, dictation for that launch
goes to it; without one, dictation fails until the next launch. Three
states reach it: a downgrade to 0.1.6 or earlier from an install
holding a tiny model, whose catalog lacks the name (the tiny model has
to be downloaded again); a later version that drops or re-pins a
catalog entry while the selected model stays valid; and an install from
an unreleased build holding a directory this catalog no longer names. A
model file that fails its load-time hash check is not one of them: the
installer deletes its directory as it quarantines the file. Installs
holding only catalog models in their pinned shape are unaffected.
Closing this means resetting the mode only when the selected model
itself is gone and keeping the selected model on restore, each with a
migration test; deferred because no such catalog change is planned.

## A superseded decode can hold the engine into the next session

**Status: open; bounded by the engine's unwind bound.**

The dictation session coordinator cancels the session in flight when
the watch opens the next one, so a decode that missed the deadline does
not hold the engine against the retry. The engine checks the abort only
between its encoder and decoder passes, and one pass of the larger
models takes seconds on a slow phone, so a decode deep in a pass can
still hold the engine when the retry's recording ends. The retry's own
decode then fails at once with a recognizer error, the same "Error
occurred. Try again." the superseded session produced, and the watch's
next retry runs clean. The hold lasts at most the engine's unwind bound
(10 seconds) and needs a pass longer than the next recording; a decode
still inside the engine when the bound expires is abandoned with its
process, so the dictation after it pays a cold load. A bounded
wait on the engine would mostly turn an immediate failure into a late
one inside the same deadline; closing this means the new session
joining the superseded decode after its recording ends and before its
provider call, with a service test that cancels a blocking engine call
and starts a second decode during the unwind.

## A model prompt's download can settle on another download's status

**Status: open; the prompt shows a failure it can retry.**

The speed nudge and the engine update prompt wait for their download by
taking the first status after the current one that is not Downloading,
and the status names no download. When a download of another model is
already running, scheduling the prompt's own cancels that job, whose
Idle then settles the prompt's wait before its own download has
reported anything: the prompt shows "Download failed" while the
download runs. A retry attaches to the running download without
starting it again; dismissing the prompt instead leaves the model to
finish downloading unselected. Closing this means settling on a
terminal status for the waited-on model, or on Idle seen after a
Downloading for it, with a flow test; deferred as cosmetic.

## A decode of the replaced model can re-raise the speed nudge

**Status: open; the prompt returns for the model just left.**

The speed tracker raises the nudge for the model whose decode it has
just recorded, and holds a pending one only between the dialog
starting a switch and that switch settling. A decode that began on
the previous model and ends after the switch is still recorded
against it, so the prompt returns offering the model the user has
already chosen. This is likeliest on the phones the nudge exists
for: their decodes overrun the watch's window, the watch opens a
retry about five seconds after the failure it reports, and that
retry's decode is often still running while the dialog is being
read. The second prompt changes no selection on its own, Keep
declines a model that is no longer in use, and Switch re-selects the
model already selected. Closing this means dropping a sample for a
model that is no longer selected, or holding the latch until decodes
that began before a switch have ended, with a tracker test that
records for the replaced model after the switch settles.

## No local dictation on Android 8.0

**Status: accepted.**

The speech engine runs in an isolated process and receives each
dictation's audio as a shared-memory region, an API whose floor is
Android 8.1 (`isWhisperSupported` in the whisper module states it). On
Android 8.0 the engine reports itself unsupported:
the model picker offers no local models and dictation takes the remote
path, as it does on a CPU below the engine's feature floor. Closing this
means a second audio transport for that one release, a temporary file
passed as a descriptor, with its own device run; deferred because the
engine's CPU floor already excludes nearly every phone that shipped with
Android 8.0, and none of those is held at it.

## The model is hashed, then opened by path

**Status: accepted; the isolated process is the layer behind it.**

Load-time verification hashes the installed model file once per process
and memoizes the result; the engine client then opens the path again to
hand the engine process a descriptor. Between the hash and the open,
and between one load and the next, the file could be replaced by
something that already writes inside the app's private files directory,
which is the app's own uid or root; the pin does not cover that, as the
design notes state. The engine process that parses the bytes holds no
permission and no path of its own, so a swapped file reaches a parser
in a process that can do nothing else with it. Every engine process
death drops the memo of the model that process held or was parsing and
of the configured one, so the next load of either re-hashes the file
whichever job makes it, since a death is the one event a corrupt file
could have caused; a load that takes the mutex before the death report
is acted on reads a memo set by a re-hash seconds earlier, so only a
swap inside that window escapes. Hashing through the
descriptor that is sent would tie the hash to the file the engine
reads, not to its bytes: a rewrite in place after the hash still
reaches the parser. Closing the window means hashing the bytes as they
are sent, a copy of the whole model through memory; deferred because
the writer it defends against already has the app's own access.

## Small engine calls on the dictation path carry the 15 second bound

**Status: open; bounded, and the wider limit is the decode's own.**

Every engine transaction has a deadline after which the client ends the
engine process, sized well above the slowest legitimate call of its
kind; the runtime report, the cancel and the free are procfs reads or a
flag, and get the table's shortest bound, 15 seconds. Two runtime reads
sit on the dictation path ahead of the decode, outside the dictation's
own timeout, and the cancel a timed-out decode sends sits ahead of the
unwind bound. An engine process that is alive and answers nothing
therefore holds a LocalFirst dictation for about 15 seconds before the
remote fallback starts, past the watch's window, and a session that
opens meanwhile finds the transcription in progress; the dictation
after that recovers with a cold load into a fresh process. No benign
cause of that state is known: a hung decode does not stall the other
transactions, which the engine serves on other binder threads. Bounds
sized to the calls' position, a second or two, would rescue that case,
but a bound that short risks ending a healthy engine on a starved
background cpuset and needs measuring on slow phones first; and it
would not move the wider limit, which is that an engine answering the
small calls and stalling the decode pushes the fallback past the window
through the decode's 8 second local timeout plus the 10 second unwind
bound, whatever these bounds say. Deferred until that measurement.

## The transcript is logged verbatim with sensitive content shown in logs

**Status: open; off by default, and the log site is where the fix belongs.**

With "Show sensitive content in phone logs" on in the watch settings,
the transcription service logs every transcript verbatim, whichever
provider produced it. The log writer appends an entry's text without
escaping, so a transcript with a line break in it starts a line of its
own in the log a user attaches to a report, and can be made to look
like a diagnostics line. The engine process is untrusted by design, and
its other strings have control characters replaced before they reach a
line; the transcript is the user's text on its way to the watch, so it
is not altered at the boundary. Closing this means escaping control
characters at the log site, for every entry; deferred to a pass over
what the log writer accepts, since the setting is off by default and a
transcript's content is the engine's to choose either way.

A transcript is not the only text that reaches those unescaped lines.
While PebbleKit 2 is on, any installed app can make pebblekit2 1.1.0 log
strings it chose, whatever the setting says. Its
`BasePebbleSenderReceiver$Binder` warns about an unknown `ACTION`, quoting
it and the calling package, and its `UniversalRequestResponseSuspending`
logs a failed request with the exception attached, whose message quotes a
`WATCHAPP_UUID` that does not parse (up to 36 characters, android16-release
libcore `UUID.fromString1`) or a `DATA_DICTIONARY` key that is not a number
(kotlin-stdlib 2.4.10 `numberFormatError`); the log writer appends a
throwable's stack trace as it does an entry's text. So a line in an
exported log can be another app's text rather than the app's own. Gravel's
own refusal log writes the reason and nothing from the request.

## Protocol debug logging writes AppMessage payloads to the app log

**Status: open; deferred to the same pass over the log writer as the entry
above.**

Debug logging of the Pebble protocol writes each packet's contents:
outbound in `RealPebbleProtocolHandler.send`, both the packet overload and
the `ByteArray` one, and inbound in `PebbleProtocolRunner.run`. AppMessage
payloads are written as byte values, which decode back to what they
carried, so what a watchapp exchanges with PKJS, a classic companion or a
PebbleKit 2 companion is in the log. Neither the "Show sensitive content in
phone logs" setting nor the verbose-connection-logging setting gates these
calls, and the app sets no minimum severity, so they are written whatever
the settings say.

The log stays on the phone until the user exports it (Settings > Get Help >
Export logs) or attaches it to a bug report, so the disclosure is to
whoever the user sends it to, which may be a public issue. Gating the
payload bytes means changing upstream's own logging calls rather than the
fork's seams, so it is recorded here and left to the pass over what the log
writer accepts.
