package dev.yamlix.ansible

import dev.yamlix.ansible.overview.FileVariableViewService
import dev.yamlix.ansible.overview.RowStatus
import dev.yamlix.ansible.overview.ViewState
import dev.yamlix.ansible.vars.VarScope

/**
 * F25 — a `vars_files:` entry is YAML whatever it is called.
 *
 * Ansible loads what a play names, and projects use that freedom: algo keeps
 * its whole configuration in `config.cfg`, a YAML mapping with an extension no
 * IDE associates with YAML. The index never saw the file, so every variable in
 * it read as undefined — and then worse than undefined, because the resolver
 * settled on whatever else mentioned the name, which for `cloud_providers` was
 * an unrelated list in `tests/fixtures/`. Adding paths on top turned that into
 * a confident walk into the wrong dictionary.
 *
 * Read at resolution time rather than indexed, and that distinction is the
 * point: which files are vars files is a fact about a *playbook*, not a path.
 * Indexing every `.cfg` in every project would invent variables out of the INI
 * files that extension usually belongs to.
 */
class UnindexedVarsFileTest : FleetFixtureTestCase() {

    private val playbookTasks = "site-probe-cfg.yml"

    private fun row(name: String) =
        when (val state = FileVariableViewService.getInstance(project).build(file(playbookTasks))) {
            is ViewState.Ready -> state.view.uses.single { it.name == name }
            else -> error("no view: $state")
        }

    fun testAVariableFromAnUnindexedVarsFileResolves() {
        val row = row("probe_cfg_setting")
        assertEquals(RowStatus.RESOLVED, row.status)
        assertEquals("from-cfg-file", row.summary)
    }

    /** At `vars_files` precedence, and navigable back to the line in the file. */
    fun testItLandsAtVarsFilePrecedenceAndPointsAtTheFile() {
        val site = row("probe_cfg_setting").sites.single()
        assertEquals(VarScope.VARS_FILE.display, site.scopeLabel)
        assertEquals("probe-settings.cfg", site.file.name)
    }

    /**
     * Only a file some play actually names is read.
     *
     * The whole safety of this rests on it: a `.cfg` nobody references stays
     * unread, so an INI file of the same name cannot contribute phantom
     * variables to the project.
     */
    fun testAnUnreferencedFileIsNotRead() {
        myFixture.addFileToProject("stray.cfg", "---\nstray_cfg_setting: never-loaded\n")
        val state = FileVariableViewService.getInstance(project).build(file(playbookTasks))
        val names = (state as ViewState.Ready).view.uses.map { it.name }
        assertFalse("nothing should have read stray.cfg: $names", "stray_cfg_setting" in names)

        myFixture.addFileToProject(
            "roles/pattern_probe_agent/tasks/stray.yml",
            "---\n- name: read it\n  ansible.builtin.debug:\n" +
                "    msg: \"{{ stray_cfg_setting }}\"\n",
        )
        val stray = (FileVariableViewService.getInstance(project)
            .build(file("roles/pattern_probe_agent/tasks/stray.yml")) as ViewState.Ready)
            .view.uses.single { it.name == "stray_cfg_setting" }
        assertEquals(
            "a .cfg no play names defines nothing",
            RowStatus.UNRESOLVED,
            stray.status,
        )
    }
}
