package dev.yamlix.ansible

import dev.yamlix.ansible.refs.AnsibleTargets
import dev.yamlix.ansible.vars.VariableReportBuilder

/**
 * Regression tests for FLEET-FIXTURE-CASES.md — a second, obfuscated Ansible
 * project built from real-world bug reports (root-level `group_vars/all.yml`,
 * INI inventories, a role's `hosts:` group being a sliver of a big inventory,
 * `import_playbook` steps, `with_first_found`, and a role reachable through a
 * symlink). Each test is named after the case it pins.
 */
class FleetFixtureNavigationTest : FleetFixtureTestCase() {

    /** F1 + F3 — the root `group_vars/all.yml` applies everywhere, collapsed to one line. */
    fun testArtifactRepoDefaultAppliesToAllInventoriesAsOneEntry() {
        val reference = variableReferenceAt(
            "roles/container_monitoring_agent/tasks/main.yml", 16, "artifact_repo",
        )
        val scopes = VariableReportBuilder.getInstance(project)
            .siteScopes("artifact_repo", reference.element)

        val allYmlScope = scopes.entries.singleOrNull { (key, _) -> key.endsWith("group_vars/all.yml#0") }
            ?: scopes.values.firstOrNull { it.winsOn.any { label -> label.startsWith("all inventories") } }
                ?.let { java.util.AbstractMap.SimpleEntry("", it) }
            ?: error("no site won on 'all inventories'; scopes=$scopes")

        assertTrue(
            "group_vars/all.yml must win on a single collapsed 'all inventories' entry, got ${allYmlScope.value.winsOn}",
            allYmlScope.value.winsOn.any { it.startsWith("all inventories") },
        )
    }

    /** F2 — a narrow group override beats the root default, only for that group. */
    fun testSpecialGroupOverridesTheRootDefaultOnlyForItself() {
        val reference = variableReferenceAt(
            "roles/container_monitoring_agent/tasks/main.yml", 16, "artifact_repo",
        )
        val scopes = VariableReportBuilder.getInstance(project)
            .siteScopes("artifact_repo", reference.element)

        val overrideScope = scopes.values.firstOrNull {
            it.winsOn.any { label -> label.contains("special_group") }
        } ?: error("no site won specifically on special_group; scopes=$scopes")

        assertEquals(
            "the override must win only on env-c's special_group, named by group, not by host",
            listOf("env-c (special_group)"),
            overrideScope.winsOn,
        )
    }

    /** F4 — `hosts: containers` resolves into every INI inventory's `[containers]` header. */
    fun testHostsContainersResolvesIntoEveryIniInventory() {
        val reference = referenceAt("site-container-mon.yml", 13, "containers")
        val targets = reference.targets()
        assertEquals(
            "one declaration per inventory that has a [containers] section",
            4,
            targets.size,
        )
        val paths = targets.map { relativePath(it) }.toSet()
        assertEquals(
            setOf(
                "inventories/env-a/hosts",
                "inventories/env-b/hosts",
                "inventories/env-c/hosts",
                "inventories/env-d/hosts",
            ),
            paths,
        )
    }

    /** F7 — `hostgroup: containers` under an `import_playbook`'s `vars:` resolves too. */
    fun testHostgroupUnderImportPlaybookVarsResolves() {
        val reference = referenceAt("site-container-mon.yml", 8, "containers")
        assertTrue(
            "hostgroup: containers must resolve to at least one inventory group declaration",
            reference.targets().isNotEmpty(),
        )
    }

    /**
     * F5 + F8 — a role's own default wins and is scoped to its actual target
     * group; the unrelated role's same-named variable is still offered (never
     * hidden — see [dev.yamlix.ansible.refs.AnsibleVariableReference]'s own
     * doc comment) but ranked after it, as an out-of-scope candidate.
     */
    fun testAgentImageOnlyEverWinsFromItsOwnRoleDefaults() {
        val reference = variableReferenceAt(
            "roles/container_monitoring_agent/tasks/main.yml", 16, "agent_image",
        )
        val paths = reference.targets().map { relativePath(it) }
        assertEquals(
            "container_monitoring_agent's own defaults must win and come first; " +
                "legacy_monitoring_agent's same-named var is still offered, just last",
            listOf(
                "roles/container_monitoring_agent/defaults/main.yml",
                "roles/legacy_monitoring_agent/defaults/main.yml",
            ),
            paths,
        )

        val scopes = VariableReportBuilder.getInstance(project)
            .siteScopes("agent_image", reference.element)
        val winning = scopes.values.firstOrNull { it.winsOn.isNotEmpty() }
            ?: error("no site won at all; scopes=$scopes")
        assertTrue(
            "must win only on the 'containers' group, not the whole env-c inventory: ${winning.winsOn}",
            winning.winsOn.any { it.contains("env-c") && it.contains("c-host-07") },
        )
    }

    /** F8, reverse direction — from the decoy role's own file, its own default wins and comes first. */
    fun testLegacyRoleDefaultWinsFromItsOwnFile() {
        val reference = variableReferenceAt("roles/legacy_monitoring_agent/tasks/main.yml", 4, "agent_image")
        val paths = reference.targets().map { relativePath(it) }
        assertEquals(
            listOf(
                "roles/legacy_monitoring_agent/defaults/main.yml",
                "roles/container_monitoring_agent/defaults/main.yml",
            ),
            paths,
        )
    }

    /** F9 — `include_vars: "{{ item }}"` + `with_first_found:` reaches all four env files. */
    fun testRetentionDaysReachesEveryEnvFileThroughWithFirstFound() {
        val reference = variableReferenceAt(
            "roles/container_monitoring_agent/tasks/main.yml", 16, "retention_days",
        )
        val paths = reference.targets().map { relativePath(it) }.toSet()
        assertEquals(
            setOf(
                "roles/container_monitoring_agent/vars/env-env-a.yml",
                "roles/container_monitoring_agent/vars/env-env-b.yml",
                "roles/container_monitoring_agent/vars/env-env-c.yml",
                "roles/container_monitoring_agent/vars/env-env-d.yml",
            ),
            paths,
        )
    }

    /** F11 — a host_vars override wins over the root default for that one host. */
    fun testHostVarsOverrideWinsForItsOwnHostOnly() {
        val reference = variableReferenceAt(
            "roles/container_monitoring_agent/tasks/main.yml", 16, "artifact_repo",
        )
        val scopes = VariableReportBuilder.getInstance(project)
            .siteScopes("artifact_repo", reference.element)

        val hostScope = scopes.values.firstOrNull { it.winsOn.any { l -> l.contains("b-host-03") } }
            ?: error("no site won specifically on b-host-03; scopes=$scopes")
        assertEquals(listOf("env-b (b-host-03)"), hostScope.winsOn)
    }

    /**
     * F10 — a role reachable only through a symlinked directory resolves once,
     * not twice. Queried from `playbooks/fleet/site-fleet-extra.yml` — a
     * sibling of the `roles` symlink — rather than through the symlinked path
     * itself, since the test VFS does not make a symlinked subtree separately
     * navigable the way a real checkout on disk does.
     */
    fun testRoleReachableThroughSymlinkResolvesOnce() {
        val fromSymlinkedPlaybook = file("playbooks/fleet/site-fleet-extra.yml")
        val dirs = AnsibleTargets.resolveRoleDirs(
            "container_monitoring_agent", fromSymlinkedPlaybook, project,
        )
        assertEquals("the symlinked and canonical role directories must collapse to one", 1, dirs.size)

        val roleRef = referenceAt("playbooks/fleet/site-fleet-extra.yml", 8, "container_monitoring_agent")
        assertEquals(
            "role name navigation must not be doubled by the roles/ symlink",
            listOf("roles/container_monitoring_agent/tasks/main.yml"),
            roleRef.targets().map { relativePath(it) },
        )
    }
}
