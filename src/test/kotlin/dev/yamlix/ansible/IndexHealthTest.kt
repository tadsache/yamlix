package dev.yamlix.ansible

import dev.yamlix.ansible.overview.IndexHealth
import junit.framework.TestCase

/**
 * When "nothing is defined" means the index is broken rather than the project
 * is empty.
 *
 * All three conditions must hold. Each on its own is ordinary: plenty of files
 * resolve nothing, a project may genuinely declare no variables, and a healthy
 * index is not evidence of anything.
 */
class IndexHealthTest : TestCase() {

    fun testAllThreeSignalsTogetherMeanTheIndexIsBroken() {
        assertTrue(
            IndexHealth.looksBroken(
                everythingUnresolved = true,
                indexKnowsNothing = true,
                varFilesExistOnDisk = true,
            )
        )
    }

    /** A working index is never accused, however little resolves. */
    fun testAWorkingIndexIsNeverAccused() {
        assertFalse(
            IndexHealth.looksBroken(
                everythingUnresolved = true,
                indexKnowsNothing = false,
                varFilesExistOnDisk = true,
            )
        )
    }

    /**
     * A project that really defines no variables has an empty index correctly,
     * and must not be told its index is broken.
     */
    fun testAProjectWithNoVariablesIsNotBroken() {
        assertFalse(
            IndexHealth.looksBroken(
                everythingUnresolved = true,
                indexKnowsNothing = true,
                varFilesExistOnDisk = false,
            )
        )
    }

    /** If anything resolved, the index demonstrably works. */
    fun testSomethingResolvingSettlesIt() {
        assertFalse(
            IndexHealth.looksBroken(
                everythingUnresolved = false,
                indexKnowsNothing = true,
                varFilesExistOnDisk = true,
            )
        )
    }
}
