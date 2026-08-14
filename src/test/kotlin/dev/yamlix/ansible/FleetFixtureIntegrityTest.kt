package dev.yamlix.ansible

import junit.framework.TestCase
import java.io.File

/**
 * `fleet-fixture/` is the specification for the real-world-shaped bug
 * reproductions in FLEET-FIXTURE-CASES.md; `src/test/testData/fleet-fixture`
 * is a copy of it. See [FixtureIntegrityTest] for why this must never drift —
 * same reasoning, second fixture.
 */
class FleetFixtureIntegrityTest : TestCase() {

    fun testTestDataMatchesTheSpecificationFixture() {
        val spec = File("fleet-fixture")
        val copy = File("src/test/testData/fleet-fixture")
        assertTrue("specification fixture missing at ${spec.absolutePath}", spec.isDirectory)
        assertTrue("testData copy missing at ${copy.absolutePath}", copy.isDirectory)

        val specFiles = relativeEntries(spec) - "FLEET-FIXTURE-CASES.md"
        val copyFiles = relativeEntries(copy)

        assertEquals(
            "testData/fleet-fixture must contain exactly the fixture's Ansible files",
            specFiles.sorted(),
            copyFiles.sorted(),
        )

        for (path in specFiles) {
            val specFile = File(spec, path)
            val copyFile = File(copy, path)
            if (isSymlink(specFile)) {
                assertTrue("$path must stay a symlink in the copy too", isSymlink(copyFile))
                assertEquals(
                    "symlink target of $path drifted",
                    specFile.toPath().let(java.nio.file.Files::readSymbolicLink).toString(),
                    copyFile.toPath().let(java.nio.file.Files::readSymbolicLink).toString(),
                )
            } else {
                assertEquals(
                    "content of $path drifted from the specification fixture",
                    specFile.readText(),
                    copyFile.readText(),
                )
            }
        }
    }

    private fun isSymlink(file: File): Boolean = java.nio.file.Files.isSymbolicLink(file.toPath())

    private fun relativeEntries(root: File): Set<String> =
        root.walkTopDown()
            .onEnter { !isSymlink(it) } // do not walk *into* a symlinked directory
            .filter { it != root && (it.isFile || isSymlink(it)) }
            .map { it.relativeTo(root).path }
            .filterNot { it.startsWith(".idea/") || it.startsWith(".git/") }
            .toSet()
}
