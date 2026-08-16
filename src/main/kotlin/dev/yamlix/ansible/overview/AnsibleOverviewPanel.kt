package dev.yamlix.ansible.overview

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorEventMulticaster
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import dev.yamlix.ansible.layout.AnsibleLayoutService
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.ScrollPaneConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The Ansible tool window: what is actually true in the file you are reading.
 *
 * Every other surface of the plugin answers a question about one symbol, in a
 * popup that must answer instantly and has one line of width. This answers
 * "what does this file get, and where does it run" — a question with no answer
 * anywhere else — and because it is a panel it can show the whole resolution
 * table for the selected variable with nothing truncated.
 *
 * One tree: the variables of the current file, each expanding to the sites
 * that define it. The caret drives the selection unless [pinned]. Sites stay
 * collapsed until you ask, so the common case — one variable, one definition —
 * costs one line rather than half the window.
 */
class AnsibleOverviewPanel(private val project: Project) :
    SimpleToolWindowPanel(true, true), Disposable {

    private val listRoot = DefaultMutableTreeNode()
    private val listModel = DefaultTreeModel(listRoot)
    private val list = Tree(listModel)

    private var file: VirtualFile? = null
    private var view: FileVariableView? = null
    private var pinned = false

    init {
        configure(list)
        val scroller = ScrollPaneFactory.createScrollPane(list, true)
        // Rows are fitted to the width instead of running past it, so a
        // horizontal bar would only ever scroll to whitespace.
        scroller.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        setContent(scroller)
        toolbar = buildToolbar()

        val connection = project.messageBus.connect(this)
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (!pinned) retargetTo(event.newFile)
                }
            },
        )

        // A file opened during start-up is the common case, not the rare one,
        // and while the index fills every variable in it resolves to nothing.
        // Without this the first thing a new user ever sees is a file they know
        // is fine, reported as entirely undefined.
        connection.subscribe(
            DumbService.DUMB_MODE,
            object : DumbService.DumbModeListener {
                override fun exitDumbMode() = refresh()
            },
        )

        // Following the caret is what makes it feel like part of the editor
        // rather than a report you have to go and refresh.
        val multicaster: EditorEventMulticaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) {
                    if (pinned) return
                    val editorFile = FileDocumentManager.getInstance().getFile(event.editor.document)
                    if (editorFile != file) return
                    selectRowAt(event.editor.caretModel.offset)
                }
            },
            this,
        )

        retargetTo(FileEditorManager.getInstance(project).selectedFiles.firstOrNull())
    }

    private fun configure(tree: Tree) {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = NodeRenderer()
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                selected(tree)?.navigate(project)
                return true
            }
        }.installOn(tree)
    }

    // ---- wiring -------------------------------------------------------------

    private fun retargetTo(target: VirtualFile?) {
        if (target == null || target == file) return
        if (!AnsibleLayoutService.getInstance(project).isAnsibleContext(target)) return
        file = target
        refresh()
    }

    private fun buildToolbar(): javax.swing.JComponent {
        val actions = DefaultActionGroup()
        actions.add(object : AnAction("Refresh", "Rebuild for the current file", AllIcons.Actions.Refresh) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(event: AnActionEvent) = refresh()
        })
        actions.addSeparator()
        actions.add(
            object : ToggleAction("Pin", "Stop following the editor", AllIcons.General.Pin_tab) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(event: AnActionEvent) = pinned
                override fun setSelected(event: AnActionEvent, state: Boolean) {
                    pinned = state
                }
            },
        )
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, actions, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    // ---- building -----------------------------------------------------------

    /**
     * Resolving every variable of a file is real work — index lookups and a
     * sweep per variable — so it happens off the EDT under a cancellable read
     * action, and only the rendering comes back.
     */
    private fun refresh() {
        val target = file ?: return
        render(null, HintNode("Resolving ${target.name}…"))

        ReadAction.nonBlocking<ViewState> {
            FileVariableViewService.getInstance(project).build(target)
        }
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.defaultModalityState()) { built ->
                view = (built as? ViewState.Ready)?.view
                listRoot.removeAllChildren()
                FileViewTree.build(built).forEach { listRoot.add(it.toSwing()) }
                listModel.reload()
                expandSections(list)
                currentCaretOffset()?.let(::selectRowAt)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun render(built: FileVariableView?, hint: OverviewNode) {
        view = built
        listRoot.removeAllChildren()
        listRoot.add(DefaultMutableTreeNode(hint))
        listModel.reload()
    }

    /**
     * Selects the variable the caret is inside, if any.
     *
     * Nothing is deselected when the caret is elsewhere — collapsing the
     * variable every time you move through ordinary YAML would make it
     * flicker. The decision itself lives in [FileVariableView.rowAt], where it
     * can be tested.
     */
    private fun selectRowAt(offset: Int) {
        val current = view ?: return
        val name = current.rowAt(offset)?.name
            ?: current.groupAt(offset)?.name
            ?: return
        selectRowNamed(name)
    }

    private fun selectRowNamed(name: String) {
        for (section in 0 until listRoot.childCount) {
            val sectionNode = listRoot.getChildAt(section) as DefaultMutableTreeNode
            for (index in 0 until sectionNode.childCount) {
                val child = sectionNode.getChildAt(index) as DefaultMutableTreeNode
                val node = child.userObject as? OverviewNode ?: continue
                val rowName = when (node) {
                    is VariableRowNode -> node.row.name
                    is GroupNode -> node.group.name
                    else -> null
                }
                if (rowName != name) continue
                val path = TreePath(arrayOf(listRoot, sectionNode, child))
                list.selectionPath = path
                // Expand it: the sites are why you looked, and the caret
                // landing on a variable is the moment you want them.
                list.expandPath(path)
                list.scrollPathToVisible(path)
                return
            }
        }
    }

    private fun currentCaretOffset(): Int? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        if (FileDocumentManager.getInstance().getFile(editor.document) != file) return null
        return editor.caretModel.offset
    }

    private fun OverviewTreeNode.toSwing(): DefaultMutableTreeNode {
        val swing = DefaultMutableTreeNode(node)
        children.forEach { swing.add(it.toSwing()) }
        return swing
    }

    /**
     * Expands the sections, and nothing below them.
     *
     * Expanding everything would put every definition site of every variable on
     * screen at once — for a task file that is a wall. The sites of the one
     * variable you are standing in are expanded by [selectRowNamed] instead,
     * which is the only set you asked about.
     */
    private fun expandSections(tree: Tree) {
        for (section in 0 until listRoot.childCount) {
            tree.expandPath(TreePath(arrayOf(listRoot, listRoot.getChildAt(section))))
        }
    }

    private fun selected(tree: Tree): OverviewNode? =
        (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? OverviewNode

    override fun dispose() = Unit

    private class NodeRenderer : ColoredTreeCellRenderer() {

        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? OverviewNode ?: return
            icon = node.icon

            // A site that lost is greyed whole. The outcome used to be a word
            // ("overridden"); carrying it in the weight instead means the eye
            // finds the winner without reading anything.
            val subdued = (node as? SiteNode)?.subdued == true || node is OverriddenSitesNode
            val nameAttributes =
                if (subdued) SimpleTextAttributes.GRAYED_ATTRIBUTES
                else SimpleTextAttributes.REGULAR_ATTRIBUTES

            val detail = node.detail
            // The whole row, always, on hover — nothing is only available by
            // widening the tool window.
            toolTipText = node.tooltip ?: detail?.let { "${node.text}    $it" } ?: node.text

            val metrics = getFontMetrics(font)
            val measure: (String) -> Int = { metrics.stringWidth(it) }
            val available = RowText.availableWidth(
                visibleWidth = tree.visibleRect.width,
                depth = (value as? DefaultMutableTreeNode)?.level ?: 1,
                iconWidth = node.icon?.let { it.iconWidth + iconTextGap } ?: 0,
            )

            if (available <= 0) {
                // No layout yet; render in full rather than render nothing.
                append(node.text, nameAttributes)
                detail?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                return
            }

            // The name has first claim on the width — a row shortened to
            // "artifact_re…" identifies nothing.
            val name = RowText.fit(node.text, available, measure)
            append(name, nameAttributes)
            if (detail == null || name != node.text) return

            val gap = "  "
            val left = available - measure(name) - measure(gap)
            val fitted = RowText.fit(detail, left, measure)
            if (fitted.isNotEmpty()) append(gap + fitted, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}
