package dev.yamlix.ansible

import dev.yamlix.ansible.vars.ValueKind
import dev.yamlix.ansible.vars.VarScope
import dev.yamlix.ansible.vars.VariableReportBuilder

/**
 * F21 — a role parameter written directly on the `- role:` entry.
 *
 * ```yaml
 * dependencies:
 *   - role: adduser
 *     user: "{{ addusers.etcd }}"
 * ```
 *
 * The nested `vars:` form was the only one read, so a role whose whole
 * interface is passed inline had no definition anywhere and every use of it
 * reported "not defined in this project". Found on kubespray, where `adduser`
 * is written exactly this way and every task in it read as broken.
 */
class InlineRoleParamTest : FleetFixtureTestCase() {

    private val roleTasks = "roles/inline_param_agent/tasks/main.yml"

    fun testAnInlineRoleParameterIsADefinition() {
        val rows = VariableReportBuilder.getInstance(project)
            .buildAll("probe_label", variableReferenceAt(roleTasks, 7, "probe_label").element)
            .flatMap { it.rows }

        val resolved = rows.filter { it.kind == ValueKind.LITERAL }
        assertTrue("nothing resolved: $rows", resolved.isNotEmpty())
        assertEquals(listOf("container-fleet"), resolved.map { it.value }.distinct())
        assertEquals(
            "and it is a role parameter, at rank 21",
            VarScope.ROLE_PARAM,
            resolved.first().winner!!.scope,
        )
    }

    /**
     * A directive on the same entry is not a variable.
     *
     * The keys are told apart by name, so `when:` sitting beside the parameter
     * would otherwise be indexed as one — inventing a variable called `when`
     * with the value `true` in every project that guards a dependency.
     */
    fun testADirectiveOnTheEntryIsNotIndexedAsAParameter() {
        val rows = VariableReportBuilder.getInstance(project)
            .buildAll("when", variableReferenceAt(roleTasks, 7, "probe_label").element)
            .flatMap { it.rows }
        assertEquals(
            "`when` is Ansible's, not a variable: $rows",
            setOf(ValueKind.UNDEFINED),
            rows.map { it.kind }.toSet(),
        )
    }
}
