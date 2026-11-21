package com.anopticlabs.gravel.accessibility

import coredevices.coreapp.TrackedTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checks main Kotlin sources against CLAUDE.md's rule "Composables take their colors from the theme".
 * Whole files are read, so a fixed color outside a composable needs an allowance too. A flagged line
 * passes when [allowed] lists its text for that file or [exemptFiles] lists the file. Skipped: util's
 * `theme` package, source sets whose name holds `test` or starts with `ios`, `apple` or `native`,
 * git submodules and [unscannedModules].
 *
 * Flagged, one line at a time with comments and literals blanked ([mask]): a `Color(...)` call, a
 * `Color.X` member other than `Transparent` and `Unspecified`, an import or type alias of `Color` or
 * `with(Color)`, by name a theme palette color or a color value an exempt or allowed file declares,
 * a slot read on a theme scheme ([themeNames]), calls to `parseColor`, `toColorInt`, `getColor`,
 * `colorResource` and the `ColorScheme` constructor and dark/light builders, and `R.color`. Not
 * flagged, among others: raw ARGB ints, a `Color.X` split across lines, the `Color` companion held
 * in a val (`val c = Color`), and colors from the converters on allowed lines (`toColorKmp`,
 * `toHsvColor`).
 */
class FixedColorSentinelTest {

    private val ui = "pebble/src/commonMain/kotlin/coredevices/pebble/ui"

    /** Lines allowed to hold a fixed color, by file; a text listed twice allows two lines. */
    private val allowed: Map<String, List<String>> = mapOf(
        // The color pickers: watch and timeline colors shown as data, the black or white drawn on
        // them, and each RGB slider in its own channel's color.
        "$ui/Colors.kt" to listOf(
            "color?.argbColor()?.let { Color(it) } ?: surfaceColor",
            "val previewColor = Color(0xFF000000u.toInt() or rgb.toInt())",
            "color = if (previewColor.luminance() > 0.55f) Color.Black else Color.White,",
            "ChannelSlider(\"R\", red, Color.Red) {",
            "ChannelSlider(\"G\", green, Color.Green) {",
            "ChannelSlider(\"B\", blue, Color.Blue) {",
            "val color = Color(0xFF000000u.toInt() or preset.rgb.toInt())",
            "val swatchColor = Color(0xFF000000u.toInt() or (currentRgb and 0x00FFFFFFu).toInt())",
            "color = if (swatchColor.luminance() > 0.55f) Color.Black else Color.White,",
            "val color = Color(this.argbColor())",
            "val textColor = if (luminance > 0.55) Color.Black else Color.White",
        ),
        // Store categories in the color the store sends, with black text on it.
        "$ui/LockerScreen.kt" to listOf(
            "color = Color.Black,",
            "Color(colorLong or 0xFF000000)",
            "Color(colorLong)",
        ),
        // Pixels of the placeholder image Compose previews show in place of a loaded one.
        "$ui/PreviewUtil.kt" to listOf("ColorImage(Color.Red.toArgb())"),
    )

    /** The Health tab keeps upstream's colors (CLAUDE.md, same rule); their names are flagged elsewhere. */
    private val exemptFiles = setOf("$ui/HealthCharts.kt", "$ui/HealthScreen.kt")

    /** libpebble3 holds no composables ([everyFileWithAComposableIsScanned]); experimental is not in the build. */
    private val unscannedModules = setOf("libpebble3", "experimental")

    private val sourceSetDir = Regex("""(?:^|/)src/([^/]+)/""")
    private val themeDir = Regex("""^util/src/[^/]+/kotlin/theme/""")

    /** [module] is the path before `src/`: empty for the root project, several segments for a nested one. */
    private data class Source(val path: String, val module: String)

    /** [path] as a main source, or null for a non-Kotlin file, a source set the class KDoc skips, or a path in [submodules]. */
    private fun sourceOf(path: String, submodules: List<String>): Source? {
        if (!path.endsWith(".kt") || submodules.any { path.startsWith("$it/") }) return null
        val set = sourceSetDir.find(path)
        val name = set?.groupValues?.get(1).orEmpty()
        if (name.contains("test", ignoreCase = true) || listOf("ios", "apple", "native").any(name::startsWith)) return null
        return Source(path, set?.let { path.substring(0, it.range.first) }.orEmpty())
    }

    private val mainSources: List<Source> by lazy {
        TrackedTree.files.mapNotNull { sourceOf(it, TrackedTree.submodulePaths) }
    }

    private val scannedFiles: Set<String> by lazy {
        mainSources.filter { it.module !in unscannedModules && !themeDir.containsMatchIn(it.path) }.map { it.path }.toSet()
    }

    private fun read(path: String) = TrackedTree.file(path).readText()

    private class ThemeNames(val colors: List<String>, val schemes: List<String>)

    /** `ColorScheme` itself and its dark/light builders (`darkColorScheme`, `dynamicLightColorScheme`, ...). */
    private val schemeBuilder = Regex("""(?:\w*(?:Dark|Light)|dark|light)?ColorScheme""")

    /**
     * Names declared `val name[: Type] = value` at the start of a line in [sources] (by path; annotations
     * and `public`, `internal` or `actual` allowed), classified by the type and the value's leading
     * dotted name (`a.b` of `a.b(...)`). A color: typed `Color`, a name with a `Color` segment, or one
     * starting with `lerp`, a known color, or a slot read on a known scheme. A scheme: typed
     * `ColorScheme`/`ExtendedColors`, a [schemeBuilder] or `ExtendedColors` call, or a known scheme or
     * its `copy`. Getters, delegates and vals inside a class or object are not read.
     */
    private fun themeNames(sources: Map<String, String>): ThemeNames {
        val declaration = Regex(
            """(?m)^(?:@\w+(?:\([^)\n]*\))?\s+)*(?:(?:public|internal|actual)\s+)*val\s+(\w+)\s*(?::\s*([\w.]+)\??\s*)?=\s*([\w.]+)""",
        )
        val found = sources.flatMap { (path, source) ->
            declaration.findAll(mask(source, path)).map {
                it.destructured.let { (name, type, value) -> Triple(name, type.substringAfterLast('.'), value) }
            }
        }
        val colors = mutableListOf<String>()
        val schemes = mutableListOf<String>()
        do {
            val before = colors.size + schemes.size
            for ((name, type, value) in found) {
                if (name in colors || name in schemes) continue
                val segments = value.split('.')
                val first = segments.first()
                val slotOfScheme = first in schemes && segments.size > 1 && segments[1] != "copy"
                when {
                    type == "Color" || "Color" in segments || first == "lerp" || first in colors || slotOfScheme ->
                        colors += name
                    type == "ColorScheme" || type == "ExtendedColors" || first in schemes ||
                        schemeBuilder.matches(segments.last()) || segments.last() == "ExtendedColors" -> schemes += name
                }
            }
        } while (colors.size + schemes.size > before)
        return ThemeNames(colors, schemes)
    }

    private val theme: ThemeNames by lazy {
        themeNames(mainSources.map { it.path }.filter { themeDir.containsMatchIn(it) }.associateWith(::read)).also {
            check(it.colors.isNotEmpty() && it.schemes.isNotEmpty()) { "No palette colors or schemes found in util's theme sources" }
        }
    }

    /** Colors declared in exempt and allowed files; a file missing from the tree is left to [everyAllowanceStillMatchesAFixedColor]. */
    private val declaredColors: List<String> by lazy {
        themeNames((exemptFiles + allowed.keys).filter { it in scannedFiles }.associateWith(::read)).colors
    }

    private val fixedColor: Regex by lazy {
        val names = theme.colors + declaredColors
        Regex(
            listOf(
                """\bColor\s*\(""",
                """\bColor\s*\.\s*(?:Companion\s*\.\s*)?+(?!Transparent\b|Unspecified\b)[\w*]""",
                """\bColor\s+as\b""",
                """\btypealias\s+\w+(?:<[^>]*>)?\s*=\s*(?:[\w.]+\.)?Color\b""",
                """\bwith\s*\(\s*(?:[\w.]+\.)?Color\s*\)""",
                """\b(?:parseColor|toColorInt|getColor|colorResource)\s*\(""",
                """\b$schemeBuilder\s*\(""",
                """\bR\s*\.\s*color\s*\.""",
                """\b(?:${names.joinToString("|")})\b""",
                """\b(?:${theme.schemes.joinToString("|")})\s*\.""",
            ).joinToString("|"),
        )
    }

    private data class Hit(val path: String, val line: Int, val text: String, val match: String) {
        override fun toString() = "$path:$line: $text   [$match]"
    }

    private val hitsByFile = mutableMapOf<String, List<Hit>>()

    private fun hits(path: String): List<Hit> = hitsByFile.getOrPut(path) {
        val source = read(path)
        val original = source.lines()
        mask(source, path).lines().mapIndexedNotNull { index, line ->
            fixedColor.find(line)?.let { Hit(path, index + 1, original[index].trim(), it.value) }
        }
    }

    /** The hits no allowance covers; each allowance covers one hit with its exact text. */
    private fun unallowed(hits: List<Hit>, allowances: List<String>): List<Hit> {
        val remaining = allowances.toMutableList()
        return hits.filterNot { remaining.remove(it.text) }
    }

    @Test
    fun noScannedLineHoldsAFixedColorOutsideTheAllowlist() {
        val unexpected = scannedFiles.filterNot { it in exemptFiles }.flatMap { unallowed(hits(it), allowed[it].orEmpty()) }
        assertTrue(
            unexpected.isEmpty(),
            "Fixed colors outside the theme (CLAUDE.md, \"Composables take their colors from the theme\"). " +
                "Take the color from MaterialTheme.colorScheme or ExtendedTheme.colors. A color shown as data, " +
                "or one outside a composable, goes in FixedColorSentinelTest.allowed with its reason; Health " +
                "code moved to a new file takes its exemptFiles entry along:\n" + unexpected.joinToString("\n"),
        )
    }

    @Test
    fun everyAllowanceStillMatchesAFixedColor() {
        val stale = allowed.flatMap { (path, lines) ->
            val found = if (path in scannedFiles) hits(path).map { it.text }.toMutableList() else mutableListOf()
            lines.filterNot(found::remove).map { "$path: $it" }
        } + exemptFiles.filter { it !in scannedFiles || hits(it).isEmpty() }
        assertTrue(stale.isEmpty(), "Allowances that match no fixed color; remove them:\n" + stale.joinToString("\n"))
    }

    @Test
    fun anAllowanceCoversOneLine() {
        val hits = listOf(Hit("a.kt", 1, "color = Color.Black,", "Color."), Hit("a.kt", 9, "color = Color.Black,", "Color."))
        assertEquals(listOf(hits[1]), unallowed(hits, listOf("color = Color.Black,")))
        assertEquals(emptyList(), unallowed(hits, listOf("color = Color.Black,", "color = Color.Black,")))
        assertEquals(hits, unallowed(hits, listOf("color = Color.White,")))
    }

    @Test
    fun everyFileWithAComposableIsScanned() {
        val missed = mainSources
            .filter { it.path !in scannedFiles && it.module != "experimental" && !themeDir.containsMatchIn(it.path) }
            .filter { composableAnnotation.containsMatchIn(mask(read(it.path), it.path)) }
            .map { it.path }
        assertTrue(
            missed.isEmpty(),
            "Files with composables that FixedColorSentinelTest does not scan; take their module out of " +
                "unscannedModules and allow its data colors:\n" + missed.joinToString("\n"),
        )
    }

    private val composableAnnotation = Regex("""@(?:\w+\.)*Composable\b""")

    /** Strings naming the experimental project, in code (a masked string keeps its opening quote; one in a comment does not). */
    private fun experimentalIncludes(settings: String): List<String> {
        val code = mask(settings, "settings.gradle.kts")
        return Regex(""""(?::)?experimental(?:"|:)""").findAll(settings).filter { code[it.range.first] == '"' }.map { it.value }.toList()
    }

    @Test
    fun experimentalStaysOutOfTheBuild() {
        assertEquals(
            emptyList(),
            experimentalIncludes(read("settings.gradle.kts")),
            "settings.gradle.kts includes experimental, which FixedColorSentinelTest skips",
        )
    }

    @Test
    fun experimentalIsFoundInCodeOnly() {
        assertEquals(
            listOf("\":experimental\"", "\"experimental\""),
            experimentalIncludes(
                "// include(\":experimental\")\ninclude(\":experimental\")\ninclude(\"experimental\")\ninclude(\":experimentalish\")",
            ),
        )
    }

    @Test
    fun aQualifiedComposableAnnotationIsFound() {
        assertTrue(composableAnnotation.containsMatchIn("@androidx.compose.runtime.Composable fun A() {}"))
    }

    @Test
    fun pathsAreClassified() {
        val submodules = listOf("whisper-native/src/main/cpp/whisper.cpp")
        assertEquals(
            listOf(Source("pebble/src/commonMain/kotlin/A.kt", "pebble"), Source("a/b/src/androidMain/kotlin/B.kt", "a/b"),
                Source("src/jvmMain/kotlin/C.kt", ""), Source("androidApp/src/debug/kotlin/D.kt", "androidApp")),
            listOf(
                "pebble/src/commonMain/kotlin/A.kt", "a/b/src/androidMain/kotlin/B.kt", "src/jvmMain/kotlin/C.kt",
                "androidApp/src/debug/kotlin/D.kt", "util/src/commonTest/kotlin/E.kt", "androidApp/src/androidTest/kotlin/F.kt",
                "pebble/src/iosMain/kotlin/G.kt", "settings.gradle.kts", "whisper-native/src/main/cpp/whisper.cpp/examples/H.kt",
            ).mapNotNull { sourceOf(it, submodules) },
        )
    }

    @Test
    fun themeNamesCoverEachDeclarationForm() {
        val sample = listOf(
            "val a = Color(0xFF000000)",
            "val b: androidx.compose.ui.graphics.Color = pick()",
            "internal actual val c = Color.Black",
            "val d = a.copy(alpha = 0.5f)",
            "val e =",
            "    lerp(a, c, 0.5f)",
            "@Stable",
            "val f = d",
            "@Suppress(\"x\") val g = Color.White",
            "private val h = Color.White",
            "    val i = Color.White",
            "val q = androidx.compose.ui.graphics.Color(0xFF000000)",
            "val r = q.copy()",
            "val s = darkColorScheme(primary = a)",
            "val t = s.copy(primary = b)",
            "val x = s.primary",
            "val u: ExtendedColors = make()",
            "val y = expressiveLightColorScheme()",
            "val z = ColorScheme(primary = a)",
            "val m = currentColorScheme()",
            "val v = Typography()",
            "// val w = Color.White",
        ).joinToString("\n")
        val names = themeNames(mapOf("sample" to sample))
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g", "q", "r", "x"), names.colors)
        assertEquals(listOf("s", "t", "u", "y", "z"), names.schemes)
    }

    @Test
    fun themeNamesComeFromTheThemeSources() {
        for (name in listOf("gravelPurple", "gravelPurpleDeep", "coreDarkGreen", "primaryDark", "onWarningLight")) {
            assertTrue(name in theme.colors, "$name missing from ${theme.colors}")
        }
        for (name in listOf("greyScheme", "blackScheme", "lightScheme", "onboardingScheme", "lightExtendedColors")) {
            assertTrue(name in theme.schemes, "$name missing from ${theme.schemes}")
        }
        assertTrue(declaredColors.isNotEmpty(), "No color values found in ${exemptFiles + allowed.keys}")
        val unmatched = declaredColors.filterNot { fixedColor.containsMatchIn("tint = $it") }
        assertTrue(unmatched.isEmpty(), "Declared color names missing from the matcher: $unmatched")
    }

    @Test
    fun matcherFlagsEachFixedColorSpelling() {
        val flagged = listOf(
            "val accent = Color(0xFF9129DE)",
            "Color(red = 0.5f, green = 0f, blue = 1f)",
            "color = Color.White,",
            "tint = Color.Red.copy(alpha = 0.9f)",
            "Color.hsv(270f, 0.8f, 0.9f)",
            "androidx.compose.ui.graphics.Color.Black",
            "import androidx.compose.ui.graphics.Color.Companion.White",
            "import androidx.compose.ui.graphics.Color as C",
            "import androidx.compose.ui.graphics.Color.Companion.*",
            "typealias Shade = androidx.compose.ui.graphics.Color",
            "typealias Shade<T> = Color",
            "val red = with(Color) { Red }",
            "val scheme = dynamicDarkColorScheme(context)",
            "MaterialTheme(colorScheme = expressiveLightColorScheme()) {}",
            "MaterialTheme(colorScheme = ColorScheme(primary = accent)) {}",
            "border = BorderStroke(1.dp, gravelPurple)",
            "tint = coreDarkGreen",
            "color = lightScheme.primary",
            "tint = darkExtendedColors.inCollection",
            "\"#9129DE\".toColorInt()",
            "android.graphics.Color.parseColor(hex)",
            "colorResource(Res.color.accent)",
            "context.getColor(id)",
            "ContextCompat.getColor(context, R.color.accent)",
            "MaterialTheme(colorScheme = darkColorScheme()) {}",
            "Text(\"https://example.com\", color = Color.Gray)",
            "Text(\"${'$'}{Color.Red}\")",
        )
        val passed = listOf(
            "color = Color.Transparent,",
            "color = Color.Unspecified",
            "color = Color.Companion.Transparent",
            "val accent: Color = MaterialTheme.colorScheme.primary",
            "MaterialTheme(colorScheme = lightScheme) {}",
            "TimelineColor.Red",
            "colorFilter = ColorFilter.tint(color)",
            "val faded = onSurface.copy(alpha = 0.62f)",
            "val purple = gravelPurpleish",
            "val name = getColorName()",
            "val mode = currentColorScheme()",
            "// color = Color.Red",
            "/* Color.Red */ color = primary",
            "Text(\"Color.Red\")",
        )
        assertEquals(flagged, flagged.filter { fixedColor.containsMatchIn(mask(it)) })
        assertEquals(emptyList(), passed.filter { fixedColor.containsMatchIn(mask(it)) })
    }

    @Test
    fun maskBlanksCommentsAndLiteralsAndKeepsLines() {
        val tq = "\"\"\""
        val source = listOf(
            "val a = \"x // y\" + Color.Red",
            "/* outer /* inner */ Color.Red */ val b = 1",
            "/**",
            " * Color.Red in a KDoc",
            " */",
            "val c = '\"'; val d = Color.Blue",
            "val e = ${tq}Color.Red",
            "raw $tq + Color.Green",
            "val f = \"${'$'}{ \"q\" + Color.Black } ${'$'}name Color.Gray\"",
            "fun `don't`() = Color.Cyan",
            "val g = \"a\\\"b // c\" + Color.Red",
            "val h = ${tq}x ${'$'}{Color.Red} y$tq",
        ).joinToString("\n")
        val masked = mask(source)
        assertEquals(source.length, masked.length)
        assertEquals(source.indices.filter { source[it] == '\n' }, masked.indices.filter { masked[it] == '\n' })
        val flaggedLines = masked.lines().withIndex().filter { fixedColor.containsMatchIn(it.value) }.map { it.index + 1 }
        assertEquals(listOf(1, 6, 8, 9, 10, 11, 12), flaggedLines)
    }

    @Test
    fun maskFailsOnASourceThatEndsInsideALiteralOrComment() {
        assertFailsWith<IllegalStateException> { mask("val s = \"\"\"open") }
        assertFailsWith<IllegalStateException> { mask("val s = \"${'$'}{ open") }
        assertFailsWith<IllegalStateException> { mask("/* open /* nested */") }
    }

    private enum class Kind { CODE, STRING, RAW_STRING }

    private class Frame(val kind: Kind) {
        var openBraces = 0
    }

    /**
     * [source] with comments, backtick names and the contents of string and char literals turned into
     * spaces, keeping line breaks so line N of the result is line N of the source. Code inside a
     * `${...}` string template stays. Throws, naming [name], when the source ends inside a string,
     * template or block comment, where the blanking cannot be trusted.
     */
    private fun mask(source: String, name: String = "sample"): String {
        val out = source.toCharArray()
        fun blank(from: Int, until: Int) {
            for (k in from until until) if (out[k] != '\n') out[k] = ' '
        }
        fun lineEnd(from: Int) = source.indexOf('\n', from).let { if (it < 0) source.length else it }

        val frames = ArrayDeque(listOf(Frame(Kind.CODE)))
        var i = 0
        while (i < source.length) {
            val frame = frames.last()
            val c = source[i]
            when (frame.kind) {
                Kind.CODE -> when {
                    source.startsWith("//", i) -> lineEnd(i).also { blank(i, it); i = it }
                    source.startsWith("/*", i) -> {
                        val end = blockCommentEnd(source, i)
                        check(end >= 0) { "$name ends inside a block comment" }
                        blank(i, end)
                        i = end
                    }
                    source.startsWith("\"\"\"", i) -> { frames.add(Frame(Kind.RAW_STRING)); i += 3 }
                    c == '"' -> { frames.add(Frame(Kind.STRING)); i++ }
                    c == '\'' || c == '`' -> {
                        val stop = lineEnd(i)
                        var end = i + 1
                        while (end < stop && source[end] != c) end += if (source[end] == '\\') 2 else 1
                        blank(i + 1, minOf(end, stop))
                        i = end + 1
                    }
                    c == '{' -> { frame.openBraces++; i++ }
                    c == '}' && frame.openBraces == 0 && frames.size > 1 -> { frames.removeLast(); i++ }
                    c == '}' -> { frame.openBraces--; i++ }
                    else -> i++
                }
                Kind.STRING, Kind.RAW_STRING -> when {
                    source.startsWith("\${", i) -> { frames.add(Frame(Kind.CODE)); i += 2 }
                    frame.kind == Kind.RAW_STRING && source.startsWith("\"\"\"", i) -> {
                        while (i < source.length && source[i] == '"') i++
                        frames.removeLast()
                    }
                    frame.kind == Kind.STRING && (c == '"' || c == '\n') -> { frames.removeLast(); i++ }
                    frame.kind == Kind.STRING && c == '\\' -> { blank(i, minOf(i + 2, source.length)); i += 2 }
                    else -> { blank(i, i + 1); i++ }
                }
            }
        }
        check(frames.size == 1) { "$name ends inside a string or string template" }
        return String(out)
    }

    /** The index just past the end of the (nested) block comment opening at [start], or -1 when it never ends. */
    private fun blockCommentEnd(source: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < source.length) {
            when {
                source.startsWith("/*", i) -> { depth++; i += 2 }
                source.startsWith("*/", i) -> { depth--; i += 2; if (depth == 0) return i }
                else -> i++
            }
        }
        return -1
    }
}
