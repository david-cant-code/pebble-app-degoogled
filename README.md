# Gravel (de-Googled Pebble app fork)

[<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="80">](https://f-droid.org/packages/com.anopticlabs.gravel/)

[<img src="https://img.shields.io/f-droid/v/com.anopticlabs.gravel" alt="F-Droid version" hspace="13">](https://f-droid.org/packages/com.anopticlabs.gravel/)

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
- [x] F-Droid inclusion (listing live)


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
  analytics would need a local, on-device reimplementation. The hosts the
  app talks to are fixed in the app, with two exceptions you control: an
  app store source you add yourself is fetched from the host you entered,
  with no account token attached and no store search sent to it, and a
  transcription server you configure receives your dictation audio
  (nothing is sent unless you set one up). Problem reports are a manual
  export: Settings > Get Help > Export logs writes a zip (app log, device
  summary, anything you attach) for you to add to an issue yourself, and
  nothing in the app can upload it.
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
  upstream bundles as a prebuilt binary is gone. A small voice activity
  detector (under 1 MB) comes from the same model host: it is installed
  with each model, and an install that already holds a model fetches it
  in the background on an unmetered network, without asking, until it is
  in place. The model picker
  measures the phone's speed and shows what a full 15 second dictation
  would cost on each model, and a model that turns out too slow for the
  watch's dictation window prompts a switch to a smaller one.
- **Dictation through your own server.** A phone too slow for the
  watch's window can send dictation audio to a self-hosted transcription
  server instead, or use one as a fallback; see
  [Using your own transcription server](#using-your-own-transcription-server).
  Nothing is sent anywhere unless you configure a server.
- **A watch microphone API for third-party applications** documented and
  authorization-gated, rather than locking watch mic audio to first-party
  features.
- **Distribution through F-Droid.** The fork is in F-Droid's main
  repository, as
  [`com.anopticlabs.gravel`](https://f-droid.org/packages/com.anopticlabs.gravel/),
  and staying there constrains every change: the tree holds no binaries
  and every dependency is free software from a repository F-Droid allows.
  The speech engine is built from source, and the Ring satellite library,
  the last dependency whose native code had no public source, is replaced
  by a stub. `DESIGN_NOTES.md` records what the tree guarantees and what
  the build recipe supplies.

Out of scope: iOS (sources remain in-tree but are unmaintained here).

Not functional for now: the Pebble Index 01 (Ring) / Index AI features,
because of how heavily they rely on Firebase and other cloud services.
Finding a way to incorporate them without that reliance is a plan, not a
current capability. Until then the Ring feature module (`experimental`) is
unplugged from the build; the `libindex`/`index-ai`/`mcp` libraries stay
compiled because the watch UI shares code with them, but their runtime is
disabled at the dependency-injection seam (verified against the built APK:
no Ring services, no Ring endpoints, rings can never be scanned or paired).

## Using your own transcription server

Settings > Speech Recognition > Self-hosted Server takes the full URL
of a transcription endpoint, an optional model name, and an optional
bearer token, and then offers three modes in the speech recognition
dropdown: server only, server with local fallback, and local with server
fallback. The app sends each dictation as a 16 kHz WAV in a multipart
POST with `response_format=json` and reads `text` from the reply, which
is what whisper.cpp's server and OpenAI-compatible servers (Speaches,
faster-whisper-server, LocalAI and others) expect.

The reference server is whisper.cpp's own, from the same project whose
engine this app builds. On the machine that will run it:

```
git clone https://github.com/ggml-org/whisper.cpp && cd whisper.cpp
cmake -B build && cmake --build build --config Release -j
./models/download-ggml-model.sh base.en
./build/bin/whisper-server -m models/ggml-base.en.bin --host 0.0.0.0 --port 8080
```

The endpoint is then `/inference` on that host. Servers that speak the
OpenAI API use `/v1/audio/transcriptions` and want a model name.

The app only talks https. Put TLS in front of the server with whatever
you already use; if you have nothing, a long-lived self-signed
certificate on a reverse proxy is the simplest:

```
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
  -keyout stt-key.pem -out stt-cert.pem -days 3650 -subj "/CN=stt"
openssl x509 -in stt-cert.pem -noout -fingerprint -sha256
```

With Caddy, for example, `stt.home.lan:8443 { tls stt-cert.pem
stt-key.pem  reverse_proxy 127.0.0.1:8080 }`. On the first "Test
connection" the app shows the certificate's SHA-256 fingerprint; compare
it with the second command's output, and trust it. From then on the app
accepts only that certificate for that host and port, and refuses if it
changes until you confirm the new one, even when the replacement comes
from a CA the phone trusts. A certificate from a public CA, or from a
CA you have installed on the phone, needs no confirmation as long as
nothing is pinned for that host; to move a pinned server to such a
certificate, tap "Forget trusted certificate" in the dialog first, after
which renewals do not ask again. Avoid proxies that rotate short-lived
certificates (Caddy's internal CA renews every 12 hours by default):
each rotation would ask you to confirm again. The bearer token belongs
to the server it was saved with: a URL edited to another host or port is
tested and saved without it unless you type one.

## Building (Android)

- Clone with `--recursive` (or run `git submodule update --init`): the
  whisper.cpp speech engine is a pinned git submodule, compiled from
  source by the Android build.
- Any JDK 17 or newer; Gradle wrapper included. Debug build:
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
