package coredevices.coreapp

import kotlin.test.fail

/**
 * Replica of the textual checks fdroidserver's source scanner
 * (fdroidserver/scanner.py, scan_source) runs over a checkout, as pure
 * functions over a path, a file's text, or its leading bytes.
 *
 * Kept free of any tree access on purpose: [FdroidGuardrailsTest] applies
 * these functions to the tracked tree, and [FdroidScannerReplicaTest] feeds
 * each one a known-bad and a known-good sample so a transcription slip in a
 * regex or a parser regression cannot quietly turn the guardrail into a test
 * that flags nothing. The constants are copied from scanner.py and from the
 * SUSS signature database; when either changes upstream, update them in
 * place and name the upstream revision in the commit.
 *
 * What is replicated: the maven-URL regex and allowlist over comment-stripped
 * gradle files; the dependency-line matchers, catalog resolution, and the
 * "usual suspects" gradle signatures; the binary suffix list and the
 * `.so` regex; the first-1024-bytes sniff of extensionless files (dotfiles
 * included, as os.path.splitext sees them) and of `.bin`/`.out`/`.exe`;
 * the DexClassLoader grep over `.java` files; and the lockfile requirement
 * for `package.json`, `Cargo.toml`, and `pubspec.yaml`.
 *
 * Where this deliberately differs from scanner.py, it is stricter, never
 * looser, so a green run here cannot hide a red run there:
 * - a tracked `.apk` fails here; the scanner deletes it with an info message;
 * - a hit under `src/test` or `/test/` fails here; the scanner downgrades
 *   those to warnings;
 * - a settings file that declares extra catalogs (`versionCatalogs {}` or
 *   `defaultLibrariesExtensionName`) fails here rather than being resolved,
 *   because only the default `gradle/libs.versions.toml` reader exists;
 * - the version-catalog reader accepts only the single-line TOML forms this
 *   catalog uses and fails on anything else, where the scanner uses a full
 *   TOML parser (see [VersionCatalog]).
 * Not replicated at all: the scanner's warnings (executable binaries,
 * unusual file permissions), which do not fail an F-Droid build.
 */
internal object FdroidScannerReplica {

    // ---- Maven repository URLs -------------------------------------------

    /**
     * scanner.py MAVEN_URL_REGEX, verbatim apart from Java's requirement that
     * the literal brace be escaped. Applied, as there, to the file with line
     * comments and block comments removed.
     */
    val mavenUrlRegex = Regex(
        """\smaven\s*(?:\{.*?(?:setUrl|url)|\(\s*(?:url)?)\s*=?\s*(?:uri|URI|Uri\.create)?\(?\s*["']?([^\s"']+)["']?[^})]*[)}]""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** scanner.py allowed_repos: the hosts F-Droid trusts to serve free artifacts, plus Debian's local maven repo. */
    val allowedMavenRepos: List<Regex> = listOf(
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
    ).map { Regex("^https://" + Regex.escape(it) + "/*") } +
        listOf("/usr/share/maven-repo").map { Regex("^file://" + Regex.escape(it) + "/*") }

    /** common.py gradle_comment: a line the scanner drops before looking for maven URLs. */
    private val gradleLineComment = Regex("""^[ ]*//""")

    fun isGradleFile(path: String): Boolean = path.endsWith(".gradle") || path.endsWith(".gradle.kts")

    /** Maven repository URLs in a gradle file's text that are off the allowlist, in file order. */
    fun unknownMavenRepos(gradleText: String): List<String> {
        val nonComment = gradleText.lines().filterNot { gradleLineComment.containsMatchIn(it) }
        val text = nonComment.joinToString("\n").replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        return mavenUrlRegex.findAll(text)
            .map { it.groupValues[1] }
            .filter { url -> allowedMavenRepos.none { it.containsMatchIn(url) } }
            .toList()
    }

    // ---- Dependency lines naming non-free artifacts ----------------------

    /**
     * The gradle "usual suspects" from F-Droid's SUSS signature database
     * (https://fdroid.gitlab.io/fdroid-suss/suss.json, version 1, database
     * timestamp 2026-08-01), copied verbatim. The scanner compiles each as
     * ".*" + signature, case-insensitive, and matches it against every
     * dependency line and every catalog coordinate such a line resolves to;
     * any hit is a build-failing error on the buildserver.
     */
    val scannerGradleSignatures: List<Regex> = listOf(
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
     * scanner.py get_gradle_compile_commands, for a build with no product
     * flavors: the leading tokens that make a line a dependency (or plugin)
     * declaration in the scanner's eyes.
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

    /**
     * Usual-suspect hits in a gradle file, one string per hit naming the
     * 1-based line, the signature, and what matched: the line itself for a
     * literal coordinate, or the catalog coordinate an accessor resolved to.
     */
    fun usualSuspects(gradleLines: List<String>, catalog: VersionCatalog): List<String> {
        val hits = mutableListOf<String>()
        gradleLines.forEachIndexed { index, line ->
            val where = "line ${index + 1}"
            if (dependencyLineWithoutCatalog.any { it.matchesAt(line, 0) }) {
                scannerGradleSignatures.forEach { signature ->
                    if (signature.matchesAt(line, 0)) {
                        hits += "$where: usual suspect '${signature.pattern.removePrefix(".*")}' in: ${line.trim()}"
                    }
                }
            }
            val accessor = dependencyLineWithCatalog.firstNotNullOfOrNull { it.matchAt(line, 0)?.groupValues?.get(1) }
            if (accessor != null) {
                for (coordinate in catalog.coordinates(accessor)) {
                    scannerGradleSignatures.forEach { signature ->
                        if (signature.matchesAt(coordinate, 0)) {
                            hits += "$where: usual suspect '${signature.pattern.removePrefix(".*")}' via libs.$accessor -> $coordinate"
                        }
                    }
                }
            }
        }
        return hits
    }

    /**
     * scanner.py get_catalogs reads catalogs the settings file declares in a
     * `versionCatalogs {}` block and honors `defaultLibrariesExtensionName`;
     * this replica reads only the default `gradle/libs.versions.toml`, so a
     * settings file using either construct must fail the guardrail until the
     * replica learns it, rather than have its dependency lines resolve to
     * nothing here while the scanner resolves them.
     */
    fun declaresCatalogsTheReplicaCannotRead(settingsText: String): Boolean =
        Regex("""versionCatalogs\s*\{""").containsMatchIn(settingsText) ||
            Regex("""defaultLibrariesExtensionName\s*=""").containsMatchIn(settingsText)

    // ---- Binaries --------------------------------------------------------

    /**
     * Names the scanner deletes without counting an error (scanner.py's
     * gradle-wrapper exemption); the replica ignores them likewise.
     */
    val scannerExemptNames = setOf("gradle-wrapper.jar", "gradlew", "gradlew.bat", "gradle-daemon-jvm.properties")

    /**
     * scanner.py's binary suffixes, each a build-failing error unless the
     * recipe removes or ignores the path. `.apk` is deliberately kept in
     * this list although the scanner only deletes such files: a tracked APK
     * has no place in the source tree either way.
     */
    private val binarySuffixes = listOf(".a", ".aar", ".class", ".dex", ".gz", ".tgz", ".zip", ".jar", ".wasm", ".apk")
    private val sharedObject = Regex(""".*\.so(\..+)*$""")

    /** Why the scanner would reject this tracked path on its name alone, or null. */
    fun binaryKind(path: String): String? {
        val name = path.substringAfterLast('/')
        if (name in scannerExemptNames) return null
        if (sharedObject.matches(name)) return "shared library"
        return binarySuffixes.firstOrNull { name.endsWith(it) }?.let { "'$it' binary" }
    }

    /**
     * Whether the scanner byte-sniffs this path: files whose extension, as
     * Python's os.path.splitext computes it, is empty or one of .bin/.out/
     * .exe. splitext ignores leading dots, so `.gitignore` has no extension
     * and is sniffed, while `foo.` has extension "." and is not. Names the
     * scanner exempts and names caught by the suffix list above are never
     * sniffed, mirroring the order of the scanner's checks.
     */
    fun isSniffed(path: String): Boolean {
        val name = path.substringAfterLast('/')
        if (name in scannerExemptNames || binaryKind(path) != null) return false
        val stem = name.trimStart('.')
        val extension = if (stem.contains('.')) stem.substring(stem.lastIndexOf('.')) else ""
        return extension in setOf("", ".bin", ".out", ".exe")
    }

    /** scanner.py textchars: the byte set a text file is allowed to contain. */
    private val textBytes = (setOf(7, 8, 9, 10, 12, 13, 27) + (0x20..0xFF)).minus(0x7F).map { it.toByte() }.toSet()

    /** scanner.py is_binary over the first 1024 bytes of a file. */
    fun isBinary(leadingBytes: ByteArray): Boolean = leadingBytes.take(1024).any { it !in textBytes }

    // ---- Other source checks --------------------------------------------

    /** scanner.py DEPFILE: dependency manifests that must have a lockfile beside them or in an ancestor directory. */
    val dependencyFileLocks: Map<String, List<String>> = mapOf(
        "Cargo.toml" to listOf("Cargo.lock"),
        "pubspec.yaml" to listOf("pubspec.lock"),
        "package.json" to listOf("package-lock.json", "yarn.lock", "pnpm-lock.yaml", "bun.lock"),
    )

    /**
     * True when [path] is a dependency manifest and no matching lockfile is
     * tracked in its directory or any ancestor up to the checkout root
     * (the scanner walks up to the build directory the same way).
     */
    fun lacksLockfile(path: String, trackedPaths: Set<String>): Boolean {
        val locks = dependencyFileLocks[path.substringAfterLast('/')] ?: return false
        var directory = path.substringBeforeLast('/', "")
        while (true) {
            val prefix = if (directory.isEmpty()) "" else "$directory/"
            if (locks.any { "$prefix$it" in trackedPaths }) return false
            if (directory.isEmpty()) return true
            directory = directory.substringBeforeLast('/', "")
        }
    }

    /** scanner.py's `.java` check: any line mentioning DexClassLoader is a build-failing error. */
    fun usesDexClassLoader(path: String, lines: List<String>): Boolean =
        path.endsWith(".java") && lines.any { it.contains("DexClassLoader") }

    // ---- Minimal version-catalog reader ----------------------------------

    /**
     * Just enough of gradle/libs.versions.toml to resolve an accessor to the
     * coordinate(s) the scanner would check, mirroring scanner.py's
     * GradleVersionCatalog: aliases map to accessors by turning '-' and '_'
     * into '.'; libraries resolve to group:name[:version], plugins to
     * id[:version], bundles to their member libraries.
     *
     * Only the single-line TOML forms this catalog uses are accepted: a
     * quoted string, a one-line inline table of quoted strings, or a one-line
     * array. Anything else (a line without '=', a multi-line array or table,
     * a nested table such as `version = { strictly = ... }`) fails the parse,
     * and so the guardrail, rather than being skipped: the scanner reads the
     * catalog with a full TOML parser, so a form this reader cannot see is a
     * dependency line it would silently stop checking while F-Droid still
     * checks it. Extend the reader when the catalog needs a new form.
     */
    class VersionCatalog(
        val libraries: Map<String, String>,
        val plugins: Map<String, String>,
        val bundles: Map<String, List<String>>,
    ) {
        fun coordinates(accessor: String): List<String> = when {
            accessor.startsWith("plugins.") ->
                listOfNotNull(plugins[accessor.removePrefix("plugins.").removeSuffix(".asLibraryDependency")])
            accessor.startsWith("bundles.") -> bundles[accessor.removePrefix("bundles.")].orEmpty()
            else -> listOfNotNull(libraries[accessor])
        }

        companion object {
            /** A catalog with no entries, for gradle files under a settings root without one. */
            val EMPTY = VersionCatalog(emptyMap(), emptyMap(), emptyMap())

            private fun accessor(alias: String) = alias.replace('-', '.').replace('_', '.')

            fun parse(tomlLines: List<String>): VersionCatalog {
                val sections = mutableMapOf<String, MutableMap<String, String>>()
                var current = ""
                for (raw in tomlLines) {
                    val line = stripComment(raw).trim()
                    if (line.isEmpty()) continue
                    if (line.startsWith("[") && line.endsWith("]") && !line.contains('=')) {
                        current = line.trim('[', ']').trim()
                        continue
                    }
                    if (!line.contains('=')) fail("Cannot parse catalog line (no '=', is it a multi-line value?): $raw")
                    val key = unquote(line.substringBefore('='))
                    val value = line.substringAfter('=').trim()
                    if (key.isEmpty() || value.isEmpty()) fail("Cannot parse catalog line: $raw")
                    val singleLine = when {
                        value.startsWith("{") -> value.endsWith("}") && !value.drop(1).contains('{')
                        value.startsWith("[") -> value.endsWith("]") && !value.drop(1).contains('[')
                        else -> isQuoted(value)
                    }
                    if (!singleLine) fail("Cannot parse catalog line (only single-line strings, tables, and arrays are supported): $raw")
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

                val bundles = sections["bundles"].orEmpty().mapValues { (alias, value) ->
                    if (!value.startsWith("[")) fail("Bundle $alias is not an array: $value")
                    value.trim('[', ']').split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        .map { member ->
                            if (!isQuoted(member)) fail("Bundle $alias has an unquoted member: $member")
                            libraries[accessor(unquote(member))] ?: fail("Bundle $alias names unknown library $member")
                        }
                }.mapKeys { accessor(it.key) }

                return VersionCatalog(libraries, plugins, bundles)
            }

            /** Drops a trailing `# comment`, leaving '#' inside quotes alone. */
            private fun stripComment(line: String): String {
                var quote: Char? = null
                line.forEachIndexed { index, c ->
                    when {
                        quote != null -> if (c == quote) quote = null
                        c == '"' || c == '\'' -> quote = c
                        c == '#' -> return line.substring(0, index)
                    }
                }
                return line
            }

            private fun isQuoted(s: String): Boolean {
                val t = s.trim()
                return t.length >= 2 && (t.first() == '"' || t.first() == '\'') && t.last() == t.first()
            }

            private fun unquote(s: String) = s.trim().trim('"', '\'')

            /** Parses '{ k = "v", k2.sub = "v2" }' into a map; values keep their quotes for [unquote]. */
            private fun inlineTable(value: String): Map<String, String> =
                value.trim().removePrefix("{").removeSuffix("}").split(',')
                    .map { it.trim() }.filter { it.isNotEmpty() }
                    .associate { entry ->
                        if (!entry.contains('=')) fail("Cannot parse inline table entry: $entry")
                        val v = entry.substringAfter('=').trim()
                        if (!isQuoted(v)) fail("Inline table value is not a quoted string: $entry")
                        entry.substringBefore('=').trim() to v
                    }
        }
    }
}
