package dev.yamlix.ansible.psi

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Structural questions about Ansible YAML, answered from PSI shape only.
 *
 * Deliberately never looks at file names to decide *what a file is* — a
 * playbook is a playbook because of how its top-level value is shaped, not
 * because it is called `site-playbook.yml`. Directory names are used only where
 * Ansible itself gives them meaning (`tasks/`, `defaults/`, `group_vars/` …).
 */
object PlayStructure {

    /**
     * The on-disk file an element belongs to.
     *
     * Completion and intention preview run against a non-physical copy of the
     * file whose `virtualFile` is a light in-memory file with no real parent —
     * walking up from it to find `ansible.cfg` finds nothing. `originalFile`
     * gives the physical file back, and is a no-op for ordinary PSI.
     */
    fun sourceFile(element: PsiElement): VirtualFile? {
        val file = element.containingFile ?: return null
        return file.originalFile.virtualFile ?: file.virtualFile
    }

    /** Directories whose name Ansible treats as a role subdirectory. */
    val ROLE_SUBDIRS = setOf(
        "tasks", "defaults", "vars", "handlers", "meta",
        "templates", "files", "library", "lookup_plugins", "filter_plugins",
    )

    private val PLAY_KEYS = setOf(
        "hosts", "roles", "tasks", "pre_tasks", "post_tasks",
        "import_playbook", "ansible.builtin.import_playbook",
    )

    /**
     * A playbook is a document whose top level is a sequence of mappings, at
     * least one of which carries a play-level key.
     */
    fun isPlaybook(file: YAMLFile): Boolean =
        plays(file).isNotEmpty()

    /** The play mappings of a playbook, in document order. Empty if not a playbook. */
    fun plays(file: YAMLFile): List<YAMLMapping> =
        file.documents
            .mapNotNull { it.topLevelValue as? YAMLSequence }
            .flatMap { it.items }
            .mapNotNull { it.value as? YAMLMapping }
            .filter { mapping -> mapping.keyValues.any { it.keyText.trim() in PLAY_KEYS } }

    /** The play a given element sits inside, if any. */
    fun enclosingPlay(element: PsiElement): YAMLMapping? {
        val file = element.containingFile as? YAMLFile ?: return null
        val plays = plays(file)
        if (plays.isEmpty()) return null
        var current: PsiElement? = element
        while (current != null) {
            if (current in plays) return current as YAMLMapping
            current = current.parent
        }
        return null
    }

    /**
     * The role directory containing [file], or null.
     *
     * `roles/app/tasks/main.yml` -> `roles/app`.
     * A file directly in a role root (rare) is not treated as being in a role,
     * matching Ansible, which only loads the known subdirectories.
     */
    fun enclosingRoleDir(file: VirtualFile): VirtualFile? {
        val subdir = file.parent ?: return null
        // Handle nested task files: roles/app/tasks/sub/more.yml
        var candidate: VirtualFile? = subdir
        while (candidate != null) {
            if (candidate.name in ROLE_SUBDIRS) {
                val roleDir = candidate.parent ?: return null
                // Guard against a stray directory literally called "tasks".
                return if (roleDir.findChild("tasks") != null ||
                    roleDir.findChild("defaults") != null ||
                    roleDir.findChild("meta") != null
                ) roleDir else null
            }
            candidate = candidate.parent
        }
        return null
    }

    /** True when [file] is a role's `meta/main.yml`. */
    fun isRoleMeta(file: VirtualFile): Boolean =
        file.parent?.name == "meta" && enclosingRoleDir(file) != null

    // ---- key/value navigation helpers -------------------------------------

    /** The [YAMLKeyValue] this scalar is the value of, if it is one. */
    fun owningKeyValue(scalar: YAMLScalar): YAMLKeyValue? =
        (scalar.parent as? YAMLKeyValue)?.takeIf { it.value === scalar }

    /**
     * The key of the sequence this scalar's item belongs to.
     *
     * ```
     * roles:
     *   - app        <- returns "roles"
     * ```
     */
    fun owningSequenceKey(scalar: YAMLScalar): String? {
        val item = scalar.parent as? YAMLSequenceItem ?: return null
        val sequence = item.parent as? YAMLSequence ?: return null
        return (sequence.parent as? YAMLKeyValue)?.keyText?.trim()
    }

    /**
     * For `- role: common` inside a `dependencies:`/`roles:` sequence, the key
     * of the enclosing sequence.
     */
    fun owningSequenceKeyOfMappingEntry(scalar: YAMLScalar): String? {
        val kv = owningKeyValue(scalar) ?: return null
        val mapping = kv.parent as? YAMLMapping ?: return null
        val item = mapping.parent as? YAMLSequenceItem ?: return null
        val sequence = item.parent as? YAMLSequence ?: return null
        return (sequence.parent as? YAMLKeyValue)?.keyText?.trim()
    }

    /**
     * When this scalar is a module argument, the module's key text.
     *
     * ```
     * - ansible.builtin.include_role:
     *     name: monitoring     <- returns "ansible.builtin.include_role"
     * ```
     */
    fun owningModuleKey(scalar: YAMLScalar): String? {
        val argKv = owningKeyValue(scalar) ?: return null
        val argMapping = argKv.parent as? YAMLMapping ?: return null
        return (argMapping.parent as? YAMLKeyValue)?.keyText?.trim()
    }

    /**
     * When this scalar is a module's free-form value.
     *
     * ```
     * - ansible.builtin.include_tasks: configure.yml   <- returns the key text
     * ```
     */
    fun freeFormModuleKey(scalar: YAMLScalar): String? =
        owningKeyValue(scalar)?.keyText?.trim()

    /** Strips an `ansible.builtin.` / `ansible.legacy.` prefix from a module key. */
    fun bareModuleName(key: String): String =
        key.removePrefix("ansible.builtin.").removePrefix("ansible.legacy.")
}
