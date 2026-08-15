package dev.yamlix.ansible.overview

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Registers the Ansible tool window.
 *
 * [DumbAware] because the structural overview needs only PSI and the VFS, so
 * it has a real answer while the IDE is still indexing. The one part that does
 * need the index — the dead-configuration analysis — asks for smart mode
 * itself, and its toolbar button disables until then.
 */
class AnsibleToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AnsibleOverviewPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}
