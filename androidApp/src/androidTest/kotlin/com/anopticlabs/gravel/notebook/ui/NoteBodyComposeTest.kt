package com.anopticlabs.gravel.notebook.ui

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performFirstLinkClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The note page's body composed on the device: a tapped link reaches only the page's handler, and
 * bodies that once broke the lazy list or dropped text render; the activity these tests run in is
 * not exported from the debug build.
 *
 * Run with:
 * adb shell am instrument -w -e class com.anopticlabs.gravel.notebook.ui.NoteBodyComposeTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class NoteBodyComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(body: String, onLink: (String) -> Unit = {}) {
        compose.setContent { NoteBody(body, onLink) }
    }

    private fun waitFor(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun aTappedLinkReachesOnlyThePageHandlerWithItsOwnDestination() {
        val tapped = mutableListOf<String>()
        show("[https://old.example](https://new.example) then [site](https://old.example)") { tapped += it }
        waitFor("then site")
        compose.onNodeWithText("then site", substring = true).performFirstLinkClick { it.item.toString().contains("old.example") }
        compose.waitForIdle()
        assertEquals(listOf("https://old.example"), tapped)
    }

    @Test
    fun aBodyWithAnEmptyTopLevelNodeRendersInsteadOfCrashing() {
        show("- <!-- todo\n\n \nmore")
        waitFor("more")
    }

    @Test
    fun htmlBlocksLinkDefinitionsAndImagesShowAsWritten() {
        show("> <div>\n> hi\n> </div>\n\n[todo]: call-mom\n\n![pic](https://example.org/p.png)\n\nend")
        waitFor("end")
        compose.onNodeWithText("call-mom", substring = true).assertExists()
        compose.onNodeWithText("![pic](https://example.org/p.png)", substring = true).assertExists()
        compose.onNodeWithText("hi", substring = true).assertExists()
    }

    @Test
    fun theActivityTheseTestsRunInIsNotExported() {
        assertFalse(testHostActivity().exported)
    }

    private fun testHostActivity(): ActivityInfo {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = ComponentName(context, ComponentActivity::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getActivityInfo(name, PackageManager.ComponentInfoFlags.of(0))
        } else {
            // The int overload is deprecated from API 33; minSdk is below that.
            @Suppress("DEPRECATION")
            context.packageManager.getActivityInfo(name, 0)
        }
    }
}
