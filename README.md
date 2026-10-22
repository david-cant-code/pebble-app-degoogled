# Gravel

[<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="80">](https://f-droid.org/packages/com.anopticlabs.gravel/)

[<img src="https://img.shields.io/f-droid/v/com.anopticlabs.gravel" alt="F-Droid version" hspace="13">](https://f-droid.org/packages/com.anopticlabs.gravel/)

Gravel is a companion app for Pebble watches that runs fully featured
without Google Play services or Firebase, for GrapheneOS, LineageOS, and
similar phones, with as little telemetry as technically possible and a
security posture tightened beyond upstream's defaults.

Gravel is an **unofficial, Android-only fork** of the
[Pebble mobile app by Core Devices](https://github.com/coredevices/mobileapp).
It is not affiliated with or endorsed by Core Devices. It ships under its
own name, icon, and application id (`com.anopticlabs.gravel`) precisely so
it cannot be mistaken for the official app.

## Status

De-google work is completed, all telemetry is removed. What remains is polish and features:

- [x] Manual weather location entry
- [x] Telemetry strip (Crashlytics, analytics, firmware-diagnostics relay)
- [x] Google Play services / Firebase removal
- [x] Core watch firmware updates from the public PebbleOS GitHub releases
- [x] Per-app watchapp permissions (internet + location, deny by default)
- [x] Free speech engine (whisper.cpp built from source, replacing the
      proprietary Cactus stack)
- [x] Notebook: voice and typed notes and reminders, from the phone or a
      Pebble watch with the
      [Notebook watch app](https://github.com/david-cant-code/gravel-watchapp)
      (early beta)
- [ ] Local on-device battery analytics (secondary goal, feasibility open)
- [ ] Recording into the Notebook from a Pebble Index 01 ring (planned)
- [x] F-Droid inclusion (listing live)

## Goals

- **No Google Play services / Firebase.** Everything the watch needs must
  work on a de-Googled phone.
- **No telemetry.** Crash reporting, analytics heartbeats, and the watch
  firmware-diagnostics relay are removed. Upstream's battery-analytics
  screen is a server-rendered page fed by that relay, so it has no data
  source in Gravel and its entry points are disabled; usable battery
  analytics would need a local, on-device reimplementation. The hosts the
  app talks to are fixed in the app, with two exceptions you control: an
  app store source you add yourself is fetched from the host you entered,
  with no account token attached and no store search sent to it, and a
  transcription server you configure receives your dictation audio and,
  in the server modes, your Notebook recordings (nothing is sent unless
  you set one up). Problem reports are a manual
  export: Settings > Get Help > Export logs writes a zip (app log, device
  summary, anything you attach) for you to add to an issue yourself, and
  nothing in the app can upload it.
- **Hardening beyond the de-Googling.** The app's own attack surface is in
  scope, not just its Google dependencies
  ([KNOWN_ISSUES.md](KNOWN_ISSUES.md) records the trade-offs). Landed so far:
  - The speech and text engines each run in an isolated process with no
    permissions and
    no path into the app's files, so a memory-safety bug in the model
    parser or decoder, reached through a model file, is contained to that
    process.
  - PebbleKit, the channel between watchapps and companion apps on the
    phone, has two switches under Watch App Permissions. Classic PebbleKit,
    which any app on the phone can listen to and send through, ships off.
    PebbleKit 2, which only apps that an installed watchapp names can use,
    stays on.
  - The code third-party watchapps run inside Gravel gets no internet or
    location access unless granted (deny by default, per-app controls,
    revocation applies to running apps). On phones where Gravel's UDP
    filter can't be turned on, such as LineageOS and CalyxOS, that code
    keeps running by default with internet access off if Android System
    WebView is version 152 or newer, and its WebRTC connections over UDP
    then depend on a single layer, in that WebView, instead of two; a switch
    under Watch App Permissions stops that code from running. With an
    older WebView, that code does not run (see `KNOWN_ISSUES.md`).
  - The app's other exported Android interfaces are authorization-gated or
    removed.
  - Plain-HTTP (cleartext) traffic is blocked app-wide.
- **Firmware updates without device reporting.** Core watch firmware
  updates come from the official
  [PebbleOS GitHub releases](https://github.com/coredevices/PebbleOS/releases):
  update checks query GitHub anonymously, with no device data in the
  requests and the same requests whatever the watch runs, the firmware
  asset is picked on the phone, and every download is verified against the
  GitHub-declared SHA-256 digest and size plus the firmware bundle's own
  manifest and CRCs before it is handed to the watch. The app offers the
  newest version named on Core's public
  [PebbleOS changelog](https://ndocs.repebble.com/pebbleos-changelog) that
  is newer than the watch's firmware and has a build for its board with a
  GitHub-declared digest: a daily job copies the changelog's version
  numbers to this repository's `firmware-list` branch, and update checks
  read that copy from GitHub. A build picker for Core watches (Settings >
  About > Choose a PebbleOS build, also opened by the Devices tab's update
  button and, for a connected watch, the update notification) lists up to
  five builds for the watch's board with a GitHub-declared digest, newer
  than its firmware (any build in recovery), including the one an update
  check would offer from the same data; it marks which are on the
  changelog and links to it. Legacy Pebble watches keep using the Rebble
  cohorts endpoint and install its offer from the Devices tab. Gravel builds ship no Memfault
  token and make no Memfault requests; upstream's optional `memfaultToken`
  Gradle property would route Core watch update checks through
  `api.memfault.com`, periodically sending the watch serial number (or a
  MAC-derived identifier), hardware revision, and firmware version in the
  background.
- **Weather without Play services.** Manual latitude/longitude entry, since
  the stock place search relies on the GMS-backed platform geocoder.
- **Free on-device dictation.** Voice dictation runs on whisper.cpp,
  built from source as a pinned git submodule, with a choice of
  integrity-pinned model downloads; the proprietary speech engine
  upstream bundles as a prebuilt binary is gone. The model picker
  measures the phone's speed and shows what a full 15 second dictation
  would cost on each model, and a model that turns out too slow for the
  watch's dictation window prompts a switch to a smaller one.
- **Dictation through your own server.** A phone too slow for the
  watch's window can send dictation audio to a self-hosted transcription
  server instead, or use one as a fallback, and the Notebook's recordings
  follow the same setting; see
  [Using your own transcription server](#using-your-own-transcription-server).
  Nothing is sent anywhere unless you configure a server.
- **A Notebook on the phone.** Voice and typed notes and reminders, taken
  from the Notebook tab or from a Pebble watch running the Notebook watch
  app ([source](https://github.com/david-cant-code/gravel-watchapp)),
  which Gravel offers to install.
  It needs Android 9 or newer, and voice notes need a phone whose
  processor can run both engines; other phones get typed notes and
  reminders. The speech recognition and the text model that writes
  titles, tags, and a reminder's wording run on the phone, in
  isolated processes, on whisper.cpp and llama.cpp built from source; a
  recording goes to a transcription server only if you set one up.
  Notes, reminders and waiting recordings are stored as encrypted files
  whose keys are sealed by a key in the phone's keystore;
  `KNOWN_ISSUES.md` records gaps of that storage. A reminder that goes
  off, and the notice of when a voice reminder is set, are phone
  notifications like any other: their text can show on the lock screen,
  goes to the watch, and is kept in Gravel's notification history. Backups
  are files written where you choose, sealed with a password or a recovery
  key. The text models are downloads you start
  yourself, checked against a pinned SHA-256; recordings wait until one
  is installed.
- **Distribution through F-Droid.** Gravel is in F-Droid's main
  repository, as
  [`com.anopticlabs.gravel`](https://f-droid.org/packages/com.anopticlabs.gravel/),
  and staying there constrains every change: the tree holds no binaries
  and every dependency is free software from a repository F-Droid allows.
  The speech and text engines are built from source, and the Ring
  satellite library,
  the last dependency whose native code had no public source, is replaced
  by a stub. `DESIGN_NOTES.md` records what the tree guarantees and what
  the build recipe supplies.

Out of scope: iOS (sources remain in-tree but are unmaintained here).

Not functional: upstream's Pebble Index 01 (Ring) and Index AI features.
The Ring feature module (`experimental`) depends on the Firebase SDKs and is
unplugged from the build; the `libindex`/`index-ai`/`mcp` libraries stay
compiled because the watch UI shares code with them, but their runtime is
disabled at the dependency-injection seam (verified against the built APK:
no Ring services, no Ring endpoints, rings cannot be scanned or paired).
The Notebook is separate from those features and was written
independently of them. Recording into the Notebook from an Index 01 ring
is planned; bringing back Index AI is not.

## Using your own transcription server

Settings > AI > Speech Recognition > Self-hosted Server takes the full URL
of a transcription endpoint, an optional model name, and an optional
bearer token, and then offers three modes in the speech recognition
dropdown: server only, server with local fallback, and local with server
fallback. The app sends each dictation as a 16 kHz WAV in a multipart
POST with `response_format=json` and reads `text` from the reply, which
is what whisper.cpp's server and OpenAI-compatible servers (Speaches,
faster-whisper-server, LocalAI and others) expect.

Unless "Use the Server on Mobile Data" is on, audio goes to the server
only while the phone is using Wi-Fi that is not marked metered, and an
upload that loses that Wi-Fi stops instead of carrying on over mobile
data. Off such Wi-Fi, server only refuses watch dictation, and the two
fallback modes transcribe it on the phone when the phone has a speech
model. From Android 10, a VPN counts as that Wi-Fi only while Android
shows it running over Wi-Fi; `KNOWN_ISSUES.md` covers older versions.
The switch starts on for a server set up before Gravel had it. "Flaky
Network Mode", offered in the two fallback modes once the phone has a
speech model, keeps watch dictation on the phone everywhere, for
connections that cannot return the words within the watch's 15 seconds.

Notebook recordings follow the same setting. Server only sends each one
to the server. Server with local fallback sends each one to the server
first: a recording of 30 seconds or less goes to the phone when the
Wi-Fi rule keeps it off the server, the server can't be reached, or the
server fails it, and a longer one waits while the Wi-Fi rule keeps it
off the server and goes to the phone after 3 failed tries to reach the
server, at least 15 minutes apart, or after an error from the server; on
the phone it waits for the charger when Long Recordings Wait for Power
is on. Without a speech model on the phone it works like server only.
Local with server fallback
sends the ones the phone has too little memory or time for, cannot load
its model for, or hears no speech in, and all of them while the phone
has no speech model. A recording is up to 5 minutes, a WAV of up to
about 9.6 MB in one request, and Gravel waits up to 10 minutes without a
reply from the server, so a reverse proxy in front of the server needs
an upload limit and a read timeout to match (nginx's
`client_max_body_size` defaults to 1 MB and its `proxy_read_timeout` to
60 seconds); the whole request may take up to 15 minutes. While the
Wi-Fi rule keeps recordings off the server, or the server's name does
not resolve, it refuses the connection or
connecting times out, they wait without counting as failed attempts. Any
other connection failure, such as one lost partway, counts as a failed
attempt and is tried again later, up to the Notebook's limit of 10. In
server with local fallback, every connection failure while the phone is
on an allowed network is instead one of a longer recording's 3 tries. An
error from the server, a reply without text, a certificate Gravel does
not trust, 10 minutes without a reply, or a request not finished in 15
minutes stops sending recordings until you tap Try again in the Notebook
or change the server's URL, model, token or trusted certificate; a
recording whose next request after Try again fails the same way moves
to the Notebook's failed list. In server
with local fallback with a speech model on the phone, recordings go to
the phone meanwhile, and none moves to the failed list for it. Pausing
processing, or a short recording interrupting a long one (Interrupt Long
Recordings), hangs up on a recording the server is working on;
whisper.cpp's server then stops working on it, while some other servers
finish it anyway.

Gravel does not send a watch dictation behind a recording over 30
seconds that is on the server. In the two fallback modes with a speech
model on the phone, the dictation is transcribed on the phone. In server
only, or a fallback mode without a speech model, Gravel hangs up on the
recording as the watch starts the dictation, sends no recording until
the dictation is over (or, if it never gets that far, for 30 seconds),
and then sends the recording again from the start, without counting a
failed attempt. On a server that finishes a request after a hang-up,
the dictation still waits behind it. A server that transcribes dictation
apart from long recordings can say so with the header
`Gravel-Dictation-Lane: separate` on its successful replies, and Gravel
then sends dictation to it as if no recording were there. Gravel keeps
that for the host and port of the last successful reply that carried it,
the settings dialog's connection test included, and forgets it when a
successful reply comes without it or another address is saved.

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

## Building

- Clone with `--recursive` (or run `git submodule update --init`): the
  whisper.cpp speech engine and the llama.cpp text engine are pinned git
  submodules, compiled from source by the Android build.
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

## Architecture

The app is the watch's companion and gateway: it holds a persistent
Bluetooth connection to relay notifications, sync data (time, weather,
calendar, health), install watchapps, and proxy network requests on the
watch's behalf. The codebase is Kotlin Multiplatform + Compose
Multiplatform, and Gravel builds and maintains only the Android app. The
stack is Koin (dependency injection), Ktor (HTTP), Room (storage), and
Kermit (logging).

| Module | What it is |
|---|---|
| `androidApp` | Android application shell: manifest, launcher resources, signing, R8 |
| `composeApp` | App-level Compose UI, navigation, and Koin wiring; the Activity and Service classes the shell declares live here |
| `libpebble3` | Talking to Pebble watches: Bluetooth transport, Pebble protocol, services, BlobDB sync |
| `pebble` | Watch features and screens above the library layer |
| `util` | Shared utilities (logging, IO, theme) |
| `whisper`, `whisper-native` | The speech engine: Kotlin bindings, and the NDK/CMake build of the pinned whisper.cpp submodule |
| `socketfilter` | A process-wide filter that refuses the creation of UDP sockets, built from its C source with the NDK; not installed, for example, where the platform's name lookups need UDP, before Android 10, or on a device that is not ARM (see `KNOWN_ISSUES.md`) |
| `notebook` | The Notebook feature (voice and typed notes, reminders): its tab, note screens, reminder list, calendar (which can also show the phone's calendar) and editor, completion review, reminder alarms, its reminder and quick-completion settings, backup, restore and check of a backup file, automatic backups to a folder, with their keeping rules, warnings and recovery key checks, recovery from a damaged file or a lost key, its text model settings, a setup guide, and recordings from the Notebook watch app. Its settings, setup guide and text model screens are in `pebble`, its engine wiring in `composeApp` and `util`, and its watch app wiring in `composeApp` and `libpebble3` |
| `llm`, `llm-native` | The Notebook's text engine: Kotlin bindings, and the NDK/CMake build of the pinned llama.cpp submodule |
| `resampler` | Audio resampling |
| `blobannotations`, `blobdbgen` | KSP annotations and code generator for BlobDB records |
| `libindex`, `index-ai`, `mcp` | Ring and Index AI libraries, compiled because the watch UI depends on them, with their runtime disabled |
| `firebase-stubs`, `haversine-stubs`, `krisp-stubs` | Inert stand-ins that keep upstream code compiling without the Firebase SDKs, the Ring satellite library, and the Krisp noise-cancellation SDK |
| `experimental` | The Ring feature module, kept in the tree but not built |
| `iosApp` | The iOS app shell, unmaintained |

How the de-Googling is wired (the DI seams, the stubs, the unplugged Ring
module) is described in [DESIGN_NOTES.md](DESIGN_NOTES.md).

## Reporting bugs

Open an issue on
[GitHub](https://github.com/david-cant-code/pebble-app-degoogled/issues).
A log export from Settings > Get Help > Export logs helps; issues are
public, so look through the zip before attaching it.

Do not report security vulnerabilities in a public issue; see
[SECURITY.md](SECURITY.md) for private reporting.

## Contributing

Pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md).

## License and attribution

Upstream code is © Core Devices and contributors, licensed under
[GPLv3](LICENSE); this fork's changes are © david-cant-code and
contributors, licensed under GPLv3 as well. No commercial license for
Gravel exists, and none is offered.

Upstream's README states that Core Devices dual-licenses the upstream app,
under GPLv3 or under a paid commercial license agreement. That commercial
option does not extend to Gravel: a commercial license or any other
agreement with Core Devices grants no rights to this fork's changes, which
are not Core Devices' to license. Contributions to Gravel are accepted
under GPLv3 with no contributor license agreement (see
[CONTRIBUTING.md](CONTRIBUTING.md)).

"Pebble" and "Core Devices" are trademarks of
their respective owners; this project is an independent fork of their
GPLv3-licensed source code. References to Pebble watches in this app and its
documentation describe device compatibility, nothing more; Gravel's own
branding (name, icon, colors) is deliberately distinct so users are never
confused about which app they are running.

The bundled Inter typeface (version 4.001, copyright 2016 The Inter Project
Authors) is licensed under the SIL Open Font License 1.1; the notice and
license text are in [LICENSE-Inter-OFL](LICENSE-Inter-OFL). The whisper.cpp
speech engine and llama.cpp text engine submodules each carry their own MIT
license file.

Development of Gravel uses AI assistance; commits carry `Co-Authored-By`
trailers accordingly.
