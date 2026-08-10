package dev.yamlix.ansible.refs

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.util.indexing.FileBasedIndex
import dev.yamlix.ansible.vars.AnsibleVarIndex
import dev.yamlix.ansible.vars.VarScope
import org.jetbrains.yaml.psi.YAMLScalar
import java.util.concurrent.ConcurrentHashMap

/**
 * Case N13 — a `{{ variable }}` reference inside a YAML scalar.
 *
 * The row itself says this is "not a single-target jump": a variable has as many
 * definitions as the precedence table allows, so the reference is poly-variant
 * and the candidates are ordered **winner first** (descending precedence).
 *
 * Candidates are ordered by what applies **at the caret**: sites that win here
 * first, then tied candidates, then sites that are in scope but lose, then sites
 * that exist elsewhere in the project but do not apply at this position at all.
 * Nothing is hidden — an out-of-scope definition is still reachable, just last.
 *
 * Every inventory contributes, because the IDE has no "current host". Which
 * inventory a row wins on is stated on the row itself.
 */
class AnsibleVariableReference(
    element: YAMLScalar,
    range: TextRange,
) : AnsibleReferenceBase(element, range, KEY) {

    /** Facts and magic variables have no definition site; that is not an error. */
    override val reportWhenUnresolved: Boolean get() = false

    override fun computeTargets(): List<PsiElement> {
        val project = element.project
        // Every candidate comes from a file index, which answers nothing while
        // the IDE is indexing. Returning early keeps us from reporting "no
        // declaration" as though it were a fact; the cache depends on
        // DumbService, so this recomputes as soon as indexing finishes.
        if (DumbService.isDumb(project)) return emptyList()

        val manager = PsiManager.getInstance(project)
        val name = refText

        // Which sites actually apply *here*. Without this the list is identical
        // at every use site, and offers `set_fact` first even in `pre_tasks`
        // where it has not run yet.
        val scopes = dev.yamlix.ansible.vars.VariableReportBuilder.getInstance(project)
            .siteScopes(name, element)

        data class Candidate(
            val priority: Int,
            val rank: Int,
            val path: String,
            val offset: Int,
            val target: PsiElement,
        )

        val candidates = ArrayList<Candidate>()
        FileBasedIndex.getInstance().processValues(
            AnsibleVarIndex.NAME,
            name,
            null,
            { file, definitions ->
                val psiFile = manager.findFile(file)
                if (psiFile != null) {
                    for (definition in definitions) {
                        val leaf = psiFile.findElementAt(definition.offset) ?: psiFile
                        val scope = scopes["${file.path}#${definition.offset}"]
                        val winsOn = scope?.winsOn.orEmpty()
                        val mayWinOn = scope?.mayWinOn.orEmpty()
                        val inScope = scope?.inScope ?: false
                        // Wrapped so the "Choose Declaration" popup can show the
                        // scope, value and path instead of identical rows.
                        val target = VarDefinitionTarget(
                            leaf, definition.scope, definition.valueText, definition.qualifier,
                            winsOn, mayWinOn, inScope,
                        )
                        // Winners first, then other applicable sites, then sites
                        // that exist but do not apply at this position.
                        val priority = when {
                            winsOn.isNotEmpty() -> 0
                            mayWinOn.isNotEmpty() -> 1
                            inScope -> 2
                            else -> 3
                        }
                        candidates += Candidate(
                            priority, definition.scope.rank, file.path, definition.offset, target,
                        )
                    }
                }
                true
            },
            GlobalSearchScope.allScope(project),
        )

        if (candidates.isEmpty()) {
            // Nothing in the repo declares it. For a variable Ansible supplies
            // itself, offer what produces the value instead of a dead end.
            val file = dev.yamlix.ansible.psi.PlayStructure.sourceFile(element)
            if (file != null) return MagicVariableOrigins.targets(name, file, project)
        }

        return candidates
            .distinctBy { Triple(it.path, it.offset, it.rank) }
            .sortedWith(
                compareBy<Candidate> { it.priority }
                    .thenByDescending { it.rank }
                    .thenBy { it.path },
            )
            .map { it.target }
    }

    override fun computeVariants(): List<LookupElement> {
        val project = element.project
        return FileBasedIndex.getInstance()
            .getAllKeys(AnsibleVarIndex.NAME, project)
            .sorted()
            .map { LookupElementBuilder.create(it).withIcon(com.intellij.icons.AllIcons.Nodes.Variable) }
    }

    companion object {
        private val KEY = Key.create<CachedValue<ConcurrentHashMap<TextRange, List<PsiElement>>>>("yamlix.ref.variable")

        /** Identifiers that are Jinja syntax, not Ansible variables. */
        private val KEYWORDS = setOf(
            "true", "false", "none", "True", "False", "None",
            "and", "or", "not", "in", "is", "if", "else", "elif", "for", "endfor",
        )

        private val JINJA_BLOCK = Regex("""\{\{(.*?)}}""", RegexOption.DOT_MATCHES_ALL)
        private val IDENTIFIER = Regex("""[A-Za-z_]\w*""")

        /**
         * Ranges of every variable identifier inside `{{ … }}` in [scalar]'s text.
         *
         * Offsets are relative to the element's own text, which is exactly what
         * `rangeInElement` wants — so this works unchanged for plain, quoted and
         * folded (`>-`) scalars without any separate offset arithmetic.
         */
        fun identifierRanges(scalar: YAMLScalar): List<TextRange> {
            val text = scalar.text
            val ranges = ArrayList<TextRange>()
            for (block in JINJA_BLOCK.findAll(text)) {
                val expression = block.groupValues[1]
                val blockStart = block.range.first + 2
                for (identifier in IDENTIFIER.findAll(expression)) {
                    val value = identifier.value
                    if (value in KEYWORDS) continue
                    val before = expression.take(identifier.range.first).trimEnd()
                    // Skip attribute access (`foo.bar`) and filter names (`| int`).
                    if (before.endsWith('.') || before.endsWith('|')) continue
                    // Skip anything inside a string literal.
                    if (before.count { it == '\'' } % 2 == 1) continue
                    if (before.count { it == '"' } % 2 == 1) continue
                    ranges += TextRange(
                        blockStart + identifier.range.first,
                        blockStart + identifier.range.last + 1,
                    )
                }
            }
            return ranges
        }

        /** Human-readable precedence label, for the picker and for M3. */
        fun scopeLabel(scope: VarScope): String = "${scope.display} (rank ${scope.rank})"
    }
}
