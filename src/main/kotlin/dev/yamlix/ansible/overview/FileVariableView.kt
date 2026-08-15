package dev.yamlix.ansible.overview

import com.intellij.openapi.vfs.VirtualFile

/**
 * Everything worth knowing about the variables in one file.
 *
 * The plugin's other surfaces answer "what is this one symbol?". This answers
 * "what is actually true in the file I am looking at" — which values arrive
 * here, where they come from, and which hosts this file even runs on. That
 * question has no answer anywhere else, and it is the one you have when
 * reading unfamiliar Ansible.
 */
data class FileVariableView(
    /** The file's own name, e.g. `tasks/main.yml`. */
    val title: String,
    /** What contains it — a role name, or the project-relative directory. */
    val subtitle: String?,
    /** Playbooks that reach this file. Empty when nothing does. */
    val reachedBy: List<VirtualFile>,
    /**
     * The hosts this file runs against, phrased for a human: the group when one
     * describes them, otherwise a count. Null when it cannot be determined,
     * which is not the same as "none".
     */
    val runsOn: String?,
    /** Variables referenced by `{{ … }}` here. */
    val uses: List<VariableRow>,
    /** Variables this file declares. */
    val defines: List<VariableRow>,
) {
    /**
     * The variable the caret sits inside, or null when it sits in ordinary
     * YAML.
     *
     * Null is a real answer, and the panel treats it as "leave the selection
     * alone" rather than "select nothing" — clearing the detail pane on every
     * keystroke between variables would make it flicker.
     *
     * The narrowest containing range wins: a definition's range spans its whole
     * value, so `foo: "{{ bar }}"` contains both, and the caret inside `bar` is
     * about `bar`.
     */
    fun rowAt(offset: Int): VariableRow? =
        (uses + defines)
            .mapNotNull { row ->
                row.ranges.filter { offset in it }.minByOrNull { it.last - it.first }
                    ?.let { row to (it.last - it.first) }
            }
            .minByOrNull { it.second }
            ?.first
}

/** How much attention a row deserves. */
enum class RowStatus {
    /** One value, known, everywhere this file runs. */
    RESOLVED,

    /** Resolves, but not to the same thing for every host. */
    VARIES,

    /** Several sites could win; which one does depends on run-time state. */
    AMBIGUOUS,

    /** Ansible supplies it itself: a loop item, a fact, a magic variable. */
    PROVIDED_BY_ANSIBLE,

    /** Nothing defines it and Ansible does not supply it either. */
    UNRESOLVED,

    /** Declared here, but something always beats it. Dead configuration. */
    NEVER_WINS,
}

data class VariableRow(
    val name: String,
    /** The value, or values, collapsed to one line. */
    val summary: String,
    val status: RowStatus,
    /** Why the status is what it is, when that needs saying. */
    val note: String?,
    /** Every definition site, ordered as the detail pane shows them. */
    val sites: List<VariableSite>,
    /**
     * Where this variable actually occupies text in the file — every
     * `{{ name }}` for a use, the whole `name: value` for a definition.
     *
     * Ranges rather than a single offset because the caret is either *inside*
     * a variable or it is not. "The nearest one above the caret" is a different
     * and much vaguer question, and answering it kept the panel pointed at a
     * variable the reader had long since scrolled past.
     */
    val ranges: List<IntRange>,
) {
    /** Where double-clicking this row lands. */
    val offset: Int get() = ranges.firstOrNull()?.first ?: 0
}

/** Where a definition sits, and whether it is the one that applies. */
enum class SiteStatus {
    WINS,
    MAY_WIN,
    OVERRIDDEN,
    NOT_IN_SCOPE,
}

data class VariableSite(
    val status: SiteStatus,
    /** True when position, not relevance, is why it may not apply. */
    val flowSensitive: Boolean,
    val value: String?,
    val file: VirtualFile,
    val offset: Int,
    /** Where it holds — `env-c (special_group)`, `all inventories`, … */
    val where: List<String>,
    val scopeLabel: String,
)
