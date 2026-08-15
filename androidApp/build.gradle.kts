import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Properties
import java.util.zip.ZipFile

// Fork: upstream applies the google-services and firebase-crashlytics plugins
// here; both are removed with the rest of the Firebase/GMS stack. See
// DESIGN_NOTES.md for the de-Googling seams.
plugins {
    alias(libs.plugins.android.application)
}

val properties = Properties().apply {
    try {
        load(rootDir.resolve("local.properties").reader())
    } catch (e: Exception) {
        println("local.properties file not found")
    }
}
val localReleaseBuild = properties["LOCAL_RELEASE_BUILD"]?.toString()?.toBooleanStrictOrNull() ?: false

// Fork: release is signed with the keystore only when one is actually present
// next to the checkout. A checkout without one (a fresh clone, CI, an F-Droid
// build, which signs the APK itself afterwards) then packages release
// unsigned instead of failing on a missing keystore file. LOCAL_RELEASE_BUILD
// keeps its meaning: sign release with the debug key so it installs over a
// debug build.
val releaseKeystore = rootDir.resolve("keystore.jks")
val signReleaseWithKeystore = !localReleaseBuild && releaseKeystore.exists()

// Number of commits in the git history, so it always increases on main.
val gitVersionCode = providers.exec {
    isIgnoreExitValue = true
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.map {
    it.trim().toIntOrNull() ?: throw GradleException("Error reading current commit count")
}

// Fork: described from HEAD, so the value is a function of the built commit:
// exactly the tag name on a tag checkout, "<tag>-<n>-g<sha>" past one, and
// "unknown" when no tag is reachable. Upstream derives it from the newest tag
// anywhere in the repository, so an older tag rebuilt after a newer one
// exists reports the newer version and every branch reports the newest tag.
// F-Droid builds a tag checkout and requires the built versionName to equal
// the one declared for that tag, which needs the per-commit derivation.
val gitVersionName = providers.exec {
    isIgnoreExitValue = true
    commandLine("git", "describe", "--tags", "HEAD")
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

android {
    namespace = "coredevices.coreapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    if (signReleaseWithKeystore) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEYSTORE_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        // Fork identity: the installed package is Gravel's. The Kotlin namespace above
        // deliberately stays "coredevices.coreapp" so source packages match upstream and
        // merges stay cheap; only the applicationId is rebranded.
        applicationId = "com.anopticlabs.gravel"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += setOf("armeabi-v7a", "arm64-v8a")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    androidResources {
        // Fork: the two Wispr Flow logos are compose resources of :pebble
        // (packaged as assets) that nothing references in fork builds, where
        // the remote Wispr transcription path can never be enabled. Dropped
        // at the packaging boundary rather than deleted from the upstream
        // resource directory, which an upstream sync would resurrect. The
        // property replaces the AGP default, so the default patterns are
        // restated ahead of the two additions.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~" +
            ":!wispr_flow_logo_black.png:!wispr_flow_logo_white.png"
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            if (localReleaseBuild) {
                signingConfig = signingConfigs.getByName("debug")
            } else if (signReleaseWithKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Fork: AGP by default appends a dependency-metadata block to the APK
    // signing block, compressed and encrypted so that only Google Play can
    // read it. Nothing in this fork's distribution can consume it, and
    // F-Droid's APK scan reports it as an extra signing block.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// Fork: compose ui-tooling contributes an exported
// androidx.compose.ui.tooling.PreviewActivity to the merged manifest. The KMP
// library modules declare it in androidMain (the AGP 9 KMP plugin has no
// debug/release split a debugImplementation could scope it to), so it is
// pruned from release here at the app boundary instead; debug keeps preview
// tooling. Both coordinate spellings are excluded because the JetBrains
// artifact redirects to the AndroidX one. The VerifyExportedComponents task
// below fails the release build if PreviewActivity ever reappears.
// configureEach because these configurations do not exist yet while the
// script body runs; AGP creates them per variant later.
configurations.matching {
    it.name == "releaseRuntimeClasspath" || it.name == "releaseCompileClasspath"
}.configureEach {
    exclude(group = "androidx.compose.ui", module = "ui-tooling")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-tooling")
}

dependencies {
    implementation(project(":composeApp"))
    // Components this module's manifest declares, so lint can resolve them.
    implementation(project(":util"))
    implementation(libs.androidx.core.ktx)
    // Fork: string notation on purpose, mirroring composeApp; the catalog has
    // no health-kmp alias so that upstream's plain libs.health.kmp fails at
    // configuration time instead of silently resolving the Google Fit
    // transitives these excludes keep out of the APK.
    implementation("com.viktormykhailiv:health-kmp:${libs.versions.health.kmp.get()}") {
        exclude(group = "com.google.android.gms", module = "play-services-auth")
        exclude(group = "com.google.android.gms", module = "play-services-fitness")
    }

    // Fork: host-side sentinels (classpath-absence probes, network security
    // config pins) run against this module because it owns the shipping
    // dependency graph after the AGP 9 split; see AppClasspathSentinelTest.
    // This must stay the junit binding: in a plain android module nothing
    // wires a kotlin-test framework capability the way the KMP plugin does
    // in the library modules, so bare kotlin-test resolves to the
    // framework-agnostic jar, which has no kotlin.test.Test annotation.
    testImplementation(libs.kotlin.test.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.ktor.client.okhttp)
    androidTestImplementation(libs.koin.core)
    androidTestImplementation(libs.koin.android)
    androidTestImplementation(libs.coroutines)
    androidTestImplementation(libs.kotlin.test)
    // The fork suites reach through the app into these modules directly
    // (watchModule seam checks, whisper STT lifecycle), and project deps of
    // :composeApp are not on the androidTest compile classpath transitively.
    androidTestImplementation(project(":pebble"))
    androidTestImplementation(project(":whisper"))
    androidTestImplementation(project(":libpebble3"))
    androidTestImplementation(libs.serialization)
    // Same artifact and version the app already ships via libpebble3; test-scope only,
    // so the binder test can speak the PebbleKit 2 AIDL types to the exported service.
    androidTestImplementation(libs.pebblekit)
    // Fork: upstream additionally pulls firebase-auth, :cactus, :experimental,
    // :libindex, :index-ai, and :mcp into androidTest for its Ring recording
    // suites. Those suites are deleted here (Ring is unplugged and Firebase is
    // stripped), and :experimental is not even in settings.gradle.kts, so the
    // dependencies would break configuration if kept.
}

/**
 * Components this app is allowed to export, pinned including whatever permission protects each
 * one. Anything exported and absent from this set fails the release build.
 *
 * The exported surface is what any other app on the device can reach, and it grows silently: a
 * dependency can contribute a component through manifest merging without a line of app code
 * changing, which is how an exported Compose preview activity once reached a release build. It
 * also survives review, because a branch diff shows no manifest change to notice.
 *
 * Deliberately checked against the merged manifest at build time rather than from an
 * instrumentation test. A release variant cannot be instrumented at all (R8 renames the classes
 * the test APK refers to), so a runtime test could only ever have pinned the debug surface,
 * which is exactly where the preview activity looked harmless.
 *
 * Note this cannot see receivers registered at runtime with RECEIVER_EXPORTED, which are
 * invisible to every manifest-based check. Classic PebbleKit registers several.
 */
val allowedExportedComponents = setOf(
    "activity|coredevices.coreapp.MainActivity|perm=|read=|write=",
    "activity-alias|coredevices.coreapp.HealthPermissionsRationaleActivity|perm=|read=|write=",
    "activity-alias|coredevices.coreapp.ViewPermissionUsageActivity|perm=android.permission.START_VIEW_PERMISSION_USAGE|read=|write=",
    "service|androidx.health.platform.client.impl.sdkservice.HealthDataSdkService|perm=|read=|write=",
    "service|io.rebble.libpebblecommon.notification.LibPebbleNotificationListener|perm=android.permission.BIND_NOTIFICATION_LISTENER_SERVICE|read=|write=",
    "service|io.rebble.libpebblecommon.calls.LibPebbleInCallService|perm=android.permission.BIND_INCALL_SERVICE|read=|write=",
    "service|io.rebble.libpebblecommon.pebblekit.two.PebbleSenderReceiver|perm=|read=|write=",
    "service|androidx.work.impl.background.systemjob.SystemJobService|perm=android.permission.BIND_JOB_SERVICE|read=|write=",
    "receiver|androidx.work.impl.diagnostics.DiagnosticsReceiver|perm=android.permission.DUMP|read=|write=",
    "receiver|androidx.profileinstaller.ProfileInstallReceiver|perm=android.permission.DUMP|read=|write=",
    "provider|io.rebble.libpebblecommon.pebblekit.two.PebbleKitProvider|perm=|read=|write=",
    "provider|io.rebble.libpebblecommon.pebblekit.classic.PebbleKitProvider|perm=|read=|write=",
)

abstract class VerifyExportedComponents : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val allowed: SetProperty<String>

    @TaskAction
    fun verify() {
        val android = "http://schemas.android.com/apk/res/android"
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(mergedManifest.get().asFile)

        val found = mutableSetOf<String>()
        listOf("activity", "activity-alias", "service", "receiver", "provider").forEach { tag ->
            val nodes = document.getElementsByTagName(tag)
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as org.w3c.dom.Element
                if (element.getAttributeNS(android, "exported") != "true") continue
                val name = element.getAttributeNS(android, "name")
                val permission = element.getAttributeNS(android, "permission")
                val read = element.getAttributeNS(android, "readPermission")
                val write = element.getAttributeNS(android, "writePermission")
                found += "$tag|$name|perm=$permission|read=$read|write=$write"
            }
        }

        val allowedSet = allowed.get()
        val unexpected = (found - allowedSet).sorted()
        // A stale entry is reported too: it usually means a component was renamed or its
        // permission changed, and the matching new entry is sitting in `unexpected`.
        val stale = (allowedSet - found).sorted()

        if (unexpected.isEmpty() && stale.isEmpty()) {
            logger.lifecycle("Exported components verified: ${found.size}, all allowlisted.")
            return
        }

        val report = buildString {
            appendLine("Exported component surface changed.")
            if (unexpected.isNotEmpty()) {
                appendLine()
                appendLine("Exported but NOT allowlisted:")
                unexpected.forEach { appendLine("  $it") }
                appendLine()
                appendLine("Each of these is reachable by any app on the device. Confirm it is")
                appendLine("meant to be, and that the permission shown actually protects it,")
                appendLine("before adding it to allowedExportedComponents.")
            }
            if (stale.isNotEmpty()) {
                appendLine()
                appendLine("Allowlisted but no longer exported as described:")
                stale.forEach { appendLine("  $it") }
            }
        }
        throw GradleException(report)
    }
}

/**
 * Fork: inspects every APK a variant packages, so the packaging decisions taken in this file are
 * verified against the built artifact on every build rather than by hand:
 * - no entry matches [forbiddenEntries]: the Wispr Flow logo assets ignoreAssetsPattern drops
 *   and the two native libraries of the Ring satellite AAR that :haversine-stubs replaces;
 * - the APK Signing Block is present or absent as the variant's signing configuration says
 *   ([expectSigned]: a release built without a keystore must come out unsigned, which is the
 *   shape F-Droid builds), and when present never carries the dependency-metadata pair
 *   (id 0x504b4453) that the dependenciesInfo block above turns off.
 * Wired with toListenTo(SingleArtifact.APK), which runs the task as a finalizer of the packaging
 * task whenever the APK is produced (assemble, install), for every variant. AGP generates the
 * dependency metadata for release builds only and an unsigned APK has no signing block to carry
 * it, so that check bites on signed release builds (a keystore build, or LOCAL_RELEASE_BUILD);
 * the debug build still exercises the signing-block parse on a signed APK.
 */
abstract class VerifyApkContents : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkDirectory: DirectoryProperty

    /** Regular expressions over zip entry names; any match fails the build. */
    @get:Input
    abstract val forbiddenEntries: ListProperty<String>

    @get:Input
    abstract val expectSigned: Property<Boolean>

    @TaskAction
    fun verify() {
        val apks = apkDirectory.get().asFile.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
        if (apks.isEmpty()) throw GradleException("No APK found under ${apkDirectory.get().asFile}")
        val forbidden = forbiddenEntries.get().map(::Regex)
        val report = StringBuilder()
        for (apk in apks) {
            ZipFile(apk).use { zip ->
                val hits = zip.entries().asSequence().map { it.name }.filter { name -> forbidden.any { it.matches(name) } }.toList()
                if (hits.isNotEmpty()) report.appendLine("${apk.name} packages forbidden entries: $hits")
            }
            val ids = signingBlockIds(apk)
            if (expectSigned.get() && ids == null) report.appendLine("${apk.name} has no APK Signing Block but this variant is configured to sign")
            if (!expectSigned.get() && ids != null) report.appendLine("${apk.name} is signed (block ids ${ids.map { it.toString(16) }}) but no keystore was configured for this variant")
            if (ids != null && DEPENDENCY_METADATA_ID in ids) report.appendLine("${apk.name} carries the dependency-metadata signing block (0x504b4453); dependenciesInfo must stay disabled")
        }
        if (report.isNotEmpty()) throw GradleException("APK contents check failed:\n$report")
        logger.lifecycle("APK contents verified: ${apks.joinToString { it.name }}")
    }

    /**
     * The pair ids in the APK Signing Block, or null when the APK has none (an unsigned APK).
     * Layout, per the APK signature scheme v2 documentation: the block sits immediately before
     * the ZIP central directory and ends with a uint64 size followed by the 16-byte magic
     * "APK Sig Block 42"; the size covers the pairs, the trailing size field, and the magic, and
     * the same size is repeated as a uint64 at the very start of the block. Each pair is a uint64
     * length, a uint32 id, and (length - 4) bytes of value.
     */
    private fun signingBlockIds(apk: File): List<Long>? = RandomAccessFile(apk, "r").use { raf ->
        val little = ByteOrder.LITTLE_ENDIAN
        val length = raf.length()
        val tailStart = maxOf(0L, length - 22 - 0xFFFF)
        val tail = ByteArray((length - tailStart).toInt()).also { raf.seek(tailStart); raf.readFully(it) }
        val tailBuffer = ByteBuffer.wrap(tail).order(little)
        val eocd = (tail.size - 22 downTo 0).firstOrNull { tailBuffer.getInt(it) == 0x06054b50 }
            ?: throw GradleException("${apk.name}: no end-of-central-directory record; not a zip")
        val centralDirectory = tailBuffer.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
        if (centralDirectory < 32) return null
        val footer = ByteArray(24).also { raf.seek(centralDirectory - 24); raf.readFully(it) }
        if (String(footer, 8, 16, Charsets.US_ASCII) != "APK Sig Block 42") return null
        val size = ByteBuffer.wrap(footer).order(little).getLong(0)
        val blockStart = centralDirectory - size - 8
        val header = ByteArray(8).also { raf.seek(blockStart); raf.readFully(it) }
        if (ByteBuffer.wrap(header).order(little).getLong(0) != size) {
            throw GradleException("${apk.name}: APK Signing Block size fields disagree")
        }
        val ids = mutableListOf<Long>()
        var position = blockStart + 8
        val pairsEnd = centralDirectory - 24
        while (position < pairsEnd) {
            val pair = ByteArray(12).also { raf.seek(position); raf.readFully(it) }
            val pairBuffer = ByteBuffer.wrap(pair).order(little)
            ids += pairBuffer.getInt(8).toLong() and 0xFFFFFFFFL
            position += 8 + pairBuffer.getLong(0)
        }
        ids
    }

    private companion object {
        const val DEPENDENCY_METADATA_ID = 0x504b4453L
    }
}

// Fork: FdroidGuardrailsTest and ExcludedAssetSentinelTest read the git-tracked
// tree (build files, tracked binaries, sources) through git rather than
// through declared task inputs, so Gradle cannot tell when their verdict is
// stale. Left tracked, an unchanged test classpath would let the unit test
// task be skipped as up-to-date or restored from the build cache while the
// tree they judge had changed, which is the silent-skip shape the guardrails
// exist to prevent. The module's unit tests are few and fast, so they simply
// always run.
tasks.withType<Test>().configureEach {
    doNotTrackState("The tree-reading sentinels scan the git-tracked tree, which is not a declared input")
}

// Resolved at execution time — a configuration-time .get() makes every commit invalidate the
// configuration cache.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach {
            it.versionCode.set(gitVersionCode)
            it.versionName.set(gitVersionName)
        }

        // Fork: artifact-level check of the packaging decisions above, for
        // every variant; see VerifyApkContents.
        val verifyApk = tasks.register<VerifyApkContents>(
            "verify${variant.name.replaceFirstChar { it.uppercase() }}ApkContents",
        ) {
            group = "verification"
            description = "Fails if the packaged APK carries excluded assets, replaced natives, or an unexpected signing block."
            forbiddenEntries.set(
                listOf(
                    """.*wispr_flow_logo_(black|white)\.png""",
                    """lib/[^/]+/libhaversinesatellitelibrary\.so""",
                    """lib/[^/]+/libppcommon\.so""",
                ),
            )
            expectSigned.set(variant.buildType != "release" || localReleaseBuild || signReleaseWithKeystore)
        }
        variant.artifacts.use(verifyApk)
            .wiredWith(VerifyApkContents::apkDirectory)
            .toListenTo(com.android.build.api.artifact.SingleArtifact.APK)
    }

    // Fork: release builds fail unless every exported component is allowlisted
    // above. Lives in this module because the merged manifest is produced here.
    onVariants(selector().withBuildType("release")) { variant ->
        val verifyName = "verify${variant.name.replaceFirstChar { it.uppercase() }}ExportedComponents"
        val verify = tasks.register<VerifyExportedComponents>(verifyName) {
            group = "verification"
            description = "Fails if the release manifest exports anything not on the allowlist."
            mergedManifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            allowed.set(allowedExportedComponents)
        }
        // Attached to the packaging tasks rather than the assemble lifecycle anchor: every
        // path that produces or deploys a release artifact (assembleRelease, installRelease,
        // bundleRelease) runs through packageRelease or packageReleaseBundle, whereas only
        // assembleRelease runs through the anchor, so anchoring there let an AAB or a direct
        // install ship with the check never executing. Matched lazily rather than looked up:
        // the packaging tasks do not exist yet while onVariants is running.
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val packageNames = setOf("package$variantName", "package${variantName}Bundle")
        tasks.matching { it.name in packageNames }.configureEach { dependsOn(verify) }

        // tasks.matching is silent when nothing matches, which is the same silent-failure
        // shape the verification exists to prevent, so assert the wiring itself: a release
        // packaging task in the executed graph without its verify task means the name-based
        // attachment above has rotted (an AGP task rename, most likely) and must fail loudly
        // rather than ship an unchecked artifact.
        gradle.taskGraph.whenReady {
            val projectPath = project.path
            val packaging = allTasks.any {
                it.name in packageNames && it.project.path == projectPath
            }
            val verifying = allTasks.any {
                it.name == verifyName && it.project.path == projectPath
            }
            if (packaging && !verifying) {
                throw GradleException(
                    "$verifyName is not in the task graph although a $variantName packaging " +
                        "task is. The exported-component check has silently detached; fix the " +
                        "wiring in androidApp/build.gradle.kts before building a release."
                )
            }
        }
    }
}
