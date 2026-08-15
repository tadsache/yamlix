package dev.yamlix.ansible.overview

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * One row of the Ansible tool window.
 *
 * Kept separate from the Swing tree so what a row *says* is decided in plain
 * data, and the renderer only decides how it looks. [text] is the bold part,
 * [detail] the grey part after it — the same split the "Choose Declaration"
 * rows use.
 */
sealed interface OverviewNode {
    val text: String
    val detail: String? get() = null
    val icon: Icon? get() = null

    /** Where double-clicking lands, or null when the row is not a place. */
    fun target(): Pair<VirtualFile, Int>? = null

    fun navigate(project: Project) {
        val (file, offset) = target() ?: return
        OpenFileDescriptor(project, file, offset).navigate(true)
    }
}

/** A row and the rows under it. */
data class OverviewTreeNode(
    val node: OverviewNode,
    val children: List<OverviewTreeNode> = emptyList(),
)

/** A grouping: "Uses (4)". */
data class SectionNode(private val title: String, private val count: Int) : OverviewNode {
    override val text: String get() = title
    override val detail: String get() = "($count)"
    override val icon: Icon get() = AllIcons.Nodes.Folder
}

/**
 * A row that explains rather than points at something.
 *
 * Used where blank would be ambiguous — "no variables here" reads very
 * differently from an empty box that might just have failed to load.
 */
data class HintNode(override val text: String) : OverviewNode {
    override val icon: Icon get() = AllIcons.General.Information
}
