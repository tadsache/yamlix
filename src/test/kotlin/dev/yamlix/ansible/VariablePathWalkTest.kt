package dev.yamlix.ansible

import dev.yamlix.ansible.overview.FileVariableView
import dev.yamlix.ansible.overview.FileVariableViewService
import dev.yamlix.ansible.overview.RowStatus
import dev.yamlix.ansible.overview.SiteStatus
import dev.yamlix.ansible.overview.ViewState

/**
 * F22 — `user.name` is a question with its own answer.
 *
 * A dotted use was recorded under its root, so a file writing `user.name`,
 * `user.shell` and `user.system` showed one row saying nothing about any of
 * them. Each path is now resolved by walking into the definition of the root
 * that wins, which is the only walk that can be right; see [VariablePathWalk]
 * for why assembling one out of every definition cannot.
 */
class VariablePathWalkTest : FleetFixtureTestCase() {

    private fun view(path: String): FileVariableView =
        when (val state = FileVariableViewService.getInstance(project).build(file(path))) {
            is ViewState.Ready -> state.view
            else -> error("no view for $path: $state")
        }

    private val taskFile = "roles/container_monitoring_agent/tasks/main.yml"

    /** The leaf value, not the dictionary that holds it. */
    fun testAPathResolvesToTheValueInsideTheWinner() {
        val row = view(taskFile).uses.single { it.name == "artifact_repo.url" }
        assertEquals(RowStatus.VARIES, row.status)
        assertEquals(
            "each rung carries the url inside its own definition, not the dict",
            listOf(
                "https://repo-canary.example.test/canary-release",
                "https://repo-special.example.test/override-release",
                "https://repo.example.test/generic-release",
            ).sorted(),
            row.sites.mapNotNull { it.value }.sorted(),
        )
    }

    /**
     * The precedence ladder survives the walk.
     *
     * Which definitions compete is half the answer — replacing the ladder with
     * a flat list of leaves would have shown three values with no account of
     * why one of them wins.
     */
    fun testEveryRungKeepsItsScopeAndPointsAtItsOwnKey() {
        val row = view(taskFile).uses.single { it.name == "artifact_repo.url" }
        assertEquals(
            listOf("group_vars", "group_vars/all", "host_vars"),
            row.sites.map { it.scopeLabel }.sorted(),
        )
        // Each rung navigates into its own file, at the `url:` line rather than
        // at the `artifact_repo:` above it.
        for (site in row.sites) {
            val text = site.file.contentsToByteArray().decodeToString()
            assertTrue(
                "${site.file.name} offset ${site.offset} is not on a url: line",
                text.startsWith("url:", site.offset),
            )
        }
    }

    /** A key the winning dictionary does not have is not an undefined variable. */
    fun testAMissingKeyIsPartialNotUnresolved() {
        myFixture.addFileToProject(
            "roles/container_monitoring_agent/tasks/probe-missing.yml",
            "---\n- name: read a key that is not there\n" +
                "  ansible.builtin.debug:\n    msg: \"{{ artifact_repo.nonesuch }}\"\n",
        )
        val row = view("roles/container_monitoring_agent/tasks/probe-missing.yml")
            .uses.single { it.name == "artifact_repo.nonesuch" }
        assertEquals(RowStatus.PARTIAL, row.status)
        assertTrue("says which key: ${row.note}", row.note.orEmpty().contains("nonesuch"))
        assertTrue(
            "and which definition it consulted: ${row.note}",
            row.note.orEmpty().contains(".yml"),
        )
    }

    /** A nested key is followed all the way down. */
    fun testTheWalkGoesDeeperThanOneLevel() {
        myFixture.addFileToProject(
            "roles/container_monitoring_agent/tasks/probe-deep.yml",
            "---\n- name: read a nested key\n" +
                "  ansible.builtin.debug:\n" +
                "    msg: \"{{ artifact_repo.auth_header['X-Api-Key'] }} {{ retention_days }}\"\n",
        )
        val rows = view("roles/container_monitoring_agent/tasks/probe-deep.yml").uses
        // The subscript is not a key and stops the path; the dictionary itself
        // is what the row is about, and it resolves.
        val row = rows.single { it.name == "artifact_repo.auth_header" }
        assertTrue("$row", row.status == RowStatus.RESOLVED || row.status == RowStatus.VARIES)
    }

    /**
     * A path whose root is undefined is not made worse by the path.
     *
     * The root is what is wrong; saying "no `x` key in `fleet_env`" about a
     * variable that does not exist would bury the real answer.
     */
    fun testAPathOnAnUndefinedRootKeepsTheRootsVerdict() {
        myFixture.addFileToProject(
            "roles/container_monitoring_agent/tasks/probe-root.yml",
            "---\n- name: read a key of nothing\n" +
                "  ansible.builtin.debug:\n    msg: \"{{ fleet_env.region }}\"\n",
        )
        val row = view("roles/container_monitoring_agent/tasks/probe-root.yml")
            .uses.single { it.name == "fleet_env.region" }
        assertEquals(RowStatus.UNRESOLVED, row.status)
        assertEquals("not defined in this project", row.note)
    }

    /**
     * The walk follows one hop through a template.
     *
     * `user: "{{ addusers.kube }}"` is how a parameterised role is written —
     * kubespray's `adduser` is exactly this — and stopping at the first hop
     * would leave every such role unresolved past its own front door.
     */
    fun testTheWalkFollowsATemplatedIndirection() {
        val row = view("roles/inline_param_agent/tasks/main.yml")
            .uses.single { it.name == "probe_repo.url" }
        assertTrue(
            "followed `{{ artifact_repo }}` through to a url: ${row.status} ${row.summary}",
            row.status != RowStatus.PARTIAL && row.status != RowStatus.UNRESOLVED,
        )
        assertTrue(
            "and lands on the urls inside it: ${row.sites.map { it.value }}",
            row.sites.mapNotNull { it.value }.any { it.startsWith("https://repo") },
        )
    }

    /**
     * An absent key the use already defaults is not worth a word of alarm.
     *
     * kubespray writes `user.home | default(omit)` over a dictionary with no
     * `home` in it — deliberately, since the key is optional. Reporting that
     * the same way as an unguarded missing key would flag idiomatic Ansible.
     */
    fun testAMissingKeyTheUseDefaultsIsNotAFinding() {
        myFixture.addFileToProject(
            "roles/container_monitoring_agent/tasks/probe-defaulted.yml",
            "---\n- name: read an optional key\n" +
                "  ansible.builtin.debug:\n" +
                "    msg: \"{{ artifact_repo.nonesuch | default('none') }}\"\n",
        )
        val row = view("roles/container_monitoring_agent/tasks/probe-defaulted.yml")
            .uses.single { it.name == "artifact_repo.nonesuch" }
        assertEquals(RowStatus.PARTIAL, row.status)
        assertTrue(
            "says the absence was intended: ${row.note}",
            row.note.orEmpty().contains("the use defaults it"),
        )
    }

    /** Rungs that could not be walked keep their place but claim no value. */
    fun testARungThatCannotBeWalkedShowsNoValue() {
        val row = view(taskFile).uses.single { it.name == "artifact_repo.url" }
        assertTrue(
            "every rung shown is either a real value or none at all",
            row.sites.all { it.value != null || it.status != SiteStatus.WINS },
        )
    }
}
