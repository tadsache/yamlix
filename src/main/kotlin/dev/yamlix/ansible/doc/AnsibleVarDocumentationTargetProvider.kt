package dev.yamlix.ansible.doc

import com.intellij.icons.AllIcons
import com.intellij.model.Pointer
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import dev.yamlix.ansible.layout.AnsibleLayoutService
import dev.yamlix.ansible.psi.PlayStructure
import dev.yamlix.ansible.refs.AnsibleVariableReference
import dev.yamlix.ansible.vars.VariableReportBuilder
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Quick Documentation (Ctrl+Q) for a `{{ variable }}` inside a YAML scalar.
 *
 * Offset-based by necessity: a Jinja expression has no PSI of its own, so there
 * is no element to hang a `PsiDocumentationTargetProvider` on. This EP receives
 * the raw caret offset, which is exactly what the in-scalar arithmetic needs.
 */
class AnsibleVarDocumentationTargetProvider : DocumentationTargetProvider {

    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val virtualFile = PlayStructure.sourceFile(file) ?: return emptyList()
        if (!AnsibleLayoutService.getInstance(file.project).isAnsibleContext(virtualFile)) {
            return emptyList()
        }

        // The caret may sit just past the last character of the identifier, so
        // probe the element to its left as well.
        val scalar = scalarAt(file, offset) ?: scalarAt(file, offset - 1) ?: return emptyList()

        val reference = scalar.references
            .filterIsInstance<AnsibleVariableReference>()
            .firstOrNull { candidate ->
                // rangeInElement is relative to the element's own text, which is
                // what makes this work unchanged for plain, quoted and folded
                // (`>-`) scalars — no separate block-header arithmetic.
                val absolute: TextRange = candidate.rangeInElement.shiftRight(scalar.textOffset)
                absolute.containsOffset(offset)
            } ?: return emptyList()

        val name = reference.rangeInElement.substring(scalar.text)
        return listOf(AnsibleVarDocumentationTarget(scalar, name))
    }

    private fun scalarAt(file: PsiFile, offset: Int): YAMLScalar? {
        if (offset < 0) return null
        return PsiTreeUtil.getParentOfType(file.findElementAt(offset), YAMLScalar::class.java, false)
    }
}

private class AnsibleVarDocumentationTarget(
    private val scalar: YAMLScalar,
    private val name: String,
) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val elementPointer = com.intellij.psi.SmartPointerManager
            .getInstance(scalar.project).createSmartPsiElementPointer(scalar)
        return Pointer {
            elementPointer.element?.let { AnsibleVarDocumentationTarget(it, name) }
        }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(name)
            .icon(AllIcons.Nodes.Variable)
            .containerText("Ansible variable")
            .presentation()

    override fun computeDocumentation(): DocumentationResult {
        val project = scalar.project
        if (com.intellij.openapi.project.DumbService.isDumb(project)) {
            return DocumentationResult.documentation(
                "<p>Indexing &mdash; variable resolution is not available yet.</p>",
            )
        }
        val reports = VariableReportBuilder.getInstance(project).buildAll(name, scalar)
        val base = PlayStructure.sourceFile(scalar)
            ?.let { AnsibleLayoutService.getInstance(project).cfgFor(it)?.baseDir }
        return DocumentationResult.documentation(
            AnsibleVarDocumentation.render(name, reports, base),
        )
    }
}
