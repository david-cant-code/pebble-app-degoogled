package coredevices.coreapp

import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The checkout as F-Droid's buildserver sees it: every git-tracked path of
 * the superproject plus every tracked path of each checked-out submodule,
 * relative to the repository root.
 *
 * Shared by the tree-reading sentinels in this source set
 * ([FdroidGuardrailsTest], [ExcludedAssetSentinelTest]) so they agree on
 * what "the tree" is. Reading through git rather than walking the disk keeps
 * build outputs, IDE files, and untracked scratch out of the picture, which
 * is also what an F-Droid clone contains.
 *
 * Fails closed on an unpopulated submodule: F-Droid's recipe checks
 * submodules out (`submodules: yes`) and its scanner walks them like any
 * other directory, so a scan that silently skipped an empty submodule
 * directory would pass here and fail there. `git submodule update --init
 * --recursive` is the fix on a checkout that trips it.
 */
internal object TrackedTree {

    /** The checkout root: the nearest ancestor of the test working directory holding settings.gradle.kts. */
    val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: fail("Could not locate the repository root from ${File("").absolutePath}")
    }

    /** Submodule paths declared in .gitmodules, relative to the root (empty when there is no .gitmodules). */
    val submodulePaths: List<String> by lazy {
        if (!File(root, ".gitmodules").isFile) return@lazy emptyList()
        git("config", "--file", ".gitmodules", "--get-regexp", """^submodule\..*\.path$""")
            .lines()
            .filter { it.isNotBlank() }
            .map { it.substringAfterLast(' ') }
    }

    /**
     * Every tracked path, superproject and submodules together. A submodule
     * that is declared but not checked out lists as its bare gitlink path
     * instead of its contents, which is the case this fails on.
     */
    val files: List<String> by lazy {
        val listed = git("ls-files", "-z", "--recurse-submodules").split('\u0000').filter { it.isNotEmpty() }
        for (submodule in submodulePaths) {
            assertTrue(
                listed.any { it.startsWith("$submodule/") },
                "Submodule $submodule is not checked out, so its tree cannot be scanned; " +
                    "run `git submodule update --init --recursive`.",
            )
        }
        listed
    }

    fun file(path: String): File = File(root, path)

    /** Runs git at the root and returns its stdout; a non-zero exit fails the calling test. */
    fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val exit = process.waitFor()
        assertTrue(exit == 0, "git ${args.joinToString(" ")} failed ($exit): $output")
        return output
    }
}
