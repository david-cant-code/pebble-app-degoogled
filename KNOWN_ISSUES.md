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
