package com.anopticlabs.gravel.notebook.llm

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** The string content of a JSON string value; null for any other kind of element. */
internal fun JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content
