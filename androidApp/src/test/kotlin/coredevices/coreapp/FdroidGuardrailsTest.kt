package coredevices.coreapp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Source-level replica of the checks F-Droid's build scanner runs over a
 * checkout, applied to this repository's tracked tree on every CI run.
 *
 * The fork targets inclusion in F-Droid's main repository, and most of the
 * ways that can regress arrive silently with an upstream sync: a dependency
 * line naming a Google artifact, a publishing block naming a private maven
 * host, a checked-in native library or archive. F-Droid only reports these
 * at submission or on its next build of a tag, long after the merge, so this
 * class fails the merge itself instead. It replicates fdroidserver's textual
 * scanner (fdroidserver/scanner.py) rather than approximating it: same
 * dependency-line matcher, same catalog resolution, same maven-URL regex and
 * allowlist, same binary-suffix list, and the same "usual suspects" gradle
 * signatures, pinned from the SUSS database (see [scannerGradleSignatures]).
 *
 * Three independent layers stand between an upstream sync and a policy
 * regression, and this class is the source-text one:
 * - the classpath sentinels ([AppClasspathSentinelTest] and the library
 *   copies) prove the shipped runtime graph is clean;
 * - the release artifact is inspected directly (fork rule: verify against
 *   the APK, not the tree);
 * - this class proves the tracked *tree* passes the scanner as F-Droid runs
 *   it, which the other two cannot see: an unplugged module's build file, an
 *   iOS-only dependency line, or a publishing block never reaches the
 *   Android classpath yet still fails the scan.
 *
 * Only the tracked tree is scanned (git ls-files), because that is what
 * F-Droid clones; the whisper.cpp submodule is deliberately not descended
 * into, since its examples and test fixtures are removed by the F-Droid
 * build recipe before the scan and never participate in the build.
 *
 * Re-syncing: when fdroidserver changes a regex or its allowlist, or the
 * SUSS database gains a signature that matters here, update the constants
 * below in place and note the upstream revision in the commit.
 */
class FdroidGuardrailsTest {

    // ---- Repository access -------------------------------------------------

    /** The checkout root: the nearest ancestor of the test working directory holding settings.gradle.kts. */
    private val repoRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: fail("Could not locate the repository root from ${File("").absolutePath}")
    }

    /**
     * Tracked paths relative to the root, as F-Droid's clone would see them.
     * The submodule shows up as a single gitlink entry and is skipped by the
     * per-file readers below (it is a directory on disk).
     */
    private val trackedFiles: List<String> by lazy {
        val process = ProcessBuilder("git", "-C", repoRoot.path, "ls-files", "-z")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val exit = process.waitFor()
        assertTrue(exit == 0, "git ls-files failed ($exit): $output")
        output.split('\u0000').filter { it.isNotEmpty() }
    }

    private fun trackedGradleFiles(): List<File> = trackedFiles
        .filter { it.endsWith(".gradle") || it.endsWith(".gradle.kts") }
        .map { File(repoRoot, it) }
        .filter { it.isFile }

    // ---- Layer 1: maven repository URLs -----------------------------------

    /**
     * fdroidserver/scanner.py MAVEN_URL_REGEX, verbatim apart from Java's
     * requirement that the literal brace be escaped. Applied, as there, to
     * the file with line comments and block comments removed.
     */
    private val mavenUrlRegex = Regex(
        """\smaven\s*(?:\{.*?(?:setUrl|url)|\(\s*(?:url)?)\s*=?\s*(?:uri|URI|Uri\.create)?\(?\s*["']?([^\s"']+)["']?[^})]*[)}]""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** fdroidserver/scanner.py allowed_repos: hosts F-Droid trusts to serve free artifacts. */
    private val allowedMavenRepos = listOf(
        "repo1.maven.org/maven2",
        "jitpack.io",
        "www.jitpack.io",
        "repo.maven.apache.org/maven2",
        "oss.jfrog.org/artifactory/oss-snapshot-local",
        "central.sonatype.com/repository/maven-snapshots",
        "oss.sonatype.org/content/repositories/snapshots",
        "oss.sonatype.org/content/repositories/releases",
        "oss.sonatype.org/content/groups/public",
        "oss.sonatype.org/service/local/staging/deploy/maven2",
        "s01.oss.sonatype.org/content/repositories/snapshots",
        "s01.oss.sonatype.org/content/repositories/releases",
        "s01.oss.sonatype.org/content/groups/public",
        "s01.oss.sonatype.org/service/local/staging/deploy/maven2",
        "clojars.org/repo",
        "repo.clojars.org",
        "s3.amazonaws.com/repo.commonsware.com",
        "plugins.gradle.org/m2",
        "maven.google.com",
    ).map { Regex("^https://" + Regex.escape(it) + "/*") }

    private val gradleLineComment = Regex("""^[ ]*//""")

    @Test
    fun everyMavenRepositoryUrlIsOnTheFdroidAllowlist() {
        val problems = mutableListOf<String>()
        for (file in trackedGradleFiles()) {
            val nonComment = file.readLines().filterNot { gradleLineComment.containsMatchIn(it) }
            val text = nonComment.joinToString("\n").replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            for (match in mavenUrlRegex.findAll(text)) {
                val url = match.groupValues[1]
                if (allowedMavenRepos.none { it.containsMatchIn(url) }) {
                    problems += "${file.relativeTo(repoRoot)}: unknown maven repo '$url'"
                }
            }
        }
        assertTrue(
            problems.isEmpty(),
            "F-Droid rejects builds that declare maven repositories off its allowlist:\n" + problems.joinToString("\n"),
        )
    }

    // ---- Layer 2: dependency lines naming non-free artifacts ---------------

    /**
     * The gradle "usual suspects" from F-Droid's SUSS signature database
     * (https://fdroid.gitlab.io/fdroid-suss/suss.json, version 1, database
     * timestamp 2026-08-01), copied verbatim. The scanner compiles each as
     * ".*" + signature, case-insensitive, and matches it against every
     * dependency line and every catalog coordinate such a line resolves to;
     * any hit is a build-failing error on the buildserver.
     */
    private val scannerGradleSignatures = listOf(
        """androidx.*play-services""",
        """androidx.core:core-google-shortcuts""",
        """androidx.credentials:credentials-play-services-auth""",
        """androidx.media3:media3-cast""",
        """androidx.media3:media3-datasource-cronet""",
        """androidx.media3:media3-exoplayer-ima""",
        """androidx.navigation:navigation-dynamic-features""",
        """androidx.wear:wear-remote-interactions""",
        """androidx.work:work-gcm""",
        """com(\.google)?\.firebase[.:](?!firebase-jobdispatcher|geofire-java|firebase-encoders)""",
        """com.amazon.device""",
        """com.amazonaws:DynamoDBLocal""",
        """com.amazonaws:aws-android-sdk-auth-facebook""",
        """com.amazonaws:aws-android-sdk-auth-google""",
        """com.amazonaws:aws-android-sdk-auth-userpools""",
        """com.amazonaws:aws-android-sdk-cognitoauth""",
        """com.amazonaws:aws-android-sdk-cognitoidentityprovider""",
        """com.amazonaws:aws-android-sdk-cognitoidentityprovider-asf""",
        """com.amazonaws:aws-android-sdk-cognitoidentityprovider-test""",
        """com.amazonaws:aws-android-sdk-kinesisvideo""",
        """com.amazonaws:aws-android-sdk-kinesisvideo-archivedmedia""",
        """com.amazonaws:aws-android-sdk-location""",
        """com.amazonaws:aws-android-sdk-mobile-client""",
        """com.amazonaws:dynamodb-key-diagnostics-library""",
        """com.amazonaws:dynamodb-lock-client""",
        """com.amazonaws:ivs-broadcast""",
        """com.amazonaws:ivs-player""",
        """com.amazonaws:kinesis-storm-spout""",
        """com.amplifyframework:aws-push-notifications-pinpoint""",
        """com.amplifyframework:aws-push-notifications-pinpoint-common""",
        """com.android.billingclient""",
        """com.android.installreferrer""",
        """com.anjlab.android.iab.v3:library""",
        """com.baidu.mobstat""",
        """com.bugsense""",
        """com.cloudinary:cloudinary-android.*:2\.[12]\.""",
        """com.cloudrail""",
        """com.crittercism""",
        """com.evernote:android-job""",
        """com.facebook.android""",
        """com.flurry.android""",
        """com.garmin.connectiq:ciq-companion-app-sdk""",
        """com.geetest""",
        """com.giphy.sdk""",
        """com.github.SanojPunchihewa:InAppUpdater""",
        """com.github.budowski:android-maps-utils""",
        """com.github.derysudrajat:compass-qibla""",
        """com.github.junrar:junrar""",
        """com.github.omicronapps:7-Zip-JBinding-4Android""",
        """com.github.penn5:donations""",
        """com.github.uccmawei:FingerprintIdentify""",
        """com.google.ads""",
        """com.google.ai.edge.aicore""",
        """com.google.ai.edge.litert:litert(-api)?:2""",
        """com.google.android.exoplayer:extension-cast""",
        """com.google.android.exoplayer:extension-cronet""",
        """com.google.android.exoplayer:extension-ima""",
        """com.google.android.gms(?!.(oss-licenses-plugin|strict-version-matcher-plugin))""",
        """com.google.android.libraries(?!.mapsplatform.secrets-gradle-plugin)""",
        """com.google.android.play:app-update""",
        """com.google.android.play:asset-delivery""",
        """com.google.android.play:core.*""",
        """com.google.android.play:feature-delivery""",
        """com.google.android.play:review""",
        """com.google.android.support:wearable""",
        """com.google.android.ump""",
        """com.google.android.wearable:wearable""",
        """com.google.androidbrowserhelper:billing""",
        """com.google.api-client:google-api-client-android""",
        """com.google.maps.android:android-maps-utils""",
        """com.google.mlkit""",
        """com.hypertrack(?!:hyperlog)""",
        """com.meta.androidbrowserhelper""",
        """com.meta.horizon""",
        """com.microsoft.appcenter:appcenter-push""",
        """com.microsoft.identity.client:msal""",
        """com.microsoft.identity:common""",
        """com.onesignal:OneSignal""",
        """com.paypal""",
        """com.pierfrancescosoffritti.androidyoutubeplayer:chromecast-sender""",
        """com.revenuecat.purchases""",
        """com.suddenh4x.ratingdialog:awesome-app-rating""",
        """com.tencent.bugly""",
        """com.umeng""",
        """com.wei.android.lib:fingerprintidentify""",
        """com.yayandroid:locationmanager""",
        """com\.mapbox(?!\.mapboxsdk:mapbox-sdk-(services|geojson|turf):([3-5]))""",
        """com\.yandex\.android(?!:authsdk)""",
        """crashlytics""",
        """io.github.g00fy2.quickie""",
        """io.github.hyochan.openiap:openiap-amazon""",
        """io.github.hyochan.openiap:openiap-google""",
        """io.github.hyochan.openiap:openiap-horizon""",
        """io.github.sinaweibosdk""",
        """io.kotzilla""",
        """io.objectbox:objectbox-gradle-plugin""",
        """me.proton.core:payment-iap""",
        """me.pushy""",
        """org.gradle.toolchains.foojay-resolver""",
        """org.mariuszgromada.math:MathParser.org-mXparser:[5-9]""",
        """xyz.belvi.mobilevision:barcodescanner""",
    ).map { Regex(".*$it", RegexOption.IGNORE_CASE) }

    /**
     * fdroidserver/scanner.py get_gradle_compile_commands, for a build with
     * no product flavors: the leading tokens that make a line a dependency
     * (or plugin) declaration in the scanner's eyes.
     */
    private val gradleCompileCommands = listOf(
        "alias", "api", "apk", "classpath", "compile", "compileOnly", "id",
        "implementation", "provided", "runtimeOnly",
    ).flatMap { listOf(it, "release$it") }

    /** A dependency line whose coordinate is a literal string. */
    private val dependencyLineWithoutCatalog = gradleCompileCommands.map {
        Regex("""\s*['"]?$it.*\s*\(?['"].*['"]""", RegexOption.IGNORE_CASE)
    }

    /** A dependency line whose coordinate comes from the version catalog; group 1 is the accessor. */
    private val dependencyLineWithCatalog = gradleCompileCommands.map {
        Regex("""\s*['"]?$it.*\s*\(?libs\.([a-z0-9.]+)""", RegexOption.IGNORE_CASE)
    }

    @Test
    fun noDependencyLineNamesAnFdroidUsualSuspect() {
        val catalog = VersionCatalog.parse(File(repoRoot, "gradle/libs.versions.toml"))
        val problems = mutableListOf<String>()
        for (file in trackedGradleFiles()) {
            file.readLines().forEachIndexed { index, line ->
                val where = "${file.relativeTo(repoRoot)}:${index + 1}"
                if (dependencyLineWithoutCatalog.any { it.matchesAt(line, 0) }) {
                    scannerGradleSignatures.forEach { signature ->
                        if (signature.matchesAt(line, 0)) {
                            problems += "$where: usual suspect '${signature.pattern.removePrefix(".*")}' in: ${line.trim()}"
                        }
                    }
                }
                val accessor = dependencyLineWithCatalog.firstNotNullOfOrNull { it.matchAt(line, 0)?.groupValues?.get(1) }
                if (accessor != null) {
                    for (coordinate in catalog.coordinates(accessor)) {
                        scannerGradleSignatures.forEach { signature ->
                            if (signature.matchesAt(coordinate, 0)) {
                                problems += "$where: usual suspect '${signature.pattern.removePrefix(".*")}' via libs.$accessor -> $coordinate"
                            }
                        }
                    }
                }
            }
        }
        assertTrue(
            problems.isEmpty(),
            "F-Droid's scanner fails the build on these dependency lines:\n" + problems.joinToString("\n"),
        )
    }

    // ---- Layer 3: binaries in the tracked tree ----------------------------

    /**
     * fdroidserver/scanner.py's binary suffix list. Anything with one of
     * these suffixes is a scanner error unless removed or ignored by the
     * build recipe; gradle-wrapper.jar is the one name the scanner exempts.
     */
    private val binarySuffixes = listOf(".a", ".aar", ".class", ".dex", ".gz", ".tgz", ".zip", ".jar", ".wasm", ".apk")
    private val sharedObject = Regex(""".*\.so(\..+)*$""")

    @Test
    fun noBinaryIsTrackedInTheTree() {
        val problems = trackedFiles.filter { path ->
            val name = path.substringAfterLast('/')
            name != "gradle-wrapper.jar" &&
                (binarySuffixes.any { name.endsWith(it) } || sharedObject.matches(name))
        }
        assertTrue(
            problems.isEmpty(),
            "F-Droid rejects checked-in binaries; the build must produce them from source:\n" + problems.joinToString("\n"),
        )
    }

    /**
     * The scanner also sniffs files with no extension (and .bin/.out/.exe)
     * for non-text bytes, with the same first-1024-bytes heuristic replicated
     * here. The submodule gitlink is a directory on disk and is skipped.
     */
    @Test
    fun noExtensionlessTrackedFileIsBinary() {
        val textBytes = (setOf(7, 8, 9, 10, 12, 13, 27) + (0x20..0xFF)).minus(0x7F).map { it.toByte() }.toSet()
        val problems = trackedFiles.filter { path ->
            val name = path.substringAfterLast('/')
            val ext = name.substringAfterLast('.', "")
            val sniffable = !name.contains('.') || ext in setOf("bin", "out", "exe")
            val file = File(repoRoot, path)
            sniffable && file.isFile && file.inputStream().use { it.readNBytes(1024) }.any { it !in textBytes }
        }
        assertTrue(
            problems.isEmpty(),
            "F-Droid's scanner flags these extensionless files as binaries:\n" + problems.joinToString("\n"),
        )
    }

    // ---- Minimal version-catalog reader ----------------------------------

    /**
     * Just enough of gradle/libs.versions.toml to resolve an accessor to the
     * coordinate(s) the scanner would check, mirroring scanner.py's
     * GradleVersionCatalog: aliases map to accessors by turning '-' and '_'
     * into '.'; libraries resolve to group:name[:version], plugins to
     * id[:version], bundles to their member libraries. Only the single-line
     * table forms this catalog uses are handled; a line that fails to parse
     * fails the test rather than silently resolving to nothing.
     */
    private class VersionCatalog(
        private val libraries: Map<String, String>,
        private val plugins: Map<String, String>,
        private val bundles: Map<String, List<String>>,
    ) {
        fun coordinates(accessor: String): List<String> = when {
            accessor.startsWith("plugins.") ->
                listOfNotNull(plugins[accessor.removePrefix("plugins.").removeSuffix(".asLibraryDependency")])
            accessor.startsWith("bundles.") -> bundles[accessor.removePrefix("bundles.")].orEmpty()
            else -> listOfNotNull(libraries[accessor])
        }

        companion object {
            private fun accessor(alias: String) = alias.replace('-', '.').replace('_', '.')

            fun parse(file: File): VersionCatalog {
                val sections = mutableMapOf<String, MutableMap<String, String>>()
                var current = ""
                for (raw in file.readLines()) {
                    val line = raw.substringBefore('#').trim()
                    if (line.isEmpty()) continue
                    if (line.startsWith("[") && line.endsWith("]")) {
                        current = line.trim('[', ']')
                        continue
                    }
                    val key = line.substringBefore('=').trim()
                    val value = line.substringAfter('=').trim()
                    if (key.isEmpty() || value.isEmpty()) fail("Cannot parse catalog line: $raw")
                    sections.getOrPut(current) { linkedMapOf() }[key] = value
                }
                val versions = sections["versions"].orEmpty().mapValues { unquote(it.value) }

                fun version(table: Map<String, String>): String? {
                    table["version"]?.let { return unquote(it) }
                    table["version.ref"]?.let { ref -> return versions[unquote(ref)] ?: fail("Unknown version ref $ref") }
                    return null
                }

                val libraries = sections["libraries"].orEmpty().map { (alias, value) ->
                    val coordinate = if (value.startsWith("{")) {
                        val table = inlineTable(value)
                        val module = table["module"]?.let(::unquote)
                            ?: table["group"]?.let { g -> table["name"]?.let { n -> "${unquote(g)}:${unquote(n)}" } }
                            ?: fail("Library $alias has no module or group/name")
                        version(table)?.let { "$module:$it" } ?: module
                    } else {
                        unquote(value)
                    }
                    accessor(alias) to coordinate
                }.toMap()

                val plugins = sections["plugins"].orEmpty().map { (alias, value) ->
                    val coordinate = if (value.startsWith("{")) {
                        val table = inlineTable(value)
                        val id = table["id"]?.let(::unquote) ?: fail("Plugin $alias has no id")
                        version(table)?.let { "$id:$it" } ?: id
                    } else {
                        unquote(value)
                    }
                    accessor(alias) to coordinate
                }.toMap()

                val bundles = sections["bundles"].orEmpty().mapValues { (_, value) ->
                    value.trim('[', ']').split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() }
                        .mapNotNull { libraries[accessor(it)] }
                }.mapKeys { accessor(it.key) }

                return VersionCatalog(libraries, plugins, bundles)
            }

            private fun unquote(s: String) = s.trim().trim('"', '\'')

            /** Parses '{ k = "v", k2.sub = "v2" }' into a map; values keep their quotes for [unquote]. */
            private fun inlineTable(value: String): Map<String, String> =
                value.trim().removePrefix("{").removeSuffix("}").split(',')
                    .map { it.trim() }.filter { it.isNotEmpty() }
                    .associate { entry -> entry.substringBefore('=').trim() to entry.substringAfter('=').trim() }
        }
    }
}
