package coredevices.coreapp.indexfeed

internal actual fun zipEntries(entries: List<Pair<String, ByteArray>>): ByteArray =
    error("Index recording export is Android-only")
