package com.anopticlabs.gravel.firmware

import co.touchlab.kermit.Logger
import coredevices.pebble.firmware.GithubReleases
import coredevices.pebble.firmware.ReleaseTagVersion
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/**
 * The PebbleOS versions Core's public changelog names, as the "PebbleOS changelog list"
 * workflow publishes them on the `firmware-list` branch (DESIGN_NOTES.md).
 */
data class PebbleOsChangelogList(
    val checkedAt: Instant,
    /** Newest first. */
    val versions: List<ReleaseTagVersion>,
)

sealed class ChangelogListResult {
    data class Success(val list: PebbleOsChangelogList) : ChangelogListResult()
    data object RateLimited : ChangelogListResult()
    data object Unreadable : ChangelogListResult()
}

class PebbleOsChangelogListSource(private val httpClient: HttpClient) {
    private val logger = Logger.withTag("PebbleOsChangelogList")

    suspend fun fetch(): ChangelogListResult = try {
        httpClient.prepareGet(LIST_URL) {
            parameter("ref", LIST_BRANCH)
            header(HttpHeaders.Accept, "application/vnd.github.raw+json")
            header("X-GitHub-Api-Version", GithubReleases.GITHUB_API_VERSION)
        }.execute { response ->
            when {
                response.status == HttpStatusCode.Forbidden ||
                    response.status == HttpStatusCode.TooManyRequests -> {
                    logger.w { "Changelog list fetch rate limited: ${response.status}" }
                    ChangelogListResult.RateLimited
                }
                !response.status.isSuccess() -> {
                    logger.w { "Changelog list fetch failed: ${response.status}" }
                    ChangelogListResult.Unreadable
                }
                else -> {
                    val body = response.bodyAsChannel().readRemaining(MAX_LIST_BYTES + 1L).readByteArray()
                    val list = if (body.size > MAX_LIST_BYTES) null else parsePebbleOsChangelogList(body.decodeToString())
                    if (list == null) {
                        logger.w { "Changelog list is malformed or too large (${body.size} bytes read)" }
                        ChangelogListResult.Unreadable
                    } else {
                        ChangelogListResult.Success(list)
                    }
                }
            }
        }
    } catch (e: IOException) {
        logger.w(e) { "Network error fetching the changelog list" }
        ChangelogListResult.Unreadable
    }

    private companion object {
        const val LIST_URL =
            "https://api.github.com/repos/david-cant-code/pebble-app-degoogled/contents/pebbleos-changelog.json"
        const val LIST_BRANCH = "firmware-list"
        const val MAX_LIST_BYTES = 64 * 1024
    }
}

private const val SCHEMA = "1"
private const val SOURCE = "https://ndocs.repebble.com/pebbleos-changelog"
private val LIST_KEYS = setOf("schema", "source", "checkedAt", "versions")
private val LISTED_VERSION = Regex("""(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})""")
private val TIMESTAMP = Regex("""[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z""")

// Python's datetime, which check_listed parses with, starts at year 1.
private val EARLIEST_CHECKED_AT = Instant.parse("0001-01-01T00:00:00Z")

// A list is an object holding one array. Nested arrays recurse on the thread stack
// (kotlinx-serialization 1.11.0, JsonTreeReader.readArray), so a deeper body is refused before
// parsing.
private const val MAX_NESTING = 2

/**
 * Applies the rules of the workflow's `check_listed` (.github/changelog/pebbleos_changelog.py)
 * and also requires at least one version; null when the text fails any of them.
 */
internal fun parsePebbleOsChangelogList(text: String): PebbleOsChangelogList? {
    if (nestsDeeperThan(text, MAX_NESTING)) return null
    val root = try {
        Json.parseToJsonElement(text)
    } catch (e: SerializationException) {
        return null
    } as? JsonObject ?: return null
    if (root.keys != LIST_KEYS) return null
    val schema = root["schema"] as? JsonPrimitive ?: return null
    if (schema.isString || schema.content != SCHEMA) return null
    if (root.stringField("source") != SOURCE) return null
    val checkedAtText = root.stringField("checkedAt")?.takeIf { TIMESTAMP.matches(it) } ?: return null
    val checkedAt = runCatching { Instant.parse(checkedAtText) }.getOrNull()
        ?.takeIf { it >= EARLIEST_CHECKED_AT } ?: return null
    val versions = (root["versions"] as? JsonArray ?: return null).map { element ->
        val version = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (!LISTED_VERSION.matches(version)) return null
        ReleaseTagVersion.from(version) ?: return null
    }
    if (versions.isEmpty() || versions != versions.distinct().sortedDescending()) return null
    return PebbleOsChangelogList(checkedAt, versions)
}

private fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun nestsDeeperThan(text: String, limit: Int): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in text) {
        when {
            escaped -> escaped = false
            inString -> when (c) {
                '\\' -> escaped = true
                '"' -> inString = false
            }
            c == '"' -> inString = true
            c == '[' || c == '{' -> if (++depth > limit) return true
            c == ']' || c == '}' -> depth--
        }
    }
    return false
}
