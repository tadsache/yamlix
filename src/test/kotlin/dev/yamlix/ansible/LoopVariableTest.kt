package dev.yamlix.ansible

import dev.yamlix.ansible.overview.FileVariableViewService
import dev.yamlix.ansible.overview.RowStatus
import dev.yamlix.ansible.overview.ViewState
import dev.yamlix.ansible.vars.ValueKind
import dev.yamlix.ansible.vars.VariableReportBuilder

/**
 * F20 — a name bound by a `loop:` is not an undefined variable.
 *
 * The shape is everywhere in real Ansible and was reported as broken by the
 * plugin on every occurrence: a role reads `{{ user }}`, and the only thing in
 * the repository that mentions the name is a `loop_control: loop_var:` in some
 * caller. Nothing defines it, so the index has nothing, so every use read "not
 * defined in this project" — an accusation against a role that is correct.
 */
class LoopVariableTest : FleetFixtureTestCase() {

    private val roleTasks = "roles/loop_probe_agent/tasks/main.yml"

    private fun report(path: String, line: Int, name: String) =
        VariableReportBuilder.getInstance(project)
            .buildAll(name, variableReferenceAt(path, line, name).element)

    /** The caller's binding is found from inside the role, across two files. */
    fun testCallerSuppliedLoopVariableIsNotUndefined() {
        val rows = report(roleTasks, 7, "probe_target").flatMap { it.rows }
        assertEquals(
            "one entry of the caller's loop, not undefined: $rows",
            setOf(ValueKind.LOOP_ITEM),
            rows.map { it.kind }.toSet(),
        )
        assertTrue(
            "names the file the binding is written in: ${rows.first().note}",
            rows.first().note!!.contains("site-probe-loop.yml"),
        )
    }

    /**
     * ...and it carries the collection, which is the half of the answer that
     * is actually in the repository and can be followed further.
     */
    fun testTheLoopedCollectionIsReported() {
        val row = report(roleTasks, 7, "probe_target").flatMap { it.rows }.first()
        assertEquals("{{ probe_targets }}", row.value)
    }

    /** A loop in the same file is answered from the PSI, without the index. */
    fun testLocalLoopVariableIsNotUndefined() {
        val rows = report(roleTasks, 13, "probe_port").flatMap { it.rows }
        assertEquals(setOf(ValueKind.LOOP_ITEM), rows.map { it.kind }.toSet())
        assertEquals("{{ probe_ports }}", rows.first().value)
    }

    /**
     * A loop variable never competes for a value.
     *
     * It is indexed, so it could be mistaken for a definition; letting it into
     * precedence would have it "win" with a value that exists on no iteration.
     * `probe_interval` is defined in the role's defaults and must resolve to
     * that, in a file where a loop binding is in scope two lines above.
     */
    fun testALoopBindingDoesNotWinAgainstARealDefinition() {
        val rows = report(roleTasks, 7, "probe_interval").flatMap { it.rows }
        // Undefined on the hosts this role never runs on is the ordinary
        // per-host split and not what this is about; what matters is that a
        // real definition is found and no loop row is invented beside it.
        assertFalse("$rows", ValueKind.LOOP_ITEM in rows.map { it.kind })
        assertEquals(
            listOf("30"),
            rows.filter { it.kind == ValueKind.LITERAL }.map { it.value }.distinct(),
        )
    }

    /**
     * The binding is not claimed by roles it was never handed to.
     *
     * Matching a loop variable by name alone would attribute one role's
     * `probe_target` to every other role that happens to read the same name —
     * exactly the collision F16 exists to prevent for ordinary definitions.
     */
    fun testTheBindingDoesNotLeakIntoAnUnrelatedRole() {
        myFixture.addFileToProject(
            "roles/pattern_probe_agent/tasks/borrow.yml",
            "---\n- name: Read a name it was never given\n" +
                "  ansible.builtin.debug:\n    msg: \"{{ probe_target }}\"\n",
        )
        val rows = report("roles/pattern_probe_agent/tasks/borrow.yml", 4, "probe_target")
            .flatMap { it.rows }
        assertEquals(
            "undefined here, and rightly so: $rows",
            setOf(ValueKind.UNDEFINED),
            rows.map { it.kind }.toSet(),
        )
    }

    /** The tool window says so in its own vocabulary. */
    fun testTheToolWindowRowReadsAsALoopItem() {
        val state = FileVariableViewService.getInstance(project).build(file(roleTasks))
        val view = (state as ViewState.Ready).view
        val row = view.uses.single { it.name == "probe_target" }
        assertEquals(RowStatus.LOOP_ITEM, row.status)
        assertEquals("each entry of {{ probe_targets }}", row.summary)
    }
}
