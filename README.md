# Gravel (de-Googled Pebble app fork)

Gravel is an **unofficial, Android-only fork** of the
[Pebble mobile app by Core Devices](https://github.com/coredevices/mobileapp).
This fork is not affiliated with or endorsed by Core Devices. It ships under
its own name, icon, and application id (`com.anopticlabs.gravel`) precisely so
it cannot be mistaken for the official app.

The goal is a companion app for Pebble watches that runs fully featured
without Google Play services or Firebase, for GrapheneOS, LineageOS, and
similar phones with as little telemetry as technically possible, and with
a security posture tightened beyond upstream's defaults.

## Fork goals

- **No Google Play services / Firebase.** Everything the watch needs must
  work on a de-Googled phone.
- **No telemetry.** Crash reporting, analytics heartbeats, and the watch
  firmware-diagnostics relay are removed. Upstream's battery-analytics
  screen is a server-rendered page fed by that relay, so it has no data
  source in this fork and its entry points are disabled; usable battery
  analytics would need a local, on-device reimplementation.
- **Hardening beyond the de-Googling.** The app's own attack surface is in
  scope, not just its Google dependencies. Landed so far: third-party
  watchapps' phone-side code gets no internet or location access unless
  granted (deny by default, per-app controls, revocation applies to running
  apps), the app's exported Android interfaces are authorization-gated or
  removed, and plain-HTTP (cleartext) traffic is blocked app-wide
  ([KNOWN_ISSUES.md](KNOWN_ISSUES.md) records the trade-offs).
- **Weather without Play services.** Manual latitude/longitude entry, since
  the stock place search relies on the GMS-backed platform geocoder.
- **Free on-device dictation.** Voice dictation runs on whisper.cpp,
  built from source as a pinned git submodule, with a choice of
  integrity-pinned model downloads; the proprietary speech engine
  upstream bundles as a prebuilt binary is gone.
- **A watch microphone API for third-party applications** documented and
  authorization-gated, rather than locking watch mic audio to first-party
  features.
- **Distribution through F-Droid.** The fork targets inclusion in F-Droid's
  main repository. The tree holds no binaries and every dependency is free
  software from a repository F-Droid allows: the speech engine is built
  from source, and the Ring satellite library, the last dependency whose
  native code had no public source, is replaced by a stub. What remains is
  the submission itself; `DESIGN_NOTES.md` records what the tree
  guarantees and what the build recipe has to supply.

Out of scope: iOS (sources remain in-tree but are unmaintained here).

Not functional for now: the Pebble Index 01 (Ring) / Index AI features,
because of how heavily they rely on Firebase and other cloud services.
Finding a way to incorporate them without that reliance is a plan, not a
current capability. Until then the Ring feature module (`experimental`) is
unplugged from the build; the `libindex`/`index-ai`/`mcp` libraries stay
compiled because the watch UI shares code with them, but their runtime is
disabled at the dependency-injection seam (verified against the built APK:
no Ring services, no Ring endpoints, rings can never be scanned or paired).

## Status

De-google work is completed, all telemetry is removed. What remains is polish and features:

- [x] Manual weather location entry
- [x] Telemetry strip (Crashlytics, analytics, firmware-diagnostics relay)
- [x] Google Play services / Firebase removal
- [x] Core watch firmware updates from the public PebbleOS GitHub releases
- [x] Per-app watchapp permissions (internet + location, deny by default)
- [x] Free speech engine (whisper.cpp built from source, replacing the
      proprietary Cactus stack)
- [ ] Third-party microphone API
- [ ] Local on-device battery analytics (secondary goal, feasibility open)
- [ ] Pebble Index 01 (Ring) / Index AI support without Firebase or other
      cloud services (approach open)
- [ ] F-Droid inclusion (tree ready; submission pending)

## Building (Android)

- Clone with `--recursive` (or run `git submodule update --init`): the
  whisper.cpp speech engine is a pinned git submodule, compiled from
  source by the Android build.
- JDK 17; Gradle wrapper included. Debug build:
  `./gradlew :androidApp:assembleDebug`
- No `google-services.json` is needed: the google-services plugin is gone
  along with the Firebase SDKs.
- Release builds: `./gradlew :androidApp:assembleRelease` signs with
  `keystore.jks` in the repo root when present (credentials from the
  `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEYSTORE_ALIAS`, and
  `RELEASE_KEY_PASSWORD` environment variables), produces an unsigned APK
  when it is absent, and signs with the debug key instead if
  `LOCAL_RELEASE_BUILD=true` is set in the root `local.properties`.
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
which applies to this fork aside from the removals above. The fork-specific
de-Googling architecture (the DI seams, the Firebase stubs, the unplugged
Ring module) is described in [DESIGN_NOTES.md](DESIGN_NOTES.md).

## License and attribution

Upstream code is © Core Devices and contributors, licensed under
[GPLv3](LICENSE); this fork's changes are GPLv3 as well. Upstream offers
separate commercial licensing (`LICENSE-COMMERCIAL`); that option applies to
upstream, not to this fork. "Pebble" and "Core Devices" are trademarks of
their respective owners; this project is an independent fork of their
GPLv3-licensed source code. References to Pebble watches in this app and its
documentation describe device compatibility, nothing more; the fork's own
branding (name, icon, colors) is deliberately distinct so users are never
confused about which app they are running.

The bundled Inter typeface (version 4.001, copyright 2016 The Inter Project
Authors) is licensed under the SIL Open Font License 1.1; the notice and
license text are in [LICENSE-Inter-OFL](LICENSE-Inter-OFL). The whisper.cpp
speech engine submodule carries its own MIT license file.

Development of this fork uses AI assistance; commits carry `Co-Authored-By`
trailers accordingly, matching upstream's disclosure practice.
