package dev.yamlix.ansible.overview

import com.intellij.openapi.vfs.VirtualFile

/**
 * What the tool window can say about the current file.
 *
 * The three cases are genuinely different claims, and collapsing any two of
 * them lies to the reader: "this file is not Ansible" is a fact, "the index is
 * still building" is a temporary inability to answer, and only [Ready] is an
 * answer. Before this existed the panel treated indexing as an answer and
 * reported every variable in the project as undefined.
 */
sealed interface ViewState {
    /** Not a file this plugin has anything to say about. */
    object NotAnsible : ViewState

    /** The index is not ready; ask again when it is. */
    object Indexing : ViewState

    /**
     * An Ansible file the IDE does not index, because it sits outside the
     * project's content roots.
     *
     * Indistinguishable from [Ready] with everything undefined unless it is
     * said out loud: every index lookup returns empty, so a correct project
     * reads as one where nothing is defined anywhere.
     */
    object OutsideContentRoots : ViewState

    data class Ready(val view: FileVariableView) : ViewState
}

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
    /** The project root paths are shown relative to, when there is one. */
    val base: VirtualFile?,
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
    /**
     * The plays this file declares, when it is a playbook. Empty otherwise.
     *
     * A site playbook often references no variables at all, so a window built
     * only from `{{ … }}` had nothing to say about the very file that decides
     * where everything runs. What a playbook declares — which hosts, which
     * roles — is its content.
     */
    val plays: List<PlayOutline> = emptyList(),
    /**
     * `import_playbook` entries, when this file is a playbook.
     *
     * Listed beside the plays because they are the other half of what a site
     * playbook does. A window that showed only the plays reported a two-entry
     * playbook as having one, which is the kind of quiet undercount that makes
     * a reader stop trusting the panel.
     */
    val imports: List<PlayImport> = emptyList(),
    /**
     * The groups this file declares, when it is an inventory. Empty otherwise.
     *
     * An inventory is where "where does this run" is decided, and until now the
     * tool window said "open a file inside an Ansible project" when you were
     * standing in one. A group's interesting question is not what it contains —
     * that is on screen already — but who targets it.
     */
    val groups: List<GroupOutline> = emptyList(),
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
    /** The group whose header the caret sits in, if any. */
    fun groupAt(offset: Int): GroupOutline? =
        groups.firstOrNull { group -> group.ranges.any { offset in it } }

    fun rowAt(offset: Int): VariableRow? =
        (uses + defines)
            .mapNotNull { row ->
                row.ranges.filter { offset in it }.minByOrNull { it.last - it.first }
                    ?.let { row to (it.last - it.first) }
            }
            .minByOrNull { it.second }
            ?.first
}

/**
 * A group declared by an inventory, and who aims at it.
 *
 * [targetedBy] is the answer to "if I change this group, what moves?" — the
 * plays whose `hosts:` selects any of its hosts. [unevaluatedPlays] counts the
 * plays whose pattern could not be evaluated, because silently omitting them
 * would present a partial list as a complete one.
 */
data class GroupOutline(
    val name: String,
    val hostCount: Int,
    val children: List<String>,
    val offset: Int,
    val ranges: List<IntRange>,
    val targetedBy: List<GroupUse>,
    val varsFiles: List<VirtualFile>,
    val unevaluatedPlays: Int,
)

/** A play that runs on this group, and how squarely. */
data class GroupUse(
    val playbook: VirtualFile,
    val offset: Int,
    val pattern: String,
    /** True when the play targets exactly this group's hosts and no others. */
    val exact: Boolean,
    val matchedHosts: Int,
)

/**
 * One play of a playbook: what it targets and what it runs there.
 *
 * [hostSummary] is the same phrasing the header uses — the group name and a
 * count, or an admission that the pattern cannot be evaluated — so a reader can
 * see at a glance that `hosts: web_ap*` reaches nothing they can enumerate.
 */
data class PlayOutline(
    val name: String?,
    val hosts: String,
    val hostSummary: String?,
    val offset: Int,
    val roles: List<PlayRole>,
)

/**
 * A role a play runs.
 *
 * [entry] is the file to open — a role directory cannot be navigated to, so it
 * is resolved to `tasks/main.yml` (or whatever the role actually has) here,
 * where the VFS is available, rather than in the renderer.
 */
data class PlayRole(val name: String, val entry: VirtualFile?)

/** An `import_playbook:` line, and the playbook it names when it resolves. */
data class PlayImport(val path: String, val target: VirtualFile?, val offset: Int)

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
    /** Ansible's own precedence number for the scope. Higher wins. */
    val scopeRank: Int,
)
