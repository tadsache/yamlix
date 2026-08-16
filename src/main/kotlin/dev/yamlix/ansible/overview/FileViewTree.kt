package dev.yamlix.ansible.overview

import com.intellij.icons.AllIcons
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * The rows of the variable list, as plain data.
 *
 * Same split as everywhere else in this package: what a row *says* is decided
 * here and tested headlessly; the panel only decides how it looks.
 */
object FileViewTree {

    private val SITE_ORDER = listOf(
        SiteStatus.WINS, SiteStatus.MAY_WIN, SiteStatus.OVERRIDDEN, SiteStatus.NOT_IN_SCOPE,
    )

    /**
     * How many losing definitions it takes before they are worth folding.
     *
     * Below this the fold costs more than it saves: it trades two visible rows
     * for one row and a click.
     */
    private const val OVERRIDDEN_FOLD_THRESHOLD = 3

    fun build(state: ViewState): List<OverviewTreeNode> {
        val view = when (state) {
            ViewState.NotAnsible ->
                return listOf(OverviewTreeNode(HintNode("Open a file inside an Ansible project")))
            // Deliberately not a partial view. Every row depends on the index,
            // so anything rendered now would read as fact and be wrong.
            ViewState.Indexing ->
                return listOf(OverviewTreeNode(HintNode("Indexing — variables cannot be resolved yet")))
            ViewState.IndexUnavailable ->
                return listOf(
                    OverviewTreeNode(HintNode("The variable index looks empty")),
                    OverviewTreeNode(
                        HintNode("This project defines variables, but the index knows none of " +
                            "them. File | Invalidate Caches… and restart rebuilds it.")
                    ),
                )
            ViewState.OutsideContentRoots ->
                return listOf(
                    OverviewTreeNode(HintNode("This file is outside the project's content roots")),
                    OverviewTreeNode(
                        HintNode("Nothing here is indexed, so no variable can be resolved. " +
                            "Add its directory as a content root in Project Structure.")
                    ),
                )
            is ViewState.Ready -> state.view
        }

        val rows = ArrayList<OverviewTreeNode>()
        rows += OverviewTreeNode(HeaderNode(view))

        // A playbook's declarations come first: they are what the file is, and
        // for a site playbook that references no variables they are the only
        // thing worth showing.
        //
        // Plays and imports are interleaved in file order rather than grouped
        // by kind, because a playbook is read top to bottom and the order is
        // the order things run in.
        val entries = view.plays.map { it.offset to playRow(it) } +
            view.imports.map { it.offset to OverviewTreeNode(PlayImportNode(it)) }
        if (entries.isNotEmpty()) {
            rows += OverviewTreeNode(
                SectionNode("Runs", entries.size),
                entries.sortedBy { it.first }.map { it.second },
            )
        }

        // An inventory's groups, and who aims at them.
        if (view.groups.isNotEmpty()) {
            // The same plays are unevaluable for every group, so the caveat
            // belongs to the section rather than repeated under each one.
            val unevaluated = view.groups.maxOf { it.unevaluatedPlays }
            rows += OverviewTreeNode(
                SectionNode(
                    "Groups",
                    view.groups.size,
                    note = if (unevaluated == 0) null else
                        "$unevaluated ${if (unevaluated == 1) "play uses a pattern" else "plays use patterns"}" +
                            " that cannot be evaluated",
                ),
                view.groups.map { group ->
                    OverviewTreeNode(GroupNode(group), groupChildren(group, view.base))
                },
            )
        }

        // The "no variables here" hint exists so an empty box cannot be
        // mistaken for a failure to load. A playbook that has already shown its
        // plays is visibly not empty, so the hint would just be a row saying
        // nothing.
        if (view.uses.isNotEmpty() || (entries.isEmpty() && view.groups.isEmpty())) {
            rows += OverviewTreeNode(
                SectionNode("Uses", view.uses.size),
                view.uses.map { variableRow(it, view.base) }.ifEmpty {
                    listOf(OverviewTreeNode(HintNode("No {{ variables }} in this file")))
                },
            )
        }

        // Only offered when the file actually declares something. A task file
        // has nothing to declare, and an empty section would imply it should.
        if (view.defines.isNotEmpty()) {
            rows += OverviewTreeNode(
                SectionNode("Defines", view.defines.size),
                view.defines.map { variableRow(it, view.base) },
            )
        }
        return rows
    }

    /**
     * A variable and its definition sites, inline.
     *
     * The sites used to live in a second pane below, which meant half the tool
     * window was reserved for a list that usually has one entry. As children
     * they cost one line when you want them and nothing when you do not.
     */
    private fun variableRow(row: VariableRow, base: VirtualFile?): OverviewTreeNode {
        // The same rule the "Choose Declaration" popup uses: a same-named
        // variable in an unrelated role is not a candidate here, and listing it
        // only invites the reader to wonder why it lost. Flow-sensitive scopes
        // stay, because there the reason is position, not relevance.
        val sites = row.sites.filter { it.status != SiteStatus.NOT_IN_SCOPE || it.flowSensitive }

        // The value is already on the variable row when there is only one, so
        // repeating it on the site says nothing. With several it is the whole
        // point of listing them separately.
        val showValue = sites.mapNotNull { it.value }.distinct().size > 1

        // Winner first, then what might still win, then what lost — and within
        // each, the higher precedence first. Index order put the overridden
        // definition above the one that beat it.
        val ordered = sites.sortedWith(
            compareBy({ SITE_ORDER.indexOf(it.status) }, { -it.scopeRank }),
        )

        fun siteNode(site: VariableSite) = OverviewTreeNode(SiteNode(site, base, showValue))

        // The same argument the panel makes about expanding every variable, one
        // level down: a variable resolved across four inventories carries a
        // dozen definitions that lost, and expanding it buries the two that can
        // still win under ten that cannot. They are folded rather than dropped,
        // because "where else is this written" is a real question — just not the
        // one that made you open the row.
        val lost = ordered.filter { it.status == SiteStatus.OVERRIDDEN }
        if (lost.size < OVERRIDDEN_FOLD_THRESHOLD) {
            return OverviewTreeNode(VariableRowNode(row), ordered.map(::siteNode))
        }

        // The fold takes the place of the block it replaces rather than going
        // last, so the rows stay in precedence order around it.
        val rank = SITE_ORDER.indexOf(SiteStatus.OVERRIDDEN)
        val above = ordered.filter { SITE_ORDER.indexOf(it.status) < rank }
        val below = ordered.filter { SITE_ORDER.indexOf(it.status) > rank }

        return OverviewTreeNode(
            VariableRowNode(row),
            above.map(::siteNode) +
                OverviewTreeNode(OverriddenSitesNode(lost.size), lost.map(::siteNode)) +
                below.map(::siteNode),
        )
    }

    private fun groupChildren(group: GroupOutline, base: VirtualFile?): List<OverviewTreeNode> {
        val rows = ArrayList<OverviewTreeNode>()
        group.targetedBy.forEach { rows += OverviewTreeNode(GroupUseNode(it)) }
        group.varsFiles.forEach { rows += OverviewTreeNode(GroupVarsNode(it, base)) }

        if (rows.isEmpty()) {
            rows += OverviewTreeNode(HintNode("no play targets this group"))
        }
        return rows
    }

    private fun playRow(play: PlayOutline) = OverviewTreeNode(
        PlayNode(play),
        play.roles.map { OverviewTreeNode(PlayRoleNode(it)) }.ifEmpty {
            listOf(OverviewTreeNode(HintNode("no roles — tasks only")))
        },
    )
}

/** The file, what reaches it, and which hosts that means. */
data class HeaderNode(val view: FileVariableView) : OverviewNode {
    override val text: String
        get() = view.subtitle?.let { "$it · ${view.title}" } ?: view.title

    override val detail: String
        get() {
            val reached = when {
                // A playbook reaches itself. Saying so is noise, and reads as
                // though something else pulled it in.
                view.plays.isNotEmpty() -> "playbook"
                view.groups.isNotEmpty() -> "inventory"
                view.reachedBy.isEmpty() -> "reached by no playbook"
                view.reachedBy.size == 1 -> "reached by ${view.reachedBy.single().name}"
                // Naming them all is what a docked tool window has least room
                // for, and the count is what the reader is actually asking.
                // The names are on the hover.
                else -> "reached by ${view.reachedBy.size} playbooks"
            }
            return view.runsOn?.let { "$reached  ·  runs on $it" } ?: reached
        }

    override val icon: Icon get() = AllIcons.FileTypes.Yaml

    /** The playbooks by name, which the row abbreviates to a count. */
    override val tooltip: String
        get() = buildList {
            add(view.subtitle?.let { "$it · ${view.title}" } ?: view.title)
            if (view.reachedBy.isNotEmpty()) {
                add("reached by " + view.reachedBy.joinToString(", ") { it.name })
            }
            view.runsOn?.let { add("runs on $it") }
        }.joinToString("\n")
}

data class VariableRowNode(val row: VariableRow) : OverviewNode {
    override val text: String get() = row.name
    override val detail: String
        get() = row.note?.let { "${row.summary}   —   $it" } ?: row.summary

    /**
     * The icon carries the status, so the eye can skim for trouble without
     * reading every line.
     */
    override val icon: Icon
        get() = when (row.status) {
            RowStatus.RESOLVED -> AllIcons.Nodes.Constant
            RowStatus.VARIES -> AllIcons.Nodes.Variable
            // A question, not a complaint. `include_vars: "{{ os_family }}.yml"`
            // has a set of answers by design, and a warning triangle would call
            // idiomatic Ansible a defect. What the reader needs to know is that
            // the answer depends on run-time state, which is what "?" says.
            RowStatus.AMBIGUOUS -> AllIcons.General.ContextHelp
            RowStatus.PROVIDED_BY_ANSIBLE -> AllIcons.Nodes.Static
            RowStatus.RUNTIME -> AllIcons.Nodes.Plugin
            // A loop is an iteration, not a defect: the same neutral icon as
            // any other value the project supplies rather than a warning.
            RowStatus.LOOP_ITEM -> AllIcons.Nodes.Variable
            // Not an error and not a warning: the variable was found, only the
            // key after the dot was not, and an optional key that the use
            // already defaults is idiomatic rather than suspect.
            RowStatus.PARTIAL -> AllIcons.Nodes.Variable
            RowStatus.UNRESOLVED -> AllIcons.General.Error
            RowStatus.NEVER_WINS -> AllIcons.General.Warning
        }
}

/** A group an inventory declares. */
data class GroupNode(val group: GroupOutline) : OverviewNode {
    override val text: String get() = group.name

    override val detail: String
        get() = buildList {
            add("${group.hostCount} ${if (group.hostCount == 1) "host" else "hosts"}")
            if (group.children.isNotEmpty()) add("children: ${group.children.joinToString(", ")}")
            add(
                when (group.targetedBy.size) {
                    0 -> "targeted by nothing"
                    1 -> "targeted by 1 play"
                    else -> "targeted by ${group.targetedBy.size} plays"
                }
            )
        }.joinToString("  ·  ")

    /** Nothing aiming at a group is worth noticing: it may be dead. */
    override val icon: Icon
        get() = if (group.targetedBy.isEmpty()) AllIcons.General.Warning else AllIcons.Nodes.Module
}

/** A play that runs on the group. */
data class GroupUseNode(val use: GroupUse) : OverviewNode {
    override val text: String get() = use.playbook.name

    override val detail: String
        get() = buildList {
            add("hosts: ${use.pattern}")
            if (!use.exact) add("${use.matchedHosts} of its hosts")
        }.joinToString("  ·  ")

    override val icon: Icon
        get() = if (use.exact) AllIcons.Actions.Checked else AllIcons.Nodes.EmptyNode

    override fun target() = use.playbook to use.offset
}

/** A `group_vars` file that applies to the group. */
data class GroupVarsNode(val file: VirtualFile, private val base: VirtualFile?) : OverviewNode {
    override val text: String get() = "group_vars"

    override val detail: String
        get() = base?.let { com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(file, it) }
            ?: file.name

    override val icon: Icon get() = AllIcons.FileTypes.Yaml

    override fun target() = file to 0
}

/**
 * One definition site, under the variable it defines.
 *
 * Led by the precedence level — `role defaults`, `group_vars` — rather than a
 * verdict. "WINS" was shouted loudest in the one case where it said nothing:
 * a single site has nothing to win against. The level is the *reason* it wins,
 * which is the part no other tool will tell you, and the outcome is carried by
 * the icon and the dimming instead of a word.
 */
private const val MAX_SITE_VALUE = 40

/**
 * The definitions that lost, behind one row.
 *
 * Greyed like the sites it stands for, and carrying the count so the row still
 * answers "how much am I not looking at" without being opened.
 */
data class OverriddenSitesNode(private val count: Int) : OverviewNode {
    override val text: String
        get() = "$count overridden ${if (count == 1) "definition" else "definitions"}"

    override val icon: Icon get() = AllIcons.Nodes.EmptyNode
}

data class SiteNode(
    val site: VariableSite,
    private val base: VirtualFile? = null,
    /** Shown only when it adds to what the variable row already says. */
    private val showValue: Boolean = false,
) : OverviewNode {

    override val text: String get() = site.scopeLabel

    override val detail: String
        get() = buildList {
            if (showValue) site.value?.let { add(flatten(it)) }
            addAll(site.where)
            if (site.status == SiteStatus.OVERRIDDEN) add("overridden")
            if (site.status == SiteStatus.MAY_WIN) add("may win at run time")
            add(pathLabel())
        }.joinToString("  ·  ")

    /**
     * Ticked wins, "?" might, bullet lost — and everything but the winner is
     * rendered dim.
     *
     * A candidate is not a defect. `may win at run time` describes a site the
     * plugin cannot rank without knowing a fact, so it takes the question mark
     * rather than the warning triangle it used to carry; the triangle claimed
     * something was wrong with a file that is merely conditional.
     */
    override val icon: Icon
        get() = when (site.status) {
            SiteStatus.WINS -> AllIcons.Actions.Checked
            SiteStatus.MAY_WIN -> AllIcons.General.ContextHelp
            else -> AllIcons.Nodes.EmptyNode
        }

    /** Whether the renderer should grey this row out. */
    val subdued: Boolean get() = site.status == SiteStatus.OVERRIDDEN ||
        site.status == SiteStatus.NOT_IN_SCOPE

    override fun target() = site.file to site.offset

    /**
     * One line, bounded.
     *
     * A mapping value arrives with its newlines, and pasted whole it pushed the
     * inventories and the path off the end of the row.
     */
    private fun flatten(value: String): String {
        val oneLine = value.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= MAX_SITE_VALUE) oneLine
        else oneLine.take(MAX_SITE_VALUE).trimEnd() + "…"
    }

    fun pathLabel(): String =
        base?.let { com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(site.file, it) }
            ?: site.file.name
}
