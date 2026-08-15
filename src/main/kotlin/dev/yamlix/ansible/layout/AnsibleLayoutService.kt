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
import dev.yamlix.ansible.vars.VarFileRole
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

        private val PLAYBOOK_CACHE_KEY =
            Key.create<CachedValue<ConcurrentHashMap<String, List<VirtualFile>>>>(
                "yamlix.ansible.playbooks",
            )

        private val NONE = Any()

        /** Depth below `ansible.cfg` worth walking for playbooks. */
        private const val MAX_PLAYBOOK_DEPTH = 5

        /**
         * Directories that cannot contain a playbook, skipped without opening
         * anything inside them.
         *
         * The role subdirectories matter most: `tasks/main.yml` is a task list,
         * not a playbook, and there is one per role. Everything else here is a
         * data or plugin tree whose YAML is never a play.
         */
        private val NON_PLAYBOOK_DIRS = setOf(
            // role trees, and role internals wherever else they appear
            "roles", "tasks", "handlers", "defaults", "vars", "meta", "files", "templates",
            // inventory and variable data
            "inventories", "inventory", "group_vars", "host_vars",
            // plugin and dependency trees
            "library", "module_utils", "collections", "ansible_collections",
            "filter_plugins", "lookup_plugins", "action_plugins", "callback_plugins",
            // build and tooling output
            "build", "target", "node_modules", "venv", "molecule",
        )

        /**
         * Directories that, in combination, mark an Ansible project root.
         *
         * Two are required: `roles/` alone appears inside collections and test
         * trees, and `group_vars/` alone sits beside an inventory.
         */
        private val PROJECT_ROOT_MARKERS = listOf(
            "roles", "playbooks", "inventories", "inventory", "group_vars", "host_vars",
        )

        /** How far above a file to look for a project root. */
        private const val MAX_BASE_SEARCH_DEPTH = 8

        /** Directory names that hold inventories, by convention. */
        private val INVENTORY_DIR_NAMES = listOf("inventories", "inventory")

        /** What an inventory file is called, with or without an extension. */
        private val INVENTORY_FILE_NAMES = setOf("hosts", "inventory")

        /** Directories that hold variables *for* an inventory, not an inventory. */
        private val VARS_DIR_NAMES = setOf("group_vars", "host_vars")
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

    /**
     * The project root [file] belongs to, with or without an `ansible.cfg`.
     *
     * Ansible does not require an `ansible.cfg`, and most real projects do not
     * have one where this code was looking. Measured against eight public
     * repositories, five had no reachable config: debops keeps its roles under
     * `ansible/roles`, openstack-ansible ships none at all, and ansible-examples
     * and ansible-for-devops keep one per sub-project rather than at the top.
     * Every one of them resolved nothing, because with no base directory there
     * were no playbooks and no inventories to resolve against.
     *
     * The config still wins where there is one — it is what Ansible itself
     * uses. Failing that, the root is inferred from the layout: the nearest
     * ancestor that holds the things only an Ansible project root holds.
     *
     * This deliberately does not widen [isAnsibleContext]. A file is still only
     * Ansible's because a config is above it or it sits inside a role; this
     * only decides *where the project starts* once that is settled.
     */
    fun baseDirFor(file: VirtualFile): VirtualFile? =
        cfgFor(file)?.baseDir ?: inferredBase(file)

    /**
     * The nearest ancestor that looks like a project root.
     *
     * A role's own directory is skipped: `roles/nginx` contains `vars` and
     * `defaults`, which would otherwise make every role look like a project of
     * its own and cut it off from the playbooks that use it.
     */
    private fun inferredBase(file: VirtualFile): VirtualFile? {
        val roleDir = PlayStructure.enclosingRoleDir(file)
        var dir: VirtualFile? = when {
            // Start above `roles/`, so `roles/nginx/tasks/main.yml` considers
            // the directory that owns the whole role tree.
            roleDir != null -> roleDir.parent?.parent
            file.isDirectory -> file
            else -> file.parent
        }
        var fallback: VirtualFile? = null
        var depth = 0
        while (dir != null && depth++ < MAX_BASE_SEARCH_DEPTH) {
            if (looksLikeProjectRoot(dir)) return dir
            if (fallback == null && dir.findChild("roles")?.isDirectory == true) fallback = dir
            dir = dir.parent
        }
        return fallback
    }

    /** Holds at least two of the things that together only a project root has. */
    private fun looksLikeProjectRoot(dir: VirtualFile): Boolean {
        val markers = PROJECT_ROOT_MARKERS.count { dir.findChild(it)?.isDirectory == true }
        return markers >= 2
    }

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
     * Every YAML file under the `ansible.cfg` directory whose shape says
     * playbook.
     *
     * This used to scan only the base directory and `playbooks/`, both
     * non-recursively, to avoid "parsing every role's `tasks/main.yml` on each
     * call". That reasoning conflates two separable costs. Walking directories
     * is cheap; *parsing* is what hurts — so the walk skips the directories
     * that structurally cannot hold a playbook ([NON_PLAYBOOK_DIRS]: role
     * subdirectories, inventories, vars trees, plugin trees) and never opens a
     * file inside them. And "on each call" is now false: the result is cached
     * until the PSI or the layout changes.
     *
     * The miss it was accepting is not as rare as the comment assumed — a
     * `playbooks/<area>/site-*.yml` layout is ordinary, and the plugin silently
     * treated those playbooks as though they did not exist.
     */
    fun playbooks(from: VirtualFile): List<VirtualFile> {
        val base = baseDirFor(from) ?: return emptyList()
        return playbookCache().getOrPut(base.path) { scanPlaybooks(base) }
    }

    private fun scanPlaybooks(base: VirtualFile): List<VirtualFile> {
        val manager = com.intellij.psi.PsiManager.getInstance(project)
        val found = ArrayList<VirtualFile>()
        // Canonical paths, so a symlinked directory — `playbooks/x/roles ->
        // ../../roles` is the common one — cannot be walked twice or forever.
        val visited = HashSet<String>()

        fun walk(dir: VirtualFile, depth: Int) {
            if (depth > MAX_PLAYBOOK_DEPTH) return
            if (!visited.add(dir.canonicalPath ?: dir.path)) return
            for (child in dir.children) {
                if (child.isDirectory) {
                    if (child.name.startsWith(".")) continue
                    if (child.name.lowercase() in NON_PLAYBOOK_DIRS) continue
                    walk(child, depth + 1)
                    continue
                }
                if (child.extension != "yml" && child.extension != "yaml") continue
                val psi = manager.findFile(child) as? org.jetbrains.yaml.psi.YAMLFile ?: continue
                if (PlayStructure.isPlaybook(psi)) found += child
            }
        }

        walk(base, 0)
        return found.sortedBy { it.path }
    }

    private fun playbookCache(): ConcurrentHashMap<String, List<VirtualFile>> =
        CachedValuesManager.getManager(project).getCachedValue(
            project,
            PLAYBOOK_CACHE_KEY,
            {
                CachedValueProvider.Result.create(
                    ConcurrentHashMap<String, List<VirtualFile>>(),
                    PsiModificationTracker.MODIFICATION_COUNT,
                    AnsibleLayoutTracker,
                )
            },
            false,
        )

    /** [playbooks], or null when there are none, so callers can fall back. */
    fun playbooksOrNull(from: VirtualFile): List<VirtualFile>? =
        playbooks(from).ifEmpty { null }

    /**
     * Inventory roots: the `inventory` entry from `ansible.cfg`, plus every
     * subdirectory of a top-level `inventories` directory.
     */
    fun inventoryRoots(from: VirtualFile): List<VirtualFile> {
        val base = baseDirFor(from) ?: return emptyList()
        val roots = LinkedHashSet<VirtualFile>()

        // What the config points at, whatever shape it is. `inventory = ./hosts`
        // naming a single file is the commonest form of all — algo uses it —
        // and it used to be added as if it were a directory, whose (absent)
        // children then produced an inventory with no hosts in it.
        cfgFor(from)?.inventory?.forEach { entry -> resolvePath(entry, base)?.let(roots::add) }

        // The classic layout: a `hosts` file beside site.yml, no directory and
        // no config entry. ansible-examples is written this way throughout.
        for (name in INVENTORY_FILE_NAMES) {
            base.findChild(name)
                ?.takeIf { !it.isDirectory && VarFileRole.isIniInventory(it) }
                ?.let(roots::add)
        }

        for (name in INVENTORY_DIR_NAMES) {
            val dir = base.findChild(name)?.takeIf { it.isDirectory } ?: continue
            // The directory itself, when it holds the inventory directly.
            if (holdsInventoryFiles(dir)) roots += dir
            // And one level down: `inventory/sample`, `inventory/production`.
            // kubespray keeps every inventory there, and taking only
            // `inventory/` found an empty one.
            dir.children.orEmpty()
                .filter { it.isDirectory && holdsInventoryFiles(it) }
                .forEach(roots::add)
        }
        return roots.toList()
    }

    /**
     * Whether [dir] directly contains something an inventory is made of.
     *
     * Deliberately narrow. "Any directory holding YAML" swept up
     * `inventory/group_vars` and openstack-ansible's `inventory/env.d`, whose
     * mappings then parsed as though they were host groups — phantom groups
     * invented out of unrelated configuration.
     */
    private fun holdsInventoryFiles(dir: VirtualFile): Boolean {
        if (dir.name in VARS_DIR_NAMES) return false
        return dir.children.orEmpty().any { child ->
            when {
                // An inventory keeps its variables beside it.
                child.isDirectory -> child.name in VARS_DIR_NAMES
                // Named like an inventory, or unmistakably one by content.
                else -> child.nameWithoutExtension in INVENTORY_FILE_NAMES ||
                    child.extension == "ini" ||
                    VarFileRole.isIniInventory(child)
            }
        }
    }
}
