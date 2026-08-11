package dev.yamlix.ansible.psi

import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * What kind of Ansible reference a scalar carries, if any.
 *
 * Classification is by *position in the PSI tree*, never by file name.
 */
enum class AnsibleRefKind {
    /** N1, N2, N3, N5, N7, N8 — a role name. */
    ROLE,

    /** N6 — `include_tasks` / `import_tasks`. */
    TASKS_FILE,

    /** N4 — a playbook `vars_files:` entry. */
    PLAY_VARS_FILE,

    /** N11 — `include_vars`, resolved against the role's `vars/`. */
    ROLE_VARS_FILE,

    /** N9 — `template:`/`copy:` `src:`. */
    TEMPLATE,

    /** N10 — `notify:`, matched against handler names. */
    HANDLER,

    /** N12 — a `hosts:` pattern naming an inventory group. */
    GROUP,
}

object AnsiblePatterns {

    private val ROLE_SEQUENCE_KEYS = setOf("roles", "dependencies")

    private val ROLE_MODULES = setOf("include_role", "import_role")
    private val TASK_MODULES = setOf("include_tasks", "import_tasks", "include")
    private val VARS_MODULES = setOf("include_vars")
    private val SRC_MODULES = setOf("template", "copy")

    /**
     * The single classifier. Patterns, references, completion and the inspection
     * all route through this so they cannot drift apart.
     */
    fun classify(scalar: YAMLScalar): AnsibleRefKind? {
        // `hostgroup: web` and `hostgroup: [web]` — the convention this
        // project uses to pass a group name as a var into `import_playbook`,
        // consumed downstream as `hosts: "{{ hostgroup | default('all') }}"`.
        // The key name itself is the signal; it is used this way regardless
        // of whether it sits under a play's `vars:`, an `import_playbook`
        // step's `vars:`, or anywhere else.
        if (PlayStructure.owningKeyValue(scalar)?.keyText?.trim() == "hostgroup") {
            return AnsibleRefKind.GROUP
        }
        if (PlayStructure.owningSequenceKey(scalar) == "hostgroup") {
            return AnsibleRefKind.GROUP
        }

        // `roles: [- app]` and `dependencies: [- common]`
        PlayStructure.owningSequenceKey(scalar)?.let { key ->
            when (key) {
                in ROLE_SEQUENCE_KEYS -> return AnsibleRefKind.ROLE
                "vars_files" -> return AnsibleRefKind.PLAY_VARS_FILE
                "notify" -> return AnsibleRefKind.HANDLER
                else -> Unit
            }
        }

        // `- role: common` inside one of those sequences
        if (PlayStructure.owningKeyValue(scalar)?.keyText?.trim() == "role") {
            val seqKey = PlayStructure.owningSequenceKeyOfMappingEntry(scalar)
            if (seqKey in ROLE_SEQUENCE_KEYS) return AnsibleRefKind.ROLE
        }

        // Module argument form: `include_role:\n  name: monitoring`
        val moduleKey = PlayStructure.owningModuleKey(scalar)
        if (moduleKey != null) {
            val module = PlayStructure.bareModuleName(moduleKey)
            val arg = PlayStructure.owningKeyValue(scalar)?.keyText?.trim()
            when {
                module in ROLE_MODULES && arg == "name" -> return AnsibleRefKind.ROLE
                module in TASK_MODULES && arg == "file" -> return AnsibleRefKind.TASKS_FILE
                module in VARS_MODULES && arg == "file" -> return AnsibleRefKind.ROLE_VARS_FILE
                module in SRC_MODULES && arg == "src" -> return AnsibleRefKind.TEMPLATE
            }
        }

        // Free-form module value: `include_tasks: configure.yml`
        val freeForm = PlayStructure.freeFormModuleKey(scalar)
        if (freeForm != null) {
            when (PlayStructure.bareModuleName(freeForm)) {
                in TASK_MODULES -> return AnsibleRefKind.TASKS_FILE
                in VARS_MODULES -> return AnsibleRefKind.ROLE_VARS_FILE
                else -> Unit
            }
            if (freeForm == "notify") return AnsibleRefKind.HANDLER
            if (freeForm == "hosts" && PlayStructure.enclosingPlay(scalar) != null) {
                return AnsibleRefKind.GROUP
            }
        }
        return null
    }

    /**
     * Matches a scalar that either sits in a referencing position, or contains a
     * `{{ … }}` expression (case N13). The `{{` test is a cheap text check so
     * the common case — an ordinary scalar — costs almost nothing.
     */
    fun anyAnsibleReference(): ElementPattern<YAMLScalar> =
        PlatformPatterns.psiElement(YAMLScalar::class.java).with(
            object : PatternCondition<YAMLScalar>("ansibleReference") {
                override fun accepts(scalar: YAMLScalar, context: ProcessingContext?): Boolean =
                    classify(scalar) != null || scalar.textContains('{')
            },
        )
}
