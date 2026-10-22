package com.anopticlabs.gravel.transcription

import com.russhwolf.settings.Settings
import coredevices.util.CoreConfigHolder
import coredevices.util.transcription.validateServerUrl

private const val SETTLED_KEY = "server_on_mobile_data_settled"

/**
 * Runs once per install, at the first app start that has "Use the Server on Mobile Data": a server
 * already set up gets the switch on, so its dictation keeps working on mobile data as before. The
 * marker lives outside the config, which other code may rebuild.
 */
fun settleServerOnMobileData(holder: CoreConfigHolder, settings: Settings) {
    if (settings.hasKey(SETTLED_KEY)) return
    val config = holder.config.value
    val serverSetUp = config.sttConfig.serverUrl?.let { validateServerUrl(it) == null } == true
    if (serverSetUp) holder.update(config.copy(sttConfig = config.sttConfig.copy(serverOnMobileData = true)))
    settings.putBoolean(SETTLED_KEY, true)
}
