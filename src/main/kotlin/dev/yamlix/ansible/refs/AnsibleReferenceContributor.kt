package dev.yamlix.ansible.refs

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext
import dev.yamlix.ansible.layout.AnsibleLayoutService
import dev.yamlix.ansible.psi.AnsiblePatterns
import dev.yamlix.ansible.psi.AnsibleRefKind
import dev.yamlix.ansible.psi.PlayStructure
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Registers Ansible references on YAML scalars.
 *
 * Registration is keyed on *position* — see [AnsiblePatterns.classify] — and
 * gated on the file actually living in an Ansible project. No file type is
 * registered and `*.yml` is not claimed; a YAML file outside an Ansible layout
 * gets no references at all.
 */
class AnsibleReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            AnsiblePatterns.anyAnsibleReference(),
            AnsibleReferenceProvider(),
            PsiReferenceRegistrar.DEFAULT_PRIORITY,
        )
    }
}

private class AnsibleReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val scalar = element as? YAMLScalar ?: return PsiReference.EMPTY_ARRAY
        val virtualFile = PlayStructure.sourceFile(scalar) ?: return PsiReference.EMPTY_ARRAY
        if (!AnsibleLayoutService.getInstance(scalar.project).isAnsibleContext(virtualFile)) {
            return PsiReference.EMPTY_ARRAY
        }
        val kind = AnsiblePatterns.classify(scalar)
            // Ansible syntax does not mark this scalar, but the project's own
            // playbooks may — `hostgroup: containers` under an
            // `import_playbook`'s `vars:`, consumed by `hosts: "{{ hostgroup }}"`
            // elsewhere. See [GroupKeyConvention].
            ?: conventionGroupReference(scalar, virtualFile)?.let { return arrayOf(it) }
            // N13: a scalar in no special position may still contain
            // `{{ variable }}` references. Only offered when the scalar is not
            // already a role/file reference, so reference ranges never overlap.
            ?: return AnsibleVariableReference.identifierRanges(scalar)
                .map { AnsibleVariableReference(scalar, it) }
                .toTypedArray()

        val range: TextRange = AnsibleReferenceBase.valueRange(scalar)
        if (range.isEmpty) return PsiReference.EMPTY_ARRAY

        val reference: PsiReference = when (kind) {
            AnsibleRefKind.ROLE -> AnsibleRoleReference(scalar, range)
            AnsibleRefKind.TASKS_FILE ->
                AnsibleFileReference(scalar, range, AnsibleTargets.FileKind.TASKS)
            AnsibleRefKind.PLAY_VARS_FILE ->
                AnsibleFileReference(scalar, range, AnsibleTargets.FileKind.PLAY_VARS)
            AnsibleRefKind.ROLE_VARS_FILE ->
                AnsibleFileReference(scalar, range, varsFileKind(scalar))
            AnsibleRefKind.TEMPLATE ->
                AnsibleFileReference(scalar, range, AnsibleTargets.FileKind.TEMPLATE)
            AnsibleRefKind.HANDLER -> AnsibleHandlerReference(scalar, range)
            AnsibleRefKind.GROUP -> AnsibleGroupReference(scalar, range)
        }
        return arrayOf(reference)
    }

    /**
     * A group reference for a key this project uses to carry a group name.
     *
     * Returns null — not an empty array — when the convention does not apply,
     * so the caller falls through to the `{{ variable }}` handling. A scalar
     * like `hostgroup: containers` holds no Jinja, so nothing is lost either
     * way; a project not using the convention behaves exactly as before.
     */
    private fun conventionGroupReference(
        scalar: YAMLScalar,
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
    ): PsiReference? {
        val key = PlayStructure.owningKeyValue(scalar)?.keyText?.trim()
            ?: PlayStructure.owningSequenceKey(scalar)
            ?: return null
        if (!GroupKeyConvention.getInstance(scalar.project).isGroupValued(key, virtualFile)) {
            return null
        }
        val range = AnsibleReferenceBase.valueRange(scalar)
        if (range.isEmpty || scalar.text.contains("{{")) return null
        // Inferred, not syntactic: never report it as an unresolved reference.
        return AnsibleGroupReference(scalar, range, reportWhenUnresolved = false)
    }
}
