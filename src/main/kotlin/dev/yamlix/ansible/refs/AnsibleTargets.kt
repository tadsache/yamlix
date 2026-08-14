package dev.yamlix.ansible.refs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.yamlix.ansible.layout.AnsibleLayoutService
import dev.yamlix.ansible.psi.PlayStructure
import dev.yamlix.ansible.vars.VarFileRole
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence

/**
 * The resolution model shared by references, completion variants and the
 * unresolved-reference inspection, so the three can never disagree.
 *
 * Implements rules R1 (role name to directory) and R2 (relative file
 * references) from NAVIGATION-CASES.md §4.
 */
object AnsibleTargets {

    private const val ANSIBLE_COLLECTIONS = "ansible_collections"

    // ---- R1: role name -> role directory ----------------------------------

    /**
     * Role directories matching [name], in Ansible's documented fallback order.
     *
     * A name with two or more dots is a fully-qualified collection name and is
     * searched **only** under the collections roots — it never falls back to
     * `roles_path` (case N2).
     */
    fun resolveRoleDirs(name: String, from: VirtualFile, project: Project): List<VirtualFile> {
        val layout = AnsibleLayoutService.getInstance(project)
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.contains("{{")) return emptyList()

        if (isFqcn(trimmed)) {
            val (ns, coll, role) = trimmed.split('.', limit = 3)
            return layout.collectionsRoots(from).mapNotNull { root ->
                root.findFileByRelativePath("$ANSIBLE_COLLECTIONS/$ns/$coll/roles/$role")
                    ?.takeIf { it.isDirectory }
            }
        }

        return layout.roleSearchPath(from)
            .mapNotNull { dir -> dir.findChild(trimmed)?.takeIf { it.isDirectory && isRoleDir(it) } }
            .distinctBy { it.canonicalPath ?: it.path }
    }

    /** A directory is a role if it has at least one of the canonical subdirectories. */
    private fun isRoleDir(dir: VirtualFile): Boolean =
        PlayStructure.ROLE_SUBDIRS.any { dir.findChild(it)?.isDirectory == true }

    fun isFqcn(name: String): Boolean = name.count { it == '.' } >= 2

    /**
     * The navigable element for a role directory: its task entry point, falling
     * back to `meta/main.yml`, then the directory itself.
     */
    fun roleNavigationTarget(roleDir: VirtualFile, project: Project): PsiElement? {
        val manager = PsiManager.getInstance(project)
        roleDir.findFileByRelativePath("tasks/main.yml")
            ?.let { return manager.findFile(it) }
        roleDir.findFileByRelativePath("tasks/main.yaml")
            ?.let { return manager.findFile(it) }
        roleDir.findFileByRelativePath("meta/main.yml")
            ?.let { return manager.findFile(it) }
        return manager.findDirectory(roleDir)
    }

    /** All role names reachable from [from], for completion. */
    fun roleVariants(from: VirtualFile, project: Project): List<Pair<String, VirtualFile>> {
        val layout = AnsibleLayoutService.getInstance(project)
        val out = LinkedHashMap<String, VirtualFile>()

        for (dir in layout.roleSearchPath(from)) {
            dir.children.filter { it.isDirectory && isRoleDir(it) }
                .forEach { out.putIfAbsent(it.name, it) }
        }
        for (root in layout.collectionsRoots(from)) {
            val collections = root.findChild(ANSIBLE_COLLECTIONS) ?: continue
            for (ns in collections.children.filter { it.isDirectory }) {
                for (coll in ns.children.filter { it.isDirectory }) {
                    val roles = coll.findChild("roles") ?: continue
                    roles.children.filter { it.isDirectory && isRoleDir(it) }.forEach {
                        out.putIfAbsent("${ns.name}.${coll.name}.${it.name}", it)
                    }
                }
            }
        }
        return out.entries.map { it.key to it.value }
    }

    // ---- R2: relative file references --------------------------------------

    enum class FileKind { TASKS, TEMPLATE, ROLE_VARS, PLAY_VARS }

    /**
     * The ordered directories searched for a relative file reference of the
     * given kind, as documented in cases N4, N6, N9 and N11.
     */
    fun searchDirs(kind: FileKind, from: VirtualFile, project: Project): List<VirtualFile> {
        val layout = AnsibleLayoutService.getInstance(project)
        val roleDir = PlayStructure.enclosingRoleDir(from)
        val basedir = layout.cfgFor(from)?.baseDir ?: from.parent
        val dirs = ArrayList<VirtualFile?>()

        when (kind) {
            FileKind.TASKS -> {
                dirs += roleDir?.findChild("tasks")
                dirs += roleDir
                dirs += basedir
                if (roleDir == null) dirs += from.parent
            }
            FileKind.TEMPLATE -> {
                dirs += roleDir?.findChild("templates")
                dirs += roleDir?.findChild("files")
                dirs += basedir?.findChild("templates")
                dirs += basedir
            }
            FileKind.ROLE_VARS -> {
                dirs += roleDir?.findChild("vars")
                dirs += roleDir?.findChild("files")
                dirs += basedir?.findChild("vars")
                dirs += basedir
            }
            FileKind.PLAY_VARS -> {
                dirs += basedir
                dirs += basedir?.findChild("vars")
                dirs += from.parent
            }
        }
        return dirs.filterNotNull().filter { it.isDirectory }.distinctBy { it.canonicalPath ?: it.path }
    }

    /**
     * Resolves a possibly Jinja-templated relative path.
     *
     * A literal path yields at most one hit per search directory. A templated
     * path (case N11, `"{{ ansible_os_family }}.yml"`) is converted to a glob and
     * yields **every** candidate — the correct answer is a set, and narrowing it
     * would mean guessing a fact value.
     */
    fun resolveFile(
        path: String,
        kind: FileKind,
        from: VirtualFile,
        project: Project,
    ): List<VirtualFile> {
        val raw = path.trim()
        if (raw.isEmpty()) return emptyList()
        val dirs = searchDirs(kind, from, project)

        if (!raw.contains("{{")) {
            return dirs.mapNotNull { dir ->
                dir.findFileByRelativePath(raw)?.takeIf { !it.isDirectory }
            }.distinctBy { it.canonicalPath ?: it.path }
        }

        val dirPart = raw.substringBeforeLast('/', "")
        val namePart = raw.substringAfterLast('/')
        if (dirPart.contains("{{")) return emptyList() // templated directory: undecidable
        val matcher = jinjaGlob(namePart)

        // Ansible takes the first search directory that contains a match, so the
        // candidate set is scoped to that directory rather than unioned across
        // all of them — otherwise `{{ ansible_os_family }}.yml` inside a role
        // would also offer every `*.yml` in the playbook's own `vars/`.
        for (base in dirs) {
            val dir = if (dirPart.isEmpty()) base else base.findFileByRelativePath(dirPart)
            if (dir == null || !dir.isDirectory) continue
            val hits = dir.children
                .filter { !it.isDirectory && matcher.matches(it.name) }
                // `main.yml` is loaded automatically by the role loader and is
                // never what a templated include_vars is reaching for.
                .filter { it.nameWithoutExtension != "main" }
                .sortedBy { it.name }
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    /** True when a path contains a Jinja expression, i.e. is fact-dependent. */
    fun isTemplated(path: String): Boolean = path.contains("{{")

    /** Turns `{{ ansible_os_family }}.yml` into a regex matching `Darwin.yml`. */
    private fun jinjaGlob(name: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < name.length) {
            val open = name.indexOf("{{", i)
            if (open < 0) {
                sb.append(Regex.escape(name.substring(i)))
                break
            }
            if (open > i) sb.append(Regex.escape(name.substring(i, open)))
            val close = name.indexOf("}}", open)
            if (close < 0) {
                sb.append(".*")
                break
            }
            sb.append("[^/]+")
            i = close + 2
        }
        return Regex("^$sb$")
    }

    // ---- N10: notify -> handler --------------------------------------------

    /** Handler definitions visible from [from], as name to the defining element. */
    fun handlers(from: VirtualFile, project: Project): List<Pair<String, PsiElement>> {
        val manager = PsiManager.getInstance(project)
        val out = ArrayList<Pair<String, PsiElement>>()
        val roleDir = PlayStructure.enclosingRoleDir(from)

        val handlerFiles = ArrayList<VirtualFile>()
        roleDir?.findChild("handlers")?.children
            ?.filter { !it.isDirectory }
            ?.let(handlerFiles::addAll)
        // Handlers of every other role in the project are also notifiable.
        AnsibleTargets.roleVariants(from, project).forEach { (_, dir) ->
            if (dir != roleDir) {
                dir.findChild("handlers")?.children?.filter { !it.isDirectory }
                    ?.let(handlerFiles::addAll)
            }
        }

        for (vf in handlerFiles.distinctBy { it.canonicalPath ?: it.path }) {
            val psi = manager.findFile(vf) as? YAMLFile ?: continue
            for (task in topLevelTasks(psi)) {
                val nameKv = task.getKeyValueByKey("name")
                val name = nameKv?.valueText?.trim()
                if (!name.isNullOrEmpty()) out += name to (nameKv.value ?: nameKv)
                // `listen:` makes a handler notifiable under additional names.
                task.getKeyValueByKey("listen")?.let { listen ->
                    when (val v = listen.value) {
                        is YAMLScalar -> out += v.textValue.trim() to v
                        is YAMLSequence -> v.items.mapNotNull { it.value as? YAMLScalar }
                            .forEach { out += it.textValue.trim() to it }
                        else -> Unit
                    }
                }
            }
        }
        return out
    }

    private fun topLevelTasks(file: YAMLFile): List<YAMLMapping> =
        file.documents
            .mapNotNull { it.topLevelValue as? YAMLSequence }
            .flatMap { it.items }
            .mapNotNull { it.value as? YAMLMapping }

    // ---- N12: hosts pattern -> inventory group -----------------------------

    /** Everything that defines the inventory group [name]: source keys and `group_vars` files. */
    fun groupDefinitions(name: String, from: VirtualFile, project: Project): List<PsiElement> {
        if (name.isBlank() || name.contains("{{")) return emptyList()
        val manager = PsiManager.getInstance(project)
        val layout = AnsibleLayoutService.getInstance(project)
        val out = ArrayList<PsiElement>()

        for (root in layout.inventoryRoots(from)) {
            for (child in root.children) {
                if (child.isDirectory) continue
                val psi = manager.findFile(child)
                when {
                    psi is YAMLFile -> collectGroupKeys(psi, name, out)
                    VarFileRole.isIniInventory(child) && psi != null ->
                        collectIniDeclarations(psi, name, root.name, out)
                    else -> Unit
                }
            }
            root.findChild("group_vars")?.children
                ?.filter { !it.isDirectory && it.nameWithoutExtension == name }
                ?.mapNotNull { manager.findFile(it) }
                ?.let(out::addAll)
            root.findChild("host_vars")?.children
                ?.filter { file ->
                    // `host_vars/<host>.yml` or `host_vars/<host>/*.yml`
                    if (file.isDirectory) file.name == name
                    else file.nameWithoutExtension == name
                }
                ?.flatMap { if (it.isDirectory) it.children.filter { c -> !c.isDirectory } else listOf(it) }
                ?.mapNotNull { manager.findFile(it) }
                ?.let(out::addAll)
        }
        return out
    }

    private fun collectGroupKeys(file: YAMLFile, name: String, out: MutableList<PsiElement>) {
        fun walk(mapping: YAMLMapping) {
            for (kv in mapping.keyValues) {
                if (kv.keyText.trim() == name) out += kv.key ?: kv
                when (val v = kv.value) {
                    is YAMLMapping -> walk(v)
                    else -> Unit
                }
            }
        }
        file.documents.mapNotNull { it.topLevelValue as? YAMLMapping }.forEach(::walk)
    }

    /**
     * `[name]`, `[name:children]`, `[name:vars]` section headers, and bare
     * host lines under a `hosts`-kind section, in an INI-format inventory.
     *
     * `hosts:` in a play is just as often a single host as a group, and this
     * project's inventories are plain INI (`inventories/<env>/hosts`, no
     * extension) rather than YAML — [AnsibleTargets.groupDefinitions]'s
     * YAML-only walk above never matches anything in them.
     *
     * Targets are wrapped in [IniOffsetTarget] rather than resolved through
     * `PsiFile.findElementAt`: an extension-less file like this gets treated
     * as plain text, whose PSI is a single leaf spanning the whole file, so
     * `findElementAt` would always navigate to offset 0 regardless of which
     * line matched.
     */
    private fun collectIniDeclarations(
        psiFile: PsiFile,
        name: String,
        inventoryName: String,
        out: MutableList<PsiElement>,
    ) {
        val text = psiFile.text
        var section = ""
        var kind = "hosts"
        var offset = 0
        for (rawLine in text.split('\n')) {
            val lineStart = offset
            offset += rawLine.length + 1
            val line = rawLine.substringBefore('#').substringBefore(';').trim()
            if (line.isEmpty()) continue

            if (line.startsWith('[') && line.endsWith(']')) {
                val header = line.substring(1, line.length - 1).trim()
                section = header.substringBefore(':')
                kind = header.substringAfter(':', "hosts")
                if (section == name) {
                    val headerOffset = rawLine.indexOf(section, rawLine.indexOf('['))
                    out += IniOffsetTarget(psiFile, lineStart + headerOffset, "Inventory: $inventoryName")
                }
                continue
            }

            if (kind != "hosts") continue
            val host = line.split(Regex("\\s+")).firstOrNull() ?: continue
            if (host == name) {
                val indent = rawLine.indexOf(host)
                out += IniOffsetTarget(psiFile, lineStart + indent, "Inventory: $inventoryName")
            }
        }
    }

    // ---- misc ---------------------------------------------------------------

    /** The `dependencies:` sequence of a role `meta/main.yml`, if present. */
    fun metaDependencies(file: YAMLFile): YAMLKeyValue? =
        file.documents
            .mapNotNull { it.topLevelValue as? YAMLMapping }
            .firstNotNullOfOrNull { it.getKeyValueByKey("dependencies") }
}
