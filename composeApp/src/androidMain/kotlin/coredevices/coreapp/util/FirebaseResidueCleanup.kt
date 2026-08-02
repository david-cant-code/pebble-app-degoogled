package coredevices.coreapp.util

import android.content.Context
import co.touchlab.kermit.Logger
import java.io.File
import kotlin.concurrent.thread

/**
 * Deletes the on-disk residue the native Firebase SDKs left behind on
 * installs upgraded from pre-strip builds.
 *
 * Pre-strip builds signed every user in (anonymously at minimum), which made
 * the Firebase Auth SDK persist a refresh token in app-private SharedPrefs
 * and Firestore keep an offline cache database. Firebase refresh tokens do
 * not expire client-side, and the strip removed every mechanism that could
 * ever touch that material again: the SDKs are off the classpath, the stub
 * signOut() is a no-op, and the sign-out UI is unreachable. Left alone, a
 * still-valid Core-account credential would sit at rest forever, riding
 * along in Auto Backup and device-to-device transfers.
 *
 * Two things have since narrowed this, and the original rationale here
 * overstated what remains. It also argued that backup restore matches on
 * package name rather than signing certificate, so restored official-app
 * data could land in a fresh install of this fork; the rebrand moved the
 * applicationId to com.anopticlabs.gravel, which no longer collides with
 * upstream's, so that route is closed. And because the SDKs were removed
 * before that rename, no build carrying Firebase ever ran under the current
 * applicationId, so a data directory this cleanup can reach should never
 * contain the residue in the first place.
 *
 * It is kept as a standing guard rather than because a path to it is known:
 * the cost is a few directory listings on a background thread at startup,
 * and it would cover a future build that reintroduced the SDKs, or a data
 * directory arriving from somewhere not anticipated here. It runs every
 * launch because idempotency beats a marker flag that backup restore could
 * carry separately from the data it describes.
 */
object FirebaseResidueCleanup {

    private val logger = Logger.withTag("FirebaseResidueCleanup")

    // File-name prefixes the Firebase SDKs used in this app's pre-strip
    // configuration (Auth token store and internals plus heartbeat prefs,
    // the Firestore offline cache, and the Firebase Installations ID).
    private val sharedPrefsPrefixes = listOf("com.google.firebase.", "FirebaseHeartBeat")
    private const val DATABASE_PREFIX = "firestore."
    private const val FILES_PREFIX = "PersistedInstallation"

    /** Runs [delete] off the main thread; safe to call on every startup. */
    fun launchInBackground(context: Context) {
        val appContext = context.applicationContext
        thread(name = "firebase-residue-cleanup", isDaemon = true) {
            delete(appContext)
        }
    }

    /** Deletes any residue found; returns the number of files removed. */
    fun delete(context: Context): Int {
        var deleted = 0
        deleted += deleteMatching(File(context.dataDir, "shared_prefs")) { name ->
            sharedPrefsPrefixes.any { name.startsWith(it) }
        }
        // Covers the main store plus its -journal/-wal siblings, which share
        // the prefix.
        deleted += deleteMatching(File(context.dataDir, "databases")) { name ->
            name.startsWith(DATABASE_PREFIX)
        }
        deleted += deleteMatching(context.filesDir) { name ->
            name.startsWith(FILES_PREFIX)
        }
        if (deleted > 0) {
            logger.i { "Deleted $deleted Firebase residue file(s) from a pre-strip install" }
        }
        return deleted
    }

    private fun deleteMatching(dir: File, matches: (String) -> Boolean): Int {
        val files = dir.listFiles() ?: return 0
        return files.count { file ->
            matches(file.name) && file.isFile && file.delete().also { ok ->
                if (!ok) logger.w { "Failed to delete residue file ${file.name}" }
            }
        }
    }
}
