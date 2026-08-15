package coredevices.coreapp

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The two Wispr Flow logo drawables are compose resources of :pebble that
 * stay in the tree (an upstream sync would only put them back) but are kept
 * out of the APK by ignoreAssetsPattern in androidApp/build.gradle.kts. The
 * generated `Res.drawable` accessors for them still compile, so a Kotlin
 * reference (upstream's WatchSettingsScreen has one, in a settings row the
 * fork removed) would build, pass every test, and throw
 * MissingResourceException the first time the screen composed. This pins the
 * three facts that make the exclusion safe: no tracked Kotlin source names
 * the resources, the exclusion is still declared, and the drawables are
 * still tracked. If the drawables are ever deleted from :pebble, delete
 * this sentinel and the ignoreAssetsPattern entries with them.
 */
class ExcludedAssetSentinelTest {

    private val excludedDrawables = listOf("wispr_flow_logo_black", "wispr_flow_logo_white")

    @Test
    fun noKotlinSourceReferencesAnExcludedDrawable() {
        val offenders = TrackedTree.files
            .filter { it.endsWith(".kt") && !it.endsWith("/ExcludedAssetSentinelTest.kt") }
            .filter { path ->
                val text = TrackedTree.file(path).readText()
                excludedDrawables.any { text.contains(it) }
            }
        assertTrue(
            offenders.isEmpty(),
            "These sources reference a drawable that ignoreAssetsPattern drops from the APK, which " +
                "would throw MissingResourceException at runtime:\n" + offenders.joinToString("\n"),
        )
    }

    @Test
    fun theExclusionIsStillDeclared() {
        val build = TrackedTree.file("androidApp/build.gradle.kts").readText()
        excludedDrawables.forEach { name ->
            assertTrue(build.contains("!$name.png"), "androidApp/build.gradle.kts no longer excludes $name.png from the APK")
        }
    }

    @Test
    fun theExcludedDrawablesAreStillTracked() {
        excludedDrawables.forEach { name ->
            assertTrue(
                TrackedTree.files.any { it.endsWith("/composeResources/drawable/$name.png") },
                "$name.png is no longer in the tree; remove its ignoreAssetsPattern entry and this sentinel",
            )
        }
    }
}
