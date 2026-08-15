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

            splitRoleVars(file)?.let { return it }

            if (isIniInventory(file)) return IniInventory
            if (isManifest(file)) return None
            return null
        }

        /**
         * Defaults split across a `<role>/defaults/main/` directory.
         *
         * Ansible accepts `defaults/main.yml`, `defaults/main.yaml` and
         * `defaults/main/` as a directory whose files are all loaded, and roles
         * with a large surface routinely use the third. The checks above look
         * one level up for the role, so the directory form fell past them into
         * [VarsFileCandidate]: indexed at `vars_files` precedence with no role
         * to qualify it, which no host ever admits. The variables were in the
         * index and resolved to nothing anyway.
         *
         * That is the whole of kubespray's central `kubespray_defaults` role —
         * `bin_dir`, `kube_config_dir`, `kubectl`, the names referenced most
         * often in the project — reported undefined everywhere they were read.
         */
        private fun splitRoleVars(file: VirtualFile): VarFileRole? {
            val parent = file.parent ?: return null
            if (parent.name != "main") return null
            val kind = parent.parent ?: return null
            val role = kind.parent ?: return null
            if (!isRoleDir(role)) return null
            return when (kind.name) {
                "defaults" -> FlatVars(VarScope.ROLE_DEFAULTS, role.name)
                // Every file in `vars/main/` is loaded automatically, so unlike
                // a loose file in `vars/` none of them needs an `include_vars`.
                "vars" -> FlatVars(VarScope.ROLE_VARS, role.name)
                else -> null
            }
        }

        /**
         * A YAML file whose top-level keys are its own schema, not variables.
         *
         * Anything that reaches [VarsFileCandidate] is indexed key by key, on
         * the assumption that a plain mapping is a file some `vars_files:` may
         * name. That assumption is wrong for the manifests every Ansible
         * repository carries: kubespray's `galaxy.yml` was indexed as thirteen
         * variables called `namespace`, `version`, `dependencies`, `readme` and
         * so on — names common enough to then be offered in completion and to
         * collide with real variables elsewhere in the project.
         *
         * A name list rather than a shape test, because these files are plain
         * mappings and look exactly like vars files; only what they are called
         * and where they sit tells them apart. Erring towards a short list: a
         * manifest wrongly indexed invents variables, while a vars file wrongly
         * skipped is only found through `vars_files:` — and none of these names
         * is one a `vars_files:` would ever point at.
         */
        internal fun isManifest(file: VirtualFile): Boolean {
            val name = file.name.lowercase()
            if (name in MANIFEST_NAMES) return true
            // `.github/workflows/*.yml`, `.gitlab/*` — CI definitions, and
            // whole directories of them.
            var dir = file.parent
            var depth = 0
            while (dir != null && depth++ < 3) {
                if (dir.name.lowercase() in MANIFEST_DIRS) return true
                dir = dir.parent
            }
            return false
        }

        private val MANIFEST_NAMES = setOf(
            // Ansible's own metadata, none of which is a variable.
            "galaxy.yml", "galaxy.yaml", "runtime.yml", "runtime.yaml",
            "requirements.yml", "requirements.yaml",
            "molecule.yml", "molecule.yaml",
            "ansible-navigator.yml", "ansible-navigator.yaml",
            // Tooling and CI that happens to live in the same repository.
            ".ansible-lint", ".yamllint", ".yamllint.yml", ".yamllint.yaml",
            ".pre-commit-config.yaml", ".pre-commit-config.yml",
            ".gitlab-ci.yml", ".gitlab-ci.yaml",
            ".readthedocs.yml", ".readthedocs.yaml",
            "_config.yml", "_config.yaml", "mkdocs.yml", "mkdocs.yaml",
            "docker-compose.yml", "docker-compose.yaml",
        )

        private val MANIFEST_DIRS = setOf(".github", ".gitlab", ".circleci", "molecule")

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
         * A `group_vars`/`host_vars` file written without an extension.
         *
         * Ansible accepts `group_vars/all`, `group_vars/all.yml`, `.yaml` and
         * `.json` alike. Only the first is invisible to an IDE, which sees
         * plain text and indexes nothing.
         */
        fun isExtensionlessVarFile(file: VirtualFile): Boolean {
            if (file.isDirectory || file.extension != null) return false
            val parent = file.parent?.name ?: return false
            val grand = file.parent?.parent?.name
            return parent in VARS_DIRS || grand in VARS_DIRS
        }

        private val VARS_DIRS = setOf("group_vars", "host_vars")

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
