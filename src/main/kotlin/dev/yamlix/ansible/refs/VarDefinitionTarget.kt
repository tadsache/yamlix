package dev.yamlix.ansible.refs

import com.intellij.icons.AllIcons
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import dev.yamlix.ansible.layout.AnsibleLayoutService
import dev.yamlix.ansible.vars.VarScope
import org.jetbrains.yaml.psi.YAMLKeyValue
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

    /**
     * Makes Find Usages work through the wrapper.
     *
     * `PsiPolyVariantReferenceBase.isReferenceTo` compares `multiResolve`
     * results against the element the search started from. Our references
     * resolve to this presentation wrapper rather than to the underlying
     * `YAMLKeyValue`, so without this the platform sees no match and reports
     * "No usages found" for a variable with a dozen live uses.
     */
    override fun isEquivalentTo(another: PsiElement?): Boolean =
        another === target || another === this

    /** Find Usages and "go to declaration" should land on the real element. */
    override fun getNavigationElement(): PsiElement = target

    override fun getTextOffset(): Int = target.textOffset

    override fun canNavigate(): Boolean = target is Navigatable && target.canNavigate()

    override fun navigate(requestFocus: Boolean) {
        (target as? Navigatable)?.navigate(requestFocus)
    }

    /**
     * Left-hand, bold: where this definition applies, and what it says there.
     *
     * Deliberately does *not* repeat the scope, the group/host qualifier, or
     * the precedence rank. The scope and qualifier are already spelled out by
     * the path in [getLocationString] — `inventories/env-a/group_vars/all.yml`
     * says "group_vars", "all" and "env-a" on its own — and the icon carries
     * the scope for scanning. The rank is an internal number a reader cannot
     * act on; the list is already ordered by it.
     */
    /**
     * Left-hand, bold: the value, and nothing else.
     *
     * The value is what the reader came for, and it is the only field that
     * differs between every row — so it goes first, where the eye lands and
     * where rows line up against each other. Everything that qualifies it
     * (which hosts, which file) is context and belongs in the grey text.
     */
    override fun getPresentableText(): String = what()

    /**
     * What this site says, on one line.
     *
     * The index only stores a scalar value, so a mapping or a sequence —
     * `artifact_repo:` with nested keys, say — arrives here as null and would
     * otherwise render as the bare scope word, which is exactly the
     * uninformative noise this row exists to avoid. The value is read back off
     * the PSI in that case; a popup shows a handful of rows, so the cost is
     * irrelevant next to being able to see the value at all.
     */
    private fun what(): String {
        valueText?.takeIf { it.isNotBlank() }?.let { return shortValue(it) }
        val nested = (target as? YAMLKeyValue)?.value?.text
        return if (nested.isNullOrBlank()) scope.display else shortValue(nested)
    }

    /** Right-hand, grey: where the value applies, then the file you will land in. */
    override fun getLocationString(): String = "${where()}  ·  ${path()}"

    private fun path(): String {
        val file = target.containingFile?.virtualFile ?: return ""
        val base = AnsibleLayoutService.getInstance(target.project).cfgFor(file)?.baseDir
        return base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.name
    }

    /**
     * Where this site applies at the caret — the first thing a reader wants
     * when the same variable is defined in a dozen places.
     *
     * "overridden" is the case worth naming explicitly: the site does apply
     * here, it simply never wins anywhere. Without it a losing row is
     * indistinguishable from a winning one.
     */
    private fun where(): String = when {
        winsOn.isNotEmpty() && mayWinOn.isNotEmpty() ->
            "${winsOn.joinToString("; ")}; may win on ${mayWinOn.joinToString("; ")}"
        winsOn.isNotEmpty() -> winsOn.joinToString("; ")
        mayWinOn.isNotEmpty() -> "may win on ${mayWinOn.joinToString("; ")}"
        !inScope -> "not in scope here"
        else -> "overridden"
    }

    /**
     * A value on one line, short enough not to push the file path off-screen.
     *
     * A mapping or a folded block is legitimately long; Quick Documentation
     * renders it in full, so truncating here loses nothing a reader cannot
     * immediately get.
     */
    private fun shortValue(value: String): String {
        val flat = value.replace(WHITESPACE, " ").trim()
        return if (flat.length <= MAX_VALUE_CHARS) flat else flat.take(MAX_VALUE_CHARS).trimEnd() + "…"
    }

    override fun getName(): String = presentableText

    private companion object {
        const val MAX_VALUE_CHARS = 60
        val WHITESPACE = Regex("\\s+")
    }

    override fun getIcon(open: Boolean): Icon = when (scope) {
        VarScope.ROLE_DEFAULTS -> AllIcons.Nodes.PropertyReadStatic
        VarScope.GROUP_VARS, VarScope.GROUP_VARS_ALL -> AllIcons.Nodes.Folder
        VarScope.HOST_VARS -> AllIcons.Nodes.Servlet
        VarScope.SET_FACT, VarScope.REGISTERED -> AllIcons.Nodes.Method
        VarScope.INCLUDE_VARS -> AllIcons.Nodes.Static
        else -> AllIcons.Nodes.Variable
    }
}
