package dev.yamlix.ansible.vars

/**
 * Where a variable definition came from, and what that is worth.
 *
 * [rank] is Ansible's documented variable precedence (NAVIGATION-CASES.md §4,
 * rule R4). Higher wins. The numbers are Ansible's own, kept verbatim rather
 * than renumbered 1..n so they can be checked against the upstream table.
 */
enum class VarScope(val rank: Int, val display: String) {
    ROLE_DEFAULTS(2, "role defaults"),
    GROUP_VARS_ALL(4, "group_vars/all"),
    GROUP_VARS(7, "group_vars"),
    HOST_VARS(10, "host_vars"),
    PLAY_VARS(13, "play vars"),
    VARS_FILE(15, "vars_files"),
    ROLE_VARS(16, "role vars"),
    BLOCK_VARS(17, "block vars"),
    TASK_VARS(18, "task vars"),
    INCLUDE_VARS(19, "include_vars"),
    SET_FACT(20, "set_fact"),
    REGISTERED(20, "registered"),
    ROLE_PARAM(21, "role param"),
    ;

    /** True for scopes whose visibility depends on where in the play you are. */
    val isFlowSensitive: Boolean
        get() = this == INCLUDE_VARS || this == SET_FACT || this == REGISTERED

    companion object {
        /**
         * Extra vars (rank 23) always win and are never in the repo — they live
         * in the user's shell history. Nothing is ever indexed at this rank; it
         * exists so the resolver can say "and this could still be overridden".
         */
        const val EXTRA_VARS_RANK = 23
    }
}
