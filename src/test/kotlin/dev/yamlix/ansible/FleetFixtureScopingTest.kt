package dev.yamlix.ansible

import com.intellij.psi.PsiManager
import dev.yamlix.ansible.vars.ResolutionContext
import dev.yamlix.ansible.vars.VarScope
import dev.yamlix.ansible.vars.VariableResolutionService

/**
 * FLEET-FIXTURE-CASES.md F12-F14 — how a play's `hosts:` pattern restricts
 * which hosts a role's variables are in scope for.
 *
 * Restricting at all is what F5 asked for. These pin the other half: the
 * restriction must never be *wider* than what the plugin actually understands,
 * because "no hosts" and "unknown hosts" render identically to a user — as the
 * variable having no definition anywhere.
 */
class FleetFixtureScopingTest : FleetFixtureTestCase() {

    private fun winnerFor(
        playbookPath: String,
        host: String,
        inventory: String = "inventories/env-a",
        name: String = "probe_setting",
    ) = VariableResolutionService.getInstance(project).resolve(
        name,
        ResolutionContext(
            host = host,
            inventoryRoot = file(inventory),
            playbook = file(playbookPath),
            position = PsiManager.getInstance(project)
                .findFile(file("roles/pattern_probe_agent/tasks/main.yml")),
        ),
    ).winner

    /** F12 — a glob pattern is not understood, so it must not narrow anything. */
    fun testGlobHostPatternDoesNotHideRoleDefaults() {
        val winner = winnerFor("site-probe-glob.yml", "a-host-02")
        assertNotNull(
            "`hosts: \"web_ap*\"` must leave the play unrestricted, not empty",
            winner,
        )
        assertEquals(VarScope.ROLE_DEFAULTS, winner!!.scope)
        assertEquals("from-role-defaults", winner.valueText)
    }

    /**
     * F12 — `localhost` is the one literal name that must NOT narrow.
     *
     * Ansible supplies it implicitly, so a `hosts: localhost` play genuinely
     * runs even though the name appears in no inventory. That makes it the
     * opposite case to [testAbsentGroupNarrowsToNothing] below, and the two
     * together are why "not in this inventory" and "cannot be evaluated" have
     * to stay distinct answers.
     */
    fun testLocalhostPatternDoesNotHideRoleDefaults() {
        val winner = winnerFor("site-probe-local.yml", "a-host-02")
        assertNotNull("`hosts: localhost` must leave the play unrestricted", winner)
        assertEquals(VarScope.ROLE_DEFAULTS, winner!!.scope)
    }

    /**
     * F15 — a literal group that this inventory does not have narrows to
     * nothing, rather than falling back to "unknown, so allow everything".
     *
     * `site-legacy-mon.yml` targets `legacy_hosts`, a group that exists only
     * in env-b. Treating its absence from env-a as ignorance leaked
     * `legacy_monitoring_agent`'s defaults onto every host of every other
     * environment — the role was reported as winning almost fleet-wide when
     * Ansible runs it on exactly one host.
     */
    fun testAbsentGroupNarrowsToNothing() {
        fun legacyWinner(inventory: String, host: String) =
            VariableResolutionService.getInstance(project).resolve(
                "agent_image",
                ResolutionContext(
                    host = host,
                    inventoryRoot = file(inventory),
                    playbook = file("site-legacy-mon.yml"),
                    position = PsiManager.getInstance(project)
                        .findFile(file("roles/legacy_monitoring_agent/tasks/main.yml")),
                ),
            ).winner

        assertNotNull(
            "env-b really does have the legacy_hosts group",
            legacyWinner("inventories/env-b", "b-host-02"),
        )
        for (inventory in listOf("inventories/env-a", "inventories/env-c", "inventories/env-d")) {
            assertNull(
                "$inventory has no legacy_hosts group, so the play never runs there",
                legacyWinner(inventory, inventory.substringAfterLast('-') + "-host-01"),
            )
        }
    }

    /** F5 stays fixed — a pattern the plugin *does* understand still narrows. */
    fun testLiteralGroupPatternStillNarrows() {
        // `site-container-mon.yml` targets `containers`, which in env-a is
        // a-host-01 only. Its role's defaults must not reach a-host-02.
        val reached = VariableResolutionService.getInstance(project).resolve(
            "agent_image",
            ResolutionContext(
                host = "a-host-02",
                inventoryRoot = file("inventories/env-a"),
                playbook = file("site-container-mon.yml"),
                position = PsiManager.getInstance(project)
                    .findFile(file("roles/container_monitoring_agent/tasks/main.yml")),
            ),
        ).winner
        assertNull("a literal group pattern must still exclude non-members", reached)
    }

    /** F13 — a role used by two plays is in scope for the union of their hosts. */
    fun testRoleUsedByTwoPlaysIsInScopeForBoth() {
        for (host in listOf("a-host-01", "a-host-02")) {
            val winner = winnerFor("site-probe-multiplay.yml", host)
            assertNotNull(
                "the role runs against $host in one of the two plays, so its " +
                    "defaults must be in scope there",
                winner,
            )
            assertEquals(VarScope.ROLE_DEFAULTS, winner!!.scope)
        }
    }

    /** F14 — playbook-adjacent group_vars outranks the inventory's own. */
    fun testPlaybookAdjacentGroupVarsWins() {
        val winner = winnerFor(
            "playbooks/site-probe-adjacent.yml", "a-host-02", name = "probe_adjacent",
        )
        assertNotNull("both group_vars/all.yml files define it", winner)
        assertEquals(
            "playbook-adjacent group_vars/all beats the inventory's own",
            "from-playbook-group-vars",
            winner!!.valueText,
        )
    }

    /** F14 — and it applies only to playbooks that actually sit beside it. */
    fun testPlaybookAdjacentGroupVarsDoesNotLeakToOtherPlaybooks() {
        val winner = winnerFor(
            "site-probe-glob.yml", "a-host-02", name = "probe_adjacent",
        )
        assertEquals(
            "a root-level playbook must see the inventory's group_vars, not " +
                "the one beside playbooks/",
            "from-inventory-group-vars",
            winner?.valueText,
        )
    }
}
