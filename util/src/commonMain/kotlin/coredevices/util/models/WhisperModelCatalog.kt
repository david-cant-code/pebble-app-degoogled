package coredevices.util.models

/**
 * Everything the installer must know to obtain and verify one whisper
 * model file before it is allowed anywhere near the native parser.
 *
 * @param id catalog identity and the on-disk directory name; stable
 *   contract with existing installs, never rename casually.
 * @param displayName what the model picker shows.
 * @param fileName the ggml file inside the source repository, and the on-
 *   disk file name under the model directory.
 * @param sha256 lowercase hex SHA-256 of the exact file bytes.
 * @param sizeBytes exact file size; enforced mid-download so a substituted
 *   response is cut off instead of filling the disk.
 * @param minRamBytes rough total-device-RAM floor for running the model;
 *   used for recommendation tiering, not as a hard gate.
 * @param multilingual false for the .en models, which only transcribe
 *   English; the transcription service forces language "en" for those
 *   regardless of the spoken-language setting.
 * @param repo the Hugging Face repository the file is served from.
 * @param commit the immutable commit of that repository the URL resolves.
 */
data class WhisperModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val minRamBytes: Long,
    val multilingual: Boolean,
    val repo: String = WhisperModelCatalog.HF_REPO,
    val commit: String = WhisperModelCatalog.HF_REPO_COMMIT,
)

/**
 * Hardcoded catalog and integrity pins for the on-device whisper models.
 * Single source of truth: the settings UI lists it, the installer
 * downloads from it, and the incompatible-model sweep is defined by it.
 *
 * Every entry is served from one immutable commit of the
 * huggingface.co/ggerganov/whisper.cpp repository (the whisper.cpp
 * author's own model conversions; engine and weights are both MIT, from
 * OpenAI's Whisper release). The download URL resolves that commit, so a
 * retargeted branch or re-uploaded file cannot swap bytes under the pin,
 * and the received bytes must match the pinned SHA-256 and exact size
 * before a file is installed, fail closed.
 *
 * The voice activity detector ([VAD_MODEL]) is pinned the same way from
 * its own repository and commit; it is not a speech model, so it lives
 * outside [MODELS] and is never offered in the picker.
 *
 * Re-pin procedure when changing a commit or an entry (each step is an
 * independent source; all three must agree before new values land):
 *  1. Pick the new commit from the repository's history and record it
 *     ([HF_REPO_COMMIT] for the speech models, [VAD_REPO_COMMIT] for the
 *     detector).
 *  2. Declared metadata: HEAD `https://huggingface.co/<repo>/resolve/
 *     <commit>/<file>` and read `x-linked-size` and `x-linked-etag`; the
 *     etag is the payload's SHA-256 from HF's content-addressed storage,
 *     declared without downloading anything.
 *  3. Independent bytes: download that URL fresh, `sha256sum` it and
 *     byte-count it locally; this pair goes into the table, with 1 and 2
 *     as cross-checks.
 * When a change invalidates existing installs, also bump [GENERATION].
 */
object WhisperModelCatalog {
    /**
     * Marker for the one-time "models changed, re-download" notification;
     * plays the dedupe role the Cactus weights tag played before the
     * engine swap. The stored last-notified value on migrated devices is a
     * Cactus tag ("v2.0.1") or absent, so any non-colliding value fires
     * the migration notification exactly once; bump this string whenever a
     * catalog change forces reinstalls.
     */
    const val GENERATION = "whisper-1"

    /** The whisper.cpp author's model conversions, source of every speech model. */
    const val HF_REPO = "ggerganov/whisper.cpp"

    /** The immutable revision of [HF_REPO] every speech model URL resolves. */
    const val HF_REPO_COMMIT = "5359861c739e955e79d9a303bcbc70fb988958b1"

    /** The whisper.cpp project's Silero VAD conversions. */
    const val VAD_REPO = "ggml-org/whisper-vad"

    /** The immutable revision of [VAD_REPO] the detector URL resolves. */
    const val VAD_REPO_COMMIT = "9ffd54a1e1ee413ddf265af9913beaf518d1639b"

    private const val GIB = 1024L * 1024L * 1024L
    private const val MIB = 1024L * 1024L

    /**
     * Total-device-RAM floor for recommending the small tier over base.
     * The small models cost several hundred MB of native heap while
     * loaded; on tighter devices the base tier is the safer default, and
     * the user can still pick any entry manually.
     */
    const val STANDARD_TIER_MIN_TOTAL_RAM: Long = 4 * GIB

    // Values derived 2026-08-09 (small, base) and 2026-09-01 (tiny) via
    // the three-source procedure above. List order is the order the model
    // picker shows. No large tier: at the pinned engine revision the
    // large-v3-turbo q5_0 quant faulted in native inference on the test
    // device, and its encoder cost cannot meet the watch dictation window
    // on phone-class CPUs (details in KNOWN_ISSUES); revisit at the next
    // engine re-pin. The tiny tier is the floor: watch dictation has a
    // fixed 15 second budget from the firmware, and a phone whose CPU
    // cannot decode a full window with base inside it still gets a local
    // option. RAM never recommends tiny; it is a user's or a speed-based
    // step-down's pick.
    val MODELS: List<WhisperModel> = listOf(
        WhisperModel(
            id = "whisper-small",
            displayName = "Whisper Small (multilingual)",
            fileName = "ggml-small.bin",
            sha256 = "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
            sizeBytes = 487_601_967,
            minRamBytes = 2 * GIB,
            multilingual = true,
        ),
        WhisperModel(
            id = "whisper-small-en",
            displayName = "Whisper Small (English only)",
            fileName = "ggml-small.en.bin",
            sha256 = "c6138d6d58ecc8322097e0f987c32f1be8bb0a18532a3f88f734d1bbf9c41e5d",
            sizeBytes = 487_614_201,
            minRamBytes = 2 * GIB,
            multilingual = false,
        ),
        WhisperModel(
            id = "whisper-base",
            displayName = "Whisper Base (multilingual)",
            fileName = "ggml-base.bin",
            sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
            sizeBytes = 147_951_465,
            minRamBytes = 1 * GIB,
            multilingual = true,
        ),
        WhisperModel(
            id = "whisper-base-en",
            displayName = "Whisper Base (English only)",
            fileName = "ggml-base.en.bin",
            sha256 = "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002",
            sizeBytes = 147_964_211,
            minRamBytes = 1 * GIB,
            multilingual = false,
        ),
        WhisperModel(
            id = "whisper-tiny",
            displayName = "Whisper Tiny (multilingual)",
            fileName = "ggml-tiny.bin",
            sha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
            sizeBytes = 77_691_713,
            minRamBytes = 1 * GIB,
            multilingual = true,
        ),
        WhisperModel(
            id = "whisper-tiny-en",
            displayName = "Whisper Tiny (English only)",
            fileName = "ggml-tiny.en.bin",
            sha256 = "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f",
            sizeBytes = 77_704_715,
            minRamBytes = 1 * GIB,
            multilingual = false,
        ),
    )

    /**
     * Silero voice activity detector in whisper.cpp's ggml conversion
     * (MIT). Installed alongside every speech model and handed to the
     * engine so silence is trimmed before decoding and silent sessions
     * are rejected without an encoder pass. Values derived 2026-09-01 via
     * the three-source procedure; the v6.2.0 conversion is the one the
     * engine's own test suite exercises at the pinned revision.
     */
    val VAD_MODEL = WhisperModel(
        id = "vad-silero",
        displayName = "Silero voice activity detector",
        fileName = "ggml-silero-v6.2.0.bin",
        sha256 = "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987",
        sizeBytes = 885_098,
        minRamBytes = 64 * MIB,
        multilingual = true,
        repo = VAD_REPO,
        commit = VAD_REPO_COMMIT,
    )

    val ids: Set<String> = MODELS.map { it.id }.toSet()

    /** Speech models only; the detector is not a pickable model (see [VAD_MODEL]). */
    fun byId(id: String): WhisperModel? = MODELS.firstOrNull { it.id == id }

    fun isVadModelId(id: String): Boolean = id == VAD_MODEL.id

    fun urlFor(model: WhisperModel): String =
        "https://huggingface.co/${model.repo}/resolve/${model.commit}/${model.fileName}"

    /**
     * The default pick for a device together with the tier that decision
     * belongs to: base (Lite) tier under [STANDARD_TIER_MIN_TOTAL_RAM] of
     * total RAM, small (Standard) tier above it, and the English-only
     * variant when the device language is English (the .en models are more
     * accurate for English at the same size). Returning the tier alongside
     * the model keeps the tier the single decision made here: callers that
     * need the Lite/Standard label read [WhisperRecommendation.standardTier]
     * instead of re-comparing RAM, so the label and the model can never
     * disagree.
     */
    fun recommended(totalRamBytes: Long, preferEnglishOnly: Boolean): WhisperRecommendation {
        val standardTier = totalRamBytes >= STANDARD_TIER_MIN_TOTAL_RAM
        val id = when {
            standardTier && preferEnglishOnly -> "whisper-small-en"
            standardTier -> "whisper-small"
            preferEnglishOnly -> "whisper-base-en"
            else -> "whisper-base"
        }
        return WhisperRecommendation(byId(id) ?: MODELS.last(), standardTier)
    }
}

/** A recommended model and the tier the recommendation placed it in. */
data class WhisperRecommendation(val model: WhisperModel, val standardTier: Boolean)
