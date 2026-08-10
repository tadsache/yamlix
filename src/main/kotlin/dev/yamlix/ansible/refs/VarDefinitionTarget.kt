package dev.yamlix.ansible.refs

import com.intellij.icons.AllIcons
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import dev.yamlix.ansible.layout.AnsibleLayoutService
import dev.yamlix.ansible.vars.VarScope
import javax.swing.Icon

/**
 * A navigation target that presents itself.
 *
 * The platform's "Choose Declaration" popup renders a plain [PsiElement] as its
 * text plus the *containing directory*. For a variable with nine definition
 * sites that produces nine identical rows — the name and the project folder,
 * nine times, with no way to tell which is which.
 *
 * [FakePsiElement] implements `ItemPresentation` and returns itself from
 * `getPresentation()`, so wrapping the real element lets each row say what it
 * actually is: the scope, the value, the file, and the precedence rank that put
 * it in that position.
 */
class VarDefinitionTarget(
    private val target: PsiElement,
    private val scope: VarScope,
    private val valueText: String?,
    private val qualifier: String,
    /** Inventories (or hosts) where this site is the winner at the caret. */
    private val winsOn: List<String> = emptyList(),
    /** Inventories where it is one of several tied candidates. */
    private val mayWinOn: List<String> = emptyList(),
    /** False when the site exists but does not apply where the caret is. */
    private val inScope: Boolean = true,
) : FakePsiElement() {

    override fun getParent(): PsiElement = target

    override fun getContainingFile(): PsiFile? = target.containingFile

    override fun getTextOffset(): Int = target.textOffset

    override fun canNavigate(): Boolean = target is Navigatable && target.canNavigate()

    override fun navigate(requestFocus: Boolean) {
        (target as? Navigatable)?.navigate(requestFocus)
    }

    /** Left-hand, bold: what this definition *is* and what it says. */
    override fun getPresentableText(): String {
        val where = when (scope) {
            VarScope.GROUP_VARS, VarScope.GROUP_VARS_ALL ->
                if (qualifier.isNotEmpty()) "${scope.display}[$qualifier]" else scope.display
            VarScope.HOST_VARS ->
                if (qualifier.isNotEmpty()) "host_vars[$qualifier]" else scope.display
            VarScope.ROLE_DEFAULTS, VarScope.ROLE_VARS, VarScope.ROLE_PARAM ->
                if (qualifier.isNotEmpty()) "${scope.display}[$qualifier]" else scope.display
            else -> scope.display
        }
        return if (valueText.isNullOrEmpty()) where else "$where = $valueText"
    }

    /**
     * Right-hand, grey: where you will land, and — the part that makes the list
     * worth reading — whether this site actually applies at the caret.
     */
    override fun getLocationString(): String {
        val file = target.containingFile?.virtualFile
        val path = if (file == null) {
            ""
        } else {
            val base = AnsibleLayoutService.getInstance(target.project).cfgFor(file)?.baseDir
            base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.name
        }
        val status = when {
            winsOn.isNotEmpty() && mayWinOn.isNotEmpty() ->
                "WINS on ${winsOn.joinToString("; ")}, may win on ${mayWinOn.joinToString("; ")}"
            winsOn.isNotEmpty() -> "WINS on ${winsOn.joinToString("; ")}"
            mayWinOn.isNotEmpty() -> "may win on ${mayWinOn.joinToString("; ")}"
            !inScope -> "not in scope here"
            else -> "in scope"
        }
        return "$path  ·  rank ${scope.rank}  ·  $status"
    }

    override fun getName(): String = presentableText

    override fun getIcon(open: Boolean): Icon = when (scope) {
        VarScope.ROLE_DEFAULTS -> AllIcons.Nodes.PropertyReadStatic
        VarScope.GROUP_VARS, VarScope.GROUP_VARS_ALL -> AllIcons.Nodes.Folder
        VarScope.HOST_VARS -> AllIcons.Nodes.Servlet
        VarScope.SET_FACT, VarScope.REGISTERED -> AllIcons.Nodes.Method
        VarScope.INCLUDE_VARS -> AllIcons.Nodes.Static
        else -> AllIcons.Nodes.Variable
    }
}
