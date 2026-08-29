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
manifest (artifact). The fork keeps the block because a config
page is remote code executed in a WebView on the phone: fetched over
cleartext, it hands any network-position attacker script injection into
that WebView, plus whatever app state rides in the config URL. Legacy
http-only watchapps break, and that is the accepted cost.

A possible future resolution is a per-app "allow insecure HTTP" toggle in
the watchapp permission controls, default off and gated behind an
explicit warning, enforced through the same layered gate as the Network
permission: the request interceptor and the config-page WebView can
refuse the scheme per app, and the proxy layer supports scheme-filtered
rules that would keep insecure WebSocket covered deterministically. This
entry leaves the file if that ships, or if the ecosystem's http-only
apps age out.

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

**Status: open; capture-chain investigation pending.**

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

## Watch-side dictation endpointing misfires in both directions

**Status: open; firmware behavior, app-side mitigation only.**

The watch's end-of-speech detection sometimes streams the entire 15
second firmware window although speech ended seconds earlier, and
sometimes ends the session after one to two seconds while the user is
still speaking. Both directions were observed repeatedly during
on-device dictation testing; a fully silent session reliably streams
the whole window. The endpointer runs in the watch firmware, so the
app can only shape what it does with the audio it receives: the
encoder-context trim keeps full-window sessions cheap to decode, and
a truncated session transcribes as the fragment that was actually
captured. Recorded here so short or slow dictation results are
attributed correctly during testing.
