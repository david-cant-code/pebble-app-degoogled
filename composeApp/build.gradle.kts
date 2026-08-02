
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidVersion)
    alias(libs.plugins.nativeCocoaPods)
    alias(libs.plugins.kotlinx.atomicfu)
}

val properties = Properties().apply {
    try {
        load(rootDir.resolve("local.properties").reader())
    } catch (e: Exception) {
        println("local.properties file not found")
    }
}
val localReleaseBuild = properties["LOCAL_RELEASE_BUILD"]?.toString()?.toBooleanStrictOrNull() ?: false
versioning.keepOriginalBundleFile = true

val headSha by lazy {
    project.providers.exec {
        commandLine("git", "describe", "--always", "--dirty")
    }.standardOutput.asText.get().trim()
}

dependencies {
    debugImplementation(compose.uiTooling)
}


kotlin {
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Make xcode invoke gradle from the right place
    tasks.register("fixXcodeProject") {
        doLast {
            val xcodeProjectFile = project.file("../iosApp/Pods/Pods.xcodeproj/project.pbxproj")
            if (xcodeProjectFile.exists()) {
                var content = xcodeProjectFile.readText()
                content = content.replace("gradlew\\\" -p \\\"\$REPO_ROOT\\\"", "gradlew\\\" -p \\\"${rootProject.projectDir}\\\"")
                xcodeProjectFile.writeText(content)
            } else {
                logger.warn("Xcode project file not found, skipping fix: ${xcodeProjectFile.path}")
            }
        }
    }
    tasks.named("podInstall") {
        finalizedBy("fixXcodeProject")
    }

    cocoapods {
        version = "1.0"
        summary = "Core App"
        homepage = "https://github.com/coredevices/CoreApp"
        license = "proprietary"
        ios.deploymentTarget = "15.6"
        podfile = project.file("../iosApp/Podfile")

        pod("GoogleSignIn", "8.0.0")
        pod("FirebaseCore")
        pod("FirebaseAuth") {
            linkOnly = true
            extraOpts += listOf("-compiler-option", "-fmodules")
        }
        pod("FirebaseFirestore") {
            linkOnly = true
        }
        pod("FirebaseStorage") {
            linkOnly = true
        }
        pod("FirebaseCrashlytics") {
            linkOnly = true
        }
        pod("FirebaseMessaging") {
            linkOnly = true
        }

        framework {
            baseName = "ComposeApp"
            linkerOpts("-framework", "Accelerate")
            val osName = when (target.name) {
                "iosArm64" -> "iphoneos"
                "iosX64", "iosSimulatorArm64" -> "iphonesimulator"
                else -> error("Unknown target ${target.name}")
            }
            val dir = project.file("../libpebble3/build/libpebble-swift/$osName")
            val xcodeExists = providers.exec {
                isIgnoreExitValue = true
                commandLine("which", "xcode-select")
            }.result.get().exitValue == 0
            if (xcodeExists) {
                val xcodeDir = providers.exec {
                    commandLine("xcode-select", "-p")
                }.standardOutput.asText.get().trim()
                linkerOpts(
                    "-framework", "LibPebbleSwift", "-F"+dir.absolutePath,
                    "-weak_framework", "CoreML",
                    "-L$xcodeDir/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/$osName"
                )
            }
        }
    }

    buildList {
        if (System.getenv("CI_RELEASE") != "true") {
            add(iosSimulatorArm64())
        } else {
            logger.warn("Skipping configuration of iOS simulator targets for CI release build")
        }
        add(iosArm64())
    }.forEach {
        it.binaries.all {
            freeCompilerArgs += listOf(
                "-Xdisable-phases=DevirtualizationAnalysis,DCEPhase"
            )
        }
    }
    
    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.uuid.ExperimentalUuidApi")
                optIn("kotlinx.serialization.ExperimentalSerializationApi")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("androidx.compose.material3.ExperimentalMaterial3Api")
                optIn("kotlinx.cinterop.BetaInteropApi")
                optIn("kotlin.time.ExperimentalTime")
            }
        }
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.coroutines.android)
            implementation(libs.androidx.work)
            implementation(libs.coil.gif)
        }
        androidInstrumentedTest.dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.rules)
            implementation(libs.ktor.client.okhttp)
            implementation(project(":util"))
            // Same artifact and version the app already ships via libpebble3; test-scope only,
            // so the binder test can speak the PebbleKit 2 AIDL types to the exported service.
            implementation(libs.pebblekit)
        }
        androidUnitTest.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.mock)
            implementation(libs.coroutines.test)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.crashkios)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        commonMain.dependencies {
            implementation(libs.kotlinx.io.okio)
            implementation(libs.kermit)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.ui)
            implementation(libs.backhandler)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.serialization)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.coil)
            implementation(libs.coil.svg)

            // Fork: inert dev.gitlive.firebase.* stand-ins replacing the real
            // gitlive firebase-auth/firebase-firestore artifacts.
            implementation(project(":firebase-stubs"))

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.client.serialization.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.coroutines)
            implementation(project(":pebble"))
            implementation(project(":util"))
            // :experimental is unplugged from the fork's build (Firebase +
            // Supabase); ring runtime is stubbed at the Koin seam instead.
            // :libindex stays because PebbleBackgroundManager and :pebble
            // compile against its types; :index-ai/:mcp remain only as its
            // transitive compile deps, not direct app deps.
            implementation(libs.kmpio)
            implementation(project(":libpebble3"))
            implementation(project(":libindex"))
            // Fork: health-kmp's Android artifact hard-depends on the Google
            // Fit backend even though only the Health Connect path is used.
            // The GMS artifacts are excluded so the APK stays GMS-free;
            // watchModule additionally pins useGoogleFit=false so the Fit
            // code path is unreachable on every device (second layer).
            // String notation because the KMP dependencies DSL has no
            // configure-lambda overload for version-catalog providers.
            implementation("com.viktormykhailiv:health-kmp:${libs.versions.health.kmp.get()}") {
                exclude(group = "com.google.android.gms", module = "play-services-auth")
                exclude(group = "com.google.android.gms", module = "play-services-fitness")
            }
        }
    }
    sourceSets.androidInstrumentedTest.dependencies {
        implementation(kotlin("test"))
    }
}

compose.resources {
    packageOfResClass = "coreapp.composeapp.generated.resources"
}

android {
    namespace = "coredevices.coreapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    buildFeatures {
        buildConfig = true
        compose = true
    }

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
        // This uses the number of commits in the git history, so it will always increase on main
        versionCode = versioning.getVersionCode()
        versionName = try { versioning.getVersionName() } catch (e: Exception) { "unknown" }
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

androidComponents {
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
        // in this Kotlin Multiplatform project the packaging tasks do not exist yet while
        // onVariants is running.
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
                        "wiring in composeApp/build.gradle.kts before building a release."
                )
            }
        }
    }
}
