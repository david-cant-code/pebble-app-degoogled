package com.anopticlabs.gravel.notebook.pipeline

import com.anopticlabs.gravel.notebook.capture.ProcessingPause
import com.anopticlabs.gravel.notebook.capture.ProcessingRules
import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.capture.mayStart
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessingControlsTest {

    private val dir = Files.createTempDirectory("controls").toFile()
    private var interruptOn = true
    private var waitForPower = true
    private var plugged = false
    private var requests = 0
    private var route = SpeechRoute.PHONE
    private val interrupts = mutableListOf<Pair<Boolean, (QueuedCapture) -> Boolean>>()

    private fun controls(file: ProcessingPauseFile = ProcessingPauseFile(dir)) = ProcessingControls(
        file,
        interruptLong = { interruptOn },
        longWaitsForPower = { waitForPower },
        pluggedIn = { plugged },
        interrupt = { transcribingOnly, which ->
            pausedAtStop += pauseNow()
            interrupts += transcribingOnly to which
        },
        runAgain = { requests++ },
        speech = { route },
    ).also { controls = it }

    private lateinit var controls: ProcessingControls
    private val pausedAtStop = mutableListOf<ProcessingPause>()

    private fun pauseNow() = controls.pause.value

    private val short = QueuedCapture("short", "short.wav", 0, durationMillis = 5_000)
    private val long = QueuedCapture("long", "long.wav", 0, durationMillis = 60_000)
    private val watch = QueuedCapture("watch", "watch.wav", 0, fromWatch = true)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun aPauseOutlivesTheInstanceAndResumeClearsIt() {
        controls().pauseAll()
        assertEquals(ProcessingPause.ALL, controls().pause.value)
        controls().resume()
        assertEquals(ProcessingPause.NONE, controls().pause.value)
        assertEquals(1, requests)
    }

    @Test
    fun anUnreadablePauseFileMeansNoPause() {
        File(dir, "notebook_processing_pause").writeText("SOMETIMES")
        assertEquals(ProcessingPause.NONE, controls().pause.value)
    }

    @Test
    fun aPauseThatCannotBeSavedStillHoldsForThisInstance() {
        val notADirectory = File(dir, "file").apply { writeText("x") }
        val controls = controls(ProcessingPauseFile(notADirectory))
        controls.pauseLong()
        assertEquals(ProcessingPause.LONG, controls.pause.value)
        assertEquals(ProcessingPause.LONG, controls.rules().pause)
    }

    @Test
    fun pausingAllStopsTheRunningCaptureUnlessItIsFromTheWatch() {
        controls().pauseAll()
        val (transcribingOnly, which) = interrupts.single()
        assertFalse(transcribingOnly)
        assertTrue(which(short))
        assertTrue(which(long))
        assertFalse(which(watch))
    }

    @Test
    fun eachPauseIsInPlaceBeforeItsStop() {
        val controls = controls()
        controls.pauseLong()
        controls.pauseAll()
        assertEquals(listOf(ProcessingPause.LONG, ProcessingPause.ALL), pausedAtStop)
    }

    @Test
    fun pausingLongOnesStopsOnlyALongCaptureAndIsIgnoredWhileAllArePaused() {
        val controls = controls()
        controls.pauseLong()
        val (transcribingOnly, which) = interrupts.single()
        assertFalse(transcribingOnly)
        assertTrue(which(long))
        assertFalse(which(short))
        controls.pauseAll()
        controls.pauseLong()
        assertEquals(ProcessingPause.ALL, controls.pause.value)
        assertEquals(2, interrupts.size)
    }

    @Test
    fun aShortCaptureQueuedStopsALongOneOnlyWhileTranscribing() {
        plugged = true
        controls().captureQueued(short)
        val (transcribingOnly, which) = interrupts.single()
        assertTrue(transcribingOnly)
        assertTrue(which(long))
        assertFalse(which(short))
    }

    @Test
    fun aLongCaptureRunningOnBatteryUnderTheChargerRuleIsNotStopped() {
        controls().captureQueued(short)
        val (_, which) = interrupts.single()
        assertFalse(which(long), "it could not start again until the phone is plugged in")
        plugged = true
        assertTrue(which(long))
    }

    @Test
    fun aShortCaptureStopsALongOneOnTheServerEvenOnBatteryUnderTheChargerRule() {
        route = SpeechRoute.SERVER
        controls().captureQueued(short)
        val (transcribingOnly, which) = interrupts.single()
        assertTrue(transcribingOnly)
        assertTrue(which(long), "a recording on the server never waits for the charger")
    }

    @Test
    fun noStopForALongCaptureTheSettingOffOrAShortOneThatMayNotStart() {
        plugged = true
        controls().captureQueued(long)
        interruptOn = false
        controls().captureQueued(short)
        assertTrue(interrupts.isEmpty())
        interruptOn = true
        val paused = controls().apply { pauseAll() }
        interrupts.clear()
        paused.captureQueued(short)
        assertTrue(interrupts.isEmpty())
        paused.captureQueued(watch)
        assertEquals(1, interrupts.size)
    }

    @Test
    fun processNowLetsThoseLongCapturesStartOnBattery() {
        val controls = controls()
        assertEquals(ProcessingRules(longWaitsForPower = true, pluggedIn = false), controls.rules())
        controls.processNow(listOf("long"))
        assertEquals(setOf("long"), controls.rules().approved)
        assertEquals(1, requests)
    }

    @Test
    fun aPowerChangeAsksForARunOnlyWhileTheChargerRuleIsOn() {
        val controls = controls()
        controls.onPowerChanged()
        assertEquals(1, requests)
        waitForPower = false
        controls.onPowerChanged()
        assertEquals(1, requests)
    }

    @Test
    fun theRulesReadTheSpeechRouteAtEachUse() {
        val controls = controls()
        assertFalse(controls.rules().mayStart(long))
        route = SpeechRoute.SERVER
        assertTrue(controls.rules().mayStart(long))
    }
}
