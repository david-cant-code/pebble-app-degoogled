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

## Update checks share GitHub's anonymous hourly limit

**Status: accepted; the limit is GitHub's and counts per public IP address.**

Core watch update checks and the build picker read GitHub anonymously. GitHub
allows 60 anonymous API requests per hour for each originating IP address
(GitHub docs, "Rate limits for the REST API"), so every device behind one
public address shares that budget. A check makes two or three requests, and a
failed check is not cached, so it is repeated on the next connection. Past the
limit, checks and the picker report that they are rate limited until GitHub's
limit resets. This entry leaves the file if checks move to a source without a
per-address limit.

## A link from another app can start a firmware update check

**Status: deferred; upstream behavior, to be changed on its own branch.**

Upstream's Rebble sign-in link, which any app can open, runs a firmware update
check when it is handled. Each check spends part of the shared GitHub limit
(see "Update checks share GitHub's anonymous hourly limit"). The fix is for the
link to start no check. This entry leaves the file when it no longer does.

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
  on the connected watch. They take a watchapp UUID and return nothing to
  the sender. Requests naming the Notebook watch app are dropped, but
  launching any other watchapp closes the Notebook app, and a recording it
  was making is lost.
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

**Status: accepted; the session fails closed.**

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

The switch for running Network-denied scripts without the UDP filter (see
"If Android refuses the UDP filter") has the same gap for a watchapp whose
internet access is off: `CompanionAppLifecycleManager` builds the session
from one read of the switch and `WebViewJsRunner.start` reads it again, so
a flip that the second read sees, undone before the switch watcher's first
collection, leaves the watchapp's phone-side script not running until the
session is rebuilt. That window also spans the start of the watchapp's
other phone-side parts.

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
  too. Canceling a session does not interrupt a read that is not
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
every observer (the library and platform behavior is cited at
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
so the only policy-compliant behavior there is no backup at all:
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
the UDP filter, where installed, covers only UDP (see the WebRTC header
entry below).
`WebViewJsRunner.applyNetworkProxy` logs a
warning when the feature is unavailable. This entry leaves the file if
minSdk/WebView baseline guarantees `PROXY_OVERRIDE`, or if a WebView-level
WebSocket intercept becomes available.

## The UDP filter is off on LineageOS and CalyxOS

**Status: accepted; the platform's name lookups there need to create a UDP
socket in the app's process.**

On LineageOS and systems built on it, the platform's DNS client creates an
IPv6 UDP socket before it hands a lookup to the system resolver, and when
that fails, the lookup runs inside the app over UDP instead (LineageOS
`lineage-23.0`, `android_system_netd`, `client/NetdClient.cpp`,
`dns_open_proxy`; `android16-release`, bionic `libc/dns/net/getaddrinfo.c`,
`android_getaddrinfofornetcontext`). CalyxOS's DNS client does the same
(CalyxOS `platform_system_netd`, `android17`, `client/NetdClient.cpp`,
`dns_open_proxy`). With the filter installed, name lookups in Gravel fail
there; Gravel 0.3.1, which installed it without a check, closed on those
systems when it made a web request. Gravel now installs the filter only
where a check made on a thread that carries it shows the platform's DNS
client still reaching the system resolver, which it does not where the
client makes that test socket.
Where the filter is not installed, the entries in this file that describe
it do not apply, and "If Android refuses the UDP filter" says whether a
watchapp with internet access off runs its phone-side script.

## No UDP for web content inside Gravel

**Status: deliberate.**

When installed at process start, Gravel's filter refuses the creation of
UDP sockets in its own process, for as long as it runs. On devices where the
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

## The Dev Connection switch closes the app with the LAN setting on

**Status: deferred; to be fixed on its own branch.**

Where the UDP filter is installed, turning on Dev Connection in a watch's
menu while "Use LAN developer connection" (Settings > Connectivity) is on
closes Gravel. The menu lists the phone's network interfaces to show its
addresses, and `NetworkInterface.getNetworkInterfaces()` returned null in
Gravel's process, which the menu does not handle. By the platform's source
that is the filter's effect: Android looks up each interface's index
through an IPv4 datagram socket, which the filter refuses, and leaves out
an interface it gets no index for (`android17-release`: bionic
`libc/bionic/net_if.cpp`, `if_nametoindex`; libcore
`ojluni/src/main/java/java/net/NetworkInterface.java`, `getAll` and
`getNetworkInterfaces`). Seen once, on a Pixel running GrapheneOS (Android
17) with a development build and the filter installed. Not tried: a
released build (0.3.1 and 0.3.3 have the same menu code and install the
filter), and a build or phone without the filter. This entry leaves the
file when the fix lands.

## The UDP filter starts with the app process, not before it

**Status: accepted.**

When installed, the filter goes in at `MainApplication.attachBaseContext`,
ahead of the app's content providers, library initializers and `onCreate`
(android16-release, `ActivityThread.handleBindApplication`). A socket
opened earlier than that by platform code would be outside the filter; none
is known. The speech and text engines' isolated processes get no filter.
A process that Android starts for a full backup or restore uses the base
`Application` class (android16-release,
`ActivityThread.handleBindApplication`, `LoadedApk.makeApplicationInner`),
so neither the filter nor the rest of Gravel's startup runs in it.

## If Android refuses the UDP filter

**Status: accepted.**

If the platform does not let Gravel install the filter, whether a
watchapp's phone-side script runs while that watchapp's internet access is
off depends on whether an install of the filter is recorded on that phone,
and on the phone's Android System WebView. Gravel records each install it
makes from this version on. Where none is recorded and the WebView is
version 152 or newer, a switch in Settings > Apps > Watch App Permissions,
on by default, decides, and the script runs without the filter (see
"Where the UDP filter has never installed, WebRTC depends on one layer").
Otherwise the script does not run and the switch is not shown. A phone
with no record counts as one where the filter has never installed,
whatever the reason: no install yet, including after an update from a
version that kept no record, a record that could not be written, or
cleared app storage. While a watchapp's internet access is off, its
permission controls say which applies.
The install, and the check before it, run at each start of Gravel's
process, and the answer holds until the next one: when the platform's DNS
client cannot reach the system resolver at that moment for any reason, or
the check does not answer in time, the filter stays off for that process.

## The UDP filter is untested on Android versions before 17

**Status: accepted.**

The filter has been run on hardware on Android 17. On Android 10 to 16 it
has not been tested installed; on LineageOS 22.2, Android 15, the check
before the install declines it. Before Android 10 Gravel does not install
it, because that check calls `android_res_nsend`, which Android 10 added
(NDK `android/multinetwork.h`). If the platform refuses the install,
Gravel behaves as described under "If Android refuses the UDP filter". On
a device that is not ARM, such as an x86 device that runs ARM code under
translation, Gravel does not install the filter and behaves the same way.

## A watchapp whose WebView renderer exits stays stopped until relaunched

**Status: accepted.**

Gravel keeps running when a PebbleKit JS session's WebView renderer exits,
and does not restart that session on its own; opening the app on the watch
again starts a new one. With Network on, `localStorage` values the script
set by property assignment rather than by `setItem` are lost in that case,
and also when the session stops or is ended while its page is too busy to
save them; with Network off, every change is written through as it
happens, so none are.

## Changing the Network permission restarts the watchapp's phone-side script

**Status: deliberate; the cases with no restart are accepted and fail closed.**

A change to a watchapp's Network permission, in either direction, stops its
PebbleKit JS session and starts a new one, except as follows. Where a
script with Network off does not run ("If Android refuses the UDP filter"),
turning Network off stops the script and starts none. For a watchapp whose
Network permission is off, a flip of the switch for running these scripts
without the UDP filter restarts the session when the flip changes whether
the script runs. Whether a connection opened before any of these changes is
cut in the moment before the restart completes has not been measured.
Turning Network off also ends a script that started with it on, without
waiting for the restart; until it ends, where the UDP filter is not
installed, Gravel has no layer against its WebRTC over UDP.

Some changes are not followed by a restart and leave the session failed
closed, for example a script ended with no new one started, a script whose
permission is on started as if it were off, or a session with no script
running. It stays that way until it is rebuilt, for example at the next app
switch, a reconnection of the watch, or a change that turns its Network
permission on or off. Where an update of Android System WebView to version
152 or newer makes the switch apply while Gravel runs, a watchapp with
Network off whose session started before the update keeps its script off,
while the switch already shows, until the session is rebuilt as above or,
for example, the switch is turned off and on again.

## The WebRTC response-header layer needs WebView 152 or newer

**Status: accepted; improves as WebView updates.**

With Network off, Gravel serves the watchapp's page with a
`Connection-Allowlist` header that allows no connections and switches
WebRTC off for it. WebView builds of Chromium 152 enable the header by
default (Chromium M152, `refs/branch-heads/7977`,
`services/network/public/cpp/features.cc`, `kConnectionAllowlists`, which
`services/network/public/cpp/parsed_headers.cc` requires before it parses
the header), as do those of 153 (`refs/branch-heads/8010`, the same
files), and Gravel cannot read back whether it is honored. Where it is
honored, the browser does not give the watchapp's frame its P2P socket
interface (same branch, `content/browser/browser_interface_binders.cc`,
`should_ban_p2p_for_connection_allowlist`). In Chromium M153, a frame that
asks for an interface it was not given is reported as sending a bad
message (`refs/branch-heads/8010`,
`content/browser/renderer_host/render_frame_host_impl.cc`,
`RenderFrameHostImpl::ReportNoBinderForInterface`). On WebView 153, when a
watchapp script with internet access off tried to set up a WebRTC
connection, its frame asked for that interface and the WebView renderer it
ran in was ended; the script then stayed stopped as described in "A
watchapp whose WebView renderer exits stays stopped until relaunched".
Other WebViews in Gravel can share that renderer (android16-release,
`WebViewClient.java`, `onRenderProcessGone`). Below version 152 the header
does nothing:
where installed, the UDP filter still stops WebRTC over UDP, WebRTC over
TCP is outside the filter and is stopped by the
black-hole proxy where the WebView supports proxy override (Chromium M153,
`refs/branch-heads/8010`, `services/network/p2p/socket_tcp.cc`,
`P2PSocketTcpBase::Init`, which opens the socket through the
proxy-resolving socket factory), and on a WebView without proxy override
(see the WebSocket entry above) WebRTC over TCP has no deterministic cover.
For phones where the UDP filter has never installed, see "If Android
refuses the UDP filter" and the next entry.

## Where the UDP filter has never installed, WebRTC depends on one layer

**Status: accepted; the switch below turns it off.**

Where the switch in Settings > Apps > Watch App Permissions applies (see
"If Android refuses the UDP filter"; LineageOS, systems built on it and
CalyxOS, with a current WebView, are such phones) and is on, which is the
default, a watchapp with internet access off still runs its phone-side
script, without the filter. That script's WebRTC over UDP then depends on
the header above alone. WebTransport is refused by the black-hole proxy
where the WebView supports proxy override (Chromium M153,
`refs/branch-heads/8010`, `net/quic/dedicated_web_transport_http3_client.cc`,
`DedicatedWebTransportHttp3Client::DoCheckProxyComplete`) and by the
header's empty allowlist (same branch, `services/network/network_context.cc`,
`NetworkContext::CreateWebTransport` and
`NetworkContext::RestrictNetworkForIds`). Web requests over HTTP/3 are
refused like every other web request; other use of UDP by web content has
not been checked. No watchapp has been checked for WebRTC use. With the
switch off, these scripts do not run, and the permission controls of a
watchapp with internet access off say so.

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

## Opening a language pack file installs it without asking

**Status: deferred; upstream behavior, to be changed on its own branch.**

Gravel opens `.pbl` files that other apps hand to it, and upstream's handler
sends the pack to the connected watch as soon as the file is opened, with no
confirmation in the app. Firmware files (`.pbz`) ask first. Packs carry no
digest (see "Language pack downloads are not digest-pinned"). The fix is a
confirmation step like the firmware one. This entry leaves the file when
opening a pack asks before installing.

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
service. The routing requires a bug-endpoint build config value that
Gravel never sets, a fact BugReportUploadUnplugTest asserts, so the toggle
changes nothing on any Gravel build. Gravel's description for it says so,
and the setting behind it keeps Gravel's off default where upstream
defaulted it on, so an inert control is not shown enabled; hiding it
behind the same predicate that makes it functional
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

## Notification images are read without the album art checks

**Status: open; planned for a branch of its own.**

When a notification carries an image (a messaging photo or a big
picture), Gravel reads the image the posting app names, with Gravel's
own access, to cache it for the watch. Album art from media apps is
checked for where it comes from before it is opened, and for how large
it is and how long reading it takes while it is read; notification
images do not get those checks yet. The image reaches only the user's
own watch, and nothing goes back to the app that posted the
notification; a slow or very large image can hold up the notifications
behind it on their way to the watch. This entry leaves the file when
notification images get the same checks as album art.

## Notebook background jobs are not scheduled where WorkManager cannot start

**Status: accepted; device defect.**

On devices where WorkManager fails as it starts (the failure
`scheduleBackgroundJob` in MainApplication.kt guards against), the
Notebook schedules none of its background jobs: automatic backups do
not run although the setting shows them on, and recordings waiting for
a charger or a network get no job to start them while Gravel is not
running. Only Gravel's log says so. This entry leaves the file if the
Notebook shows the failure or runs those jobs without WorkManager.

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

**Status: accepted; too slow for watch dictation on the test phone; the
earlier crash did not recur in the v1.9.5 runs below.**

The catalog originally carried ggml-large-v3-turbo-q5_0. Two findings
removed it. First, transcribing with it on the test platform (a Pixel
running GrapheneOS) crashed the app with a native out-of-bounds read
inside ggml's quantized matrix-multiply path at whisper.cpp v1.9.2, the
revision pinned then; the hardened system allocator surfaces the stray
read as a fault. At v1.9.5 the same file ran three dictation calls and
a 53-second recording on that phone without a fault, in a debug build
and in one built with NDEBUG (C asserts compiled out). Second, the
model is too slow for the watch there: at 4 threads, a 4-second
dictation took about 13.5 seconds with the language set and 24 with it
detected, and the recording took about three times its length.
Gravel's dictation speed check extrapolates the 13.5 seconds to about
42 for a full 15-second recording, against the 14 seconds Gravel leaves
a decode after the recording ends. This entry leaves the file when a
large tier returns to the catalog, either fast enough for watch
dictation on the test phone or excluded from watch dictation, after it
passes the engine's on-device suites.

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

The watch can also lose the first syllables of a recording when speech
starts the moment the microphone icon shows. The audio Gravel received in
those recordings was complete, so the loss happens on the watch. In one
test, a phrase spoken right at the icon kept its first words in 1 of 3
recordings, and in 3 of 3 when the speaker waited about a second after
the icon before speaking.

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

## A Notebook recording can lose a phrase at a whisper window boundary

**Status: accepted for now; engine behavior.**

Whisper transcribes a recording in 30-second windows and chooses where
the next window starts from its own timestamps. When it judges a window
finished before the window's end, it skips the rest of that window
(whisper.cpp v1.9.5, `src/whisper.cpp`, `whisper_full_with_state`, the
single-timestamp ending), so speech it did not transcribe there is lost;
on-device runs of a four-minute test recording that repeats its phrases
lost whole sentences within a single call. Starting each window at a
pause, instead of where whisper's timestamps put it, might address this;
it is not built.

## A watch dictation restarts a Notebook transcription in progress

**Status: accepted.**

Watch dictation and Notebook transcription share one speech engine, and
a dictation must answer within the watch's deadline. A dictation the
phone transcribes while a Notebook recording is being transcribed, such
as a spoken reply to a message, stops that transcription, which then
starts again from the beginning once the dictation is done; where the
phone answers first (Local Only, Local (with Server Fallback), and
Server (with Local Fallback) while it keeps dictation on the phone) a
watch session opening stops it
already. The recording
itself is kept; the transcript arrives later, and the stopped work is
done again. Recordings are capped at 5 minutes, so each stop repeats at
most one recording's transcription; the number of stops is not limited.
The same holds on a transcription server without a separate dictation
lane in server only, or a fallback mode without a speech model on the
phone: a watch session opening hangs up on a recording over 30 seconds
there, which is uploaded and transcribed again from the start once the
dictation is over.
Notebook text generation (a note's title and tags, a reminder's message)
runs in an engine of its own but gives way the same way: loading the
model and generating each wait while a dictation holds the speech
engine, and a dictation arriving mid-call stops the call, which starts
again. A load of the model that is already under way when a dictation
arrives runs to its end beside it.

## The Notebook watch app check reads the phone's copy, not the watch's

**Status: open; closing it needs the watch to report what it runs.**

Gravel takes recordings into the Notebook only from a copy of the Notebook
watch app it trusts, judged by the digest of the watch app file the phone
keeps for the watch. The watch does not report which binary it runs, so in
some conditions a different copy under the same app ID could pass the
check. Such a copy can add recordings to the Notebook. It cannot read or
change what is already there, and it gets back only the short answer the
watch shows. Installing or replacing the watch app from Settings >
Notebook stores Gravel's copy on the phone for the watch to fetch; it
does not tell Gravel which copy the watch then runs. A copy you mark as
trusted in Settings > Notebook passes the check the same way a shipped
one does.

## The watch can show a failure for a Notebook recording the phone then stores

**Status: open; closing it needs the Notebook to answer before the watch's
deadline with what it knows by then, for example that the recording may
still be saved.**

The Notebook watch app gets its answer within the deadline the watch allows
a dictation. When the phone's checks, storing and lookup of the recording
take longer, the watch is told the recording failed while the phone still
stores and processes it, and a recording made again after the failure is
stored twice. That takes a stall such as one of the phone's key store or
storage, or a long wait for the recordings list while a restore writes to
it, which device testing did not meet.

## A BACK on the watch during a Notebook recording can leave it stored

**Status: open; closing it needs the watch to report whether BACK was
pressed during the recording.**

BACK during a recording on the Notebook watch app discards it, and BACK
after SELECT has ended it keeps it. Gravel tells the two apart by how soon
after the audio ends the watch's message arrives (`DESIGN_NOTES.md`, the
watch source): within 50 milliseconds counts as a discard. A discard whose
message arrives later leaves the recording stored and processed, with no
sign of it on the watch, and a BACK after SELECT whose message arrives
within that time drops a recording the user meant to keep. The limit rests
on eight measurements on one phone and one watch.

## A Notebook watch recording can start on the slow link or be refused

**Status: open; closing it needs watch firmware that starts the
microphone only on the fast link, or keeps what it records before.**

The watch starts its microphone when the phone answers a recording's
start, and audio recorded while the Bluetooth link is still in its slow
idle state can be lost. Gravel therefore holds that answer for a
Notebook watch app recording until Android reports the link as fast,
for up to 6 seconds, asks for the fast link itself while it waits, and
refuses the recording if the link is still slow then
(`VoiceSessionCoordinator`). Known cases where it falls short:

- **No link report.** The link's speed comes from an Android callback
  outside the public SDK, which reports a change of the link's settings.
  Before a connection's first report, and after Gravel's GATT server
  closes or reopens until the next report (also when that happens during
  the hold), a recording is answered at once and can lose its start as
  before the hold. On a phone that does not deliver the callback, and on a
  Bluetooth Classic connection, that is every recording. Both test
  phones deliver it: a Pixel running GrapheneOS (Android 17) and one
  running LineageOS 22.2 (Android 15).
- **A slow switch.** On some phones the link turns fast later than the
  hold allows, and the watch shows "Something went wrong. Try again."
  without starting the microphone. On the phone running LineageOS, 2 of
  6 recordings were refused this way; the switch took about 3 seconds
  when the watch asked for it, and longer when only the phone did. On
  the one running GrapheneOS, held recordings waited 1.3 to 4.6 seconds
  and none was refused.
- **The end of a fast window.** The link stays fast for about 10 seconds
  after the watch app sends a message (among them one when it opens and,
  while its menu stays open, one after a recording), and after a refusal
  it can turn fast for a few seconds. A recording started in the moment the
  watch returns the link to slow can be answered as if the link were
  fast and lose its start.
  Device testing did not reach this case.

## A notice that a reminder is set can cut a watch recording short

**Status: open; closing it needs Gravel to hold its own notices while a
watch recording is in progress.**

Gravel posts a notice when it sets a voice reminder ("Reminder set", or
"Check this reminder's time" when it asks the user to check the time),
and the watch shows it. When the notice arrives while the next recording is
still running on the watch, the watch ends that recording where it is
(PebbleOS v4.41.1, `fw/applib/voice/voice_window.c`,
`prv_mic_window_disappear`), and Gravel keeps what was recorded up to
then. When the notice is dismissed, the watch starts a new recording on
its own (`prv_mic_window_appear`), which can be saved as a note of a
stray phrase (see "A silent dictation can transcribe as a stray word").
Seen once, on a Pixel running GrapheneOS (Android 17), when a second
recording was started right after a reminder.

## A dictation can wait for a Notebook transcription's first seconds

**Status: accepted; whisper cannot stop the recording's preparation or
an encoder pass once it has begun.**

A Notebook transcription stops for a dictation only at whisper's next
abort check, and whisper makes no abort check while it prepares the
whole recording and encodes the first 30 seconds (whisper.cpp v1.9.5,
`src/whisper.cpp`, `whisper_full_with_state`, `whisper_encode_internal`).
A dictation that arrives in that stretch waits for it: on the test phone
with the base model and a four-minute recording, a cancel at the start
of a call took about 4 seconds to land, with the language set or
detected, and a slower phone or larger model takes longer, possibly past
the watch's deadline. Gravel does not set the callback whisper calls
before the first encoder pass (`encoder_begin_callback`,
`whisper_full_with_state`), which could stop a call canceled while the
recording is prepared. In the modes where local transcription is only a
fallback, no hold on the speech engine is raised when the watch session
opens unless the dictation is to stay on the phone, so a local fallback
meets this whenever a transcription has just started.

## Local (with Rebble Fallback) raises no dictation hold when a watch session opens

**Status: open; raising the hold at the session's opening in that mode
would close it.**

Under Local (with Rebble Fallback) the phone transcribes a watch dictation
first, but unlike Local Only and Local (with Server Fallback) the watch
session's opening does not stop a Notebook transcription, so the dictation
waits for that transcription to stop as described in the entry above.

## Notebook reminder times are read only from the phrasings the rules know

**Status: open; covering more needs more phrasings in the rules.**

A voice reminder's time is read by fixed English rules, not by the text
model. When the rules are unsure, for example with a time said another way
("after lunch", "in a couple of hours", "every Tuesday", "by Friday") or a
word around a time that could change it, the reminder is set for the
Default Reminder Time and Gravel asks the user to check it with a mark in
the Reminders list and, unless Android refused its alarm, a notification.
A reminder whose words the rules do not take for a time at all is set for
the Default Reminder Time without asking; this is the case for English
phrasings the rules do not know, such as "first thing" or "at sunset", and
often for a reminder said in another language, though English-looking
words in it, such as "5 pm", are read. Some words are still read as a
time the user did not mean:
"stop at 7-Eleven" reads as 7:11 PM, and a time stated as a fact, as in "I
parked at 9", is read as the time to remind. Gravel posts a notice saying
when a voice reminder is set, where the phone lets its notifications show
and Android did not refuse the reminder's alarm, so a time read wrong or
set to the default can be seen there while the notice is up; only the ten
newest "Reminder set" notices stay. Some words that only sound like a time
make Gravel ask ("ask Sam when the party is"). A reminder cannot be set to
repeat by voice.

## A Notebook transcription needs more engine memory than a dictation

**Status: open; the check before a transcription uses an estimate, not the
engine's own use.**

Each Notebook transcription runs on a second engine state beside the one
dictation keeps, for the length of the call: whisper.cpp counts that state
at about 160 MiB with the base model and 230 MiB with the small one, and in
one measured call per model on the test phone the engine's memory peaked
123 MiB (base) and 264 MiB (small) above its size with the model loaded. Before it starts, the Notebook
compares the phone's available memory with a measured estimate of that
need; when it is short, it frees its text model, and if that is not
enough the transcription waits for the next capture run. The estimate
comes from measurements on a single phone, and the available figure does
not count memory the system could free by ending cached apps, so the check
can make a transcription wait that would have fit, or let one start that
the system then ends; an ended engine is retried three times and the
transcription then fails. On a phone that stays short, every run stops at
the first capture and the whole queue waits until memory frees or a
smaller speech model is chosen, except under Local (with Server Fallback)
with a server set up, which sends such a capture to the server, and for a
capture Server (with Local Fallback) passed to the phone, which waits
alone while the rest still go to the server. The text
model's own step has no such check.

## A provider that fails before the recording ends is answered only after it

**Status: open; reachable only through a codec frame the decoder rejects
or a session declaring a frame size or rate the phone does not decode,
neither of which the firmware has been observed to send.**

The dictation session coordinator waits for the watch to end the
recording before it looks at the provider's result, because the
watch's result clock starts at that moment. A provider that returns or
throws before it has read the recording is therefore answered only
when the recording ends, up to the firmware's recording cap later,
while the user keeps speaking into a session that has already failed.
The local provider decodes each codec frame as the watch sends it and
throws on a frame the decoder rejects, so a corrupt frame would reach
this case; no dictation has produced one, and the only encoder the
firmware sends is the one every provider accepts. The local provider
also refuses, before reading, a session that declares a frame size or
rate other than its decoder's. If a rejected frame, a refused session,
a second encoder or another provider that fails before reading ever
shows up,
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

## A dictation that meets an engine restart can fail

**Status: open; arises when the engine's process ends as a
transcription arrives at the engine or waits for it.**

After the speech engine's process ends, the service reloads the model
on its own, within the limits `DESIGN_NOTES.md` gives ("The whisper
speech engine"). A dictation decides what to wait for from what is on
record when it arrives, and does not wait again once it has the
engine. One that arrives, or is already waiting for the engine, as the
process ends can therefore reach the engine before the reload has put
the model back and fail, reported as the engine unavailable or as no
model installed, or reach it right after the load and decode ahead of
the warm-up that is meant to come first, with the first decode's
one-time cost inside its own decode. The failure reaches the watch
where the phone is the last provider tried for that dictation, as
under Local Only, and the dictation goes to the next provider where
one follows the phone, as under Local (with Server Fallback). A
Notebook transcription makes the same wait: one that meets a restart
can stop the queue until its next run, or go to the server under Local
(with Server Fallback). Closing this means tying what a transcription
waits for to the model it then decodes on, with service tests that end
the engine process before, during and after that wait; deferred to a
change of its own.

## A model prompt's download can settle on another download's status

**Status: open; the prompt shows a failure it can retry.**

The speed nudge and the engine update prompt wait for their download by
taking the first status after the current one that is not Downloading,
and the status names no download. When a download of another speech
model is already running, scheduling the prompt's own cancels that
job, whose Idle then settles the prompt's wait before its own download
has reported anything: the prompt shows "Download failed" while the
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
swap inside that window escapes. The Notebook's text model is hashed and
opened the same way, and a death of its engine process while the model is
loaded or loading drops its memo.
Hashing through the
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

## Notebook files can be rolled back or deleted without detection

**Status: accepted; detecting it is left to a later change.**

Each Notebook file is sealed under a data key of its own, which is sealed
under a device-bound key, and both seals are bound to the file's path, so
an offline adversary with access to the file system cannot read a file,
forge one, or make one open from another path. The same adversary can still
replace a file with an older authentic version of itself, or delete it, and
the app accepts both: nothing binds a version or a directory listing into
what is authenticated. For the capture queue this has a further cost: the
blob of a capture queued after a rolled-back queue file has no entry, and
the sweep for unreferenced blobs deletes it. A rolled-back reminders file
hands out ids again that the event log still holds occurrences for, so a
new reminder can inherit an older one's history. The Notebook's own backup
gives the user a copy to compare against but does not detect the change
either. A note file put back after its note was deleted is listed again at
the next start. Closing this needs authenticated state that is not rolled
back along with the files.

## The Notebook directory shows how many notes there are, their sizes and times

**Status: accepted.**

Note files are named by random ids and keep their folder, name and title
inside the encryption, but the directory still shows anyone who can read
the app's files how many notes and queued recordings there are, the size
of each file, and when each file was last written. Hiding these would need
padded files and a different layout.

## A Notebook key that stops working can leave only Try again

**Status: accepted.**

When Android still holds the Notebook's key but can no longer use it, the
Notebook tab offers Start over only when Android reports the failure as
permanent, which it does from Android 13 on and not for every error.
Otherwise the tab shows a read that may pass, with Try again only, until the
key works again. Clearing Gravel's storage in Android's settings is the way
out: it erases all of Gravel's data and settings, the Notebook key included
(android16-release `PackageManagerService.clearApplicationUserDataLIF`
clears the app's Keystore entries), after which a backup brings the
Notebook back.

## A Notebook key store that does not answer can be offered Start over

**Status: open; retrying before taking Android's verdict would narrow it.**

On Android 16, the release checked, a key store that does not answer a
retried lookup of the Notebook key is reported as a permanent failure, and
Gravel takes it as one at the first failure, so the Notebook tab can offer
Start over for a key a restart of the phone would bring back. Start over
looks the key up again before deleting anything.

## A Notebook capture the store could not seal waits as plaintext for a later attempt

**Status: accepted.**

When the queue cannot take a just-finished recording, from the phone's
microphone or from the Notebook watch app, whatever the failure, the app
writes it under the Notebook directory for a later attempt, and each later
capture run tries again to seal and queue everything waiting there; the
watch is answered "Saved". If that write fails too, the recording is lost:
the Notebook screen says a phone recording could not be saved, and for a
watch recording the watch shows "Couldn't save. Try again." and the phone
shows nothing. A recording kept this way is dated in the time zone current
when it is processed, not the one it was recorded in.
Until that succeeds the recordings sit unencrypted in the app's private
storage, still under Android's file-based encryption and excluded from
backup with the rest of the Notebook. When the cause is a busy key store
the window closes at the next run; when the queue file is corrupt it stays
open until the file is rebuilt, at the next app start or from the Notebook
tab, and when the Notebook key is gone until the Notebook starts over, with
every capture retained meanwhile joining it. The alternative,
deleting the only copy of a recording because the store failed, was judged
worse. The store provides the holding area, the retry, and a count of what
is waiting, which the Notebook screen shows. The holding area is also the one place where the store
accepts a file it did not seal itself: a recording placed there by whoever
can write to the app's private storage is queued and transcribed as if
the user had recorded it, which the sealed files' authentication cannot
prevent because sealing is exactly what failed.

## A Notebook recording in progress is lost if the app process ends

**Status: accepted; sealing the recording in parts while it records would
close it.**

A phone recording is held in memory until it stops, so Gravel writes an
unencrypted copy to storage only in the failure case above. If the app
process ends before the recording stops, as in a crash, a restart of the
phone or a flat battery, the recording is lost. Leaving the Notebook tab,
leaving Gravel, turning the screen off or swiping Gravel away from recent
apps stops the recording and keeps what was recorded, unless it is under a
second or cannot be saved, which the Notebook screen reports.

## A Notebook write just before a power loss can be undone

**Status: open; syncing the directory after each write would close it.**

A sealed Notebook file's move into place after its write is not synced
(`DESIGN_NOTES.md` describes the write). If the phone loses power soon
after a write, the file can be back at its earlier version after the
restart, or missing if it was new, although a just-saved watch recording
was already answered as saved. How soon is up to the storage and was not
measured.

## Notebook processing that starts out of sight can wait for the app to open

**Status: accepted; the start rule is Android's.**

The Notebook keeps processing going while no Gravel screen is shown
through a foreground service, and Android 12 and later let an app start
one from the background only under an exemption. When processing begins
with no Gravel screen shown and no exemption applies, the start is refused,
the system can freeze or end the app partway, and the recording stays
queued for a later run, such as when Gravel is next opened. Processing
already under way when the user leaves a Gravel screen asks for the
service at that moment, a start Android allowed in device testing.

## Notebook long recordings can wait past plug-in while Gravel is not running

**Status: accepted.**

With Long Recordings Wait for Power on (the default), plugging the phone
in asks for a run through a receiver while Gravel's process is running;
that run is a start out of sight like the one in the entry above. When
the process is not running, a scheduled job starts the run, and that job
waits for Android's idea of charging, not of being plugged in. On
Android 14 that is the platform's charging broadcast alone, which a
phone holding its charge below full (an adaptive or 80% charging limit)
may not send, so the recordings can wait until charging resumes or
another run starts, such as when Gravel is opened, a recording is made
or Process now is tapped. Android 15 and 16 also count a held charge of
70% or more as charging when the phone reports an adaptive charging
mode. Releases before Android 14 were not checked. A run on power that
leaves a long recording queued, such as after a failed attempt, leaves no
job behind, and if Gravel's process then ends before a run off power sets
one, a later plug-in starts nothing.
After a force stop
the job is gone too (android16-release `JobSchedulerService` cancels a
force-stopped app's jobs on `ACTION_PACKAGE_RESTARTED`), so nothing
starts them until Gravel is opened. The charging sources are cited at
`WorkManagerChargingWork`.

## Notebook recordings can wait past a network's return while Gravel is not running

**Status: accepted.**

A Notebook recording can wait for a network its server may use, such as
Wi-Fi with "Use the Server on Mobile Data" off. While Gravel's process
is running, a run is asked for when that network connects; the watch
connection service runs while a watch is connected. When the process is
not running, a scheduled job checks on any connected network, and each
check that still has to wait puts the next one further off: 15 minutes,
then 30, then 45, up to 5 hours (androidx.work 2.12.0,
`WorkSpec.calculateNextRunTime` and `WorkRequest.MAX_BACKOFF_MILLIS`).
After seven hours of waiting the next check is two hours away, so the
recording can stay queued that long after the phone is back on Wi-Fi,
or until another run starts, such as when Gravel is opened, a recording
is made or Process now is tapped.

## A damaged Notebook recording can leave the queue without being listed

**Status: open; closing it needs the flag written before the file is set
aside.**

A recording whose file fails to open is set aside and meant to show in
the failed list as damaged. When the failure is not stored after the
file is set aside, a later processing pass finds no recording and
removes the entry without listing it, so the user gets no notice. The
recording could not have been read anyway.

## Set-aside Notebook files are aged by the phone's clock

**Status: accepted.**

A Notebook file that is set aside is deleted once the time in its name
is 30 days behind the phone's clock. If the clock jumps 30 days or more
ahead, the set-aside files are deleted at the next cleanup, however
recent. A file set aside while the clock was behind goes early by that
much once the clock is right, and one set aside while it was ahead stays
that much longer. No screen opens a set-aside file, so what changes is
how long a copy stays on the phone.

## Automatic Notebook backups are thinned by the phone's clock

**Status: open; skipping the thinning on a run whose clock reads far past
the last confirmed backup would narrow it.**

Older automatic backups are thinned by their age on the phone's clock. A
run made while the clock reads ahead thins as if every backup were that
much older, so it can delete backups the keeping rule would still keep, and
they stay deleted once the clock is right. What goes is older restore
points; the newest backup stays.

## Start over anyway can leave the Notebook key in place

**Status: accepted.**

Start over anyway is offered when Android's key store cannot use or
look up the Notebook key. If the key store still does not answer when
the key is to be deleted, the delete is skipped and Gravel reports the
key deleted. The old Notebook files are deleted all the same; the key
stays, and the Notebook uses it again once the key store answers.
`NotebookKeystore.deleteKey` asks whether the key exists before it
deletes, and that check answers no when the key store cannot be
reached (android16-release `AndroidKeyStoreSpi.getKeyMetadata`;
android11-release, android10-release and pie-release
`KeyStore.contains`). Deleting without the check would close this.

## A Notebook capture can be published twice

**Status: open; closing it needs the capture's id stored with what it
published.**

A capture leaves the queue in a second write after the note, reminder
or completion claim it produced is stored. The next drain processes
the capture again in cases such as when the app is ended between the two
writes or the
queue file cannot be written at that moment, after a damaged queue file is
rebuilt while the capture's recording was still there, and after this
Notebook restores a backup made while the capture was queued, a restore run
again after a stopped one included. A note with the same content then
reuses its file, but a reminder or a claim is stored a
second time, and a note whose second model reply differs is written beside
the first. Storing the capture's id with each result, and skipping a
capture already published, would close this.

## A Notebook completion backlog past fifty drops the oldest

**Status: accepted.**

A spoken completion waiting for the user to choose the reminder it
completes sits in a store until the user acts on it. The store holds fifty
entries; the fifty-first save drops the oldest completion claim without
asking. The bound keeps an ignored backlog from growing without limit.

## A Notebook reminder whose alarm Android refuses can stay silent

**Status: open; the screens do not tell a reminder or snooze stored
without its alarm from one that has it.**

Each Notebook reminder with a time ahead and each pending snooze holds one
Android alarm, beside Gravel's other alarms, and Android refuses a new one
once Gravel holds its per-app limit (500 by default; android16-release
`AlarmManagerService`, `MAX_ALARMS_PER_UID`). Nothing in the Notebook keeps
the number of reminders below that limit. A reminder whose alarm is
refused is stored without one: one made under Reminders in the Notebook
tab is reported as saved, one from a recording is listed under Recent
captures as a reminder but gets no notice that it is set or that its time
needs checking, and one brought back by a restore shows nothing.
A snooze refused this way is kept without its alarm: a swiped
notification is gone with nothing set, and a snooze picked in Gravel is
reported as "The reminder couldn't be snoozed." but rings after the next
re-arm. Nothing sets the missing alarms until the reminder is edited or
the next re-arm runs, such as when Gravel next starts, and while Gravel
stays at the limit both are refused too, an edit then being reported as
not saved although it is stored; a reminder whose time has passed by then
is shown late or listed as missed. An edit of a reminder that has its
alarm moves that alarm, but another of Gravel's alarms set in the same
instant can take its place and leave the reminder without one.

## The Notebook key is usable while the device is locked

**Status: accepted by design.**

The Notebook key requires no user authentication and no unlocked device, so
code running as Gravel can read and write Notebook files whenever the
process runs, including on a locked phone. That is what lets a reminder
fire and a watch capture be stored while the phone stays in a pocket.
The cost is that a device seized while locked, if an attacker can execute
code as Gravel on it, yields the notes; the offline file-system adversary
the encryption is aimed at still cannot, because the key never leaves the
Keystore. Gating the key on an unlocked device would break the background
uses above and is not planned.

## The phone holds the key that opens every automatic backup

**Status: accepted by design.**

Automatic Notebook backups are sealed under a recovery key that Gravel
shows at setup and also keeps, sealed by the Notebook key, so a backup can
run without the user. Like the Notebook key, that copy needs no user
authentication, so code running as Gravel, or as root, on the phone can
open every automatic backup made with that key, in the current folder or an
earlier one, older ones holding notes since deleted included. Manual
backups are sealed under a password Gravel does not keep.

## Gravel cannot tell whether the backup folder leaves the phone

**Status: accepted.**

Automatic backups go to the folder the user picks. Gravel cannot see
whether another app copies that folder off the phone, so backups in a
folder that stays on the phone are lost with the phone. First setup asks
for a folder a sync or cloud app keeps.

## Automatic backup files show when the Notebook changed

**Status: accepted.**

An automatic backup is written about an hour after a change, and its file
name and time say when. Whoever can list the backup folder, such as the
app or service that syncs it, can see when the Notebook was changed,
though not what changed.

## Automatic backups wait for the first unlock after the phone starts

**Status: accepted.**

The Notebook and its backup key are in the app's default storage, which
Android makes available only after the user unlocks the phone once since
it started (developer.android.com/training/articles/direct-boot,
credential encrypted storage). No automatic backup runs before that.

## A force-stopped Gravel gives no backup warning until it is used again

**Status: accepted.**

A force stop removes an app's alarms (android14-release and main,
`AlarmManagerService`, `ACTION_PACKAGE_RESTARTED` handling), and Android
keeps a force-stopped app stopped until the user opens or otherwise uses
it, canceling its pending intents from Android 15
(developer.android.com/about/versions/15/behavior-changes-all, "Changes to
package stopped state"). So after a force stop neither the warning about a
day without a confirmed backup nor a recovery key reminder comes until the
user opens or otherwise uses Gravel again.

## Without a screen lock, anyone holding the phone can redirect or restore automatic backups

**Status: accepted.**

Setting up automatic backups, making a new recovery key, changing the
backup folder and restoring the newest automatic backup ask for the phone's
screen lock, or a biometric the phone accepts for it, first. A phone with no
screen lock has nothing to ask for, so whoever holds it can send later
backups to a folder of their choosing under a key they see, as they can
already read the Notebook on it, and while part of the Notebook cannot be
read can restore the newest automatic backup, bringing back what was
deleted since it was made.

## The Notebook tab's screen-capture block can start a frame late

**Status: accepted.**

Screenshots, screen recordings and the Recents preview leave out the
Notebook tab: Gravel marks the app's window secure while the tab is open
and clears the mark after the tab closes. Android applies each change a
moment after Gravel makes it, so the tab holds its content back for two
frames after setting the mark and keeps the mark for two frames after
closing; on a slow phone, a screen recording already running as the tab
opens or closes can still catch a frame of it.

## A keyboard can ignore the Notebook's request not to learn

**Status: accepted.**

The Notebook's text fields ask the keyboard not to learn from what is
typed in them, so note text stays out of its suggestions and typing
history. Android leaves this to the keyboard ("the flag is not a
guarantee, and some IMEs may not respect it", android16-release,
`EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`): a keyboard that ignores
the request can keep words from notes, titles, folder names and tags.

## The Notebook's text models are community quantizations

**Status: accepted.**

Both text models in `LlmModelCatalog` are unsloth's GGUF quantizations
rather than files from the model's publisher: Qwen publishes no GGUF of
Qwen3.5 0.8B and only an 8-bit one of Qwen3 1.7B. The pins hold each
file to the bytes vetted on 2026-09-22, but whether those bytes are a
faithful quantization of Qwen's weights rests on unsloth. ggml-org, the
llama.cpp project's organization, publishes a Q4_K_M of Qwen3 1.7B that
could replace the first entry after a quality check on the Notebook's
prompts; it publishes no Q5_K_M of Qwen3.5 0.8B.

## Behind a VPN, the Wi-Fi-only rule sees only what Android shows

**Status: accepted.**

With "Use the Server on Mobile Data" off, Gravel sends audio to a
self-hosted transcription server only while the phone uses Wi-Fi that
is not marked metered. On Android 10 and newer a VPN shows which kinds
of network it runs over, and Gravel sends through it only while that is
Wi-Fi. On Android 9 a VPN shows that only if its app declares its
networks; when it does not, Gravel sends while such Wi-Fi is connected.
On Android 8 Gravel cannot see a VPN at all and goes by the network
under it. In both cases the route is the VPN app's, so a VPN running
over mobile data at the same time carries the audio over mobile data.
When a VPN moves off Wi-Fi during an upload, Gravel stops the upload
once Android reports the move; what was sent before that went the new
way. On Android 8 uploads are also not tied to the Wi-Fi itself, so in
the moment between the Wi-Fi dropping and Android reporting it, an
upload can start over mobile data. On any version, Gravel does not
check that the Wi-Fi a VPN runs over is the one not marked metered,
which matters only while two Wi-Fi networks are connected at once. Server
(with Local Fallback) counts a long Notebook recording's failed tries to
reach the server only while the network Android gives Gravel runs over
something besides the VPN; on Android 9 and older, where a VPN may show
nothing under it, every failure behind a VPN counts, with no signal too.
