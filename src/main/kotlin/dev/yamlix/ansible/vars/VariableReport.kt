package dev.yamlix.ansible.vars

import com.intellij.openapi.vfs.VirtualFile

/**
 * How confident we are about a value. Anything other than [LITERAL] must be
 * rendered as unresolved, showing the raw template — never a guessed value.
 */
enum class ValueKind {
    /** A plain value with no Jinja in it. */
    LITERAL,

    /** The winning value is itself a template. Ansible expands it lazily. */
    TEMPLATE,

    /** `register:` — the value only exists once the task has run. */
    RUNTIME,

    /**
     * Bound by a loop: one entry of a collection, different every iteration.
     *
     * Distinct from [RUNTIME] because the useful half of the answer survives —
     * the collection being looped over is written in the repo and can itself be
     * resolved, so "each entry of `{{ users }}`" is sayable where "set at run
     * time" would throw that away.
     */
    LOOP_ITEM,

    /** Several sites could win; which one does depends on a fact or a guard. */
    AMBIGUOUS,

    /** Nothing in the repo defines it: a fact, a magic variable, or `-e`. */
    UNDEFINED,
}

/**
 * One row of the Quick Documentation table: a set of hosts in one inventory
 * that all resolve identically.
 */
data class ReportRow(
    /** The inventory this row is about, or null when the project has none. */
    val inventory: String?,
    val hosts: List<String>,
    /** True when every host in this inventory shares this row. */
    val coversWholeInventory: Boolean,
    val kind: ValueKind,
    /** The value, or the raw template when [kind] is not [ValueKind.LITERAL]. */
    val value: String?,
    val winner: VarSite?,
    /** Sites that would win if the condition went the other way. */
    val alternatives: List<VarSite>,
    val note: String?,
)

/**
 * The whole table for one variable, under one or more playbooks.
 *
 * Plural because playbooks that resolve a variable identically are one answer,
 * not several. A shared play imported by a dozen sites produced a dozen
 * byte-identical tables; they collapse into one section listing the playbooks
 * it holds for.
 */
data class VariableReport(
    val name: String,
    val playbooks: List<VirtualFile>,
    val rows: List<ReportRow>,
    val caveats: List<String>,
    /**
     * Set when the name is a variable Ansible provides itself. The per-inventory
     * table is meaningless for those — the answer is the same everywhere and is
     * not in the repo — so the renderer shows this instead.
     */
    val magic: MagicVariable? = null,
) {
    val isFullyResolved: Boolean get() = rows.all { it.kind == ValueKind.LITERAL }
}
