package dev.yamlix.ansible.overview

import com.intellij.openapi.vfs.VirtualFile

/**
 * A whole Ansible project, summarised.
 *
 * Everything the plugin does elsewhere answers a question about the symbol
 * under the caret. This answers questions about the project: how the
 * inventories are shaped, what each playbook actually targets, which roles are
 * reached from where — and which of those answers look wrong.
 *
 * Deliberately a plain data tree with no Swing in sight, so the analysis can be
 * tested without a UI and the panel stays a renderer.
 */
data class AnsibleOverview(
    val base: VirtualFile,
    val inventories: List<InventorySummary>,
    val playbooks: List<PlaybookSummary>,
    val roles: List<RoleSummary>,
    val findings: List<Finding>,
) {
    companion object {
        fun empty(base: VirtualFile) =
            AnsibleOverview(base, emptyList(), emptyList(), emptyList(), emptyList())
    }
}

data class InventorySummary(
    val name: String,
    val root: VirtualFile,
    val hosts: List<String>,
    val groups: List<GroupSummary>,
) {
    val hostCount: Int get() = hosts.size
}

data class GroupSummary(val name: String, val hostCount: Int)

data class PlaybookSummary(
    val file: VirtualFile,
    val plays: List<PlaySummary>,
)

data class PlaySummary(
    /** The `hosts:` pattern verbatim. */
    val pattern: String,
    /**
     * The hosts it targets, per inventory. A null value is an inventory whose
     * answer is not statically knowable — a templated or glob pattern. Null is
     * "unknown", which is not the same as an empty set, and the panel must not
     * render them alike.
     */
    val targeted: Map<String, Set<String>?>,
    val roles: List<String>,
) {
    /** Total across inventories, or null when any inventory is unknown. */
    val totalTargeted: Int?
        get() = qualifiedHosts()?.size

    /**
     * Targeted hosts as `inventory/host`, or null when any inventory is
     * unknown.
     *
     * Qualified because two inventories routinely hold hosts of the same name,
     * and because callers union these across plays — a role reached by two
     * playbooks that both target `containers` runs on those hosts once, not
     * twice.
     */
    fun qualifiedHosts(): Set<String>? {
        val out = LinkedHashSet<String>()
        for ((inventory, hosts) in targeted) {
            hosts?.mapTo(out) { "$inventory/$it" } ?: return null
        }
        return out
    }
}

data class RoleSummary(
    val name: String,
    val dir: VirtualFile,
    /** Playbooks whose role closure reaches this role. */
    val usedBy: List<VirtualFile>,
    /** Hosts it ever runs on, or null when any reaching play is unknowable. */
    val totalTargeted: Int?,
)

enum class FindingKind {
    /** A `group_vars`/`host_vars` file naming something no inventory has. */
    ORPHAN_VARS_FILE,

    /** A role no playbook reaches. */
    UNUSED_ROLE,

    /** A play whose pattern matches nothing anywhere. */
    PLAY_TARGETS_NOTHING,

    /** An inventory with no hosts at all. */
    EMPTY_INVENTORY,

    /** A definition that never wins for any host of any playbook. */
    NEVER_WINS,
}

data class Finding(
    val kind: FindingKind,
    val message: String,
    val file: VirtualFile?,
    /** Offset within [file], for navigation; null means "the file itself". */
    val offset: Int? = null,
    /** Why this is worth showing — rendered as the row's tooltip. */
    val detail: String? = null,
)
