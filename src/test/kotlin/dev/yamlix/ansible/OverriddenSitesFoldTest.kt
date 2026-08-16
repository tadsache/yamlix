package dev.yamlix.ansible

import dev.yamlix.ansible.overview.FileViewTree
import dev.yamlix.ansible.overview.FileVariableViewService
import dev.yamlix.ansible.overview.OverriddenSitesNode
import dev.yamlix.ansible.overview.OverviewTreeNode
import dev.yamlix.ansible.overview.SectionNode
import dev.yamlix.ansible.overview.SiteNode
import dev.yamlix.ansible.overview.SiteStatus
import dev.yamlix.ansible.overview.ViewState

/**
 * The definitions that lost are folded away.
 *
 * `app_port` in this fixture is defined sixteen times, and thirteen of those
 * lose. Expanding the variable used to put all sixteen on screen, which buried
 * the two `include_vars` candidates that can still win under ten that cannot —
 * the same wall the panel already avoids one level up by not expanding every
 * variable at once.
 */
class OverriddenSitesFoldTest : AnsibleFixtureTestCase() {

    private val roleTask = "roles/app/tasks/main.yml"

    private fun row(path: String, name: String): OverviewTreeNode {
        val state = FileVariableViewService.getInstance(project).build(file(path))
        val view = (state as? ViewState.Ready)?.view ?: error("no view for $path: $state")
        return FileViewTree.build(ViewState.Ready(view))
            .single { (it.node as? SectionNode)?.text == "Uses" }
            .children.single { it.node.text == name }
    }

    private fun OverviewTreeNode.fold(): OverviewTreeNode? =
        children.singleOrNull { it.node is OverriddenSitesNode }

    fun testTheLosingDefinitionsAreNotDirectChildren() {
        val appPort = row(roleTask, "app_port")

        val direct = appPort.children.mapNotNull { it.node as? SiteNode }
        assertTrue(
            "nothing overridden is left at the top level: " +
                direct.filter { it.site.status == SiteStatus.OVERRIDDEN }.map { it.text },
            direct.none { it.site.status == SiteStatus.OVERRIDDEN },
        )
    }

    /** Folded, not dropped: "where else is this written" stays answerable. */
    fun testTheyAreKeptUnderOneRowThatCountsThem() {
        val appPort = row(roleTask, "app_port")
        val fold = appPort.fold() ?: error("no fold row under app_port")

        val hidden = fold.children.map { it.node as SiteNode }
        assertTrue("more than the threshold, or it would not fold", hidden.size >= 3)
        assertTrue(
            "every folded row is one that lost",
            hidden.all { it.site.status == SiteStatus.OVERRIDDEN },
        )
        assertEquals("${hidden.size} overridden definitions", fold.node.text)
    }

    /**
     * The candidates that can still win stay where the eye lands, and the fold
     * takes the place of the block it replaces rather than going last.
     */
    fun testWhatCanStillWinStaysVisibleAboveTheFold() {
        val appPort = row(roleTask, "app_port")

        val mayWin = appPort.children
            .mapNotNull { it.node as? SiteNode }
            .filter { it.site.status == SiteStatus.MAY_WIN }
        assertEquals("both include_vars candidates are still on top", 2, mayWin.size)

        val foldIndex = appPort.children.indexOfFirst { it.node is OverriddenSitesNode }
        val lastMayWin = appPort.children.indexOfLast {
            (it.node as? SiteNode)?.site?.status == SiteStatus.MAY_WIN
        }
        assertTrue("the fold sits below them: $lastMayWin vs $foldIndex", lastMayWin < foldIndex)
    }

    /**
     * Every row under the fold is reachable, and none is listed twice.
     *
     * Folding is a move, not a copy: a site that appears both above and inside
     * would read as two definitions of the same thing.
     */
    fun testFoldingMovesTheRowsRatherThanCopyingThem() {
        val appPort = row(roleTask, "app_port")
        val fold = appPort.fold() ?: error("no fold row under app_port")

        val above = appPort.children.mapNotNull { it.node as? SiteNode }
        val inside = fold.children.map { it.node as SiteNode }

        val overlap = above.map { it.site.file to it.site.offset }
            .intersect(inside.map { it.site.file to it.site.offset }.toSet())
        assertTrue("no site is in both places: $overlap", overlap.isEmpty())
    }
}
