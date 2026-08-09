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

**Location enforcement** is a single deterministic gate:
`GeolocationInterface.geolocationPermissionGranted()` now asks the
resolver (upstream read a row nothing ever wrote and defaulted to
"granted", so the gate was inert). Denial is reported to the JS callback
as a geolocation error.

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

## Branding assets

applicationId is `com.anopticlabs.gravel`; the Kotlin `namespace` and
source packages stay `coredevices.coreapp` (see the branding rule in
`CLAUDE.md`). The launcher icon, in-app wordmark, and fastlane graphics
come from `art/gravel-logo.png`; the brand color is `gravelPurple`
(0xFF9129DE in `util` `theme/Theme.kt`), while the adaptive-icon
background (`util .../res/values/ic_launcher_background.xml`) is a
neutral dark so the purple mark stays visible against it.
