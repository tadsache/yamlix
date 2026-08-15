package dev.yamlix.ansible

import com.intellij.testFramework.DumbModeTestUtils
import dev.yamlix.ansible.overview.FileVariableView
import dev.yamlix.ansible.overview.FileVariableViewService
import dev.yamlix.ansible.overview.FileViewTree
import dev.yamlix.ansible.overview.HintNode
import dev.yamlix.ansible.overview.RowStatus
import dev.yamlix.ansible.overview.SectionNode
import dev.yamlix.ansible.overview.SiteNode
import dev.yamlix.ansible.overview.SiteStatus
import dev.yamlix.ansible.overview.ViewState
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
        when (val state = FileVariableViewService.getInstance(project).build(file(path))) {
            is ViewState.Ready -> state.view
            else -> error("no view for $path: $state")
        }

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
        val rows = FileViewTree.build(ViewState.Ready(view(taskFile)))
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
        val titles = FileViewTree.build(ViewState.Ready(view(taskFile))).mapNotNull { (it.node as? SectionNode)?.text }
        assertEquals(listOf("Uses"), titles)
    }

    fun testNonAnsibleFileGetsAnExplanationNotABlankPanel() {
        val rows = FileViewTree.build(ViewState.NotAnsible)
        assertTrue(rows.single().node is HintNode)
    }

    // ---- what a playbook declares -------------------------------------------

    /**
     * A site playbook is about where things run, not about `{{ }}`.
     *
     * Built only from variable uses, the window for a site playbook was empty
     * but for "No {{ variables }} in this file" — nothing about the hosts it
     * targets or the roles it runs, which is the whole content of the file.
     */
    fun testPlaybookListsWhatItRuns() {
        val view = view("site-probe-multiplay.yml")
        assertEquals(2, view.plays.size)
        assertEquals(listOf("containers", "web_app"), view.plays.map { it.hosts })
        assertEquals(
            listOf("containers — 4 hosts", "web_app — 24 hosts"),
            view.plays.map { it.hostSummary },
        )
        assertEquals(
            listOf("pattern_probe_agent"),
            view.plays.first().roles.map { it.name },
        )
        assertNotNull("and the role opens somewhere", view.plays.first().roles.single().entry)
    }

    /**
     * A pattern that cannot be enumerated is admitted, and the play still
     * appears — a reader opening this file wants to see the role it runs even
     * though the host set is unknown.
     */
    fun testUnevaluablePatternStillListsThePlay() {
        val play = view("site-probe-glob.yml").plays.single()
        assertEquals("web_ap*", play.hosts)
        assertEquals("pattern cannot be evaluated", play.hostSummary)
        assertEquals(listOf("pattern_probe_agent"), play.roles.map { it.name })
    }

    /** An `import_playbook` is half of what this playbook does; it is listed. */
    fun testImportedPlaybooksAreListedInFileOrder() {
        val view = view("site-container-mon.yml")
        assertEquals(listOf("shared/noop.yml"), view.imports.map { it.path })
        assertNotNull("and resolves", view.imports.single().target)

        val runs = FileViewTree.build(ViewState.Ready(view))
            .single { (it.node as? SectionNode)?.text == "Runs" }
        assertEquals("(2)", runs.node.detail)
        assertTrue(
            "the import comes first, as it does in the file",
            runs.children.first().node.text.startsWith("import_playbook:"),
        )
    }

    /**
     * No "no variables here" row on a playbook that has already shown its
     * plays — the hint exists to disambiguate an empty panel, and this panel
     * is visibly not empty.
     */
    fun testPlaybookWithNoVariablesHasNoEmptyUsesSection() {
        val titles = FileViewTree.build(ViewState.Ready(view("site-probe-glob.yml")))
            .mapNotNull { (it.node as? SectionNode)?.text }
        assertEquals(listOf("Runs"), titles)
    }

    /** A task file is not a playbook and declares no plays. */
    fun testTaskFileHasNoRunsSection() {
        assertTrue(view(taskFile).plays.isEmpty())
    }

    /**
     * A file the IDE does not index is said to be unindexed, not undefined.
     *
     * Found the hard way: a demo project whose module content root pointed at
     * `.idea/` rather than the project put every Ansible file outside the
     * indexed scope. Each index lookup came back empty, so a completely correct
     * project reported every variable as "not defined in this project" — and
     * nothing on screen distinguished that from a genuine finding.
     */
    fun testFileOutsideTheContentRootsSaysSoRatherThanUndefined() {
        // A real directory on disk that no content root covers — the same
        // situation as an Ansible tree opened outside the project's roots.
        val dir = com.intellij.openapi.util.io.FileUtil.createTempDirectory("detached", null)
        java.io.File(dir, "ansible.cfg").writeText("[defaults]\nroles_path = ./roles\n")
        val tasks = java.io.File(dir, "roles/detached_role/tasks").apply { mkdirs() }
        java.io.File(tasks, "main.yml").writeText(
            "---\n- name: use it\n  ansible.builtin.debug:\n    msg: \"{{ agent_image }}\"\n"
        )
        val outside = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(java.io.File(tasks, "main.yml"))
            ?: error("could not see the detached file")

        assertFalse(
            "the premise: no content root covers it",
            com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isInContent(outside),
        )

        val state = FileVariableViewService.getInstance(project).build(outside)
        assertEquals(ViewState.OutsideContentRoots, state)
        assertTrue(
            "and it renders as an explanation, never as undefined variables",
            FileViewTree.build(state).all { it.node is HintNode },
        )
    }

    // ---- definition sites, inline -------------------------------------------

    /**
     * A site row leads with the precedence level, not a verdict.
     *
     * "WINS" was loudest in the case where it said least: with one site there
     * is nothing to have won against. The level is the *reason* it wins, and
     * the outcome is carried by the icon instead.
     */
    fun testSiteRowLeadsWithThePrecedenceLevel() {
        val row = FileViewTree.build(ViewState.Ready(view(taskFile)))
            .single { (it.node as? SectionNode)?.text == "Uses" }
            .children.single { it.node.text == "agent_image" }

        val site = row.children.single().node as SiteNode
        assertEquals("role defaults", site.text)
        assertFalse("the winner is not greyed", site.subdued)
        assertTrue(
            "and the path is on the row, not hidden under it",
            site.detail.contains("roles/container_monitoring_agent/defaults/main.yml"),
        )
        assertFalse(
            "the value is not repeated from the variable row above",
            site.detail.contains("registry.example.test"),
        )
    }

    /** With several values the site carries its own, since that is the point. */
    fun testSitesShowTheirOwnValueWhenTheyDiffer() {
        val row = FileViewTree.build(ViewState.Ready(view(taskFile)))
            .single { (it.node as? SectionNode)?.text == "Uses" }
            .children.single { it.node.text == "artifact_repo" }

        assertEquals(3, row.children.size)
        // Highest precedence first, so the list reads as the ladder it is.
        assertEquals(
            listOf("host_vars", "group_vars", "group_vars/all"),
            row.children.map { it.node.text },
        )
        assertTrue(row.children.first().node.detail!!.contains("repo-canary"))
    }

    /**
     * A mapping value is flattened and bounded — pasted whole, its newlines
     * pushed the inventories and the path off the end of the row.
     */
    fun testLongSiteValuesDoNotPushOutTheRestOfTheRow() {
        val row = FileViewTree.build(ViewState.Ready(view(taskFile)))
            .single { (it.node as? SectionNode)?.text == "Uses" }
            .children.single { it.node.text == "artifact_repo" }

        for (child in row.children) {
            val detail = child.node.detail!!
            assertFalse("no newlines: $detail", detail.contains("\n"))
            assertTrue("path survives: $detail", detail.contains(".yml"))
        }
    }

    /** A variable with nothing behind it has nothing to expand. */
    fun testUnresolvedVariableHasNoSites() {
        val row = FileViewTree.build(ViewState.Ready(view(taskFile)))
            .single { (it.node as? SectionNode)?.text == "Uses" }
            .children.single { it.node.text == "fleet_env" }
        assertTrue(row.children.isEmpty())
    }

    /**
     * The ambiguity note describes the ambiguity.
     *
     * It used to be taken from whichever of the variable's rows carried a note
     * first, so a variable ambiguous under one inventory and undefined under
     * another read "not defined anywhere in this project" beside a list of its
     * four candidate values. Row order differs between a copied fixture and a
     * real VFS, so only the IDE ever showed it.
     */
    fun testAmbiguityNoteDescribesTheAmbiguity() {
        val row = view(taskFile).uses.single { it.name == "retention_days" }
        assertEquals(RowStatus.AMBIGUOUS, row.status)
        val note = row.note.orEmpty()
        assertTrue("says why it is ambiguous: $note", note.contains("could win"))
        assertFalse("and never claims it is undefined: $note", note.contains("not defined"))
    }

    // ---- indexing -----------------------------------------------------------

    /**
     * While the index is building the panel must say so, not answer.
     *
     * This is the bug that made the tool window look broken on first open: the
     * panel ran during start-up indexing, every index lookup came back empty,
     * and a role's own `defaults/main.yml` was reported as "not defined in this
     * project". A wrong answer is worse than a delayed one, and a first-run
     * user cannot tell the difference between the two by looking.
     */
    fun testIndexingIsReportedRatherThanAnsweredWrongly() {
        val service = FileVariableViewService.getInstance(project)
        val target = file(taskFile)
        assertTrue("resolves normally when smart", service.build(target) is ViewState.Ready)

        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertEquals(ViewState.Indexing, service.build(target))
            val rows = FileViewTree.build(service.build(target))
            assertTrue(
                "and renders as a hint, never as variable rows",
                rows.single().node is HintNode,
            )
        }
    }
}
