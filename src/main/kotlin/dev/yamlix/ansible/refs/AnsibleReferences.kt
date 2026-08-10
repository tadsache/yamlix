package dev.yamlix.ansible.refs

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.yamlix.ansible.layout.AnsibleLayoutService
import dev.yamlix.ansible.layout.AnsibleLayoutTracker
import dev.yamlix.ansible.psi.PlayStructure
import org.jetbrains.yaml.psi.YAMLScalar
import java.util.concurrent.ConcurrentHashMap

/**
 * Base for every Ansible reference.
 *
 * All are poly-variant: where the documented lookup rule admits several
 * candidates, all are returned in the documented fallback order and the IDE
 * offers a picker. Resolution is pure PSI/VFS work inside the caller's read
 * action — nothing here spawns a process or performs I/O beyond the VFS.
 */
abstract class AnsibleReferenceBase(
    element: YAMLScalar,
    range: TextRange,
    private val cacheKey: Key<CachedValue<ConcurrentHashMap<TextRange, List<PsiElement>>>>,
) : PsiPolyVariantReferenceBase<YAMLScalar>(element, range) {

    /** The referenced text, with quotes stripped and Jinja left intact. */
    protected val refText: String get() = rangeInElement.substring(element.text)

    protected abstract fun computeTargets(): List<PsiElement>

    /** Candidate names for completion. */
    protected open fun computeVariants(): List<LookupElement> = emptyList()

    /** True when the reference legitimately has no single answer (fact-dependent). */
    open val isFactDependent: Boolean get() = false

    /**
     * Whether the unresolved-reference inspection should report this.
     *
     * False for variable references: magic variables (`inventory_hostname`),
     * gathered facts and anything supplied by `-e` have no definition site in
     * the repo, so "unresolved" is the normal case, not a defect.
     */
    open val reportWhenUnresolved: Boolean get() = true

    /**
     * Resolved targets, cached per element **and per range**.
     *
     * The range matters: one scalar can carry many references — a `msg:` with
     * five `{{ … }}` expressions carries five. Caching under the element alone
     * gives them one shared slot, so whichever resolves first answers for all of
     * them and `inventory_hostname` starts reporting `app_port`'s definitions.
     */
    fun targets(): List<PsiElement> {
        val perRange = CachedValuesManager.getCachedValue(element, cacheKey) {
            CachedValueProvider.Result.create(
                ConcurrentHashMap<TextRange, List<PsiElement>>(),
                PsiModificationTracker.MODIFICATION_COUNT,
                AnsibleLayoutTracker,
                // Indexing finishing changes the answer for anything that reads
                // a file index, and bumps neither of the trackers above. Without
                // this dependency an empty result computed during indexing is
                // cached for the rest of the session.
                DumbService.getInstance(element.project).modificationTracker,
            )
        }
        return perRange.getOrPut(rangeInElement) { computeTargets() }
    }

    final override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        targets().map { PsiElementResolveResult(it) }.toTypedArray()

    final override fun getVariants(): Array<Any> = computeVariants().toTypedArray()

    /** Our own inspection reports unresolved references, so nothing here is a hard error. */
    override fun isSoft(): Boolean = true

    companion object {
        fun valueRange(scalar: YAMLScalar): TextRange =
            ElementManipulators.getValueTextRange(scalar)
    }
}

// ---------------------------------------------------------------------------

/** N1, N2, N3, N5, N7, N8 — a role name in any of the six positions. */
class AnsibleRoleReference(element: YAMLScalar, range: TextRange) :
    AnsibleReferenceBase(element, range, KEY) {

    override fun computeTargets(): List<PsiElement> {
        val file = PlayStructure.sourceFile(element) ?: return emptyList()
        val project = element.project
        return AnsibleTargets.resolveRoleDirs(refText, file, project)
            .mapNotNull { AnsibleTargets.roleNavigationTarget(it, project) }
    }

    override fun computeVariants(): List<LookupElement> {
        val file = PlayStructure.sourceFile(element) ?: return emptyList()
        return AnsibleTargets.roleVariants(file, element.project).map { (name, dir) ->
            LookupElementBuilder.create(name)
                .withIcon(com.intellij.icons.AllIcons.Nodes.Module)
                .withTypeText(dir.parent?.name ?: "", true)
        }
    }

    companion object {
        private val KEY = Key.create<CachedValue<ConcurrentHashMap<TextRange, List<PsiElement>>>>("yamlix.ref.role")
    }
}

/** N4, N6, N9, N11 — a relative file path. */
class AnsibleFileReference(
    element: YAMLScalar,
    range: TextRange,
    private val kind: AnsibleTargets.FileKind,
) : AnsibleReferenceBase(element, range, keyFor(kind)) {

    override val isFactDependent: Boolean get() = AnsibleTargets.isTemplated(refText)

    override fun computeTargets(): List<PsiElement> {
        val file = PlayStructure.sourceFile(element) ?: return emptyList()
        val project = element.project
        val manager = PsiManager.getInstance(project)
        return AnsibleTargets.resolveFile(refText, kind, file, project)
            .mapNotNull { manager.findFile(it) }
    }

    override fun computeVariants(): List<LookupElement> {
        val file = PlayStructure.sourceFile(element) ?: return emptyList()
        return AnsibleTargets.searchDirs(kind, file, element.project)
            .flatMap { dir -> dir.children.filter { !it.isDirectory } }
            .distinctBy { it.name }
            .map { LookupElementBuilder.create(it.name).withIcon(it.fileType.icon) }
    }

    companion object {
        private val KEYS = AnsibleTargets.FileKind.entries.associateWith {
            Key.create<CachedValue<ConcurrentHashMap<TextRange, List<PsiElement>>>>("yamlix.ref.file.${it.name}")
        }

        private fun keyFor(kind: AnsibleTargets.FileKind) = KEYS.getValue(kind)
    }
}

/** N10 — `notify:` matched against handler `name:`/`listen:` strings. */
class AnsibleHandlerReference(element: YAMLScalar, range: TextRange) :
    AnsibleReferenceBase(element, range, KEY) {

    override fun computeTargets(): List<PsiElement> {
        val file = PlayStructure.sourceFile(element) ?: return emptyList()
        val wanted = refText.trim()
        return AnsibleTargets.handlers(file, element.project)
            .filter { it.first == wanted }
            .map { it.second }
    }

    override fun computeVariants(): List<LookupElement> {
        val file = PlayStructure.sourceFile(element) ?: return emptyList()
        return AnsibleTargets.handlers(file, element.project)
            .distinctBy { it.first }
            .map { LookupElementBuilder.create(it.first).withIcon(com.intellij.icons.AllIcons.Nodes.Method) }
    }

    companion object {
        private val KEY = Key.create<CachedValue<ConcurrentHashMap<TextRange, List<PsiElement>>>>("yamlix.ref.handler")
    }
}

/** N12 — a `hosts:` pattern naming an inventory group. */
class AnsibleGroupReference(element: YAMLScalar, range: TextRange) :
    AnsibleReferenceBase(element, range, KEY) {

    override fun computeTargets(): List<PsiElement> {
        val file = PlayStructure.sourceFile(element) ?: return emptyList()
        return AnsibleTargets.groupDefinitions(refText.trim(), file, element.project)
    }

    override fun computeVariants(): List<LookupElement> {
        val file = PlayStructure.sourceFile(element) ?: return emptyList()
        val layout = AnsibleLayoutService.getInstance(element.project)
        val names = LinkedHashSet<String>()
        for (root in layout.inventoryRoots(file)) {
            root.findChild("group_vars")?.children
                ?.filter { !it.isDirectory }
                ?.forEach { names += it.nameWithoutExtension }
        }
        return names.map { LookupElementBuilder.create(it).withIcon(com.intellij.icons.AllIcons.Nodes.Folder) }
    }

    companion object {
        private val KEY = Key.create<CachedValue<ConcurrentHashMap<TextRange, List<PsiElement>>>>("yamlix.ref.group")
    }
}

/** Chooses the file-search flavour for a `vars`/`tasks` reference by position. */
internal fun varsFileKind(scalar: YAMLScalar): AnsibleTargets.FileKind {
    val vf = PlayStructure.sourceFile(scalar)
    return if (vf != null && PlayStructure.enclosingRoleDir(vf) != null) {
        AnsibleTargets.FileKind.ROLE_VARS
    } else {
        AnsibleTargets.FileKind.PLAY_VARS
    }
}
