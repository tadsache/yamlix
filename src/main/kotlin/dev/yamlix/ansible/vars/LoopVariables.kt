package dev.yamlix.ansible.vars

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * A name a task binds for the duration of its loop.
 *
 * ```yaml
 * - include_role:
 *     name: adduser
 *   loop: "{{ users }}"
 *   loop_control:
 *     loop_var: user
 * ```
 *
 * Inside `adduser`, `user` is written everywhere and defined nowhere. Nothing
 * in the role names it, no `group_vars` holds it, and the index has no
 * definition to find — so it read as "not defined in this project", which is
 * both wrong and unhelpful: the role is correct, and the answer a reader wants
 * is *which task supplies it, from which collection*.
 *
 * [collection] is the loop expression as written, kept because it is the useful
 * half of the answer — `user` is one entry of `{{ users }}`, and `users` is a
 * name the plugin can already resolve.
 */
data class LoopBinding(
    val varName: String,
    /** The loop expression, exactly as written. */
    val collection: String,
    /**
     * The role this binding is handed to, when the looping task is an
     * `include_role`/`import_role`; null when it binds only its own subtree.
     */
    val targetRole: String?,
)

/**
 * Reading `loop:` / `with_*:` plus `loop_control:` off a task.
 *
 * Shared deliberately between [AnsibleVarIndex], which records the bindings a
 * task hands to some other role, and the report builder, which walks the PSI
 * upwards for the ones a task binds over itself. One parser means the two can
 * never disagree about what a loop binds.
 */
object LoopVariables {

    private val INCLUDE_ROLE = setOf(
        "include_role", "import_role",
        "ansible.builtin.include_role", "ansible.builtin.import_role",
    )

    /**
     * `item` is Ansible's default loop variable and is already described as a
     * magic variable, so recording it here would only duplicate that — and
     * badly, since every loop in the project would claim to bind it.
     */
    private const val DEFAULT_LOOP_VAR = "item"

    /** The binding [task] establishes, or null when it does not loop. */
    fun bindingOf(task: YAMLMapping): LoopBinding? {
        val loop = task.keyValues.firstOrNull {
            val key = it.keyText.trim()
            key == "loop" || key.startsWith("with_")
        } ?: return null

        val name = (task.getKeyValueByKey("loop_control")?.value as? YAMLMapping)
            ?.getKeyValueByKey("loop_var")?.valueText?.trim()
            ?.ifBlank { null }
            ?: return null
        if (name == DEFAULT_LOOP_VAR) return null

        val collection = loop.valueText.trim().ifBlank { null } ?: return null
        return LoopBinding(name, collection, targetRoleOf(task))
    }

    /**
     * The role an `include_role`/`import_role` task pulls in.
     *
     * A collection-qualified name (`myorg.mycoll.adduser`) is reduced to its
     * last segment, which is what the role directory is called on disk — the
     * only form the rest of the plugin can match against.
     */
    private fun targetRoleOf(task: YAMLMapping): String? {
        val include = task.keyValues.firstOrNull { it.keyText.trim() in INCLUDE_ROLE } ?: return null
        val name = (include.value as? YAMLMapping)?.getKeyValueByKey("name")?.valueText?.trim()
            ?: include.valueText.trim()
        return name.ifBlank { null }?.substringAfterLast('.')
    }

    /**
     * The binding [name] has at [position] from a loop written in the same
     * file, or null when there is none.
     *
     * Answered from the PSI rather than the index because it can be answered
     * exactly: the binding holds inside the looping task and nowhere else, and
     * walking up from the use is the only way to honour that. The index cannot
     * — it records a file, not a range.
     */
    fun localBinding(position: PsiElement, name: String): LoopBinding? {
        var item = PsiTreeUtil.getParentOfType(position, YAMLSequenceItem::class.java, false)
        while (item != null) {
            val task = item.value as? YAMLMapping
            val binding = task?.let(::bindingOf)
            // Includes count too: a task that loops over a role may still read
            // its own loop variable in `when:` or in the parameters it passes.
            if (binding != null && binding.varName == name) return binding
            item = PsiTreeUtil.getParentOfType(item, YAMLSequenceItem::class.java, true)
        }
        return null
    }
}
