package com.anopticlabs.gravel.transcription

import coredevices.util.CoreConfigHolder
import coredevices.util.transcription.SelfHostedServerStore
import coredevices.util.transcription.serverHostPort

/** Gives a token an earlier build stored without an address to the configured server, or removes it without one; run at app start and when the server dialog opens. */
fun adoptUnboundServerToken(holder: CoreConfigHolder, store: SelfHostedServerStore) {
    store.adoptUnboundToken(holder.config.value.sttConfig.serverUrl?.let(::serverHostPort))
}
