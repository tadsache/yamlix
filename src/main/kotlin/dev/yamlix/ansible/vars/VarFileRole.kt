package dev.yamlix.ansible.vars

import com.intellij.openapi.vfs.VirtualFile

/**
 * What a file is, for indexing purposes, decided from its path shape alone.
 *
 * Path shape is deterministic and project-independent, which is what a file
 * index requires. Anything needing project configuration (is this role in the
 * play? which inventory is selected?) is deferred to the resolution service.
 */
sealed interface VarFileRole {

    /** Whole file is a flat `name: value` mapping at one fixed scope. */
    data class FlatVars(val scope: VarScope, val qualifier: String) : VarFileRole

    /** A list of tasks: mine `set_fact`, `register` and task `vars:`. */
    data object Tasks : VarFileRole

    /** A playbook: mine play `vars:`, role params, and the task phases. */
    data object Playbook : VarFileRole

    /** A role `meta/main.yml`: mine dependency role params. */
    data object RoleMeta : VarFileRole

    /** A plain mapping file that only becomes vars when `vars_files` names it. */
    data object VarsFileCandidate : VarFileRole

    /** An INI-format inventory source. */
    data object IniInventory : VarFileRole

    data object None : VarFileRole

    companion object {

        /**
         * Only the directories that *define* a role. `vars/` and `handlers/`
         * are deliberately excluded: a playbook root with a top-level `vars/`
         * directory would otherwise classify as a role, and every file in it
         * would be mis-scoped as role vars instead of a `vars_files` candidate.
         */
        private val ROLE_MARKERS = listOf("tasks", "defaults", "meta")

        /**
         * Classifies by path only. Returns null when the path alone is not
         * decisive and the caller should look at the file's structure.
         */
        fun fromPath(file: VirtualFile): VarFileRole? {
            val parent = file.parent ?: return VarFileRole.None
            val grandParent = parent.parent

            // group_vars/<group>.yml and group_vars/<group>/*.yml
            groupOrHostQualifier(file, "group_vars")?.let { group ->
                val scope = if (group == "all") VarScope.GROUP_VARS_ALL else VarScope.GROUP_VARS
                return FlatVars(scope, group)
            }
            groupOrHostQualifier(file, "host_vars")?.let { host ->
                return FlatVars(VarScope.HOST_VARS, host)
            }

            if (grandParent != null && isRoleDir(grandParent)) {
                val roleName = grandParent.name
                when (parent.name) {
                    "defaults" -> return FlatVars(VarScope.ROLE_DEFAULTS, roleName)
                    "vars" ->
                        // main.yml is loaded automatically at role-vars precedence;
                        // anything else in vars/ can only arrive via include_vars.
                        return if (file.nameWithoutExtension == "main") {
                            FlatVars(VarScope.ROLE_VARS, roleName)
                        } else {
                            FlatVars(VarScope.INCLUDE_VARS, roleName)
                        }
                    "meta" -> return RoleMeta
                    "tasks", "handlers" -> return Tasks
                }
            }

            if (isIniInventory(file)) return IniInventory
            return null
        }

        /** `<inv>/group_vars/web.yml` -> "web"; `<inv>/group_vars/web/a.yml` -> "web". */
        private fun groupOrHostQualifier(file: VirtualFile, dirName: String): String? {
            val parent = file.parent ?: return null
            if (parent.name == dirName) return file.nameWithoutExtension
            if (parent.parent?.name == dirName) return parent.name
            return null
        }

        private fun isRoleDir(dir: VirtualFile): Boolean =
            ROLE_MARKERS.any { dir.findChild(it)?.isDirectory == true }

        /**
         * An extension-less or `.ini` file that looks like an inventory by
         * position: named `hosts*`, or sitting in an `inventor*` directory.
         */
        fun isIniInventory(file: VirtualFile): Boolean {
            val extension = file.extension
            if (extension != null && extension != "ini") return false
            val name = file.name.lowercase()
            if (name.startsWith("hosts") || name.endsWith(".ini")) return true
            val parentName = file.parent?.name?.lowercase() ?: return false
            val grandName = file.parent?.parent?.name?.lowercase() ?: ""
            return parentName.startsWith("inventor") || grandName.startsWith("inventor")
        }
    }
}
