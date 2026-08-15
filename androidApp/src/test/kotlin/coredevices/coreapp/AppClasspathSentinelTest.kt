package coredevices.coreapp

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Classpath-absence sentinels at the application boundary.
 *
 * The library-module copies of these probes (FirebaseUnplugTest and friends
 * in :composeApp, MixpanelAbsenceTest in :util) pin the library classpaths,
 * which was the app classpath until the AGP 9 split. Now :androidApp owns
 * the shipping dependency graph, and upstream's own androidApp is exactly
 * where the google-services and crashlytics plugins plus the Firebase
 * artifacts re-enter on every upstream sync, so a dependency added directly
 * to this module would otherwise reach the APK with every library sentinel
 * still green. These probes run on this module's unit-test classpath, which
 * includes the app's full runtime graph, and fail the moment any stripped
 * artifact returns, whatever the source tree looks like.
 *
 * The positive probes prove the strings resolve by exact binary name on this
 * classpath, so a package rename cannot quietly turn the absence assertions
 * into tautologies.
 */
class AppClasspathSentinelTest {

    private fun assertAbsent(className: String) {
        assertFailsWith<ClassNotFoundException>("$className should not be on the app classpath") {
            Class.forName(className)
        }
    }

    @Test
    fun probeStringsResolveOnThisClasspath() {
        // Positive controls: one class from the app itself, one from a
        // library the app legitimately ships.
        Class.forName("coredevices.coreapp.MainApplication")
        Class.forName("androidx.health.connect.client.HealthConnectClient")
    }

    @Test
    fun firebaseSdksAreOffTheAppClasspath() {
        assertAbsent("com.google.firebase.FirebaseApp")
        assertAbsent("com.google.firebase.auth.FirebaseAuth")
        assertAbsent("com.google.firebase.firestore.FirebaseFirestore")
        assertAbsent("com.google.firebase.messaging.FirebaseMessaging")
        assertAbsent("com.google.firebase.crashlytics.FirebaseCrashlytics")
    }

    @Test
    fun gmsRidersAreOffTheAppClasspath() {
        assertAbsent("com.google.android.gms.auth.api.signin.GoogleSignIn")
        assertAbsent("com.google.android.gms.common.api.GoogleApiClient")
        assertAbsent("com.google.android.play.core.integrity.IntegrityManagerFactory")
        assertAbsent("androidx.credentials.playservices.CredentialProviderPlayServicesImpl")
    }

    @Test
    fun healthKmpGoogleFitBackendIsOffTheAppClasspath() {
        // This module declares its own health-kmp dependency with the GMS
        // excludes re-stated; this is the probe that observes that copy of
        // the excludes (the composeApp sentinel only sees composeApp's).
        assertAbsent("com.google.android.gms.fitness.Fitness")
    }

    @Test
    fun pushStackIsOffTheAppClasspath() {
        assertAbsent("com.mmk.kmpnotifier.notification.NotifierManager")
    }

    @Test
    fun playAppUpdateIsOffTheAppClasspath() {
        assertAbsent("com.google.android.play.core.appupdate.AppUpdateManagerFactory")
    }

    @Test
    fun experimentalRingModuleIsOffTheAppClasspath() {
        // The fork's stub facade is expected; the real ring classes are not.
        Class.forName("coredevices.ExperimentalDevices")
        assertAbsent("coredevices.ring.service.RingSync")
        assertAbsent("coredevices.ring.RingDelegate")
    }

    @Test
    fun mixpanelIsOffTheAppClasspath() {
        assertAbsent("com.mixpanel.android.mpmetrics.MixpanelAPI")
    }

    @Test
    fun haversineSatelliteLibraryResolvesToTheForkStub() {
        // libindex's upstream dependency line still names the prebuilt
        // io.github.coredevices.haversine AAR; settings.gradle.kts swaps it
        // for :haversine-stubs across every project. This probe pins the
        // swap from the app's own runtime graph, so a lost or misapplied
        // substitution (a settings.gradle.kts merge that drops it, say)
        // fails here rather than quietly returning the AAR's two native
        // libraries to the APK. The KMP facade name exists in both the AAR
        // and the stub, so the positive control alone proves nothing: what
        // distinguishes them is the AAR's wrapped vendor library
        // (com.wtlp.*, the JNI side of the bundled .so files) and its
        // transfer delegate, neither of which the stub declares.
        Class.forName("coredevices.haversine.KMPHaversineSatelliteManager")
        assertAbsent("com.wtlp.haversinesatellitelibrary.HaversineSatellite")
        assertAbsent("com.wtlp.haversinesatellitelibrary.HaversineSatelliteManager")
        assertAbsent("com.wtlp.ppcommon.PPCommon")
        assertAbsent("coredevices.haversine.HaversineTransferDelegate")
    }
}
