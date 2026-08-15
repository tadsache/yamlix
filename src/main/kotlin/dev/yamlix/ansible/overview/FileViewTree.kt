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

    fun build(view: FileVariableView?): List<OverviewTreeNode> {
        if (view == null) {
            return listOf(OverviewTreeNode(HintNode("Open a file inside an Ansible project")))
        }

        val rows = ArrayList<OverviewTreeNode>()
        rows += OverviewTreeNode(HeaderNode(view))

        rows += OverviewTreeNode(
            SectionNode("Uses", view.uses.size),
            view.uses.map { OverviewTreeNode(VariableRowNode(it)) }.ifEmpty {
                listOf(OverviewTreeNode(HintNode("No {{ variables }} in this file")))
            },
        )

        // Only offered when the file actually declares something. A task file
        // has nothing to declare, and an empty section would imply it should.
        if (view.defines.isNotEmpty()) {
            rows += OverviewTreeNode(
                SectionNode("Defines", view.defines.size),
                view.defines.map { OverviewTreeNode(VariableRowNode(it)) },
            )
        }
        return rows
    }
}

/** The file, what reaches it, and which hosts that means. */
data class HeaderNode(val view: FileVariableView) : OverviewNode {
    override val text: String
        get() = view.subtitle?.let { "$it · ${view.title}" } ?: view.title

    override val detail: String
        get() {
            val reached = when {
                view.reachedBy.isEmpty() -> "reached by no playbook"
                else -> "reached by ${view.reachedBy.joinToString(", ") { it.name }}"
            }
            return view.runsOn?.let { "$reached  ·  runs on $it" } ?: reached
        }

    override val icon: Icon get() = AllIcons.FileTypes.Yaml
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
            RowStatus.AMBIGUOUS -> AllIcons.General.Warning
            RowStatus.PROVIDED_BY_ANSIBLE -> AllIcons.Nodes.Static
            RowStatus.UNRESOLVED -> AllIcons.General.Error
            RowStatus.NEVER_WINS -> AllIcons.General.Warning
        }
}

/** One definition site, in the detail pane. */
data class SiteNode(val site: VariableSite, private val base: VirtualFile?) : OverviewNode {
    override val text: String
        get() = when (site.status) {
            SiteStatus.WINS -> "WINS"
            SiteStatus.MAY_WIN -> "MAY WIN"
            SiteStatus.OVERRIDDEN -> "overridden"
            SiteStatus.NOT_IN_SCOPE -> "not in scope here"
        }

    override val detail: String
        get() {
            val where = if (site.where.isEmpty()) "" else "  ·  ${site.where.joinToString("; ")}"
            return "${site.value ?: site.scopeLabel}$where"
        }

    override val icon: Icon
        get() = if (site.status == SiteStatus.WINS) AllIcons.Actions.Commit else AllIcons.Nodes.Padlock

    override fun target() = site.file to site.offset

    /** The path, shown on its own line so a long value never squeezes it out. */
    fun pathLabel(): String =
        base?.let { com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(site.file, it) }
            ?: site.file.name
}
