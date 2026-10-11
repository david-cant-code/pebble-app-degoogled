package com.anopticlabs.gravel.ui

import com.russhwolf.settings.MapSettings
import coredevices.pebble.ui.NO_SPEECH_MODEL_MESSAGE
import coredevices.pebble.ui.deleteModelKeepingMode
import coredevices.pebble.ui.noSpeechModelMessage
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import coredevices.util.STTConfig
import coredevices.util.models.CactusSTTMode
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The model list's delete: the mode it leaves, the selection it leaves and what the screen says. */
class DeleteModelKeepingModeTest {

    private val tiny = "whisper-tiny-en"
    private val base = "whisper-base-en"
    private val small = "whisper-small-en"
    private val usesPhone = CactusSTTMode.entries.filter { it.usesLocalCactus() }
    private val plain = "No speech model is installed on this phone"
    private val toRebble = "No speech model is installed on this phone. Watch dictation uses Rebble"
    private val toServer = "No speech model is installed on this phone. Watch dictation uses your server"
    private val toServerOnWifi = "No speech model is installed on this phone. Watch dictation uses your server on Wi-Fi"

    /** What the list says once the last model is gone, with a server address set and the server kept off mobile data. */
    private val lastModelMessage = mapOf(
        CactusSTTMode.LocalOnly to plain,
        CactusSTTMode.RebbleFirst to toRebble,
        CactusSTTMode.RebbleFallback to toRebble,
        CactusSTTMode.RemoteFirst to toServerOnWifi,
        CactusSTTMode.LocalFirst to toServerOnWifi,
    )

    /** [start] is the speech settings before any delete. */
    private class Phone(val holder: CoreConfigHolder, val start: STTConfig, installed: List<String>) {
        val installed = installed.toMutableList()
        val deleted = mutableListOf<String>()
        var recommended = "whisper-small-en"
        var deleteRemoves = true

        val stt get() = holder.config.value.sttConfig

        fun delete(slug: String): String? = deleteModelKeepingMode(
            slug = slug,
            holder = holder,
            installed = { installed.toList() },
            delete = { deleted += it; if (deleteRemoves) installed -= it },
            recommended = { recommended },
        )
    }

    private fun phone(
        mode: CactusSTTMode,
        selected: String?,
        vararg installed: String,
        serverUrl: String? = "https://a.example.net/inference",
        serverOnMobileData: Boolean = false,
    ): Phone {
        val holder = CoreConfigHolder(defaultValue = CoreConfig(), settings = MapSettings(), json = Json { ignoreUnknownKeys = true })
        val start = STTConfig(
            mode = mode,
            modelName = selected,
            spokenLanguage = "de",
            serverUrl = serverUrl,
            serverOnMobileData = serverOnMobileData,
        )
        holder.update(holder.config.value.copy(sttConfig = start))
        return Phone(holder, start, installed.toList())
    }

    @Test
    fun deletingTheOnlyModelKeepsTheModeAndClearsTheSelection() {
        for (mode in usesPhone) {
            val phone = phone(mode, tiny, tiny)
            assertEquals(lastModelMessage.getValue(mode), phone.delete(tiny), "$mode")
            assertEquals(listOf(tiny), phone.deleted, "$mode")
            assertEquals(phone.start.copy(modelName = null), phone.stt, "$mode")
        }
    }

    @Test
    fun aServerModeWithoutAValidServerAddressNamesNoDestination() {
        val invalid = listOf(null, "   ", "http://a.example.net/inference", "https://user:pw@a.example.net/inference")
        for (mode in listOf(CactusSTTMode.RemoteFirst, CactusSTTMode.LocalFirst)) {
            for (url in invalid) {
                val phone = phone(mode, tiny, tiny, serverUrl = url)
                assertEquals(NO_SPEECH_MODEL_MESSAGE, phone.delete(tiny), "$mode $url")
                assertEquals(phone.start.copy(modelName = null), phone.stt, "$mode $url")
            }
        }
    }

    @Test
    fun aServerAllowedOnMobileDataIsNamedWithoutWifi() {
        for (mode in listOf(CactusSTTMode.RemoteFirst, CactusSTTMode.LocalFirst)) {
            assertEquals(toServer, phone(mode, tiny, tiny, serverOnMobileData = true).delete(tiny), "$mode")
        }
    }

    @Test
    fun aRebbleModeNamesRebbleWithoutAServerAddress() {
        for (mode in listOf(CactusSTTMode.RebbleFirst, CactusSTTMode.RebbleFallback)) {
            assertEquals(toRebble, phone(mode, tiny, tiny, serverUrl = null).delete(tiny), "$mode")
        }
    }

    @Test
    fun localOnlyAndTheModesWithoutAPhonePartGetThePlainText() {
        val plainModes = listOf(CactusSTTMode.LocalOnly, CactusSTTMode.RemoteOnly, CactusSTTMode.RebbleOnly, CactusSTTMode.PlatformOnly)
        for (mode in plainModes) {
            val stt = STTConfig(mode = mode, serverUrl = "https://a.example.net/inference", serverOnMobileData = true)
            assertEquals(plain, noSpeechModelMessage(stt), "$mode")
        }
    }

    /** The catalog lists small ahead of base. */
    @Test
    fun deletingTheSelectedModelSelectsTheRecommendedOneWhenItIsInstalled() {
        for (mode in usesPhone) {
            val phone = phone(mode, tiny, base, small, tiny).apply { recommended = base }
            assertEquals("Now using $base", phone.delete(tiny), "$mode")
            assertEquals(phone.start.copy(modelName = base), phone.stt, "$mode")
        }
    }

    @Test
    fun withoutTheRecommendedModelTheFirstInstalledOneInTheCatalogsOrderIsSelected() {
        val phone = phone(CactusSTTMode.LocalOnly, small, tiny, base, small)
        assertEquals("Now using $base", phone.delete(small))
        assertEquals(phone.start.copy(modelName = base), phone.stt)
    }

    @Test
    fun deletingAModelThatIsNotSelectedLeavesTheSettingsAlone() {
        val phone = phone(CactusSTTMode.LocalOnly, tiny, tiny, base)
        val before = phone.holder.config.value
        assertNull(phone.delete(base))
        assertEquals(listOf(base), phone.deleted)
        assertSame(before, phone.holder.config.value)
    }

    @Test
    fun aModeThatDoesNotUseThePhoneGetsNoMessage() {
        for (mode in CactusSTTMode.entries - usesPhone.toSet()) {
            val two = phone(mode, tiny, tiny, base).apply { recommended = base }
            assertNull(two.delete(tiny), "$mode")
            assertEquals(two.start.copy(modelName = base), two.stt, "$mode")
            val one = phone(mode, tiny, tiny)
            assertNull(one.delete(tiny), "$mode")
            assertEquals(one.start.copy(modelName = null), one.stt, "$mode")
        }
    }

    /** The selection can name a model that is not installed, one still downloading for example. */
    @Test
    fun theLastModelGoingIsSaidWhicheverModelIsSelected() {
        for (mode in usesPhone) {
            val phone = phone(mode, small, tiny)
            assertEquals(lastModelMessage.getValue(mode), phone.delete(tiny), "$mode")
            assertEquals(phone.start, phone.stt, "$mode")
        }
    }

    /** The list also deletes leftover directories that are not speech models, and lets one be selected. */
    @Test
    fun deletingSomethingThatWasNotAnInstalledSpeechModelSaysNothing() {
        val phone = phone(CactusSTTMode.LocalOnly, null)
        assertNull(phone.delete("leftover"))
        assertEquals(listOf("leftover"), phone.deleted)
        assertEquals(phone.start, phone.stt)

        val selected = phone(CactusSTTMode.LocalOnly, "leftover")
        assertNull(selected.delete("leftover"))
        assertEquals(selected.start.copy(modelName = null), selected.stt)
    }

    @Test
    fun aDeleteThatRemovedNothingChangesNothing() {
        val phone = phone(CactusSTTMode.LocalOnly, tiny, tiny, base).apply { deleteRemoves = false }
        val before = phone.holder.config.value
        assertNull(phone.delete(tiny))
        assertEquals(listOf(tiny), phone.deleted)
        assertSame(before, phone.holder.config.value)
    }
}
