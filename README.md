# Pebble Mobile App (de-Googled fork)

An **unofficial, Android-only fork** of the
[Pebble mobile app by Core Devices](https://github.com/coredevices/mobileapp).
This fork is not affiliated with or endorsed by Core Devices.

The goal is a companion app for Pebble watches that runs fully featured
without Google Play services or Firebase, for GrapheneOS, LineageOS, and
similar phones with as little telemetry as technically possible.

## Fork goals

- **No Google Play services / Firebase.** Everything the watch needs must
  work on a de-Googled phone.
- **No telemetry.** Crash reporting, analytics heartbeats, and the watch
  firmware-diagnostics relay are removed. Upstream's battery-analytics
  screen is a server-rendered page fed by that relay, so it has no data
  source in this fork and its entry points are disabled; usable battery
  analytics would need a local, on-device reimplementation.
- **Weather without Play services.** Manual latitude/longitude entry, since
  the stock place search relies on the GMS-backed platform geocoder.
- **A watch microphone API for third-party applications** documented and
  authorization-gated, rather than locking watch mic audio to first-party
  features.

Out of scope: iOS (sources remain in-tree but are unmaintained here), and the
Pebble Index 01 (Ring) / Index AI features, due to their heavy reliance on
Firebase and other cloud services. The Ring feature module (`experimental`)
is unplugged from the build; the `libindex`/`index-ai`/`mcp` libraries stay
compiled because the watch UI shares code with them, but their runtime is
disabled at the dependency-injection seam (verified against the built APK:
no Ring services, no Ring endpoints, rings can never be scanned or paired).

## Status

Early days, the fork currently tracks upstream while the de-Google work
lands piece by piece:

- [x] Manual weather location entry
- [x] Telemetry strip (Crashlytics, analytics, firmware-diagnostics relay)
- [x] Google Play services / Firebase removal
- [x] Core watch firmware updates from the public PebbleOS GitHub releases
- [ ] Third-party microphone API

## Building (Android)

- JDK 17; Gradle wrapper included. Debug build:
  `./gradlew :composeApp:assembleDebug`
- No `google-services.json` is needed: the google-services plugin is gone
  along with the Firebase SDKs.
- For a release build signed with the debug key, set
  `LOCAL_RELEASE_BUILD=true` in the root `local.properties`, then
  `./gradlew :composeApp:assembleRelease`.
- Fork builds ship no Memfault token and make no Memfault requests.
  Upstream's optional `memfaultToken` Gradle property would route Core
  watch update checks through `api.memfault.com`, periodically sending the
  watch serial number (or a MAC-derived identifier), hardware revision,
  and firmware version in the background.
- Core watch firmware updates come from the official
  [PebbleOS GitHub releases](https://github.com/coredevices/PebbleOS/releases):
  the release list is fetched anonymously with no device data in the
  request, the firmware asset is picked on the phone, and every download
  is verified against the GitHub-declared SHA-256 digest and size plus the
  firmware bundle's own manifest and CRCs before it is handed to the
  watch. The default channel offers a release line once its first release
  has been public for a week, and picks up later hotfix patches within
  that line immediately; a debug-settings toggle ("Early PebbleOS
  updates") offers the newest release immediately. Legacy Pebble watches
  keep using the Rebble cohorts endpoint.

## Architecture

The app is the watch's companion and gateway: it holds a persistent
Bluetooth connection to relay notifications, sync data (time, weather,
calendar, health), install watchapps, and proxy network requests on the
watch's behalf. The codebase is Kotlin Multiplatform + Compose
Multiplatform; watch communication lives in the `libpebble3` module (Pebble
protocol, services, BlobDB sync).

For the full architecture write-up, module map, and developer documentation,
see the [upstream README](https://github.com/coredevices/mobileapp#readme),
which applies to this fork aside from the removals above.

## License and attribution

Upstream code is © Core Devices and contributors, licensed under
[GPLv3](LICENSE); this fork's changes are GPLv3 as well. Upstream offers
separate commercial licensing (`LICENSE-COMMERCIAL`); that option applies to
upstream, not to this fork. "Pebble" and "Core Devices" are trademarks of
their respective owners; this project is an independent fork of their
GPLv3-licensed source code.

Development of this fork uses AI assistance; commits carry `Co-Authored-By`
trailers accordingly, matching upstream's disclosure practice.
