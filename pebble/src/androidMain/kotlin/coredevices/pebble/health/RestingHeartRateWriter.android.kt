package coredevices.pebble.health

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.resume

internal actual fun createRestingHeartRateWriter(appContext: AppContext): RestingHeartRateWriter =
    HealthConnectRestingHeartRateWriter(appContext.context.applicationContext)

private val WRITE_RESTING_HR = HealthPermission.getWritePermission(RestingHeartRateRecord::class)

private class HealthConnectRestingHeartRateWriter(private val context: Context) : RestingHeartRateWriter {
    private val logger = Logger.withTag("RestingHeartRateWriter")

    private val client: HealthConnectClient? by lazy {
        if (isSupported()) HealthConnectClient.getOrCreate(context) else null
    }

    override fun isSupported(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    override suspend fun isGranted(): Boolean {
        val client = client ?: return false
        return try {
            WRITE_RESTING_HR in client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            logger.w(e) { "Failed to read granted permissions" }
            false
        }
    }

    override suspend fun requestPermission(): Boolean {
        if (!isSupported()) return false
        if (isGranted()) return true
        return RestingHeartRatePermissionActivity.request(context)
    }

    override suspend fun write(entries: List<RestingHeartRateEntry>): Result<Unit> = runCatching {
        val client = checkNotNull(client) { "Health Connect not available" }
        val version = System.currentTimeMillis()
        val records = entries.map { entry ->
            val time = Instant.ofEpochSecond(entry.wakeEpochSec)
            RestingHeartRateRecord(
                time = time,
                zoneOffset = ZoneId.systemDefault().rules.getOffset(time),
                beatsPerMinute = entry.bpm.toLong(),
                // Stable id per day: a re-sync with a newer version updates instead of duplicating.
                metadata = Metadata.autoRecorded(
                    device = Device(type = Device.TYPE_WATCH),
                    clientRecordId = "pebble-rhr-${entry.epochDay}",
                    clientRecordVersion = version,
                ),
            )
        }
        client.insertRecords(records)
        Unit
    }
}

/**
 * Hosts the Health Connect permission dialog for [WRITE_RESTING_HR].
 *
 * The permission set is a constant rather than an intent extra, so a recreated activity
 * (process death, resume from recents) can't launch with an empty set and crash — the issue
 * documented for health-kmp's own permission activity in androidApp's manifest.
 */
class RestingHeartRatePermissionActivity : ComponentActivity() {

    companion object {
        private var continuation: CancellableContinuation<Boolean>? = null

        internal suspend fun request(context: Context): Boolean = suspendCancellableCoroutine {
            continuation?.cancel()
            continuation = it
            context.startActivity(
                Intent(context, RestingHeartRatePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            continuation?.resume(WRITE_RESTING_HR in granted)
            continuation = null
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            requestPermissions.launch(setOf(WRITE_RESTING_HR))
        }
    }

    override fun onDestroy() {
        // Dismissed without a result (e.g. system killed the dialog): don't leave the caller hanging.
        if (isFinishing) {
            continuation?.resume(false)
            continuation = null
        }
        super.onDestroy()
    }
}
