# Known issues

Deliberately deferred issues, each with the rationale and threat-model
context, per the fork rule that nothing is deferred silently. An entry
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

## Pre-pinning STT/LM model installs are grandfathered without retroactive verification

**Status: accepted until the first model pin bump.**

On-device model archives are now verified before install (immutable-commit
download URLs, SHA-256 and exact-size gates, bounded staged extraction),
but that verification is prospective only. Installs made before the
pinning scheme wrote the release tag (`v2.0.1`) as their
`.cactus_version` marker after an unverified download from a mutable tag,
so the marker proves nothing about the installed bytes. `CactusModelPins`
grandfathers that legacy marker while a model's current pin still names
the very archive the tag shipped, which means an install that was already
tampered with under the old scheme (the retargeted-tag or
compromised-org threats the old entry here described) keeps feeding its
weights to the native parser until the first real pin bump forces a
verified reinstall. Retroactive verification is not feasible cheaply:
only the archive digest is pinned and nothing per-file survives
extraction, so re-verifying would force every existing user through a
full re-download (383 MB for STT) after first downgrading them to
remote-only STT via the incompatible-model sweep, a user-hostile trade
against a purely historical exposure window. The grandfather clause and
its frozen anchor digests live in `CactusModelPins.kt`; the first pin
bump ends the exception automatically, and this entry leaves the file
with it.

## Classic PebbleKit broadcasts cannot be restricted to authorized callers

**Status: accepted, no fix available on Android.**

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
service and a ContentProvider, where the caller is authoritative, and every
entry point there is gated on the caller being a declared companion of the
watchapp it addresses.

This entry leaves the file if a future Android release attaches sender
identity to broadcasts, or if the classic surface is retired.

## Classic PebbleKit content provider stays exported without a caller gate

**Status: accepted for compatibility; revisit if its exposure grows.**

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
described above. This entry leaves the file if the provider ever grows a
column beyond connection state and firmware version, at which point it gets
the registry gate regardless of the compatibility cost.

## PebbleKit 2 watch metadata is identical across callers at model level

**Status: accepted; the shared columns identify no individual device.**

Each PebbleKit 2 companion sees a per-caller pseudonymous watch identifier,
and the name column serves the advertised model name with its device-unique
suffix stripped, never the user's nickname. What remains identical across
callers is model-level metadata: platform codename, board revision, and the
running firmware version. Two colluding companions can still narrow "is this
the same watch" to "same model on the same firmware release", an anonymity
set of every watch of that model on that release. That residue is accepted:
those columns exist so companions can do feature detection by model and
firmware, and serving them per-caller-differently would break that purpose
without hiding anything a Bluetooth scan does not already reveal.

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

The watchapp network gate (see `DESIGN_NOTES.md`) enforces a denied app's
network block in three layers. Two of them, the `shouldInterceptRequest`
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
`PROXY_OVERRIDE`-less WebView and a deliberately hostile watchapp, and it
is limited to WebSocket only. `WebViewJsRunner.applyNetworkProxy` logs a
warning when the feature is unavailable. This entry leaves the file if
minSdk/WebView baseline guarantees `PROXY_OVERRIDE`, or if a WebView-level
WebSocket intercept becomes available.

## Cleartext HTTP is blocked app-wide, breaking http-only watchapps

**Status: deliberate; a guarded per-app opt-in may lift it later.**

Watchapps whose developer config pages or PebbleKit JS requests use plain
`http://` fail even when the app's Network permission is granted: the
config page shows a load error and JS requests fail with
`ERR_CLEARTEXT_NOT_PERMITTED`. `https://` is unaffected.

The block began as an upstream accident this fork keeps on purpose.
Upstream's manifest declares `android:usesCleartextTraffic="true"`, but
Android ignores that attribute whenever an `android:networkSecurityConfig`
is declared, which upstream later added to trust user-installed CAs
(upstream commit `7549c661`, "Android: trust user-installed CA certs via
Network Security Config"), and with targetSdk 28+ the config's base
default is cleartext off. The
fork keeps the block because a config page is remote code executed in a
WebView on the phone: fetched over cleartext, it hands any
network-position attacker script injection into that WebView, plus
whatever app state rides in the config URL. Legacy http-only watchapps
break, and that is the accepted cost.

A possible future resolution is a per-app "allow insecure HTTP" toggle in
the watchapp permission controls, default off and gated behind an
explicit warning, enforced through the same layered gate as the Network
permission: the request interceptor and the config-page WebView can
refuse the scheme per app, and the proxy layer supports scheme-filtered
rules that would keep insecure WebSocket covered deterministically. This
entry leaves the file if that ships, or if the ecosystem's http-only
apps age out.

## The bundled speech stack blocks F-Droid inclusion

**Status: open; which way to resolve it is not yet decided.**

The fork targets inclusion in F-Droid's main repository. The de-Google
work itself passes F-Droid's checks: the fdroidserver scanner flags real
Google dependency coordinates and class paths, not mere names like the
`firebase-stubs` module, and the built release APK carries no Firebase,
GMS, or tracker classes. What fails is the on-device speech stack, which
is prebuilt binary code that F-Droid's scanner rejects and its inclusion
policy disallows (dependencies must be free software, built from source
or served from trusted repositories):

- `cactus-native/src/main/jniLibs/arm64-v8a/libcactus_engine.so`, the
  dictation engine, is a 55 MB prebuilt native library checked into git
  with no source or license in the tree, and it ships in the APK. The
  missing license is a problem beyond F-Droid: the app links it while
  shipping under GPLv3. (Upstream moved it from
  `cactus/src/androidMain/jniLibs/` into the `:cactus-native` module and
  updated the binary; the module's CMake build only compiles a small
  CPU-capability shim, `cactus_cpu.c`, so the engine itself is still
  sourceless.)
- `models/needle-pebble-ft-cq4.zip` (13.7 MB) is bundled into APK assets
  via a symlink, and the main STT weights (383 MB) are downloaded at
  runtime from Hugging Face; neither carries a license statement in the
  tree, and both would draw non-free-assets objections.
- The `io.github.coredevices.haversine` AAR ships prebuilt satellite
  `.so` libraries into the APK, even though the Ring runtime they serve
  is disabled at the DI seam in this fork.

Resolving this means building the engine from source (upstream has not
published it), dropping the speech stack from an F-Droid build, or
replacing it with a free engine; each option costs something real, so
the choice is recorded here rather than made silently. Routine
submission work (build recipe, signing, versioning) is not tracked
here. This entry leaves the file when an F-Droid submission is accepted
or the target is dropped.
