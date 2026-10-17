package com.anopticlabs.gravel.ui

import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.QuickLaunchSetting
import io.rebble.libpebblecommon.database.entity.QuicklaunchWatchPref
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import kotlin.uuid.Uuid

/** What Settings and the guide offer to install over the watch app copy in the locker: the button, the row's hint and the question asked first. */
enum class WatchAppOffer(val label: String, val hint: String, val question: String) {
    INSTALL("Install", "Tap to install it.", "Install the Notebook watch app?"),
    UPDATE("Update", "Tap to update.", "Update the Notebook watch app?"),
    REPLACE("Replace", "Tap to replace it with Gravel's copy.", "Replace it with Gravel's copy?"),
}

internal object WatchAppText {
    const val UNVERIFIED = "A Notebook watch app that Gravel did not ship is installed. Its recordings are not saved."
    const val UNCHECKED = "The installed Notebook watch app could not be checked. Its recordings are not saved."
    private const val OWN_COPY_START = "If you installed your own build or a modified one intentionally, you can mark it as trusted with Trust This Copy"
    const val OWN_COPY = "$OWN_COPY_START in Settings > Notebook."

    /** [OWN_COPY] as Settings > Notebook words it, where the Trust This Copy row is on the same screen. */
    const val OWN_COPY_IN_SETTINGS = "$OWN_COPY_START."
    const val LATER = "You can also do this later in Settings > Notebook."

    fun download(bytes: Long): String = "Gravel downloads the watch app (${(bytes + 500) / 1000} KB) from GitHub and checks it before installing."
}

/** What is in the locker, or null while there is nothing to say: the status is unread, or nothing is installed and Gravel has no release. */
fun watchAppStatusLine(values: NotebookWatchAppValues): String? = when (values.status) {
    null -> null
    WatchAppStatus.NOT_INSTALLED -> "Not installed".takeIf { values.offered != null }
    WatchAppStatus.INSTALLED -> "Version ${values.installedVersion} is installed"
    WatchAppStatus.UPDATE_AVAILABLE -> "Version ${values.installedVersion} is installed. Version ${values.offered} is available."
    WatchAppStatus.USER_TRUSTED -> "A copy you marked as trusted is installed"
    WatchAppStatus.UNVERIFIED -> if (values.sha256 == null) WatchAppText.UNCHECKED else WatchAppText.UNVERIFIED
}

/** Whether Trust This Copy applies: an unverified copy whose digest was read, which is what a mark names. */
fun watchAppCanBeTrusted(values: NotebookWatchAppValues): Boolean = values.status == WatchAppStatus.UNVERIFIED && values.sha256 != null

/** Nothing while Gravel has no release, an action runs, the status is unread, or its newest release is the copy in the locker. */
fun watchAppOffer(values: NotebookWatchAppValues): WatchAppOffer? = when {
    values.offered == null || values.busy -> null
    else -> when (values.status) {
        WatchAppStatus.NOT_INSTALLED -> WatchAppOffer.INSTALL
        WatchAppStatus.UPDATE_AVAILABLE -> WatchAppOffer.UPDATE
        WatchAppStatus.USER_TRUSTED, WatchAppStatus.UNVERIFIED -> WatchAppOffer.REPLACE
        WatchAppStatus.INSTALLED, null -> null
    }
}

/** The Settings row's description: the status, how to trust an unverified copy and what a tap on [offer] does, every part but the last ending in a period. */
fun watchAppRowDescription(values: NotebookWatchAppValues, offer: WatchAppOffer? = watchAppOffer(values)): String {
    val parts = listOfNotNull(
        watchAppStatusLine(values),
        WatchAppText.OWN_COPY_IN_SETTINGS.takeIf { watchAppCanBeTrusted(values) },
        offer?.hint,
    )
    return parts.mapIndexed { index, part -> if (index < parts.lastIndex) part.removeSuffix(".") + "." else part }.joinToString(" ")
}

fun watchAppInLocker(values: NotebookWatchAppValues): Boolean = values.status != null && values.status != WatchAppStatus.NOT_INSTALLED

/** [sha256] whole, in groups of four with four groups to a line, to compare with a digest computed elsewhere. */
fun digestInFours(sha256: String): String = sha256.chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") }

/** One hold button in the Quick Launch picker: what it opens now, and whether that is the app being offered. */
data class QuickLaunchRow(val button: QuicklaunchWatchPref, val name: String, val opensNow: String, val opensThisApp: Boolean)

/** The four hold buttons a Quick Launch picker offers, in the watch's order. */
val HOLD_BUTTONS = listOf(QuicklaunchWatchPref.QlUp, QuicklaunchWatchPref.QlSelect, QuicklaunchWatchPref.QlDown, QuicklaunchWatchPref.QlBack)

/** [names] gives what an app UUID is called, null being no app; a button set to an app [names] lacks shows as "Another app". */
fun quickLaunchRows(prefs: List<WatchPreference<*>>, names: Map<Uuid?, String>, appId: Uuid): List<QuickLaunchRow> = HOLD_BUTTONS.map { button ->
    val setting = prefs.firstOrNull { it.pref == button }?.valueOrDefault() as? QuickLaunchSetting ?: button.defaultValue
    val opens = setting.uuid.takeIf { setting.enabled }
    QuickLaunchRow(button, button.displayName.removePrefix("Quick Launch: "), names[opens] ?: "Another app", opens == appId)
}

/** Whether a hold button in [prefs] opens the app [appId]. */
fun aHoldButtonOpens(prefs: List<WatchPreference<*>>, appId: Uuid): Boolean = quickLaunchRows(prefs, emptyMap(), appId).any { it.opensThisApp }

/**
 * Whether a Quick Launch button set on the phone reaches a watch: [settingsSync] is on and one of the connected
 * watches, given by their capabilities, takes watch settings from the phone. The watch's Quick Launch settings in
 * Settings are shown under the same two conditions (`rememberSettingsItemsState`, `anyWatchSupportsSettingsSync`).
 */
fun watchButtonsCanBeSet(connected: List<Set<ProtocolCapsFlag>>, settingsSync: Boolean): Boolean =
    settingsSync && connected.any { ProtocolCapsFlag.SupportsBlobDbVersion in it }
