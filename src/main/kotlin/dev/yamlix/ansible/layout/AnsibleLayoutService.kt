package dev.yamlix.ansible.layout

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.yamlix.ansible.psi.PlayStructure
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovers and caches the Ansible layout of a project: where `ansible.cfg`
 * lives, what the effective `roles_path` and `collections_path` are, and which
 * directories are inventories.
 *
 * ### The CWD caveat
 *
 * NAVIGATION-CASES.md §3 establishes that real Ansible discovers `ansible.cfg`
 * from the *process working directory*, and that relative `roles_path` entries
 * resolve against that same CWD — running the fixture from its parent directory
 * drops `./external-roles` and breaks case N3.
 *
 * An IDE has no working directory. This service therefore resolves relative
 * entries against the directory containing the `ansible.cfg` found by walking up
 * from the referencing file. That is what a user running `ansible-playbook` from
 * their project root gets, but it is not literally Ansible's rule, and a project
 * whose cfg is meant to be used from elsewhere will resolve differently here.
 */
@Service(Service.Level.PROJECT)
class AnsibleLayoutService(private val project: Project) {

    companion object {
        fun getInstance(project: Project): AnsibleLayoutService = project.service()

        private val CFG_CACHE_KEY =
            Key.create<CachedValue<ConcurrentHashMap<String, Any>>>("yamlix.ansible.cfgCache")

        private val NONE = Any()
    }

    // ---- ansible.cfg -------------------------------------------------------

    /** The nearest `ansible.cfg` above [file], parsed. */
    fun cfgFor(file: VirtualFile): AnsibleCfg? {
        val cache = cfgCache()
        var dir: VirtualFile? = if (file.isDirectory) file else file.parent
        val visited = ArrayList<String>()
        while (dir != null) {
            val key = dir.path
            when (val cached = cache[key]) {
                null -> {
                    val cfgFile = dir.findChild(AnsibleVfsListener.ANSIBLE_CFG)
                    if (cfgFile != null && !cfgFile.isDirectory) {
                        val parsed = AnsibleCfg.parse(cfgFile, LoadTextUtil.loadText(cfgFile))
                        cache[key] = parsed
                        visited.forEach { cache[it] = parsed }
                        return parsed
                    }
                    visited += key
                }
                NONE -> Unit
                else -> {
                    val parsed = cached as AnsibleCfg
                    visited.forEach { cache[it] = parsed }
                    return parsed
                }
            }
            dir = dir.parent
        }
        visited.forEach { cache[it] = NONE }
        return null
    }

    private fun cfgCache(): ConcurrentHashMap<String, Any> =
        CachedValuesManager.getManager(project).getCachedValue(
            project,
            CFG_CACHE_KEY,
            {
                CachedValueProvider.Result.create(
                    ConcurrentHashMap<String, Any>(),
                    PsiModificationTracker.MODIFICATION_COUNT,
                    AnsibleLayoutTracker,
                )
            },
            false,
        )

    /** True when [file] sits anywhere inside a directory tree carrying an `ansible.cfg`. */
    fun isAnsibleContext(file: VirtualFile): Boolean =
        cfgFor(file) != null || PlayStructure.enclosingRoleDir(file) != null

    // ---- role search path (rule R1) ---------------------------------------

    /**
     * The ordered role search path for a reference appearing in [from].
     *
     * Mirrors Ansible's `RoleDefinition._load_role_path`:
     * `[basedir/roles] + roles_path + [role_basedir] + [basedir]`.
     */
    fun roleSearchPath(from: VirtualFile): List<VirtualFile> {
        val cfg = cfgFor(from)
        val basedir = basedirFor(from, cfg)
        val roleBasedir = PlayStructure.enclosingRoleDir(from)?.parent

        val dirs = ArrayList<VirtualFile>()
        basedir?.findChild("roles")?.let(dirs::add)
        for (entry in cfg?.rolesPath ?: AnsibleCfg.DEFAULT_ROLES_PATH) {
            resolvePath(entry, cfg?.baseDir ?: basedir)?.let(dirs::add)
        }
        roleBasedir?.let(dirs::add)
        basedir?.let(dirs::add)

        // Deduplicated by canonical (symlink-resolved) path, not the logical
        // one: a `playbooks/<x>/roles -> ../../roles` symlink — a common way
        // to make a sub-playbook's relative role references work — is a
        // second logical path to the exact same directory. Deduping on
        // `.path` alone leaves both in the list, so every role, every
        // definition inside it, and every "Choose Declaration" candidate
        // reachable through `roleSearchPath` shows up twice.
        return dirs.filter { it.isDirectory }.distinctBy { it.canonicalPath ?: it.path }
    }

    /** Roots under which `ansible_collections/<ns>/<name>` may be found. */
    fun collectionsRoots(from: VirtualFile): List<VirtualFile> {
        val cfg = cfgFor(from)
        val base = cfg?.baseDir ?: basedirFor(from, null)
        return (cfg?.collectionsPath ?: AnsibleCfg.DEFAULT_COLLECTIONS_PATH)
            .mapNotNull { resolvePath(it, base) }
            .filter { it.isDirectory }
            .distinctBy { it.canonicalPath ?: it.path }
    }

    /**
     * The loader basedir: the playbook's own directory when the reference is in a
     * playbook, otherwise the directory holding `ansible.cfg`.
     */
    private fun basedirFor(from: VirtualFile, cfg: AnsibleCfg?): VirtualFile? {
        val parent = from.parent
        if (parent != null && cfg == null) return parent
        val inRole = PlayStructure.enclosingRoleDir(from) != null
        return if (!inRole && parent != null) parent else cfg?.baseDir ?: parent
    }

    private fun resolvePath(entry: String, base: VirtualFile?): VirtualFile? {
        val expanded = when {
            entry.startsWith("~/") -> System.getProperty("user.home") + entry.substring(1)
            else -> entry
        }
        if (expanded.startsWith("/")) {
            return LocalFileSystem.getInstance().findFileByPath(expanded)
        }
        val relative = expanded.removePrefix("./")
        return base?.findFileByRelativePath(relative)
    }

    // ---- inventories -------------------------------------------------------

    /**
     * Playbooks in the project: YAML files directly under the `ansible.cfg`
     * directory or under a `playbooks/` subdirectory, whose shape says playbook.
     *
     * Scanning is limited to those two locations on purpose — a recursive walk
     * would parse every role's `tasks/main.yml` on each call, and playbooks
     * living somewhere else are rare enough to be worth the miss.
     */
    fun playbooks(from: VirtualFile): List<VirtualFile> {
        val base = cfgFor(from)?.baseDir ?: return emptyList()
        val manager = com.intellij.psi.PsiManager.getInstance(project)
        val candidates = base.children.filter { !it.isDirectory } +
            (base.findChild("playbooks")?.children?.filter { !it.isDirectory } ?: emptyList())
        return candidates
            .filter { it.extension == "yml" || it.extension == "yaml" }
            .filter { file ->
                (manager.findFile(file) as? org.jetbrains.yaml.psi.YAMLFile)
                    ?.let(PlayStructure::isPlaybook) == true
            }
            .sortedBy { it.name }
    }

    /** [playbooks], or null when there are none, so callers can fall back. */
    fun playbooksOrNull(from: VirtualFile): List<VirtualFile>? =
        playbooks(from).ifEmpty { null }

    /**
     * Inventory roots: the `inventory` entry from `ansible.cfg`, plus every
     * subdirectory of a top-level `inventories` directory.
     */
    fun inventoryRoots(from: VirtualFile): List<VirtualFile> {
        val cfg = cfgFor(from)
        val base = cfg?.baseDir ?: return emptyList()
        val roots = LinkedHashSet<VirtualFile>()
        cfg.inventory.forEach { entry -> resolvePath(entry, base)?.let(roots::add) }
        base.findChild("inventories")?.children
            ?.filter { it.isDirectory }
            ?.forEach(roots::add)
        base.findChild("inventory")?.takeIf { it.isDirectory }?.let(roots::add)
        return roots.toList()
    }
}
