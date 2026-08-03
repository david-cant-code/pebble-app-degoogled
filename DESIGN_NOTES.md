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

## Branding assets

applicationId is `com.anopticlabs.gravel`; the Kotlin `namespace` and
source packages stay `coredevices.coreapp` (see the branding rule in
`CLAUDE.md`). The launcher icon, in-app wordmark, and fastlane graphics
come from `art/gravel-logo.png`; the brand color is `gravelPurple`
(0xFF9129DE in `util` `theme/Theme.kt`), while the adaptive-icon
background (`util .../res/values/ic_launcher_background.xml`) is a
neutral dark so the purple mark stays visible against it.
