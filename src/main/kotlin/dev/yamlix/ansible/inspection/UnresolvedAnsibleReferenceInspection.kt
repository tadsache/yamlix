package dev.yamlix.ansible.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import dev.yamlix.ansible.refs.AnsibleReferenceBase
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YamlPsiElementVisitor

/**
 * Flags Ansible roles, includes and file references that resolve to nothing.
 *
 * A Jinja-templated target that still matched at least one candidate is not a
 * problem — `include_vars: "{{ ansible_os_family }}.yml"` legitimately has a set
 * of answers. A templated target that matched nothing is reported, because no
 * possible fact value would make it resolve.
 */
class UnresolvedAnsibleReferenceInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : YamlPsiElementVisitor() {
            override fun visitScalar(scalar: YAMLScalar) {
                for (reference in scalar.references) {
                    if (reference !is AnsibleReferenceBase) continue
                    if (!reference.reportWhenUnresolved) continue
                    if (reference.targets().isNotEmpty()) continue
                    val text = reference.rangeInElement.substring(scalar.text)
                    val message = if (reference.isFactDependent) {
                        "No file matches the templated Ansible reference '$text'"
                    } else {
                        "Cannot resolve Ansible reference '$text'"
                    }
                    holder.registerProblem(
                        scalar,
                        message,
                        ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
                        reference.rangeInElement,
                    )
                }
            }
        }
}
