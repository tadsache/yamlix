package dev.yamlix.ansible

import dev.yamlix.ansible.overview.FileVariableViewService
import dev.yamlix.ansible.overview.IndexHealth
import dev.yamlix.ansible.overview.ViewState

/**
 * The index-health check against a real project.
 *
 * The danger of this feature is the false positive: telling someone their
 * working project has a broken index is worse than the silence it replaces.
 */
class IndexHealthFixtureTest : FleetFixtureTestCase() {

    private val taskFile = "roles/container_monitoring_agent/tasks/main.yml"

    /**
     * The decisive property: with a working index the accusation cannot be
     * made, even when told that nothing in the file resolved.
     */
    fun testAHealthyIndexIsNeverCalledBroken() {
        assertFalse(
            IndexHealth.looksBroken(project, file(taskFile), everythingUnresolved = true),
        )
    }

    /** And the ordinary path is unaffected. */
    fun testAHealthyProjectStillBuildsItsView() {
        assertTrue(
            FileVariableViewService.getInstance(project).build(file(taskFile))
                is ViewState.Ready,
        )
    }

    /** The fixture plainly defines variables — group_vars and role defaults. */
    fun testTheProjectIsSeenToDefineVariablesFromTheVfsAlone() {
        assertTrue(IndexHealth.definesVariablesOnDisk(project, file(taskFile)))
    }

    /**
     * A directory that could hold variables but does not is not evidence.
     *
     * Counting an empty `defaults/` would accuse a project whose index is
     * empty for the correct reason — the false positive that matters most,
     * since the message tells the reader to go and invalidate their caches.
     */
    fun testAnEmptyVarsDirectoryIsNotEvidence() {
        val dir = com.intellij.openapi.util.io.FileUtil.createTempDirectory("bare-ansible", null)
        java.io.File(dir, "ansible.cfg").writeText("[defaults]\nroles_path = ./roles\n")
        java.io.File(dir, "roles/hollow/defaults").mkdirs()   // exists, holds nothing
        java.io.File(dir, "roles/hollow/tasks").mkdirs()
        java.io.File(dir, "roles/hollow/tasks/main.yml").writeText(
            "---\n- name: use it\n  ansible.builtin.debug:\n    msg: \"{{ nothing_here }}\"\n"
        )
        val tasks = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(java.io.File(dir, "roles/hollow/tasks/main.yml"))!!

        assertFalse(
            "an empty defaults/ defines no variables",
            IndexHealth.definesVariablesOnDisk(project, tasks),
        )
    }

    /** The state renders as an explanation with the remedy, never as rows. */
    fun testTheBrokenIndexStateExplainsItselfAndSaysWhatToDo() {
        val rows = dev.yamlix.ansible.overview.FileViewTree.build(ViewState.IndexUnavailable)
        assertTrue(rows.all { it.node is dev.yamlix.ansible.overview.HintNode })
        val text = rows.joinToString(" ") { it.node.text }
        assertTrue("names the cause: $text", text.contains("index"))
        assertTrue("and the remedy: $text", text.contains("Invalidate Caches"))
    }
}
