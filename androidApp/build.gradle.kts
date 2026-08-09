import java.util.Properties

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

// Hoisted out of the lambda below, which must not capture the project.
val providerFactory = providers

// Number of commits in the git history, so it always increases on main.
val gitVersionCode = providers.exec {
    isIgnoreExitValue = true
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.map {
    it.trim().toIntOrNull() ?: throw GradleException("Error reading current commit count")
}

// Newest tag anywhere in the repo, including on branches HEAD doesn't descend from.
val gitVersionName = providers.exec {
    isIgnoreExitValue = true
    commandLine("git", "rev-list", "--tags", "--max-count=1")
}.standardOutput.asText.flatMap { rev ->
    providerFactory.exec {
        isIgnoreExitValue = true
        commandLine("git", "describe", "--tags", rev.trim().ifEmpty { "HEAD" })
    }.standardOutput.asText
}.map { it.trim().ifEmpty { "unknown" } }

android {
    namespace = "coredevices.coreapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    if (!localReleaseBuild) {
        signingConfigs {
            create("release") {
                storeFile = file("../keystore.jks")
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
            // Fork: :cactus keeps its iOS static archives (libcactus_engine.a,
            // libcurl.a) under src/commonMain/resources so the cinterop build
            // can link them. Under AGP 9's androidKotlinMultiplatformLibrary
            // plugin commonMain resources are packaged as Android java
            // resources, which silently shipped ~23 MB of Mach-O dead weight
            // in every APK (and shipped the sourceless cactus engine in a
            // second form). Excluding here at the application boundary keeps
            // the archives available to the iOS toolchain while keeping them
            // out of the Android artifact.
            excludes += "/ios/**"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            if (localReleaseBuild) {
                signingConfig = signingConfigs.getByName("debug")
            } else {
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
    // (watchModule seam checks, Cactus STT lifecycle), and project deps of
    // :composeApp are not on the androidTest compile classpath transitively.
    androidTestImplementation(project(":pebble"))
    androidTestImplementation(project(":cactus"))
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

// Resolved at execution time — a configuration-time .get() makes every commit invalidate the
// configuration cache.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach {
            it.versionCode.set(gitVersionCode)
            it.versionName.set(gitVersionName)
        }
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
