package com.anopticlabs.gravel.models

import coredevices.util.models.WhisperModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechModelInstallsTest {

    private val installs = SpeechModelInstalls()

    @Test
    fun eachSpeechModelDownloadIsCounted() {
        assertEquals(0, installs.count.value)
        installs.downloaded(WhisperModelCatalog.MODELS.first().id)
        assertEquals(1, installs.count.value)
        installs.downloaded(WhisperModelCatalog.MODELS.last().id)
        assertEquals(2, installs.count.value)
    }

    @Test
    fun aTextModelDownloadIsNotCounted() {
        installs.downloaded(LlmModelCatalog.MODELS.first().id)
        assertEquals(0, installs.count.value)
    }
}
