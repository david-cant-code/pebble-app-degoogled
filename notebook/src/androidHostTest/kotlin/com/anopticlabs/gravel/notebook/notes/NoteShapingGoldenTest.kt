package com.anopticlabs.gravel.notebook.notes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Golden vectors for the note-shaping helpers, in `resources/golden`. */
class NoteShapingGoldenTest {

    private fun golden(name: String): JsonArray {
        val stream = checkNotNull(javaClass.getResourceAsStream("/golden/$name")) { "missing golden fixture $name" }
        val text = stream.reader(Charsets.UTF_8).use { it.readText() }
        return Json.parseToJsonElement(text).jsonArray
    }

    private fun JsonElement.toStringList(): List<String> = jsonArray.map { it.jsonPrimitive.content }

    private fun JsonElement.toStringListMap(): Map<String, List<String>> =
        jsonObject.entries.associateTo(LinkedHashMap()) { (k, v) -> k to v.toStringList() }

    @Test
    fun slugify() {
        val rows = golden("slugify.json")
        assertTrue(rows.isNotEmpty())
        for (row in rows) {
            val o = row.jsonObject
            val input = o.getValue("in").jsonPrimitive.content
            assertEquals(o.getValue("out").jsonPrimitive.content, NoteSlug.slugify(input), "slugify($input)")
        }
    }

    @Test
    fun sanitizeTags() {
        for (row in golden("sanitize_tags.json")) {
            val o = row.jsonObject
            assertEquals(o.getValue("out").toStringList(), NoteSanitizers.sanitizeTags(o["in"]), "sanitizeTags(${o["in"]})")
        }
    }

    @Test
    fun normalizeTagListWithCap() {
        for (row in golden("normalize_tag_list_cap8.json")) {
            val o = row.jsonObject
            val cap = o.getValue("cap").jsonPrimitive.content.toInt()
            assertEquals(o.getValue("out").toStringList(), NoteSanitizers.normalizeTagList(o["in"], cap), "normalizeTagList(${o["in"]}, $cap)")
        }
    }

    @Test
    fun sanitizeFolderName() {
        for (row in golden("sanitize_folder_name.json")) {
            val o = row.jsonObject
            val input = o.getValue("in").jsonPrimitive.content
            assertEquals(o.getValue("out").jsonPrimitive.content, NoteSanitizers.sanitizeFolderName(input), "sanitizeFolderName($input)")
        }
    }

    @Test
    fun sanitizeFolderTags() {
        for (row in golden("sanitize_folder_tags.json")) {
            val o = row.jsonObject
            val actual = NoteSanitizers.sanitizeFolderTags(o["in"])
            assertEquals(o.getValue("out").toStringListMap(), actual, "sanitizeFolderTags(${o["in"]})")
            assertEquals(o.getValue("out").toStringListMap().keys.toList(), actual.keys.toList(), "key order")
        }
    }

    @Test
    fun yamlSafe() {
        for (row in golden("yaml_safe.json")) {
            val o = row.jsonObject
            val input = o.getValue("in").jsonPrimitive.content
            assertEquals(o.getValue("out").jsonPrimitive.content, NoteMarkdown.yamlSafe(input), "yamlSafe($input)")
        }
    }

    @Test
    fun buildMarkdown() {
        for (row in golden("build_markdown.json")) {
            val o = row.jsonObject
            val actual = NoteMarkdown.buildMarkdown(
                title = o.getValue("title").jsonPrimitive.content,
                tags = o.getValue("tags").toStringList(),
                content = o.getValue("content").jsonPrimitive.content,
                date = o.getValue("date").jsonPrimitive.content,
            )
            assertEquals(o.getValue("out").jsonPrimitive.content, actual, "buildMarkdown(${o["title"]})")
        }
    }

    @Test
    fun renderedTagsReadBack() {
        val tags = listOf("work", "ideas")
        val md = NoteMarkdown.buildMarkdown("Round Trip", tags, "body", "2026-05-31")
        assertEquals(tags, FrontmatterTags.parseFrontmatterTags(md))
    }
}
