package com.anopticlabs.gravel

import coredevices.coreapp.TrackedTree
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Gravel declares no auto-verified App Links. Android sends an app's auto-verified web hosts to
 * the phone's domain verification agent when the app is installed or updated (android16-release,
 * DomainVerificationService.addPackage and migrateState, DomainVerificationProxyV2.runMessage), and
 * AOSP's agent fetches each host's /.well-known/assetlinks.json (packages/StatementService,
 * StatementRetriever). Upstream's manifest carries one for its GitHub sign-in return link.
 *
 * Covers the tracked manifests outside iosApp and the engine submodules; manifests merged in from
 * Maven libraries are not covered.
 */
class AppLinkVerificationTest {

    @Test
    fun noManifestClaimsAVerifiedAppLink() {
        val manifests = TrackedTree.files
            .filter { it.endsWith("AndroidManifest.xml") && !it.startsWith("iosApp/") }
            .filter { path -> TrackedTree.submodulePaths.none { path.startsWith("$it/") } }
        assertTrue("androidApp/src/main/AndroidManifest.xml" in manifests, "the app manifest is not listed: $manifests")
        val claims = manifests.flatMap { path ->
            TrackedTree.file(path).inputStream().use(::parse).verifyClaims().map { "$path: $it" }
        }
        assertEquals(emptyList(), claims)
    }

    @Test
    fun anyValueButFalseCountsAsAClaim() {
        val claimed = listOf("true", "TRUE", " true ", "@bool/verify", "\${verify}")
        claimed.forEach { value -> assertEquals(1, sample("android:autoVerify=\"$value\"").size, value) }
        assertEquals(0, sample("android:autoVerify=\"false\"").size)
        assertEquals(0, sample("android:autoVerify=\" False \"").size)
        assertEquals(0, sample("").size)
    }

    @Test
    fun theAndroidNamespaceIsReadUnderAnyPrefix() {
        assertEquals(1, sample("x:autoVerify=\"true\" tools:autoVerify=\"false\"").size)
        assertEquals(0, sample("android:autoVerify=\"false\" tools:autoVerify=\"true\"").size)
    }

    private fun sample(attributes: String): List<String> = parse(
        """
            <manifest xmlns:android="$ANDROID" xmlns:x="$ANDROID" xmlns:tools="http://schemas.android.com/tools">
              <intent-filter $attributes />
            </manifest>
        """.trimIndent().byteInputStream(),
    ).verifyClaims()

    private fun Document.verifyClaims(): List<String> {
        val all = getElementsByTagName("*")
        return (0 until all.length).map { all.item(it) as Element }
            .filter { it.hasAttributeNS(ANDROID, "autoVerify") }
            .filter { !it.getAttributeNS(ANDROID, "autoVerify").trim().equals("false", ignoreCase = true) }
            .map { "<${it.tagName}> autoVerify=\"${it.getAttributeNS(ANDROID, "autoVerify")}\"" }
    }

    private fun parse(input: InputStream): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(input)

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
