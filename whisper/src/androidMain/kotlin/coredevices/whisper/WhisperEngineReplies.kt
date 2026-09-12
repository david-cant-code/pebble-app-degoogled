package coredevices.whisper

/**
 * The pure decisions [WhisperEngineClient] makes over values it has
 * already read from an engine reply, kept apart from the Parcel code so
 * the host suite can feed them hostile values: the engine process parses
 * untrusted model bytes, so everything it sends back is untrusted, and
 * these are the checks that stand between its reply and the app.
 */

/**
 * The sample count to record for a transcribe call: the engine's echo
 * [reported] only when it is the count this process sent ([sent]) or the
 * -1 that means the engine never reached its input; any other value is
 * a reply this process did not ask for and reads as unknown. The count
 * feeds the per-model speed record, so a forged one would steer the
 * model recommendation.
 */
internal fun boundedSampleEcho(reported: Int, sent: Int): Int =
    if (reported == -1 || reported == sent) reported else -1

/**
 * Engine-supplied text with every ISO control character replaced by a
 * question mark. The text lands in exception messages and in the
 * diagnostics lines of the log a user attaches to a report, and those
 * lines are one per line by contract, so an embedded line break from
 * the engine process must not be able to forge or split one.
 */
internal fun sanitizeEngineText(text: String): String {
    if (text.none { it.isISOControl() }) return text
    return buildString(text.length) {
        for (ch in text) append(if (ch.isISOControl()) '?' else ch)
    }
}
