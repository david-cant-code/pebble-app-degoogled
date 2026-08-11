package coredevices.util.models

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

// This file outlives the Cactus engine it is named after, for two reasons:
// promoteSingleRootDir is still called by the iOS model downloader, and
// CactusSTTMode's entry names are persisted inside the serialized
// CoreConfig JSON on every existing install, so renaming the enum (or its
// entries) is a data migration, not a refactor.

fun promoteSingleRootDir(dir: Path) {
    val root = SystemFileSystem.list(dir).singleOrNull() ?: return
    if (SystemFileSystem.metadataOrNull(root)?.isDirectory != true) return
    SystemFileSystem.list(root).forEach { child ->
        SystemFileSystem.atomicMove(child, Path(dir, child.name))
    }
    SystemFileSystem.delete(root)
}

enum class CactusSTTMode(val id: Int) {
    RemoteOnly(0),
    LocalOnly(1),
    RemoteFirst(2),
    LocalFirst(3),
    RebbleOnly(4),
    RebbleFirst(5),
    RebbleFallback(6),

    /** OS-native on-device engine: Apple SpeechAnalyzer (iOS 26+) / ML Kit GenAI (Android). */
    PlatformOnly(7);

    companion object {
        fun fromId(id: Int): CactusSTTMode {
            return entries.firstOrNull { it.id == id } ?: RemoteOnly
        }
    }

    fun usesLocalCactus(): Boolean {
        return this in setOf(RemoteFirst, LocalOnly, LocalFirst, RebbleFirst, RebbleFallback)
    }
}