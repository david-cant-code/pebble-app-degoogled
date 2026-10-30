package com.anopticlabs.gravel.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import co.touchlab.kermit.Logger
import coredevices.util.transcription.NetworkBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dns
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import javax.net.SocketFactory

/**
 * The Wi-Fi-only rule from two callbacks: the app's default network, and the unmetered Wi-Fi itself,
 * since a VPN keeping VpnService.Builder's metered default reads metered on home Wi-Fi
 * (frameworks/base android16-release, Vpn.agentConnect). From Android 9 the default network is the
 * app's own, a VPN included (android-9.0.0_r1, ConnectivityService.requestNetwork); on 8.x it is
 * the system's, the one under any VPN (android-8.1.0_r1, same method), so sockets and lookups there
 * are left unbound.
 */
class AndroidUnmeteredWifi(private val context: Context) : UnmeteredWifi {
    private val sdk = Build.VERSION.SDK_INT
    private val rule = WifiRule<Network>(
        attach = { network, socket -> if (sdk >= Build.VERSION_CODES.P) network.bindSocket(socket) },
        lookup = { network, host ->
            if (sdk >= Build.VERSION_CODES.P) network.getAllByName(host).toList() else Dns.SYSTEM.lookup(host)
        },
    )
    private val route get() = rule.route
    override val usable: StateFlow<Boolean> get() = route.usable

    fun binding(): NetworkBinding = NetworkBinding(rule.sockets, rule.dns)

    /** Called once, at app start. */
    fun start() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        // A new request starts with NOT_VPN, so this matches the Wi-Fi itself, and a network that
        // stops matching gets onLost (Connectivity android16-release: NetworkRequest.Builder(),
        // NetworkCapabilities.DEFAULT_CAPABILITIES, ConnectivityService.processNewlyLostListenRequests).
        val unmeteredWifi = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            .build()
        connectivity.registerNetworkCallback(
            unmeteredWifi,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = changed { route.wifiAvailable(network) }
                override fun onLost(network: Network) = changed { route.wifiLost(network) }
            },
        )
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed {
                    route.defaultChanged(
                        network,
                        defaultKindOf(
                            vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                            otherUplink = OTHER_UPLINKS.any(caps::hasTransport),
                            notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                            captive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                            vpnShowsUplinks = sdk >= Build.VERSION_CODES.Q,
                        ),
                    )
                }
                override fun onLost(network: Network) = changed { route.defaultLost(network) }
            },
        )
    }

    private fun changed(change: () -> Unit) {
        if (rule.changed(change)) logger.i { "Wi-Fi-only rule: usable=${route.usable.value}" }
    }

    private companion object {
        val logger = Logger.withTag("AndroidUnmeteredWifi")
    }
}

/** Every public transport but Wi-Fi and VPN. */
private val OTHER_UPLINKS = listOf(
    NetworkCapabilities.TRANSPORT_CELLULAR,
    NetworkCapabilities.TRANSPORT_BLUETOOTH,
    NetworkCapabilities.TRANSPORT_ETHERNET,
    NetworkCapabilities.TRANSPORT_WIFI_AWARE,
    NetworkCapabilities.TRANSPORT_LOWPAN,
    NetworkCapabilities.TRANSPORT_USB,
    NetworkCapabilities.TRANSPORT_THREAD,
    NetworkCapabilities.TRANSPORT_SATELLITE,
)

/**
 * Gravel: whether the app's default network goes out over something besides a VPN, whatever the Wi-Fi-only rule says.
 * `ConnectivityManager.getActiveNetwork` is null when there is no default network or it is blocked for the caller
 * (Connectivity android16-release); what a VPN shows of the networks under it is as in [defaultKindOf].
 */
fun hasUplinkNetwork(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork ?: return false) ?: return false
    return hasUplink(
        vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        uplink = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || OTHER_UPLINKS.any(caps::hasTransport),
        vpnShowsUplinks = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
    )
}

/** A VPN with no network shown under it has none from Android 10; before, where it may show none, it counts as having one. */
internal fun hasUplink(vpn: Boolean, uplink: Boolean, vpnShowsUplinks: Boolean): Boolean = !vpn || uplink || !vpnShowsUplinks

internal enum class DefaultKind { UNMETERED_WIFI, VPN_NOT_OFF_WIFI, OTHER }

/** The route with the sockets and lookups held to it; every route change closes the sockets it left. */
internal class WifiRule<N>(attach: (N, Socket) -> Unit, lookup: (N, String) -> List<InetAddress>) {
    val route = WifiRoute<N>()
    val sockets = RouteBoundSocketFactory(route::current, attach)
    val dns = RouteBoundDns(route::current, lookup)

    /** Applies [change] and reports whether it changed [WifiRoute.usable]. */
    fun changed(change: () -> Unit): Boolean {
        val before = route.usable.value
        change()
        sockets.closeOffRoute(route.current())
        return route.usable.value != before
    }
}

/**
 * A VPN shows the transports of the networks it runs over from Android 10 (android-10.0.0_r1,
 * Vpn.updateCapabilities; Connectivity android16-release, ConnectivityService.applyUnderlyingCapabilities),
 * so there it must show Wi-Fi; before 10 it may show none (android-9.0.0_r1, Vpn.updateCapabilities;
 * android-8.1.0_r1, Vpn), which [vpnShowsUplinks] false accepts.
 */
internal fun defaultKindOf(
    vpn: Boolean,
    wifi: Boolean,
    otherUplink: Boolean,
    notMetered: Boolean,
    captive: Boolean,
    vpnShowsUplinks: Boolean,
): DefaultKind = when {
    vpn -> if (otherUplink || (vpnShowsUplinks && !wifi)) DefaultKind.OTHER else DefaultKind.VPN_NOT_OFF_WIFI
    wifi && notMetered && !captive -> DefaultKind.UNMETERED_WIFI
    else -> DefaultKind.OTHER
}

/** The rule's state; the Wi-Fi networks are a set, so a lost one ends only itself. */
internal class WifiRoute<N> {
    private val wifi = HashSet<N>()
    private var default: N? = null
    private var defaultKind = DefaultKind.OTHER
    private val _usable = MutableStateFlow(false)
    val usable: StateFlow<Boolean> = _usable.asStateFlow()

    @Synchronized
    fun current(): N? = default?.takeIf {
        defaultKind == DefaultKind.UNMETERED_WIFI || (defaultKind == DefaultKind.VPN_NOT_OFF_WIFI && wifi.isNotEmpty())
    }

    @Synchronized
    fun wifiAvailable(network: N) = update { wifi += network }

    @Synchronized
    fun wifiLost(network: N) = update { wifi -= network }

    @Synchronized
    fun defaultChanged(network: N, kind: DefaultKind) = update {
        default = network
        defaultKind = kind
    }

    @Synchronized
    fun defaultLost(network: N) = update { if (default == network) default = null }

    private fun update(change: () -> Unit) {
        change()
        _usable.value = current() != null
    }
}

/**
 * Sockets for the network [route] allows when each is made, refused when it allows none. A socket
 * whose network stops being the route is closed by [closeOffRoute], so an upload cannot carry on
 * once its Wi-Fi is gone, a VPN's included.
 */
internal class RouteBoundSocketFactory<N>(
    private val route: () -> N?,
    private val attach: (N, Socket) -> Unit,
) : SocketFactory() {
    private val live = HashMap<Socket, N>()

    override fun createSocket(): Socket {
        val network = route() ?: throw NotOnAllowedNetworkException()
        val socket = Socket()
        try {
            attach(network, socket)
        } catch (e: Throwable) {
            socket.close()
            throw e
        }
        synchronized(live) {
            live.keys.removeAll(Socket::isClosed)
            live[socket] = network
        }
        // A route change between the read above and the registration has already run closeOffRoute.
        val now = route()
        if (now != network) {
            closeOffRoute(now)
            throw NotOnAllowedNetworkException()
        }
        return socket
    }

    fun closeOffRoute(current: N?) {
        val stale = synchronized(live) {
            live.filter { (socket, network) -> socket.isClosed || network != current }.keys
                .also { live.keys.removeAll(it) }
        }
        stale.forEach { runCatching { it.close() } }
    }

    // A host name here would be resolved on the default network, outside RouteBoundDns.
    override fun createSocket(host: String, port: Int): Socket = throw UnsupportedOperationException("connect by address")

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        throw UnsupportedOperationException("connect by address")

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
}

/** Name lookups on the network [route] allows, refused like a socket when it allows none. */
internal class RouteBoundDns<N>(
    private val route: () -> N?,
    private val lookup: (N, String) -> List<InetAddress>,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val network = route()
            ?: throw UnknownHostException("no network the Wi-Fi-only rule allows").apply { initCause(NotOnAllowedNetworkException()) }
        return lookup(network, hostname)
    }
}
