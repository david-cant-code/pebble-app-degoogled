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

## Classic PebbleKit app start and stop cannot be restricted to authorized callers

**Status: accepted, no fix available on Android.**

`com.getpebble.action.app.START` and `.STOP` are ordered broadcasts that any
installed application can send, and acting on one launches or stops a
watchapp on the connected watch. Every other cross-app entry point in this
fork is gated on the caller being a declared companion of the watchapp it is
addressing, but a `BroadcastReceiver` is given no caller identity at all:
`onReceive` sees the intent and nothing about who sent it, and no platform
API recovers it after the fact. There is nothing to check.

The obvious alternative, requiring a permission on the receiver, is not
available either. Classic PebbleKit has never declared a permission, so every
existing third-party watchapp companion would break, and a custom permission
at `normal` protection level is granted to anything that asks for it, which
would look protective while stopping nobody.

Impact is bounded to nuisance rather than disclosure: start and stop take a
watchapp UUID and return nothing to the sender, so an app abusing them can
launch or close watchapps but learns nothing about the watch or its data. The
adjacent leaks have been closed separately: the classic provider exposes no
serial, watch data broadcast back out is narrowed to the watchapp's declared
companions where one is declared, and a message send is ignored unless it
addresses the session's own watchapp. The equivalent PebbleKit 2 operations
travel over a bound service, where the caller is authoritative, and are
authorization-gated there.

This entry leaves the file if a future Android release attaches sender
identity to broadcasts, or if the classic surface is retired.

## PebbleKit 2 watch metadata is identical across callers at model level

**Status: accepted; the shared columns identify no individual device.**

Each PebbleKit 2 companion sees a per-caller pseudonymous watch identifier,
and the name column serves the advertised model name with its device-unique
suffix stripped, never the user's nickname. What remains identical across
callers is model-level metadata: platform codename, board revision, and the
running firmware version. Two colluding companions can still narrow "is this
the same watch" to "same model on the same firmware release", an anonymity
set of every watch of that model on that release. That residue is accepted:
those columns exist so companions can do feature detection by model and
firmware, and serving them per-caller-differently would break that purpose
without hiding anything a Bluetooth scan does not already reveal.
