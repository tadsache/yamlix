package dev.yamlix.ansible.layout

import com.intellij.openapi.vfs.VirtualFile

/**
 * The subset of `ansible.cfg` that affects navigation.
 *
 * Paths are kept as written; resolution against [baseDir] happens in
 * [AnsibleLayoutService]. See the CWD caveat documented there.
 */
data class AnsibleCfg(
    /** Directory containing the `ansible.cfg` this was parsed from. */
    val baseDir: VirtualFile,
    val rolesPath: List<String>,
    val inventory: List<String>,
    val collectionsPath: List<String>,
) {
    companion object {

        /** Ansible's built-in defaults, used when a key is absent. */
        val DEFAULT_ROLES_PATH = listOf(
            "~/.ansible/roles",
            "/usr/share/ansible/roles",
            "/etc/ansible/roles",
        )

        val DEFAULT_COLLECTIONS_PATH = listOf(
            "~/.ansible/collections",
            "/usr/share/ansible/collections",
        )

        /**
         * Minimal INI reader. `ansible.cfg` is INI with `#`/`;` comments and
         * `key = value` pairs; we only care about a handful of keys in
         * `[defaults]`, so a hand-rolled parser beats pulling in a dependency.
         */
        fun parse(cfgFile: VirtualFile, text: CharSequence): AnsibleCfg {
            val defaults = HashMap<String, String>()
            var section = ""
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#') || line.startsWith(';')) continue
                if (line.startsWith('[') && line.endsWith(']')) {
                    section = line.substring(1, line.length - 1).trim().lowercase()
                    continue
                }
                if (section != "defaults") continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim().lowercase()
                val value = line.substring(eq + 1).trim()
                defaults[key] = value
            }

            val baseDir = cfgFile.parent ?: cfgFile

            fun pathList(vararg keys: String, fallback: List<String>): List<String> {
                for (k in keys) {
                    val v = defaults[k] ?: continue
                    // Ansible accepts both ':' and ',' as separators.
                    val parts = v.split(':', ',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (parts.isNotEmpty()) return parts
                }
                return fallback
            }

            return AnsibleCfg(
                baseDir = baseDir,
                rolesPath = pathList("roles_path", fallback = DEFAULT_ROLES_PATH),
                inventory = pathList("inventory", "hostfile", fallback = emptyList()),
                collectionsPath = pathList(
                    "collections_path", "collections_paths",
                    fallback = DEFAULT_COLLECTIONS_PATH,
                ),
            )
        }
    }
}
