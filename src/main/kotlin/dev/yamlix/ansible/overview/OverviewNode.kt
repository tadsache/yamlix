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

    /**
     * What the hover says, when the row abbreviates something.
     *
     * Null means the row already says it all and the renderer falls back to
     * the row's own text. Nothing in this window may be reachable *only* by
     * widening the tool window.
     */
    val tooltip: String? get() = null

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

/**
 * A grouping: "Uses (4)".
 *
 * No icon on purpose. A section is not a directory and there is no icon that
 * means "uses" — a folder glyph only invites the reader to look for a folder.
 * Indentation and the count already say everything a section row has to say.
 */
data class SectionNode(
    private val title: String,
    private val count: Int,
    /** A caveat about the whole section, when one applies to every row in it. */
    private val note: String? = null,
) : OverviewNode {
    override val text: String get() = title
    override val detail: String
        get() = note?.let { "($count)  ·  $it" } ?: "($count)"
}

/**
 * One play: what it targets, and how many hosts that turns out to be.
 *
 * The pattern is shown verbatim because that is what is written in the file,
 * with the evaluated set beside it — `web_ap*` and "pattern cannot be
 * evaluated" side by side is exactly the surprise worth surfacing.
 */
data class PlayNode(val play: PlayOutline) : OverviewNode {
    override val text: String get() = "hosts: ${play.hosts}"

    override val detail: String
        get() = listOfNotNull(play.hostSummary, play.name).joinToString("  ·  ")

    override val icon: Icon get() = AllIcons.Nodes.Servlet
}

/** A role a play runs. */
data class PlayRoleNode(val role: PlayRole) : OverviewNode {
    override val text: String get() = role.name

    override val detail: String?
        get() = if (role.entry == null) "role not found" else null

    override val icon: Icon
        get() = if (role.entry == null) AllIcons.General.Error else AllIcons.Nodes.Module

    override fun target(): Pair<VirtualFile, Int>? = role.entry?.let { it to 0 }
}

/** An `import_playbook:` entry. */
data class PlayImportNode(val import: PlayImport) : OverviewNode {
    override val text: String get() = "import_playbook: ${import.path}"

    override val detail: String?
        get() = if (import.target == null) "not found" else null

    override val icon: Icon
        get() = if (import.target == null) AllIcons.General.Error else AllIcons.Nodes.Include

    override fun target(): Pair<VirtualFile, Int>? = import.target?.let { it to 0 }
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
