package dev.yamlix.ansible

import dev.yamlix.ansible.overview.FileVariableView
import dev.yamlix.ansible.overview.FileVariableViewService
import dev.yamlix.ansible.overview.FileViewTree
import dev.yamlix.ansible.overview.HintNode
import dev.yamlix.ansible.overview.RowStatus
import dev.yamlix.ansible.overview.SectionNode
import dev.yamlix.ansible.overview.SiteStatus
import dev.yamlix.ansible.overview.VariableRowNode

/**
 * What the Ansible tool window says about the file you are reading.
 *
 * The panel is a renderer over this, so the header, every row's text and its
 * status are asserted here rather than left to be discovered by looking at the
 * UI.
 */
class FileVariableViewTest : FleetFixtureTestCase() {

    private fun view(path: String): FileVariableView =
        FileVariableViewService.getInstance(project).build(file(path))
            ?: error("no view for $path")

    private val taskFile = "roles/container_monitoring_agent/tasks/main.yml"

    // ---- the header ---------------------------------------------------------

    /**
     * The header answers "where does this file even run" — the question that
     * has no answer anywhere else in the plugin.
     */
    fun testHeaderNamesWhatReachesTheFileAndWhichHosts() {
        val view = view(taskFile)
        assertEquals("container_monitoring_agent", view.subtitle)
        assertEquals(
            listOf("site-container-mon.yml", "site-fleet-extra.yml"),
            view.reachedBy.map { it.name }.sorted(),
        )
        // Named by the group, because that is what a reader thinks in, with the
        // count because that is the blast radius.
        assertEquals("containers — 4 hosts", view.runsOn)
    }

    /**
     * A reach that cannot be evaluated says so rather than vanishing.
     *
     * `pattern_probe_agent` is reached by a playbook using a glob. Returning
     * nothing would render as "no host context at all", which is a different
     * and wrong claim.
     */
    fun testUnknowableReachIsStatedNotOmitted() {
        val view = view("roles/pattern_probe_agent/defaults/main.yml")
        assertEquals("? hosts — some patterns cannot be evaluated", view.runsOn)
    }

    // ---- the rows -----------------------------------------------------------

    fun testResolvedVariableShowsItsValue() {
        val row = view(taskFile).uses.single { it.name == "agent_image" }
        assertEquals(RowStatus.RESOLVED, row.status)
        assertEquals("registry.example.test/container-agent:1.0.0", row.summary)
        assertNull("a plain answer needs no explanation", row.note)
    }

    /**
     * An ambiguous variable still shows its candidate values.
     *
     * The report deliberately carries no value for an ambiguous row — the
     * candidates live in its alternatives — so reading the report alone said
     * "no static value" for `retention_days`, when being one of 7, 14, 30 or 3
     * is the entire point.
     */
    fun testAmbiguousVariableListsItsCandidateValues() {
        val row = view(taskFile).uses.single { it.name == "retention_days" }
        assertEquals(RowStatus.AMBIGUOUS, row.status)
        assertEquals("7 · 14 · 30 · 3", row.summary)
        assertEquals(4, row.sites.size)
        assertTrue("each is a candidate", row.sites.all { it.status == SiteStatus.MAY_WIN })
    }

    /**
     * Several long values collapse to a count instead of three truncated URLs.
     *
     * `7 · 14 · 30 · 3` is informative; `url: "https://repo.e… · url: "https…`
     * is not, and says less than the number does.
     */
    fun testManyLongValuesCollapseToACount() {
        val row = view(taskFile).uses.single { it.name == "artifact_repo" }
        assertEquals(RowStatus.VARIES, row.status)
        assertEquals("3 different values", row.summary)
        assertEquals("differs by host", row.note)
    }

    /**
     * A variable Ansible supplies is not an error.
     *
     * `{{ item }}` is the loop variable; reporting it as "not defined in this
     * project" is technically true and completely unhelpful.
     */
    fun testLoopVariableIsReportedAsProvidedByAnsible() {
        val row = view(taskFile).uses.single { it.name == "item" }
        assertEquals(RowStatus.PROVIDED_BY_ANSIBLE, row.status)
        assertTrue("explains what it is: ${row.note}", row.note.orEmpty().contains("loop"))
    }

    /** A variable genuinely absent is still reported as absent. */
    fun testGenuinelyUndefinedVariableStaysUnresolved() {
        val row = view(taskFile).uses.single { it.name == "fleet_env" }
        assertEquals(RowStatus.UNRESOLVED, row.status)
    }

    /**
     * The detail pane shows every site that could apply, and only those.
     *
     * `agent_image` is also defined by an unrelated role; that site is not a
     * candidate here for the same reason "Choose Declaration" stopped offering
     * it.
     */
    fun testSitesAreTheOnesThatCouldApply() {
        val row = view(taskFile).uses.single { it.name == "agent_image" }
        assertEquals(1, row.sites.size)
        val site = row.sites.single()
        assertEquals(SiteStatus.WINS, site.status)
        assertEquals("main.yml", site.file.name)
        assertEquals(listOf("all inventories (containers)"), site.where)
    }

    fun testDefinedVariablesAreListedSeparately() {
        val view = view("roles/pattern_probe_agent/defaults/main.yml")
        assertEquals(listOf("probe_setting"), view.defines.map { it.name })
        assertTrue("a defaults file uses nothing", view.uses.isEmpty())
    }

    // ---- caret matching -----------------------------------------------------

    /**
     * The caret selects the variable it is *inside*, and only that.
     *
     * The first attempt picked "the nearest variable at or before the caret",
     * which is a different question: it left the panel pointed at something the
     * reader had scrolled well past, and never admitted it did not know.
     */
    fun testCaretInsideAVariableSelectsIt() {
        val view = view(taskFile)
        val text = file(taskFile).contentsToByteArray().decodeToString()

        for (name in listOf("agent_image", "artifact_repo", "retention_days")) {
            val at = text.indexOf(name, text.indexOf("msg:"))
            assertTrue("$name is on the msg line", at > 0)
            assertEquals(
                "the caret inside $name must select it",
                name,
                view.rowAt(at + 1)?.name,
            )
        }
    }

    /** Between variables the answer is "I do not know", not a nearby guess. */
    fun testCaretOutsideAnyVariableMatchesNothing() {
        val view = view(taskFile)
        val text = file(taskFile).contentsToByteArray().decodeToString()
        val inPlainYaml = text.indexOf("ansible.builtin.debug") + 5
        assertNull(
            "plain YAML is not a variable, and pretending otherwise is what was wrong",
            view.rowAt(inPlainYaml),
        )
    }

    /**
     * Every occurrence counts, not just the first.
     *
     * `item` appears in `include_vars` and again in the loop; a reader standing
     * on either is standing on `item`.
     */
    fun testEveryOccurrenceIsMatchable() {
        val row = view(taskFile).uses.single { it.name == "agent_image" }
        assertTrue("has at least one range", row.ranges.isNotEmpty())
        assertTrue(
            "and the row's own ranges all resolve back to it",
            row.ranges.all { view(taskFile).rowAt(it.first)?.name == "agent_image" },
        )
    }

    /**
     * The narrowest match wins, so a use inside a definition is about the use.
     *
     * A definition's range spans its whole value, so `probe_setting: "{{ x }}"`
     * contains both — and the caret inside `x` is about `x`.
     */
    fun testNarrowestRangeWinsWhenRangesNest() {
        val view = view("roles/pattern_probe_agent/tasks/main.yml")
        val text = file("roles/pattern_probe_agent/tasks/main.yml")
            .contentsToByteArray().decodeToString()
        val at = text.indexOf("probe_adjacent")
        assertEquals("probe_adjacent", view.rowAt(at + 1)?.name)
    }

    // ---- the rendered rows --------------------------------------------------

    fun testTreeHasAHeaderAndAUsesSection() {
        val rows = FileViewTree.build(view(taskFile))
        assertTrue("header first", rows.first().node.text.contains("container_monitoring_agent"))

        val uses = rows.single { (it.node as? SectionNode)?.text == "Uses" }
        assertEquals("(5)", uses.node.detail)
        assertTrue(
            "every child is a variable",
            uses.children.all { it.node is VariableRowNode },
        )
    }

    /**
     * A file that defines nothing gets no Defines section — an empty one would
     * imply it ought to have something.
     */
    fun testNoDefinesSectionWhenTheFileDeclaresNothing() {
        val titles = FileViewTree.build(view(taskFile)).mapNotNull { (it.node as? SectionNode)?.text }
        assertEquals(listOf("Uses"), titles)
    }

    fun testNonAnsibleFileGetsAnExplanationNotABlankPanel() {
        val rows = FileViewTree.build(null)
        assertTrue(rows.single().node is HintNode)
    }
}
