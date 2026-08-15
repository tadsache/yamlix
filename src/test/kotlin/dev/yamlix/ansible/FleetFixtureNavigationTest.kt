package dev.yamlix.ansible

import dev.yamlix.ansible.refs.AnsibleTargets
import dev.yamlix.ansible.vars.ValueKind
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
     * group, and the unrelated role's same-named variable is not offered at
     * all.
     *
     * It used to be listed last as an out-of-scope candidate. That was noise:
     * the two roles never share a play, so it can never be the declaration of
     * the thing under the caret — it was only ever there because Ansible's
     * variable namespace is global and the index is keyed by name. See
     * [dev.yamlix.ansible.refs.AnsibleVariableReference.computeTargets].
     */
    fun testAgentImageOnlyEverWinsFromItsOwnRoleDefaults() {
        val reference = variableReferenceAt(
            "roles/container_monitoring_agent/tasks/main.yml", 16, "agent_image",
        )
        val paths = reference.targets().map { relativePath(it) }
        assertEquals(
            "only container_monitoring_agent's own defaults belong here; " +
                "legacy_monitoring_agent's same-named var is unreachable from this play",
            listOf("roles/container_monitoring_agent/defaults/main.yml"),
            paths,
        )

        val scopes = VariableReportBuilder.getInstance(project)
            .siteScopes("agent_image", reference.element)
        val winning = scopes.values.firstOrNull { it.winsOn.isNotEmpty() }
            ?: error("no site won at all; scopes=$scopes")
        // Named by the group rather than by the one host it holds in each
        // environment: the play targets `containers`, and "the containers
        // group" is the finding — `env-a (a-host-01); env-b (b-host-01);
        // env-c (c-host-07); +1 more` said the same thing at four times the
        // width. The group is recovered from the hosts won, not from the
        // play's pattern; see VariableReportBuilder.groupNamed.
        assertEquals(
            "must win on the 'containers' group, not on whole inventories",
            listOf("all inventories (containers)"),
            winning.winsOn,
        )
    }

    /**
     * F17 — playbooks that resolve a variable identically are one answer.
     *
     * `site-container-mon.yml` and `playbooks/fleet/site-fleet-extra.yml` both
     * run `container_monitoring_agent` against `containers`, so they produce
     * the same table. Rendering both showed it twice; on a project where a
     * dozen sites import one shared play, a dozen times.
     */
    fun testIdenticalPerPlaybookReportsCollapse() {
        val reference = variableReferenceAt(
            "roles/container_monitoring_agent/tasks/main.yml", 16, "agent_image",
        )
        val reports = VariableReportBuilder.getInstance(project)
            .buildAll("agent_image", reference.element)

        assertEquals("both playbooks resolve it identically", 1, reports.size)
        assertEquals(
            "and the one report says which playbooks it holds for",
            listOf("site-container-mon.yml", "site-fleet-extra.yml"),
            reports.single().playbooks.map { it.name }.sorted(),
        )
    }

    /**
     * F17 — and rows identical in every inventory collapse to one line.
     *
     * A role bound to a one-host group yields two rows per environment: the
     * targeted host, and everyone else undefined. Across four environments
     * that was eight rows saying what two say.
     */
    fun testRowsIdenticalInEveryInventoryCollapse() {
        val reference = variableReferenceAt(
            "roles/container_monitoring_agent/tasks/main.yml", 16, "agent_image",
        )
        val rows = VariableReportBuilder.getInstance(project)
            .buildAll("agent_image", reference.element).single().rows

        assertEquals("one row for the targeted hosts, one for the rest", 2, rows.size)
        assertTrue(
            "both hold for every inventory: ${rows.map { it.inventory }}",
            rows.all { it.inventory == "all inventories" },
        )
        assertEquals(
            "the targeted row names the one containers host per environment",
            listOf("a-host-01", "b-host-01", "c-host-07", "d-host-01"),
            rows.single { it.kind == ValueKind.LITERAL }.hosts.sorted(),
        )
    }

    /**
     * F18 — a mapping value is a literal, not a run-time value.
     *
     * The index stores a scalar or nothing, so `artifact_repo:` with nested
     * keys arrived as null. Reading that null as "no static value" labelled it
     * *registered at run time* — telling the reader a plain literal only
     * exists during the play, which is simply false.
     */
    fun testMappingValuedVariableIsNotReportedAsRuntime() {
        val reference = variableReferenceAt(
            "roles/container_monitoring_agent/tasks/main.yml", 16, "artifact_repo",
        )
        val rows = VariableReportBuilder.getInstance(project)
            .buildAll("artifact_repo", reference.element).single().rows

        val default = rows.single { it.inventory == "all inventories" }
        assertEquals(
            "a nested mapping is a literal value, not a registered one",
            ValueKind.LITERAL,
            default.kind,
        )
        assertTrue(
            "and the value itself is shown: ${default.value}",
            default.value.orEmpty().contains("repo.example.test/generic-release"),
        )
    }

    /** F8, reverse direction — from the decoy role's own file, only its own default is offered. */
    fun testLegacyRoleDefaultWinsFromItsOwnFile() {
        val reference = variableReferenceAt("roles/legacy_monitoring_agent/tasks/main.yml", 4, "agent_image")
        val paths = reference.targets().map { relativePath(it) }
        assertEquals(listOf("roles/legacy_monitoring_agent/defaults/main.yml"), paths)
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
