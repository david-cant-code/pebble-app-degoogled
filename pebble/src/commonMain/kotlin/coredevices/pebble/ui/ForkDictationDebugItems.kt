package coredevices.pebble.ui

import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import coredevices.util.STTConfig
import coredevices.util.isDebugBuild
import io.rebble.libpebblecommon.voice.PEBBLE_FW_TRANSCRIPTION_TIMEOUT

/**
 * Fork: the settings toggles for the debug-only dictation test hooks (the
 * `debug*` fields of [STTConfig]), kept out of upstream's settings list so
 * the fork's insertion there is one line. Offered only when isDebugBuild()
 * is true, and the code that honours each flag re-checks the build.
 */
internal fun forkDictationDebugItems(coreConfig: CoreConfig, coreConfigHolder: CoreConfigHolder): List<SettingsItem> {
    // The window named in the copy is the firmware's result timeout the hooks are built around.
    val window = PEBBLE_FW_TRANSCRIPTION_TIMEOUT.inWholeSeconds
    fun toggle(title: String, description: String, checked: Boolean, update: STTConfig.(Boolean) -> STTConfig) =
        basicSettingsToggleItem(
            title = title,
            description = description,
            topLevelType = TopLevelType.Phone,
            section = Section.Speech,
            checked = checked,
            onCheckChanged = {
                coreConfigHolder.update(coreConfig.copy(sttConfig = coreConfig.sttConfig.update(it)))
            },
            isDebugSetting = true,
            show = { isDebugBuild() },
        )
    return listOf(
        toggle(
            title = "Dictation: single engine thread",
            description = "Run local speech recognition on one thread so a fast phone reproduces a decode that overruns the watch's $window second window.",
            checked = coreConfig.sttConfig.debugSingleThread,
        ) { copy(debugSingleThread = it) },
        toggle(
            title = "Dictation: use the bundled test clip",
            description = "Replace the watch's audio with a bundled speech clip, for an emulated watch or a silent room.",
            checked = coreConfig.sttConfig.debugSubstituteAudio,
        ) { copy(debugSubstituteAudio = it) },
        toggle(
            title = "Dictation: hold each result past the watch's window",
            description = "Delay every decode past the watch's $window second window so the deadline report can be watched on any phone.",
            checked = coreConfig.sttConfig.debugSlowDecode,
        ) { copy(debugSlowDecode = it) },
        toggle(
            title = "Dictation: keep audio captures",
            description = "Write each dictation's audio and codec frames under the app's private files (last 20 of each kept) for replay through the engine. Nothing is uploaded or backed up; the files are deleted when this is turned off.",
            checked = coreConfig.sttConfig.debugCaptureDump,
        ) { copy(debugCaptureDump = it) },
    )
}
