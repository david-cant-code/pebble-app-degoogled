package com.anopticlabs.gravel

import coredevices.coreapp.TrackedTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Source sentinels for the token layout of the PebbleOS changelog workflow (DESIGN_NOTES, "PebbleOS
 * changelog list"), which runs only on GitHub. Checked as text, line by line.
 */
class ChangelogWorkflowSentinelTest {

    private val workflow by lazy { TrackedTree.file(".github/workflows/pebbleos-changelog.yml").readLines() }

    private fun String.indent() = length - trimStart().length

    /** The lines below [key] at [indent], up to the next line at that indent or less. */
    private fun block(lines: List<String>, key: String, indent: Int): List<String> {
        val start = lines.indexOf(" ".repeat(indent) + key)
        assertTrue(start >= 0, "no `$key` at indent $indent in the workflow")
        return lines.drop(start + 1).takeWhile { it.isBlank() || it.indent() > indent }
    }

    private val jobs by lazy { block(workflow, "jobs:", 0) }

    private val jobNames by lazy {
        jobs.filter { it.indent() == 2 && it.endsWith(":") && !it.trimStart().startsWith("#") }.map { it.trim().removeSuffix(":") }
    }

    private fun job(name: String) = block(jobs, "$name:", 2)

    private fun steps(job: List<String>): List<List<String>> {
        if ("    steps:" !in job) return emptyList()
        val lines = block(job, "steps:", 4).filter { it.isNotBlank() }
        val dash = lines.first().indent()
        val starts = lines.indices.filter { lines[it].indent() == dash && lines[it].trimStart().startsWith("- ") }
        return starts.mapIndexed { i, start -> lines.subList(start, starts.getOrElse(i + 1) { lines.size }) }
    }

    private fun trimmed(lines: List<String>) = lines.map { it.trim() }.filter { it.isNotEmpty() }

    @Test
    fun theWorkflowTokenIsReadOnlyExceptInThePublishJob() {
        assertEquals(listOf("contents: read"), trimmed(block(workflow, "permissions:", 0)))
        assertTrue("publish" in jobNames && jobNames.size > 1, "unexpected jobs: $jobNames")
        for (name in jobNames - "publish") {
            assertTrue(job(name).none { it.trim().startsWith("permissions") }, "the $name job sets its own permissions")
        }
        assertEquals(listOf("contents: write"), trimmed(block(job("publish"), "permissions:", 4)))
    }

    @Test
    fun everyCheckoutLeavesNoCredentialsBehind() {
        val checkouts = jobNames.flatMap { name ->
            steps(job(name)).filter { step -> step.any { "actions/checkout@" in it } }.map { name to it }
        }
        assertTrue(checkouts.isNotEmpty(), "no checkout step found")
        for ((name, step) in checkouts) {
            assertTrue(step.any { it.trim() == "persist-credentials: false" }, "a checkout in the $name job keeps credentials")
        }
    }

    @Test
    fun onlyThePublishJobsPushStepUsesTheToken() {
        assertTrue(workflow.none { "secrets." in it }, "the workflow reads a secret")
        assertEquals(1, workflow.count { "github.token" in it })
        val push = steps(job("publish")).filter { step -> step.any { "github.token" in it } }
        assertEquals(listOf("- name: Push"), push.map { it.first().trim() })
    }

    @Test
    fun theJobsRunOnlyUnderTheirConditions() {
        val update = trimmed(job("update"))
        assertTrue("needs: test" in update)
        assertTrue("if: github.event_name != 'push' && github.ref == 'refs/heads/master'" in update)
        val publish = trimmed(job("publish"))
        assertTrue("needs: update" in publish)
        assertTrue("if: needs.update.outputs.list != ''" in publish)
    }
}
