package com.anopticlabs.gravel.network

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import coredevices.util.transcription.SelfHostedClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import org.koin.mp.KoinPlatform
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The Wi-Fi-only rule against the platform: the device's Wi-Fi as its own connection is usable and
 * binds sockets, a metered mark (also once the Wi-Fi has reconnected) or Wi-Fi switched off ends
 * that, and the app starts its tracker at launch, closes a socket left on a Wi-Fi it lost, and holds
 * its Wi-Fi-only server client to it. The device must be on Wi-Fi whose metered setting
 * is automatic, with an SSID free of spaces and quotes; the test fails otherwise. It puts both back
 * afterwards; run it over USB, since a run cut short leaves them changed (restore with `adb shell
 * svc wifi enable` and `adb shell cmd netpolicy set metered-network <ssid> undefined`).
 */
class UnmeteredWifiDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun followsTheWifiAsItIsMarkedMeteredAndSwitchedOff() = runBlocking {
        val ssid = wifiReady()
        val wifi = AndroidUnmeteredWifi(context).also { it.start() }
        try {
            await(wifi, usable = true, "on the Wi-Fi")
            wifi.binding().socketFactory.createSocket().close()
            shell("cmd netpolicy set metered-network $ssid true")
            await(wifi, usable = false, "with the Wi-Fi marked metered")
            awaitWifiConnected()
            delay(3.seconds)
            if (wifi.usable.value) fail("usable again once the Wi-Fi reconnected as metered")
            assertFailsWith<NotOnAllowedNetworkException> { wifi.binding().socketFactory.createSocket() }
            shell("cmd netpolicy set metered-network $ssid undefined")
            await(wifi, usable = true, "with the metered mark taken off")
            shell("svc wifi disable")
            await(wifi, usable = false, "with Wi-Fi switched off")
            assertFailsWith<NotOnAllowedNetworkException> { wifi.binding().socketFactory.createSocket() }
        } finally {
            shell("cmd netpolicy set metered-network $ssid undefined")
            shell("svc wifi enable")
        }
        await(wifi, usable = true, "with Wi-Fi back on", timeout = 60.seconds)
    }

    @Test
    fun theAppStartsItsTrackerAtLaunch() = runBlocking {
        wifiReady()
        await(KoinPlatform.getKoin().get<UnmeteredWifi>(), usable = true, "in the app's tracker")
    }

    @Test
    fun theAppsWifiOnlyClientIsHeldToItsTracker() = runBlocking {
        wifiReady()
        val wifi = KoinPlatform.getKoin().get<AndroidUnmeteredWifi>()
        val clients = KoinPlatform.getKoin().get<SelfHostedClientFactory>()
        await(wifi, usable = true, "in the app's tracker")
        val held = wifi.binding().socketFactory.createSocket()
        try {
            assertFalse(held.isClosed, "the socket was closed before the Wi-Fi went")
            shell("svc wifi disable")
            await(wifi, usable = false, "with Wi-Fi switched off")
            // usable turns false on the callback thread before that thread closes the sockets left off the route.
            withTimeoutOrNull(1.seconds) { while (!held.isClosed) delay(20) }
                ?: fail("a socket made on the Wi-Fi stayed open")
            assertTrue(refusedByRule(attempt(clients.create("127.0.0.1:9", { null }, wifiOnly = true, 20.seconds))))
            assertFalse(refusedByRule(attempt(clients.create("127.0.0.1:9", { null }, wifiOnly = false, 20.seconds))))
        } finally {
            held.close()
            shell("svc wifi enable")
        }
        await(wifi, usable = true, "with Wi-Fi back on", timeout = 60.seconds)
    }

    /** A request to a closed loopback port: it fails either way, refused by the rule or by the port. */
    private suspend fun attempt(client: HttpClient): Throwable =
        client.use { runCatching { it.get("https://127.0.0.1:9/") }.exceptionOrNull() } ?: fail("the request succeeded")

    private fun refusedByRule(failure: Throwable) =
        generateSequence(failure) { it.cause }.take(16).any { it is NotOnAllowedNetworkException }

    /** The connected SSID, after checking the preconditions; a test that failed before this one can have left the Wi-Fi reconnecting. */
    private suspend fun wifiReady(): String {
        withTimeoutOrNull(60.seconds) { while (!shell("cmd wifi status").contains("Wifi is connected to")) delay(500) }
        val ssid = Regex("Wifi is connected to \"(.*)\"").find(shell("cmd wifi status"))?.groupValues?.get(1)
            ?: fail("not on Wi-Fi, or `cmd wifi status` prints something else on this build")
        // executeShellCommand splits on whitespace and does not unquote.
        if (ssid.any { it.isWhitespace() || it == '"' }) fail("the SSID cannot be passed to the shell")
        val metered = shell("cmd netpolicy list wifi-networks").lines()
            .firstOrNull { it.substringBeforeLast(';') == ssid }?.substringAfterLast(';')
        if (metered != "none") fail("the Wi-Fi's metered setting is $metered; the test needs it automatic")
        return ssid
    }

    private suspend fun awaitWifiConnected() {
        withTimeoutOrNull(30.seconds) {
            while (!shell("cmd wifi status").contains("Wifi is connected to")) delay(500)
        } ?: fail("the Wi-Fi did not reconnect")
    }

    private suspend fun await(wifi: UnmeteredWifi, usable: Boolean, case: String, timeout: Duration = 15.seconds) {
        withTimeoutOrNull(timeout) { wifi.usable.first { it == usable } }
            ?: fail("usable stayed ${!usable} $case")
    }

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString().trim() }
    }
}
