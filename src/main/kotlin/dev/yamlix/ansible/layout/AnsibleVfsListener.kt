package dev.yamlix.ansible.layout

import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent

/**
 * Modification tracker for Ansible *layout* state — the parts of the model that
 * depend on the shape of the file tree rather than on the contents of any PSI
 * file. [com.intellij.psi.util.PsiModificationTracker] does not see a directory
 * being created, so cached values that enumerate directories need this too.
 */
object AnsibleLayoutTracker : SimpleModificationTracker()

/**
 * Bumps [AnsibleLayoutTracker], but only for events that can actually change the
 * layout. Filtering here keeps every unrelated VFS event (build output, VCS
 * churn) from invalidating the whole role search path.
 */
internal class AnsibleVfsListener : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        if (events.any { it.isRelevant() }) {
            AnsibleLayoutTracker.incModificationCount()
        }
    }

    private fun VFileEvent.isRelevant(): Boolean {
        // Structural events change which directories exist at all.
        if (this is VFileCreateEvent || this is VFileDeleteEvent ||
            this is VFileMoveEvent || this is VFilePropertyChangeEvent
        ) {
            return true
        }
        val name = file?.name ?: path.substringAfterLast('/')
        if (name == ANSIBLE_CFG) return true
        // Content edits to inventory sources change the group graph.
        val path = path
        return path.contains("/group_vars/") ||
            path.contains("/host_vars/") ||
            name.startsWith("hosts")
    }

    companion object {
        const val ANSIBLE_CFG = "ansible.cfg"
    }
}
