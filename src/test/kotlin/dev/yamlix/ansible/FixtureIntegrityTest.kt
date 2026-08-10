package dev.yamlix.ansible

import junit.framework.TestCase
import java.io.File

/**
 * The fixture repo is the specification. `src/test/testData/fixture` is a copy of
 * it, and a copy that drifts is a specification that has been quietly edited to
 * suit the implementation. This test makes that impossible to do by accident.
 */
class FixtureIntegrityTest : TestCase() {

    fun testTestDataMatchesTheSpecificationFixture() {
        val spec = File("test-fixture")
        val copy = File("src/test/testData/fixture")
        assertTrue("specification fixture missing at ${spec.absolutePath}", spec.isDirectory)
        assertTrue("testData copy missing at ${copy.absolutePath}", copy.isDirectory)

        val specFiles = relativeFiles(spec) - "NAVIGATION-CASES.md"
        val copyFiles = relativeFiles(copy)

        assertEquals(
            "testData/fixture must contain exactly the fixture's Ansible files",
            specFiles.sorted(),
            copyFiles.sorted(),
        )

        for (path in specFiles) {
            assertEquals(
                "content of $path drifted from the specification fixture",
                File(spec, path).readText(),
                File(copy, path).readText(),
            )
        }
    }

    private fun relativeFiles(root: File): Set<String> =
        root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).path }
            // Opening the fixture in a sandbox IDE writes .idea/; that is a
            // local artefact, not part of the specification.
            .filterNot { it.startsWith(".idea/") || it.startsWith(".git/") }
            .toSet()
}
