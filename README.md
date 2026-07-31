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
- [ ] Google Play services / Firebase removal
- [ ] Third-party microphone API

## Building (Android)

- JDK 17; Gradle wrapper included. Debug build:
  `./gradlew :composeApp:assembleDebug`
- Until the Firebase strip lands, the build still expects a
  `google-services.json`:
  `cp composeApp/src/google-services-dummy.json composeApp/src/google-services.json`
- For a release build signed with the debug key, set
  `LOCAL_RELEASE_BUILD=true` in the root `local.properties`, then
  `./gradlew :composeApp:assembleRelease`.
- The optional `memfaultToken` Gradle property routes Core-device firmware
  update checks through Memfault. Those checks periodically send the watch
  serial number (or a MAC-derived identifier), hardware revision, and
  firmware version to `api.memfault.com` in the background. Fork builds
  ship no token and make no Memfault requests; firmware updates still work
  through the cohorts endpoint.

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
