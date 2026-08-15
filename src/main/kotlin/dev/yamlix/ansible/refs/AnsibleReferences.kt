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
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.yamlix.ansible.inventory.InventoryGraphService
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

    /**
     * A use seen through a symlink is the same use, and is claimed only once.
     *
     * A `playbooks/<area>/roles -> ../../roles` symlink — a common way to give
     * a subtree its own role path — makes every role file reachable at two VFS
     * paths. They are one file on disk, so Find Usages listed the same line
     * twice, and a rename would have rewritten the same bytes twice.
     *
     * Only [isReferenceTo] is refused, never [resolve]: someone who opens the
     * file through the symlinked path still gets working navigation from it.
     * The claim being declined is "this is a distinct place where the variable
     * is used", which is false, not "this text points at that declaration",
     * which is true.
     */
    override fun isReferenceTo(element: PsiElement): Boolean =
        !isSecondaryPath() && super.isReferenceTo(element)

    /** True when this element's file is reached through a symlinked ancestor. */
    private fun isSecondaryPath(): Boolean {
        val file = element.containingFile?.virtualFile ?: return false
        val root = ProjectFileIndex.getInstance(element.project).getContentRootForFile(file)
        var current: VirtualFile? = file
        // Stop at the content root. Anything above it is the project's own
        // location — a project living under a symlinked path (/tmp on macOS)
        // is not a duplicate of anything, and must not be silenced.
        while (current != null && current != root) {
            if (current.`is`(VFileProperty.SYMLINK)) return true
            current = current.parent
        }
        return false
    }

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
class AnsibleGroupReference(
    element: YAMLScalar,
    range: TextRange,
    /**
     * False for a reference inferred from a project convention rather than
     * from Ansible syntax — see [GroupKeyConvention]. Such a key is an
     * ordinary variable that this project *happens* to use for group names, so
     * a value that names no group is not a defect worth flagging.
     */
    override val reportWhenUnresolved: Boolean = true,
) : AnsibleReferenceBase(element, range, KEY) {

    override fun computeTargets(): List<PsiElement> {
        val file = PlayStructure.sourceFile(element) ?: return emptyList()
        return AnsibleTargets.groupDefinitions(refText.trim(), file, element.project)
    }

    /**
     * Every group and host of every inventory.
     *
     * Taken from the parsed [InventoryGraph] rather than by listing
     * `group_vars/` filenames: the graph already holds every group from every
     * inventory source — INI and YAML alike, including groups that only ever
     * appear as a child under `[web:children]` — whereas a `group_vars` listing
     * misses every group that has no vars file of its own, which on an
     * INI-inventory project can be all of them.
     */
    override fun computeVariants(): List<LookupElement> {
        val file = PlayStructure.sourceFile(element) ?: return emptyList()
        val layout = AnsibleLayoutService.getInstance(element.project)
        val graphs = InventoryGraphService.getInstance(element.project)

        val groups = LinkedHashSet<String>()
        val hosts = LinkedHashSet<String>()
        for (root in layout.inventoryRoots(file)) {
            val graph = graphs.graphFor(root)
            groups += graph.groups.keys
            hosts += graph.hosts
        }
        // `hosts:` takes either; the icon is what tells them apart.
        return groups.map {
            LookupElementBuilder.create(it).withIcon(com.intellij.icons.AllIcons.Nodes.Folder)
        } + (hosts - groups).map {
            LookupElementBuilder.create(it).withIcon(com.intellij.icons.AllIcons.Nodes.Servlet)
        }
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
