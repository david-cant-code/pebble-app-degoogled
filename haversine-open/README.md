# :haversine-open

Pure-Kotlin Index 01 client for Gravel. It speaks the ring protocol described
in [pebble-app-degoogled issue #23](https://github.com/david-cant-code/pebble-app-degoogled/issues/23)
and the types under `coredevices.haversine` that `:libindex` and
`experimental/` already call. BLE goes through Kable. There is no native
code and no Ktor dependency.

This file was written from the text of that issue (including its comments)
and from this repository's GPL-3.0 sources: the haversine stubs, libindex,
and the experimental ring call sites. The closed `io.github.coredevices.haversine`
artifact and AlphaFinger were not downloaded, decompiled, or read. No ring
firmware image is stored or fetched.

## Selecting it

`settings.gradle.kts` substitutes `io.github.coredevices.haversine:haversine`
to `:haversine-stubs` unless Gradle is started with `-Pgravel.ring=open`,
which selects this module. The property is not set in `gradle.properties`.
Any other value fails configuration. Both modules stay on the classpath of
their own test tasks either way:

```
./gradlew :haversine-open:testAndroidHostTest
./gradlew :haversine-stubs:testAndroidHostTest
./gradlew :haversine-open:testAndroidHostTest -Pgravel.ring=open
```

With the open property, `:composeApp` binds `RealLibIndex` in place of
`NoOpLibIndex`, plus `KMPHaversineSatelliteManager`, `GravelHacksDelegate`,
`LocalRingDebugDelegate`, and the settings-backed collection index.
Onboarding offers Watch, Index 01, and Both. Index 01 asks for Bluetooth,
then scans and pairs; Both does the watch setup first. The devices screen
always shows **Add Index 01**, whether or not the feed is on. Pairing
promotes a Watch or unset choice to Both and turns on Enable Index Feed
(Settings → General). An Index 01 or Both choice turns that on during
getting started. The Index tab follows that toggle: newest first, with
the time, duration, transcript, playback, and delete. After pairing, the
app scans and stores each recording (WAV plus metadata) in
`filesDir/index-recordings`, deduped by ring, iteration, and collection
index. The tab's overflow exports a zip of every recording.
Transcription uses
Gravel's on-device whisper model, or a self-hosted server if Speech
Recognition is set to one. The feed from the unplugged `experimental`
module is not in this build.
The iOS app graph is not switched. `iosArm64` and `iosSimulatorArm64`
actuals exist so the KMP metadata is complete.

## v1 scope

Implemented:

- Advertisement parsing (`0x0EEA`, fingerprint, count, servicing and recording flags).
- Pairing stays in libindex. Android bonding is `createBond`. On iOS the bond is the data-characteristic write libindex already performs. An insufficient-encryption failure (GATT 5 or 15, or an error whose message mentions encryption) becomes `AuthenticationFailed`. GATT 137 becomes `PeerRemovedPairingInformation`. GATT 133 is retried five times after the first attempt (backoff 0.5, 1, 2, 4, 4 s) and then `ConnectFailed`. There is no automatic re-pair.
- ATT writes are split into MTU−3 byte chunks. The phone does not call `requestMtu`; the ring starts that exchange. Control and data writes are without response. Disconnect enables notifications on system input and closes the link if it is still up after 200 ms.
- Request and reply codec, one request at a time, 5 s timeout, cancel (type 4) on timeout, one retry on status `0x42`.
- An address allow-list. `0x40060000` is never sent. Erase of `0x40030000` is rejected unless `RingFeatureFlags.allowUncapturedErase` is set (default false).
- Collection sync. The stored index is the last fully handled index, inclusive. `null` starts at the window start. The index moves when a group closes or when a page is skipped, and only after `emit` returns. An open multi-part group is left unstored so the next session reads it again.
- Record parsing, timestamps `(tag 0x02 + tag 0x35 + 1) mod 2^32`, multi-part joining, the rice-delta decoder, clock writes when the advert says the ring needs servicing, and battery from tag `0x37`.

Not implemented. Each call fails with `HaversineFeatureUnavailable` (or
`Result.failure` of that type) and does not touch the radio:

- Firmware update (`SatelliteStatus.FirmwareUpdating` exists and is never emitted).
- User-id programming for firmware before 3.62 (`programSatelliteWithUserID`).
- Forced failsafe (`forceFailsafe`).
- Debug dumps (`handleHaversineDebugInfo` is never called).
- Hard erase of collections. `eraseCollections` stores `end - 1` so factory recordings are not downloaded.

`RingHacksDelegate.wipedCollectionsBeforeTransfer` no longer clears the
stored index. libindex calls that delegate again after `eraseCollections`
returns, so a delegate that writes null undoes the soft erase. The library
also writes the soft-erase index back if the callback it makes itself
cleared the value.

## Assumptions that need a ring

Every one of these is marked `ASSUMPTION` or `TODO` next to the code as well.

| Topic | What the code does | What would confirm it |
| --- | --- | --- |
| Stored index | Last handled index, inclusive. A finished window stores `end - 1`. | A sync against firmware 3.75 that does not repeat the last recording and does not skip the next one. |
| Version bytes `0x40030006` | Four bytes `fwMajor, fwMinor, fwPatch, hwMajor`, hardware minor 0. Printable ASCII is used as the string instead. | Compare with a ring that reports hw 11.0 / fw 3.75. |
| Hardware serial `0x40030008` | Six bytes printed as `AA:BB:CC:DD:EE:FF`. | Compare with the bonded address and the serial on the box. |
| Programmed serial `0x40000004` | Printable ASCII, or null when empty or all `0x00` / `0xFF`. | Compare with the boxed serial. |
| Memory advert `0x4003000E` | Ten bytes `09 FF EA 0E` plus the six-byte state. Flag byte at index 9. | Read the characteristic once. |
| Tag `0x50` | u32 rate, then signed little-endian i16. | A capture that actually contains the tag. |
| Tag `0x53` | Running press count. Bit i (i < 32) means press i was long. A backwards count restarts at 0. | A known tap pattern. |
| Audio vs gesture | Multi-part groups, and single parts of at least 1.0 s, are audio. | The same pattern, plus a hold just under and just over 1 s. |
| Missing tag `0x52` | The page is one final part whose start index is the collection index. | A capture with and without the tag. |
| Sample jump | A step above 4096 between parts is logged and kept. | A multi-part recording. |
| Debounce | 1.5 s quiet window. The last non-audio sequence wins. | Tap timing on hardware. |
| Reply timing | A write's reply arrives after its data bytes. | A clock write on the wire. |
| Status `0x06` | Success for writes. | A clock write. |
| Status `0x04` / `0x05` | Skip that index. | A ring that returns them. |
| Timeouts | 5 s for a read up to 4 KiB, then cancel. | A stalled read. |
| GATT 133 backoff | 0.5, 1, 2, 4, 4 s, six attempts in total. | A flaky connect. |
| Connect trigger | Advert byte 4 is compared with the low byte of the last session's range end. | Watch the advert while the range end crosses a multiple of 256. |
| `isInCollectionState` | The recording flag. | `RingSync.lastRingSummary` against a hold. |
| `isNearby` | True after an advert for that id. | The same summary. |
| `truncatedCollectionCount` | Advert byte 4. | The same summary. |
| Clock | Written only when the advert says the ring needs servicing. | A servicing session, and a session that does not. |
| iOS encryption errors | Status 5 or 15, or the word "encrypt" in the message. | A bond failure on iOS. |
| Rollover | Set when the pending indices cross 65535→0, or when the stored index sits in the gap and the next index is not in the window. An index sitting immediately before the window is not a rollover. | A window that slides forward, and one that wraps. |

## Open questions

- Real collection captures (silence and a tone were promised on issue #23 and are not in the tree). Drop hex files in `src/androidHostTest/resources/golden/` and list them in `index.txt`. See `golden/README.txt`.
- Whether tag `0x25` inside a collection uses the same layout as `0x40030006`.
- Whether a recording that is still open when the next group starts should be kept for a later session instead of skipped.
- The press-count baseline is per process. A later session can report the whole count again.
- iOS state restoration and background restore are out of scope. `Peripheral(uuid)` throws if CoreBluetooth has not already seen that id.

## Deviations from the design note

- The default substitution stays on `:haversine-stubs`, and that build still binds `NoOpLibIndex`.
- `-Pgravel.ring=open` binds `RealLibIndex`, offers Index 01 during onboarding, always shows **Add Index 01**, stores recordings, and shows the Index tab when Enable Index Feed is on. The `:experimental` feed stays unplugged.
- The iOS application Koin graph is unchanged. The KMP actuals are still compiled in source.
- kotlinx-io is not a direct dependency. Kable pulls it in; this module does not call it.
- `KMPHaversineSatelliteState` also exposes `isInCollectionState`, `isNearby`, and `truncatedCollectionCount`, because `RingSync.lastRingSummary` reads them.
- `KMPHaversineSatelliteManager` has a public zero-argument constructor that does nothing, so the platform constructors experimental already calls can be secondary constructors on the actual class.
- Golden vectors are hex text under the Android host-test resources. A `.bin` file would fail `FdroidGuardrailsTest`.
- Two `TransferStarted` events are emitted on one connection when a window wraps, rather than dropping the link between them.
- `HaversineStubShapeTest` still pins the stub module. This module has its own snapshot in `HaversineOpenHostTest`.

## Tests

Common tests cover the codec, allow-list, split replies, data-before-reply, cancel, MTU chunking at 20 and 174 bytes, advertisement parsing, record parsing, a rice round trip, and a fake ring that runs the sync scenarios (soft erase, multi-part across sessions, wrap at 65535, failed read, corrupt page, taps versus audio, crash before persist, connect policy). `src/androidHostTest` reflects the public API and decodes every recording named in `golden/index.txt`.
