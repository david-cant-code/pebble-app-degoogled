package coredevices.ring.util.trace

import kotlinx.serialization.Serializable

/**
 * Fork-owned stand-in for the unplugged :experimental module's ring trace
 * exporter. BugReportProcessor looks this up with getOrNull and skips the
 * ring-trace attachment when absent; the stub is never bound in Koin, so
 * that lookup stays null and the code path is dead. It exists only so the
 * type reference compiles (same tripwire pattern as
 * coredevices.ExperimentalDevices).
 */
@Suppress("unused")
class TraceSessionExporter private constructor() {
    suspend fun exportLastNSessions(limit: Int, offset: Int = 0): List<TraceSessionDocument> =
        emptyList()
}

@Serializable
class TraceSessionDocument
