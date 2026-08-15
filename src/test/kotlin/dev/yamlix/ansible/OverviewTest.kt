package dev.yamlix.ansible

import dev.yamlix.ansible.overview.AnsibleOverviewService
import dev.yamlix.ansible.overview.FindingKind

/**
 * The project-level model behind the Ansible tool window.
 *
 * Asserted headlessly and deliberately: the panel is a renderer over this, so
 * everything worth being right about is right here, where it can be tested
 * without Swing.
 */
class OverviewTest : FleetFixtureTestCase() {

    private fun overview() =
        AnsibleOverviewService.getInstance(project).build(file("ansible.cfg"))

    fun testInventoriesAreSummarised() {
        val inventories = overview().inventories
        assertEquals(
            listOf("env-a", "env-b", "env-c", "env-d"),
            inventories.map { it.name }.sorted(),
        )
        val envC = inventories.single { it.name == "env-c" }
        assertEquals("env-c is the big one", 22, envC.hostCount)
        assertEquals(
            "the containers group is a sliver of it",
            1,
            envC.groups.single { it.name == "containers" }.hostCount,
        )
    }

    fun testPlaybooksReportWhatTheyActuallyTarget() {
        val playbooks = overview().playbooks
        val containerMon = playbooks.single { it.file.name == "site-container-mon.yml" }
        val play = containerMon.plays.single { it.pattern == "containers" }

        assertEquals(
            "one containers host in each of the four inventories",
            4,
            play.totalTargeted,
        )
        assertEquals(listOf("container_monitoring_agent"), play.roles)

        // `hosts: legacy_hosts` exists only in env-b — the count must reflect
        // that, not silently spread across all four.
        val legacy = playbooks.single { it.file.name == "site-legacy-mon.yml" }
        assertEquals(1, legacy.plays.single().totalTargeted)
    }

    /** A pattern that cannot be evaluated is unknown, never zero. */
    fun testUnknowablePatternIsNullNotZero() {
        val glob = overview().playbooks.single { it.file.name == "site-probe-glob.yml" }
        assertNull(
            "`hosts: \"web_ap*\"` is a glob; reporting 0 would read as 'targets nothing'",
            glob.plays.single().totalTargeted,
        )
    }

    fun testRolesKnowWhoUsesThemAndHowFarTheyReach() {
        val roles = overview().roles
        val container = roles.single { it.name == "container_monitoring_agent" }
        assertEquals(
            "reached by the root playbook and by the one under playbooks/fleet/",
            listOf("site-container-mon.yml", "site-fleet-extra.yml"),
            container.usedBy.map { it.name }.sorted(),
        )
        // Both playbooks target `containers`, which is the same four hosts.
        // Summing per playbook would report 8 and overstate the blast radius.
        assertEquals("distinct hosts, not host-plays", 4, container.totalTargeted)

        val legacy = roles.single { it.name == "legacy_monitoring_agent" }
        assertEquals("one host in one environment", 1, legacy.totalTargeted)
    }

    /**
     * Playbooks are found wherever they live, not only in the two directories
     * the scan used to look in.
     *
     * `playbooks/fleet/site-fleet-extra.yml` was invisible, so anything built
     * on the playbook list — role reach, the overview, which playbooks a role's
     * variables are resolved against — silently under-reported.
     */
    fun testPlaybooksAreFoundBelowTheTopTwoDirectories() {
        val names = overview().playbooks.map { it.file.name }
        assertTrue("nested under playbooks/fleet/: $names", "site-fleet-extra.yml" in names)
        assertTrue("an import_playbook target in shared/: $names", "noop.yml" in names)
    }

    /**
     * ...but the walk still never opens anything inside a role, which is what
     * the old two-directory limit was really protecting against.
     */
    fun testRoleInternalsAreNotScannedForPlaybooks() {
        val paths = overview().playbooks.map { it.file.path }
        assertTrue(
            "a role's tasks/main.yml is a task list, not a playbook: $paths",
            paths.none { it.contains("/roles/") },
        )
    }

    /**
     * The expensive analysis finds genuinely dead configuration, and stays
     * quiet on a healthy project.
     *
     * The fixture as shipped has nothing dead, so the positive case is built
     * here rather than baked into the fixture: a `group_vars` value for the
     * `containers` group, shadowed by a `host_vars` file for every host that
     * group has in every inventory. Nobody ever reads the group_vars value.
     */
    fun testNeverWinsFindsShadowedConfigurationAndNothingElse() {
        val service = AnsibleOverviewService.getInstance(project)
        val anchor = file("ansible.cfg")

        assertEquals(
            "the fixture as shipped has no dead configuration",
            emptyList<String>(),
            service.neverWinningDefinitions(anchor).map { it.message },
        )

        myFixture.addFileToProject(
            "inventories/env-a/group_vars/containers.yml",
            "---\nshadowed_setting: never-read\n",
        )
        // `containers` holds exactly one host per inventory; override each.
        for ((inventory, host) in listOf(
            "env-a" to "a-host-01", "env-b" to "b-host-01",
            "env-c" to "c-host-07", "env-d" to "d-host-01",
        )) {
            myFixture.addFileToProject(
                "inventories/$inventory/host_vars/$host.yml",
                "---\nshadowed_setting: this-one-wins\n",
            )
        }

        val dead = service.neverWinningDefinitions(anchor)
        assertEquals(
            "only the group_vars definition is dead; the four host_vars ones win",
            1,
            dead.size,
        )
        assertTrue("names the variable: ${dead.first().message}", dead.first().message.startsWith("shadowed_setting"))
        assertEquals("containers.yml", dead.first().file?.name)
    }

    /**
     * A role's `defaults` losing is the design working, not a defect — that is
     * what defaults are for. Reporting them would flag idiomatic Ansible on
     * every project and drown the findings that matter.
     */
    fun testRoleDefaultsAreNeverReportedAsDead() {
        val service = AnsibleOverviewService.getInstance(project)
        val anchor = file("ansible.cfg")
        myFixture.addFileToProject(
            "inventories/env-a/group_vars/all.yml.bak", "",
        )
        // `probe_setting` lives only in role defaults and is overridden nowhere,
        // but it must still never be reported.
        assertTrue(
            "role defaults are exempt",
            service.neverWinningDefinitions(anchor).none { it.message.startsWith("probe_setting") },
        )
    }

    /** The fleet fixture is deliberately healthy; nothing should be invented. */
    fun testNoOrphanVarsFilesInAWellFormedProject() {
        val orphans = overview().findings.filter { it.kind == FindingKind.ORPHAN_VARS_FILE }
        assertEquals("every group_vars/host_vars file names something real: $orphans", 0, orphans.size)
    }

    /**
     * Exactly one role is unreachable, and it is the one meant to be.
     *
     * F19's `register_probe_agent` is deliberately run by no playbook — that is
     * the shape it exists to reproduce. Asserting the name rather than a count
     * of zero keeps the guard against a role becoming unreachable by accident,
     * and gives the unused-role finding its only positive case in the fixture.
     */
    fun testTheOnlyUnreachableRoleIsTheOneMeantToBe() {
        val unused = overview().findings.filter { it.kind == FindingKind.UNUSED_ROLE }
        assertEquals("exactly one: $unused", 1, unused.size)
        assertTrue(
            "and it is F19's: ${unused.single().message}",
            unused.single().message.contains("register_probe_agent"),
        )
    }
}
