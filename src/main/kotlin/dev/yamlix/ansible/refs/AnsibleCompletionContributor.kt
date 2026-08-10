package dev.yamlix.ansible.refs

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Surfaces the references' own `getVariants()` as completion.
 *
 * YAML does not run the platform's legacy reference-completion fallback, so
 * without this the variants would exist but never reach the user. Deliberately
 * a thin delegate: the candidate list still comes from
 * [AnsibleReferenceBase.getVariants], which is the same model resolution uses.
 */
class AnsibleCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val scalar = PsiTreeUtil.getParentOfType(
                        parameters.position,
                        YAMLScalar::class.java,
                        false,
                    ) ?: return
                    for (reference in scalar.references) {
                        if (reference !is AnsibleReferenceBase) continue
                        reference.variants
                            .filterIsInstance<LookupElement>()
                            .forEach(result::addElement)
                    }
                }
            },
        )
    }
}
