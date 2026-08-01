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

## Watch health sync not yet exercised end to end after the Firebase strip

**Status: deferred to the first watch-paired e2e pass.**

The Firebase strip excluded the GMS Google Fit transitives from health-kmp
and rerouted manager creation through a fork seam that pins
`useGoogleFit=false`, which reshapes the runtime inputs under the live
Health Connect sync path. Verification so far is static plus test-level:
APK dex checks show no GMS classes, an instrumented seam test
(`HealthSeamTest`) resolves the production Koin module in a forced
Health-Connect-unavailable environment and asserts the Google Fit backend
is never chosen, and a bytecode scan of health-kmp 1.4.0 found no
live-path references into the `-dontwarn` namespaces. What has not run is
an actual watch health sync
against the built APK, because no watch has been paired to the test device
yet. Deferred, not skipped: the sync e2e is part of the watch-paired
verification pass, and this entry leaves the file when that pass is
recorded.

## On-device STT model downloads have no integrity verification

**Status: deferred to a dedicated STT hardening branch.**

The fork-owned `CactusModelProvider`
(`composeApp/src/androidMain/kotlin/coredevices/coreapp/model/CactusModelProvider.kt`)
downloads STT/LM model weights from `https://huggingface.co/Cactus-Compute`,
a third-party Hugging Face org (the Cactus engine vendor, not Core Devices
and not this fork), pinned only to the mutable git tag in
`CACTUS_WEIGHTS_VERSION` (currently `v2.0.1`). There is no checksum,
signature, or immutable-revision pin between download and extraction, and no
size cap on the extracted output (Zip-Slip is handled). The extracted
weights are parsed by the bundled native `libcactus_engine.so`.

Threat model: a compromise of the Cactus-Compute account, or a retargeted
tag, silently swaps the weights on the next download or version bump; a
native parser consuming attacker-controlled blobs is a memory-corruption
surface, and swapped weights could also manipulate transcriptions silently.
The only defensive layer today is TLS with default certificate validation.
This matches upstream's behavior at the fork point (the Play services strip
promoted the code into fork-owned surface without widening its exposure),
which is why it is deferred rather than blocking: the exposure is not new,
one real layer exists, and exploitation requires compromising the vendor
org.

Planned resolution, on its own branch: pin an immutable revision (or verify
a build-time SHA-256 of the downloaded archive), cap extraction size, and
review the on-device STT verification story end to end.

## Dictation lost the inference-boost foreground service with the Ring unplug

**Status: deferred to the same STT hardening branch.**

Upstream holds foreground-process priority during local Cactus
transcription via `InferenceForegroundService`, a `shortService`-type
foreground service hosted in the unplugged `:experimental` module. With the
module gone, `utilModule`'s `getOrNull<InferenceBoost>()` fallback always
yields `NoOpInferenceBoost`, so local transcription runs at the priority of
the app process. This is masked while the watch-connection foreground
service is active (the `androidForegroundServiceForWatchConnectionV2`
setting, default on); a user who disables that setting gets
background-priority inference, which modern Android CPU restrictions can
slow enough to hit the transcription timeouts.

Deferred because the degradation only manifests in a non-default
configuration, is a performance regression rather than a correctness or
security issue, and the fix (a fork-owned minimal boost service, since
upstream's is pure Android with no GMS dependency) belongs with the rest of
the STT work rather than in the Play services strip.
