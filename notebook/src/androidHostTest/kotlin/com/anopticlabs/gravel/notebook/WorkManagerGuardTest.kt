package com.anopticlabs.gravel.notebook

import androidx.work.WorkManager
import com.anopticlabs.gravel.notebook.backup.WorkAutoBackupScheduler
import com.anopticlabs.gravel.notebook.pipeline.WorkManagerChargingWork
import com.anopticlabs.gravel.notebook.pipeline.WorkManagerNetworkWork
import com.anopticlabs.gravel.notebook.pipeline.WorkManagerServerTryWork
import kotlin.test.Test
import kotlin.test.assertNull

class WorkManagerGuardTest {
    private val unavailable: () -> WorkManager = { throw NoSuchMethodError("JobScheduler.forNamespace") }

    @Test
    fun aThrowingInitializationGivesNoWorkManager() {
        assertNull(workManagerOrNull(unavailable))
        assertNull(workManagerOrNull { throw IllegalStateException("not initialized") })
    }

    @Test
    fun theNotebooksWorkSkipsSchedulingWhenWorkManagerThrows() {
        WorkAutoBackupScheduler(unavailable).run {
            afterChange(null)
            schedulePeriodic()
            runSoon()
            cancel()
        }
        WorkManagerNetworkWork(unavailable).schedule()
        WorkManagerServerTryWork(unavailable).schedule()
        WorkManagerChargingWork(unavailable).schedule()
    }
}
