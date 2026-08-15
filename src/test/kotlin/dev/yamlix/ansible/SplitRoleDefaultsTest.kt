package dev.yamlix.ansible

import dev.yamlix.ansible.vars.ValueKind
import dev.yamlix.ansible.vars.VarScope
import dev.yamlix.ansible.vars.VariableReportBuilder

/**
 * F24 — `defaults/main/` is a directory Ansible loads whole.
 *
 * Ansible accepts `defaults/main.yml`, `defaults/main.yaml`, and `defaults/
 * main/` as a directory; roles with a large surface routinely use the third.
 * The path checks looked exactly one level up for the role, so the directory
 * form fell through to a `vars_files` candidate — indexed with no role to
 * qualify it, which no host ever admits. The variables were in the index and
 * resolved to nothing regardless.
 *
 * Found on kubespray, where it is the whole of `kubespray_defaults`: `bin_dir`,
 * `kube_config_dir` and `kubectl`, the names read most often in the project,
 * every one of them reported as undefined.
 */
class SplitRoleDefaultsTest : FleetFixtureTestCase() {

    private val roleTasks = "roles/inline_param_agent/tasks/main.yml"

    fun testDefaultsInADirectoryAreRoleDefaults() {
        val rows = VariableReportBuilder.getInstance(project)
            .buildAll("probe_prefix", variableReferenceAt(roleTasks, 7, "probe_prefix").element)
            .flatMap { it.rows }

        val resolved = rows.filter { it.kind == ValueKind.LITERAL }
        assertTrue("nothing resolved: $rows", resolved.isNotEmpty())
        assertEquals(listOf("split-defaults"), resolved.map { it.value }.distinct())
    }

    /**
     * At role-defaults precedence, not the `vars_files` rank it used to land
     * on. The rank is the whole point: defaults must lose to everything else,
     * and a definition sitting at rank 15 would beat `group_vars`.
     */
    fun testTheyLandAtRoleDefaultsPrecedence() {
        val winner = VariableReportBuilder.getInstance(project)
            .buildAll("probe_prefix", variableReferenceAt(roleTasks, 7, "probe_prefix").element)
            .flatMap { it.rows }
            .firstNotNullOf { it.winner }
        assertEquals(VarScope.ROLE_DEFAULTS, winner.scope)
        assertEquals("inline_param_agent", winner.qualifier)
    }
}
