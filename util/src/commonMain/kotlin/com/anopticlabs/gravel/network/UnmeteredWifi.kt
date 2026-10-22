package com.anopticlabs.gravel.network

import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.IOException

/** Whether the self-hosted server's Wi-Fi-only rule lets a request out now. */
interface UnmeteredWifi {
    val usable: StateFlow<Boolean>
}

/** A connection refused at the socket because the rule no longer allows the network. */
class NotOnAllowedNetworkException : IOException("no network the Wi-Fi-only rule allows")
