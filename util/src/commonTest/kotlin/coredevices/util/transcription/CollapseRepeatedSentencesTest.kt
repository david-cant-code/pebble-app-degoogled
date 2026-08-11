package coredevices.util.transcription

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Host tests for [collapseRepeatedSentences], the text-level backstop for
 * decoder repetition loops now that the engine runs without whisper's
 * temperature-fallback ladder. The pathological inputs mirror real outputs
 * captured on-device from degraded watch audio (thirteen consecutive copies
 * of the dictated phrase, punctuated and unpunctuated); the pass-through
 * inputs mirror real healthy dictations, including a capture that genuinely
 * contains its phrase twice.
 */
class CollapseRepeatedSentencesTest {

    @Test
    fun collapsesLongPunctuatedRun() {
        val spam = "Take out the trash. ".repeat(13).trim()
        assertEquals("Take out the trash.", collapseRepeatedSentences(spam))
    }

    @Test
    fun collapsesThresholdRun() {
        val spam = "Two dozen trash. Two dozen trash. Two dozen trash."
        assertEquals("Two dozen trash.", collapseRepeatedSentences(spam))
    }

    @Test
    fun keepsDoubleRepeat() {
        // A real capture contains its phrase twice; a double is plausible speech.
        val doubled = "Take out the trash. Take out the trash."
        assertEquals(doubled, collapseRepeatedSentences(doubled))
    }

    @Test
    fun collapsesUnpunctuatedRun() {
        val spam = "take out the trash take out the trash take out the trash take out the trash"
        assertEquals("take out the trash", collapseRepeatedSentences(spam))
    }

    @Test
    fun keepsUnpunctuatedDouble() {
        val doubled = "take out the trash take out the trash"
        assertEquals(doubled, collapseRepeatedSentences(doubled))
    }

    @Test
    fun collapseIsCaseAndSpacingInsensitive() {
        val spam = "Take out the trash.  take out the trash. TAKE OUT THE TRASH. Take out the trash."
        assertEquals("Take out the trash.", collapseRepeatedSentences(spam))
    }

    @Test
    fun collapsesRunButKeepsDistinctTail() {
        val mixed = "Same thing. Same thing. Same thing. Then something else."
        assertEquals("Same thing. Then something else.", collapseRepeatedSentences(mixed))
    }

    @Test
    fun dropsTruncatedLoopResidueAfterCollapsedRun() {
        // The per-segment token cap cuts loops mid-sentence (observed live).
        val spam = "Two dozen trash. Two dozen trash. Two dozen trash. Two"
        assertEquals("Two dozen trash.", collapseRepeatedSentences(spam))
    }

    @Test
    fun keepsPrefixTailWhenNoRunCollapsed() {
        // Without a collapsed run, a prefix-looking tail is plausibly speech.
        val doubled = "Buy apples. Buy apples. Buy"
        assertEquals(doubled, collapseRepeatedSentences(doubled))
    }

    @Test
    fun collapsesUnpunctuatedRunWithPartialTail() {
        val spam = "take out the trash take out the trash take out the trash take out"
        assertEquals("take out the trash", collapseRepeatedSentences(spam))
    }

    @Test
    fun leavesNormalSentencesAlone() {
        val normal = "Remind me to pick up the dry cleaning tomorrow afternoon."
        assertEquals(normal, collapseRepeatedSentences(normal))
    }

    @Test
    fun leavesMultiSentenceSpeechAlone() {
        val normal = "Add milk to the list. Also eggs. Also bread."
        assertEquals(normal, collapseRepeatedSentences(normal))
    }

    @Test
    fun passesThroughBlank() {
        assertEquals("", collapseRepeatedSentences(""))
        assertEquals("  ", collapseRepeatedSentences("  "))
    }

    /** Sentence-spacing-insensitive comparison: joins may normalize whitespace. */
    private fun assertEquals(expected: String, actual: String) {
        kotlin.test.assertEquals(
            expected.replace(Regex("\\s+"), " ").trim(),
            actual.replace(Regex("\\s+"), " ").trim(),
        )
    }
}
