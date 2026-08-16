package dev.yamlix.ansible.overview

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.yamlix.ansible.layout.AnsibleLayoutService

/**
 * Registers the Ansible tool window.
 *
 * [DumbAware] so the window can be opened while the IDE indexes — but every
 * row in it comes from the variable index, so it has no answer until indexing
 * finishes. The panel says so and rebuilds itself on the way out of dumb mode;
 * it must never render variable rows from an index that is still filling,
 * because those rows are wrong rather than incomplete.
 */
class AnsibleToolWindowFactory : ToolWindowFactory, DumbAware {

    /**
     * No Ansible in the project, no tool window. Registering it unconditionally
     * put a permanent, permanently empty "Ansible" tab on the right-hand stripe
     * of every project the user opens, Ansible or not.
     */
    override suspend fun isApplicableAsync(project: Project): Boolean =
        readAction { AnsibleLayoutService.getInstance(project).hasAnsibleContent() }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AnsibleOverviewPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}
