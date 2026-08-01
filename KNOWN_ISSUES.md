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

## Pre-pinning STT/LM model installs are grandfathered without retroactive verification

**Status: accepted until the first model pin bump.**

On-device model archives are now verified before install (immutable-commit
download URLs, SHA-256 and exact-size gates, bounded staged extraction),
but that verification is prospective only. Installs made before the
pinning scheme wrote the release tag (`v2.0.1`) as their
`.cactus_version` marker after an unverified download from a mutable tag,
so the marker proves nothing about the installed bytes. `CactusModelPins`
grandfathers that legacy marker while a model's current pin still names
the very archive the tag shipped, which means an install that was already
tampered with under the old scheme (the retargeted-tag or
compromised-org threats the old entry here described) keeps feeding its
weights to the native parser until the first real pin bump forces a
verified reinstall. Retroactive verification is not feasible cheaply:
only the archive digest is pinned and nothing per-file survives
extraction, so re-verifying would force every existing user through a
full re-download (383 MB for STT) after first downgrading them to
remote-only STT via the incompatible-model sweep, a user-hostile trade
against a purely historical exposure window. The grandfather clause and
its frozen anchor digests live in `CactusModelPins.kt`; the first pin
bump ends the exception automatically, and this entry leaves the file
with it.
