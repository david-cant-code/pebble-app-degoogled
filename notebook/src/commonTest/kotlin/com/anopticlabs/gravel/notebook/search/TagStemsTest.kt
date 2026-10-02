package com.anopticlabs.gravel.notebook.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagStemsTest {

    @Test
    fun nullStaysNullAndEmptyStaysEmpty() {
        assertNull(computeStemmedTags(null))
        assertEquals(emptySet(), computeStemmedTags(emptyList()))
    }

    @Test
    fun tagsAreStemmedLowercasedAndDeduped() {
        assertEquals(setOf("shop", "errand", "exercis"), computeStemmedTags(listOf("Shopping", "Errands", "EXERCISE")))
        assertEquals(setOf("run", "ran"), computeStemmedTags(listOf("running", "runs", "ran")))
        assertEquals(setOf("left", "arm"), computeStemmedTags(listOf("left arm")))
        assertEquals(emptySet(), computeStemmedTags(listOf("the", "")))
    }

    @Test
    fun theOutputAndTheScanAreBounded() {
        val many = (1..MAX_STEMMED_TAGS_PER_NOTE + 40).map { "tag$it" }
        val stems = computeStemmedTags(many)!!
        assertEquals(MAX_STEMMED_TAGS_PER_NOTE, stems.size)
        assertTrue("tag1" in stems)

        val overshoot = (1 until MAX_STEMMED_TAGS_PER_NOTE).map { "tag$it" } + "alpha beta gamma"
        assertEquals(MAX_STEMMED_TAGS_PER_NOTE, computeStemmedTags(overshoot)!!.size)

        val longTag = (1..40).joinToString(" ") { "word$it" }
        val fromLongTag = computeStemmedTags(listOf(longTag))!!
        assertTrue(fromLongTag.size < 12, fromLongTag.toString())
        assertTrue("word1" in fromLongTag)

        val padded = List(MAX_SCANNED_TAG_ENTRIES) { "the" } + "budget"
        assertEquals(emptySet(), computeStemmedTags(padded))
        val justInside = List(MAX_SCANNED_TAG_ENTRIES - 1) { "the" } + "budget"
        assertEquals(setOf("budget"), computeStemmedTags(justInside))
    }
}
