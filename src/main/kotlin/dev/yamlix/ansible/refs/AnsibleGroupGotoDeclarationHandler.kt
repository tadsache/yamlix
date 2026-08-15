package dev.yamlix.ansible.refs

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import dev.yamlix.ansible.inventory.GroupUsageService

/**
 * Ctrl-click on an inventory group goes to the plays that run on it.
 *
 * The direction is deliberately inverted. Everywhere else "go to declaration"
 * leaves a use and arrives at a definition; here the caret is already *on* the
 * definition — `[web_app]` is where the group comes into existence — so the
 * platform's honest answer was "Cannot find declaration to go to". The useful
 * jump from a group is to where it is consumed, which is the one direction
 * nothing else in the IDE offers: the name is written in the inventory and read
 * in a playbook, with no link between the files.
 *
 * Several plays commonly target one group, and each is returned so the platform
 * offers its usual picker rather than guessing at one.
 *
 * Known wart on INI inventories: such a file has no language, so its whole
 * content is one `PsiPlainText` element, and the platform underlines that whole
 * element while Ctrl is held — the entire file turns blue. A `PsiReference`
 * would carry a narrow range, but `PsiPlainTextImpl` returns no references and
 * is not a `PsiExternalReferenceHost`, so neither the classic nor the symbol
 * reference API reaches it. Narrowing it needs a real file type for inventories.
 */
class AnsibleGroupGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val file = sourceElement?.containingFile?.virtualFile
            ?: editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
            ?: return null

        val project = sourceElement?.project ?: editor?.project ?: return null
        val usages = GroupUsageService.getInstance(project)
        val root = usages.inventoryRootOf(file) ?: return null
        val group = usages.groupNameAt(file, offset) ?: return null

        return usages.usages(root, group)
            .map { it.hostsKey }
            .ifEmpty { return null }
            .toTypedArray()
    }

    /** Names the direction, so the popup does not claim to show a declaration. */
    override fun getActionText(context: DataContext): String = "Go to Plays Targeting This Group"
}
