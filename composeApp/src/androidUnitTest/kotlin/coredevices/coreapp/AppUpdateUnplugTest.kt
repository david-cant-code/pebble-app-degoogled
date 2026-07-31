package coredevices.coreapp

import coredevices.coreapp.di.androidDefaultModule
import coredevices.coreapp.util.AppUpdate
import coredevices.coreapp.util.AppUpdateState
import coredevices.coreapp.util.NoOpAppUpdate
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import org.koin.dsl.koinApplication

class AppUpdateUnplugTest {
    // Play in-app updates were swapped for NoOpAppUpdate at the DI seam and
    // the app-update artifacts dropped. Unlike the GMS auth artifacts (kept
    // alive transitively by firebase-auth until the Firebase strip), nothing
    // else pulls com.google.android.play:app-update, so this probe fails the
    // moment the artifact returns to the app classpath, whatever the source
    // tree looks like.
    @Test
    fun playAppUpdateArtifactIsAbsentFromTheClasspath() {
        assertFailsWith<ClassNotFoundException> {
            Class.forName("com.google.android.play.core.appupdate.AppUpdateManagerFactory")
        }
    }

    // Definitions resolve lazily, so only the probed binding is instantiated.
    @Test
    fun appUpdateSeamResolvesToTheForkNoOp() {
        val koin = koinApplication { modules(androidDefaultModule) }.koin
        val appUpdate = koin.get<AppUpdate>()
        assertIs<NoOpAppUpdate>(appUpdate)
        assertSame(AppUpdateState.NoUpdateAvailable, appUpdate.updateAvailable.value)
    }
}
