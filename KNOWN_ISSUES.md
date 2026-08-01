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
