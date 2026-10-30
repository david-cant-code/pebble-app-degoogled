package com.anopticlabs.gravel.network

import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WifiRouteTest {

    @Test
    fun unmeteredWifiAsTheDefaultIsTheRoute() {
        val route = WifiRoute<String>()
        assertNull(route.current())
        route.defaultChanged("home", DefaultKind.UNMETERED_WIFI)
        assertEquals("home", route.current())
        assertTrue(route.usable.value)
    }

    @Test
    fun unmeteredWifiThatIsNotTheDefaultIsNoRoute() {
        val route = WifiRoute<String>()
        route.wifiAvailable("cafe")
        route.defaultChanged("cellular", DefaultKind.OTHER)
        assertNull(route.current(), "the phone's own connection is mobile data")
        assertFalse(route.usable.value)
    }

    @Test
    fun aVpnIsTheRouteOnlyWhileUnmeteredWifiIsConnected() {
        val route = WifiRoute<String>()
        route.defaultChanged("vpn", DefaultKind.VPN_NOT_OFF_WIFI)
        assertNull(route.current())
        route.wifiAvailable("home")
        route.wifiAvailable("home-2")
        route.wifiLost("home")
        assertEquals("vpn", route.current(), "one Wi-Fi network is still connected")
        route.wifiLost("home-2")
        assertNull(route.current())
    }

    @Test
    fun aVpnOverAnotherUplinkIsNoRoute() {
        val route = WifiRoute<String>()
        route.wifiAvailable("home")
        route.defaultChanged("vpn", DefaultKind.OTHER)
        assertNull(route.current())
    }

    @Test
    fun onlyTheCurrentDefaultsLossEndsIt() {
        val route = WifiRoute<String>()
        route.defaultChanged("home", DefaultKind.UNMETERED_WIFI)
        route.defaultLost("old")
        assertEquals("home", route.current())
        route.defaultLost("home")
        assertNull(route.current())
        assertFalse(route.usable.value)
    }

    @Test
    fun aRefusingRouteMakesNoSocket() {
        var attached = 0
        val factory = RouteBoundSocketFactory<String>({ null }) { _, _ -> attached++ }
        assertFailsWith<NotOnAllowedNetworkException> { factory.createSocket() }
        assertFailsWith<NotOnAllowedNetworkException> { factory.createSocket(InetAddress.getLoopbackAddress(), 9) }
        assertEquals(0, attached)
    }

    @Test
    fun aHostNameIsNeverResolvedBesideTheRule() {
        val factory = RouteBoundSocketFactory({ "home" }) { _, _ -> }
        assertFailsWith<UnsupportedOperationException> { factory.createSocket("localhost", 9) }
        assertFailsWith<UnsupportedOperationException> { factory.createSocket("localhost", 9, InetAddress.getLoopbackAddress(), 0) }
    }

    @Test
    fun aRouteChangeClosesTheSocketsItLeftAndOnlyThose() {
        val rule = WifiRule<String>(attach = { _, _ -> }, lookup = { _, _ -> emptyList() })
        assertTrue(rule.changed { rule.route.defaultChanged("home", DefaultKind.UNMETERED_WIFI) })
        val socket = rule.sockets.createSocket()
        assertFalse(rule.changed { rule.route.defaultChanged("home", DefaultKind.UNMETERED_WIFI) }, "the same network again")
        assertFalse(socket.isClosed, "a capabilities update for the route's own network")
        assertTrue(rule.changed { rule.route.defaultChanged("cellular", DefaultKind.OTHER) })
        assertTrue(socket.isClosed)
    }

    @Test
    fun aVpnLeavingWifiClosesItsSockets() {
        val rule = WifiRule<String>(attach = { _, _ -> }, lookup = { _, _ -> emptyList() })
        rule.changed { rule.route.wifiAvailable("home") }
        rule.changed { rule.route.defaultChanged("vpn", DefaultKind.VPN_NOT_OFF_WIFI) }
        val socket = rule.sockets.createSocket()
        rule.changed { rule.route.defaultChanged("vpn", DefaultKind.OTHER) }
        assertTrue(socket.isClosed)
    }

    @Test
    fun socketsOffTheRouteAreClosed() {
        var network: String? = "home"
        val factory = RouteBoundSocketFactory({ network }) { _, _ -> }
        val onHome = factory.createSocket()
        network = "vpn"
        val onVpn = factory.createSocket()
        factory.closeOffRoute("vpn")
        assertTrue(onHome.isClosed, "a socket on the Wi-Fi the VPN replaced")
        assertFalse(onVpn.isClosed)
        network = null
        factory.closeOffRoute(null)
        assertTrue(onVpn.isClosed)
    }

    @Test
    fun aRouteThatChangesWhileTheSocketIsMadeRefusesIt() {
        var network: String? = "home"
        lateinit var made: Socket
        val factory = RouteBoundSocketFactory({ network }) { _, socket -> made = socket; network = null }
        assertFailsWith<NotOnAllowedNetworkException> { factory.createSocket() }
        assertTrue(made.isClosed)
    }

    @Test
    fun aLookupWithNoRouteIsARefusalByTheRule() {
        val dns = RouteBoundDns<String>({ null }) { _, _ -> error("looked up") }
        val failure = assertFailsWith<UnknownHostException> { dns.lookup("stt.test") }
        assertTrue(failure.cause is NotOnAllowedNetworkException)
        val looked = ArrayList<Pair<String, String>>()
        RouteBoundDns({ "home" }) { n, host -> looked += n to host; emptyList() }.lookup("stt.test")
        assertEquals(listOf("home" to "stt.test"), looked)
    }

    @Test
    fun aVpnHasANetworkUnderItOnlyWhenItShowsOneWhereVpnsShowTheirs() {
        assertTrue(hasUplink(vpn = false, uplink = false, vpnShowsUplinks = true), "a network that is not a VPN")
        assertTrue(hasUplink(vpn = true, uplink = true, vpnShowsUplinks = true))
        assertFalse(hasUplink(vpn = true, uplink = false, vpnShowsUplinks = true), "a VPN with nothing under it")
        assertTrue(hasUplink(vpn = true, uplink = false, vpnShowsUplinks = false), "where a VPN may show nothing")
    }

    @Test
    fun theDefaultNetworkIsJudgedByWhatItShows() {
        fun kind(
            vpn: Boolean = false, wifi: Boolean = false, otherUplink: Boolean = false,
            notMetered: Boolean = true, captive: Boolean = false, vpnShowsUplinks: Boolean = true,
        ) = defaultKindOf(vpn, wifi, otherUplink, notMetered, captive, vpnShowsUplinks)

        assertEquals(DefaultKind.UNMETERED_WIFI, kind(wifi = true))
        assertEquals(DefaultKind.OTHER, kind(wifi = true, notMetered = false), "Wi-Fi marked metered")
        assertEquals(DefaultKind.OTHER, kind(wifi = true, captive = true), "a sign-in page")
        assertEquals(DefaultKind.OTHER, kind(otherUplink = true), "mobile data")
        assertEquals(DefaultKind.VPN_NOT_OFF_WIFI, kind(vpn = true, wifi = true, notMetered = false), "a VPN keeping the metered default")
        assertEquals(DefaultKind.OTHER, kind(vpn = true, wifi = true, otherUplink = true), "a VPN over Wi-Fi and mobile data")
        assertEquals(DefaultKind.OTHER, kind(vpn = true), "a VPN showing no uplink where VPNs show theirs")
        assertEquals(DefaultKind.VPN_NOT_OFF_WIFI, kind(vpn = true, vpnShowsUplinks = false), "a VPN before Android 10")
        assertEquals(DefaultKind.OTHER, kind(vpn = true, otherUplink = true, vpnShowsUplinks = false))
    }

    @Test
    fun eachSocketIsAttachedToTheRouteOfItsMoment() {
        var network = "home"
        val attached = ArrayList<Pair<String, Socket>>()
        val factory = RouteBoundSocketFactory({ network }) { n, socket -> attached += n to socket }
        val first = factory.createSocket()
        network = "vpn"
        val second = factory.createSocket()
        assertEquals(listOf("home", "vpn"), attached.map { it.first })
        assertSame(first, attached[0].second)
        assertFalse(first.isConnected)
        listOf(first, second).forEach(Socket::close)
    }

    @Test
    fun aSocketThatCannotBeAttachedIsClosed() {
        var made: Socket? = null
        val factory = RouteBoundSocketFactory({ "home" }) { _, socket ->
            made = socket
            throw java.io.IOException("bind refused")
        }
        assertFailsWith<java.io.IOException> { factory.createSocket() }
        assertTrue(made!!.isClosed)
    }
}
