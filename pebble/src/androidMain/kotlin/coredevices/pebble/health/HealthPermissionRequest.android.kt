package coredevices.pebble.health

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import com.viktormykhailiv.kmp.health.HealthManager
import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.resume

/**
 * Every Health Connect permission Gravel writes with. Must cover
 * [PlatformHealthSync.RequestedWriteTypes]; a constant (not an intent extra) so a recreated
 * [HealthPermissionsActivity] always has a non-empty set.
 */
private val GRAVEL_HEALTH_PERMISSIONS = setOf(
    HealthPermission.getWritePermission(StepsRecord::class),
    HealthPermission.getWritePermission(HeartRateRecord::class),
    HealthPermission.getWritePermission(SleepSessionRecord::class),
    HealthPermission.getWritePermission(ExerciseSessionRecord::class),
    HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
)

private object AppContextHolder : KoinComponent {
    val appContext: AppContext by inject()
}

/**
 * Fork: shows the Health Connect dialog from Gravel's own activity instead of health-kmp's.
 * health-kmp's HealthConnectPermissionActivity is declared noHistory in androidApp's manifest
 * (a crash workaround), so it is finished the moment the dialog covers it and cancels the
 * request before the user answers. That made the first "Sync to Health Connect" attempt
 * always fail, and made it impossible to enable sync with only some types granted.
 */
internal actual suspend fun requestHealthPermissionsWithDialog(healthManager: HealthManager) {
    HealthPermissionsActivity.request(AppContextHolder.appContext.context.applicationContext)
}

class HealthPermissionsActivity : ComponentActivity() {

    companion object {
        private var continuation: CancellableContinuation<Unit>? = null

        internal suspend fun request(context: Context): Unit = suspendCancellableCoroutine {
            continuation?.cancel()
            continuation = it
            context.startActivity(
                Intent(context, HealthPermissionsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            // The caller re-checks what was granted; partial grants are valid.
            continuation?.resume(Unit)
            continuation = null
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            requestPermissions.launch(GRAVEL_HEALTH_PERMISSIONS)
        }
    }

    override fun onDestroy() {
        // Dismissed without a result: let the caller re-check instead of hanging.
        if (isFinishing) {
            continuation?.resume(Unit)
            continuation = null
        }
        super.onDestroy()
    }
}
