package dev.yamlix.ansible.vars

/**
 * Variables Ansible provides itself.
 *
 * None of them are declared anywhere in a repository, so "no declaration found"
 * is the *correct* answer for Ctrl+Click — but it is a useless one. Knowing that
 * `inventory_hostname` is a magic variable, and where the thing it names is
 * defined, is what the user actually wants.
 */
enum class MagicOrigin {
    /** Comes from the inventory: hosts, groups, the inventory path. */
    INVENTORY,

    /** Comes from fact gathering, so it depends on `gather_facts`. */
    FACTS,

    /** Comes from the play or the runtime, with no file behind it. */
    RUNTIME,
}

data class MagicVariable(
    val name: String,
    val origin: MagicOrigin,
    val description: String,
)

object AnsibleMagicVariables {

    private val CATALOGUE: Map<String, MagicVariable> = listOf(
        MagicVariable("inventory_hostname", MagicOrigin.INVENTORY,
            "The name of the current host as written in the inventory."),
        MagicVariable("inventory_hostname_short", MagicOrigin.INVENTORY,
            "`inventory_hostname` up to the first dot."),
        MagicVariable("inventory_dir", MagicOrigin.INVENTORY,
            "Directory of the inventory source currently in use."),
        MagicVariable("inventory_file", MagicOrigin.INVENTORY,
            "Path of the inventory source currently in use."),
        MagicVariable("group_names", MagicOrigin.INVENTORY,
            "The groups the current host belongs to."),
        MagicVariable("groups", MagicOrigin.INVENTORY,
            "Every group in the inventory, mapped to its hosts."),
        MagicVariable("hostvars", MagicOrigin.INVENTORY,
            "All variables of every host. Contents are only known at run time."),
        MagicVariable("play_hosts", MagicOrigin.INVENTORY,
            "Hosts in the current play batch. Deprecated alias of `ansible_play_batch`."),
        MagicVariable("ansible_play_hosts", MagicOrigin.INVENTORY,
            "Hosts still active in the current play."),
        MagicVariable("ansible_play_hosts_all", MagicOrigin.INVENTORY,
            "All hosts targeted by the current play."),
        MagicVariable("ansible_play_batch", MagicOrigin.INVENTORY,
            "Hosts in the current serial batch."),

        MagicVariable("ansible_facts", MagicOrigin.FACTS,
            "All gathered facts. Requires `gather_facts` or a `setup` task."),

        MagicVariable("playbook_dir", MagicOrigin.RUNTIME,
            "Absolute path of the directory holding the running playbook."),
        MagicVariable("role_path", MagicOrigin.RUNTIME,
            "Absolute path of the role currently executing."),
        MagicVariable("role_name", MagicOrigin.RUNTIME,
            "Name of the role currently executing."),
        MagicVariable("ansible_role_names", MagicOrigin.RUNTIME,
            "Names of all roles in the current play."),
        MagicVariable("omit", MagicOrigin.RUNTIME,
            "Sentinel that removes a parameter instead of passing it."),
        MagicVariable("ansible_check_mode", MagicOrigin.RUNTIME,
            "True when running with `--check`."),
        MagicVariable("ansible_diff_mode", MagicOrigin.RUNTIME,
            "True when running with `--diff`."),
        MagicVariable("ansible_verbosity", MagicOrigin.RUNTIME,
            "The `-v` level the play was started with."),
        MagicVariable("ansible_version", MagicOrigin.RUNTIME,
            "Version of the ansible-core running the play."),
        MagicVariable("ansible_playbook_python", MagicOrigin.RUNTIME,
            "Python interpreter that launched ansible-playbook."),
        MagicVariable("ansible_run_tags", MagicOrigin.RUNTIME, "Tags selected with `--tags`."),
        MagicVariable("ansible_skip_tags", MagicOrigin.RUNTIME, "Tags excluded with `--skip-tags`."),
        MagicVariable("ansible_limit", MagicOrigin.RUNTIME, "The `--limit` pattern in force."),
        MagicVariable("ansible_config_file", MagicOrigin.RUNTIME, "The ansible.cfg actually loaded."),
        MagicVariable("ansible_forks", MagicOrigin.RUNTIME, "Configured fork count."),
        MagicVariable("ansible_loop", MagicOrigin.RUNTIME, "Extended loop information."),
        MagicVariable("ansible_index_var", MagicOrigin.RUNTIME, "Loop index variable name."),
    ).associateBy { it.name }

    /**
     * Connection settings that look like facts but are normally *set* in
     * inventory, so they resolve through the index and must not be shadowed by
     * the fact fallback below.
     */
    private val CONNECTION_VARS = setOf(
        "ansible_host", "ansible_port", "ansible_user", "ansible_connection",
        "ansible_password", "ansible_become", "ansible_become_user",
        "ansible_become_method", "ansible_become_password", "ansible_shell_type",
        "ansible_python_interpreter", "ansible_ssh_private_key_file",
        "ansible_ssh_common_args", "ansible_group_priority",
    )

    /**
     * The catalogue entry for [name], treating any other `ansible_*` name as a
     * gathered fact — that is what an unrecognised one almost always is.
     */
    fun lookup(name: String): MagicVariable? {
        CATALOGUE[name]?.let { return it }
        if (name in CONNECTION_VARS) return null
        if (!name.startsWith("ansible_")) return null
        return MagicVariable(
            name,
            MagicOrigin.FACTS,
            "A gathered fact. Its value comes from `gather_facts` on the target host, " +
                "so it cannot be known without running Ansible.",
        )
    }
}
