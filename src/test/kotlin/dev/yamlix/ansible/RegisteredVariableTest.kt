package dev.yamlix.ansible

import dev.yamlix.ansible.overview.FileVariableView
import dev.yamlix.ansible.overview.FileVariableViewService
import dev.yamlix.ansible.overview.RowStatus
import dev.yamlix.ansible.overview.ViewState

/**
 * F19 — a variable a task registers is not an undefined variable.
 *
 * It exists; it just does not exist yet. Calling it "not defined in this
 * project" is a false statement about correct code, and on debops — which
 * registers in one task file and reads in another — it was most of what the
 * plugin could not explain.
 */
class RegisteredVariableTest : FleetFixtureTestCase() {

    private fun view(path: String): FileVariableView =
        when (val state = FileVariableViewService.getInstance(project).build(file(path))) {
            is ViewState.Ready -> state.view
            else -> error("no view for $path: $state")
        }

    private val taskFile = "roles/register_probe_agent/tasks/main.yml"

    /**
     * `probe_status.stdout`, not `probe_status`: rows are named by the whole
     * path written at the use, and the file reads the registered result's
     * `stdout` rather than the bare result.
     */
    private val registered = "probe_status.stdout"

    fun testARegisteredVariableIsReportedAsRunTimeNotUndefined() {
        val row = view(taskFile).uses.single { it.name == registered }
        assertEquals(RowStatus.RUNTIME, row.status)
        assertTrue(
            "and never claims a value: ${row.summary}",
            row.summary == "set at run time",
        )
    }

    /** The note names the task that produces it, which is the useful part. */
    fun testTheNoteNamesTheTaskThatProducesIt() {
        val row = view(taskFile).uses.single { it.name == registered }
        assertTrue("names the file: ${row.note}", row.note.orEmpty().contains("collect.yml"))
    }

    /**
     * A genuinely absent variable is still absent — the point is to tell the
     * two apart, not to stop saying "undefined" at all.
     */
    fun testAGenuinelyUndefinedVariableIsStillUnresolved() {
        val row = view("roles/container_monitoring_agent/tasks/main.yml")
            .uses.single { it.name == "fleet_env" }
        assertEquals(RowStatus.UNRESOLVED, row.status)
    }
}
