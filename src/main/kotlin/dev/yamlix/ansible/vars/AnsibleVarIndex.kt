package dev.yamlix.ansible.vars

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import dev.yamlix.ansible.psi.PlayStructure
import com.intellij.psi.PsiFileFactory
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence

/**
 * Maps a variable name to every place it is defined.
 *
 * The index is deliberately dumb: it records *sites*, classified by path shape,
 * with no notion of which inventory is selected or which roles are in a play.
 * All of that is precedence, and precedence is [VariableResolutionService]'s job.
 */
class AnsibleVarIndex : FileBasedIndexExtension<String, List<VarDefinitionData>>() {

    companion object {
        val NAME: ID<String, List<VarDefinitionData>> = ID.create("yamlix.ansible.vars")

        /**
         * Bump on ANY change to what is indexed or to the record format.
         * Forgetting is the classic way to ship a corrupt index.
         */
        /**
         * Bumped whenever what gets indexed changes, so an existing index is
         * rebuilt rather than trusted. Version 6 widened the filter to
         * extensionless `group_vars/all` files, which an older index never saw
         * and would otherwise keep reporting as non-existent; version 7 added
         * [VarScope.LOOP_VAR] records; version 8 stopped indexing manifests
         * such as `galaxy.yml`, whose keys an older index still holds as
         * variables.
         */
        const val VERSION = 8

        /**
         * Keys on a `roles:` entry that Ansible reads itself. Everything else
         * on the entry is a parameter handed to the role.
         *
         * A denylist rather than an allowlist because parameter names are the
         * role author's to choose; getting this list wrong invents a variable
         * at worst, where an allowlist would lose every real one.
         */
        private val ROLE_ENTRY_DIRECTIVES = setOf(
            "role", "name", "vars", "when", "tags", "apply", "delegate_to",
            "delegate_facts", "become", "become_user", "become_method", "become_flags",
            "remote_user", "connection", "port", "environment", "no_log",
            "ignore_errors", "any_errors_fatal", "run_once", "check_mode", "diff",
            "throttle", "timeout", "collections", "module_defaults",
        )

        private val SET_FACT = setOf("set_fact", "ansible.builtin.set_fact")
        private val TASK_CONTAINERS = listOf("block", "rescue", "always")
        private val TASK_PHASES = listOf("pre_tasks", "tasks", "post_tasks", "handlers")
    }

    override fun getName() = NAME
    override fun getVersion() = VERSION
    override fun dependsOnFileContent() = true
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getValueExternalizer(): DataExternalizer<List<VarDefinitionData>> =
        VarDefinitionExternalizer

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file ->
            file.fileType == YAMLFileType.YML ||
                VarFileRole.isIniInventory(file) ||
                // Ansible loads `group_vars/all` with no extension at all, and
                // the official ansible-examples repository is written that way.
                // Such a file is plain text to the IDE, so without this it was
                // never indexed and its variables did not exist.
                VarFileRole.isExtensionlessVarFile(file)
        }

    override fun getIndexer(): DataIndexer<String, List<VarDefinitionData>, FileContent> =
        DataIndexer { content ->
            val sink = LinkedHashMap<String, MutableList<VarDefinitionData>>()
            val collector = Collector(sink)
            try {
                index(content, collector)
            } catch (_: Exception) {
                // A malformed file must never break indexing for the project.
            }
            sink.mapValues { it.value.toList() }
        }

    private class Collector(private val sink: MutableMap<String, MutableList<VarDefinitionData>>) {
        fun add(
            name: String,
            offset: Int,
            scope: VarScope,
            qualifier: String,
            valueText: String?,
            guard: String? = null,
        ) {
            if (name.isBlank()) return
            sink.getOrPut(name) { ArrayList() } +=
                VarDefinitionData(offset, scope, qualifier, valueText, guard)
        }
    }

    /**
     * The file as YAML, parsing plain text as YAML when that is what it is.
     *
     * An extensionless `group_vars/all` has no YAML PSI of its own — the IDE
     * sees plain text — so one is built from the content here. Ansible reads
     * the file as YAML regardless of what it is called, and so must this.
     */
    private fun yamlOf(content: FileContent): YAMLFile? {
        (content.psiFile as? YAMLFile)?.let { return it }
        if (!VarFileRole.isExtensionlessVarFile(content.file)) return null
        return PsiFileFactory.getInstance(content.project).createFileFromText(
            content.fileName, YAMLLanguage.INSTANCE, content.contentAsText,
        ) as? YAMLFile
    }

    private fun index(content: FileContent, out: Collector) {
        val file: VirtualFile = content.file
        when (val role = VarFileRole.fromPath(file)) {
            is VarFileRole.IniInventory -> indexIni(content.contentAsText.toString(), out)
            is VarFileRole.FlatVars -> {
                val psi = yamlOf(content) ?: return
                indexFlatMapping(psi, role.scope, role.qualifier, out)
            }
            is VarFileRole.Tasks -> {
                val psi = content.psiFile as? YAMLFile ?: return
                indexTaskList(topLevelSequence(psi), file.parent?.parent?.name ?: "", out)
            }
            is VarFileRole.RoleMeta -> {
                val psi = content.psiFile as? YAMLFile ?: return
                indexRoleParams(psi, out)
            }
            VarFileRole.None -> Unit
            null -> {
                val psi = content.psiFile as? YAMLFile ?: return
                indexByStructure(psi, out)
            }
            else -> Unit
        }
    }

    /** Files whose path was not decisive: decide from shape. */
    private fun indexByStructure(psi: YAMLFile, out: Collector) {
        if (PlayStructure.isPlaybook(psi)) {
            indexPlaybook(psi, out)
            return
        }
        val sequence = topLevelSequence(psi)
        if (sequence != null) {
            indexTaskList(sequence, "", out)
            return
        }
        // A plain mapping. Only becomes variables when `vars_files` names it, so
        // it is recorded at vars_files precedence and the resolver decides
        // whether it was actually reached.
        indexFlatMapping(psi, VarScope.VARS_FILE, "", out)
    }

    private fun indexPlaybook(psi: YAMLFile, out: Collector) {
        for (play in PlayStructure.plays(psi)) {
            play.getKeyValueByKey("vars")?.let { varsKv ->
                (varsKv.value as? YAMLMapping)?.let { mapping ->
                    for (entry in mapping.keyValues) {
                        out.add(
                            entry.keyText.trim(), entry.textOffset,
                            VarScope.PLAY_VARS, "", scalarText(entry),
                        )
                    }
                }
            }
            (play.getKeyValueByKey("roles")?.value as? YAMLSequence)?.let { roles ->
                indexRoleParamSequence(roles, out)
            }
            for (phase in TASK_PHASES) {
                (play.getKeyValueByKey(phase)?.value as? YAMLSequence)?.let {
                    indexTaskList(it, "", out)
                }
            }
        }
    }

    private fun indexRoleParams(psi: YAMLFile, out: Collector) {
        val root = psi.documents.mapNotNull { it.topLevelValue as? YAMLMapping }.firstOrNull() ?: return
        (root.getKeyValueByKey("dependencies")?.value as? YAMLSequence)?.let {
            indexRoleParamSequence(it, out)
        }
    }

    /**
     * `- role: common` contributes role params at rank 21 — from its `vars:`
     * block *and* from keys written directly on the entry.
     *
     * Both forms are ordinary Ansible and the inline one is the older and
     * commoner of the two:
     *
     * ```yaml
     * dependencies:
     *   - role: adduser
     *     user: "{{ addusers.etcd }}"      # inline
     *   - role: adduser
     *     vars:
     *       user: "{{ addusers.kube }}"    # nested
     * ```
     *
     * Reading only the nested form left roles whose entire interface is passed
     * inline with no definition anywhere: kubespray's `adduser` reads `user` in
     * every task of the role and nothing in the repository appeared to define
     * it, so all of it reported as "not defined in this project". Anything that
     * is a directive rather than a parameter is excluded by name — that list is
     * finite and documented, while parameter names are arbitrary.
     */
    private fun indexRoleParamSequence(sequence: YAMLSequence, out: Collector) {
        for (item in sequence.items) {
            val mapping = item.value as? YAMLMapping ?: continue
            val roleName = mapping.getKeyValueByKey("role")?.valueText?.trim()
                ?: mapping.getKeyValueByKey("name")?.valueText?.trim()
                ?: ""

            val inline = mapping.keyValues.filter { it.keyText.trim() !in ROLE_ENTRY_DIRECTIVES }
            val nested = (mapping.getKeyValueByKey("vars")?.value as? YAMLMapping)?.keyValues
                .orEmpty()
            for (entry in inline + nested) {
                out.add(
                    entry.keyText.trim(), entry.textOffset,
                    VarScope.ROLE_PARAM, roleName, scalarText(entry),
                )
            }
        }
    }

    private fun indexFlatMapping(
        psi: YAMLFile,
        scope: VarScope,
        qualifier: String,
        out: Collector,
    ) {
        for (document in psi.documents) {
            val mapping = document.topLevelValue as? YAMLMapping ?: continue
            for (entry in mapping.keyValues) {
                out.add(entry.keyText.trim(), entry.textOffset, scope, qualifier, scalarText(entry))
            }
        }
    }

    private fun indexTaskList(sequence: YAMLSequence?, roleName: String, out: Collector) {
        if (sequence == null) return
        for (item in sequence.items) {
            val task = item.value as? YAMLMapping ?: continue
            indexTask(task, roleName, out)
        }
    }

    private fun indexTask(task: YAMLMapping, roleName: String, out: Collector) {
        val guard = task.getKeyValueByKey("when")?.valueText?.trim()?.ifBlank { null }

        // A loop that hands its variable to an included role is the only way a
        // role's own files can be searched for the binding: it is written in
        // the caller and read in the callee, with nothing in between. Bindings
        // that stay inside one file are left out on purpose — the PSI answers
        // those exactly, including where they stop applying, which an index
        // record cannot.
        LoopVariables.bindingOf(task)?.let { binding ->
            binding.targetRole?.let { target ->
                out.add(
                    binding.varName, task.textOffset, VarScope.LOOP_VAR,
                    target, binding.collection, guard,
                )
            }
        }

        for (kv in task.keyValues) {
            val key = kv.keyText.trim()
            when {
                key in SET_FACT -> {
                    val mapping = kv.value as? YAMLMapping ?: continue
                    for (fact in mapping.keyValues) {
                        val name = fact.keyText.trim()
                        if (name == "cacheable") continue
                        out.add(
                            name, fact.textOffset, VarScope.SET_FACT,
                            roleName, scalarText(fact), guard,
                        )
                    }
                }

                key == "register" -> {
                    val name = kv.valueText.trim()
                    out.add(name, kv.textOffset, VarScope.REGISTERED, roleName, null, guard)
                }

                key == "vars" -> {
                    val mapping = kv.value as? YAMLMapping ?: continue
                    for (entry in mapping.keyValues) {
                        out.add(
                            entry.keyText.trim(), entry.textOffset, VarScope.TASK_VARS,
                            roleName, scalarText(entry), guard,
                        )
                    }
                }

                key in TASK_CONTAINERS -> indexTaskList(kv.value as? YAMLSequence, roleName, out)
            }
        }
    }

    // ---- INI inventories ----------------------------------------------------

    /**
     * Parses `[group:vars]`, `[all:vars]` and inline host variables.
     *
     * Hand-rolled rather than reusing the platform's Properties support: INI
     * inventories are not `.properties`, section headers carry meaning, and the
     * indexer must stay free of PSI for non-YAML input.
     */
    private fun indexIni(text: String, out: Collector) {
        var section = ""
        var kind = "hosts"
        var offset = 0

        for (rawLine in text.split('\n')) {
            val lineStart = offset
            offset += rawLine.length + 1
            val line = rawLine.substringBefore('#').substringBefore(';').trim()
            if (line.isEmpty()) continue

            if (line.startsWith('[') && line.endsWith(']')) {
                val header = line.substring(1, line.length - 1).trim()
                section = header.substringBefore(':')
                kind = header.substringAfter(':', "hosts")
                continue
            }

            val indent = rawLine.indexOf(line.first())
            when (kind) {
                "vars" -> {
                    val eq = line.indexOf('=')
                    if (eq <= 0) continue
                    val name = line.substring(0, eq).trim()
                    val scope =
                        if (section == "all") VarScope.GROUP_VARS_ALL else VarScope.GROUP_VARS
                    out.add(
                        name, lineStart + indent, scope, section,
                        line.substring(eq + 1).trim(),
                    )
                }

                "hosts" -> {
                    // `host key=value key=value`
                    val tokens = line.split(Regex("\\s+"))
                    val host = tokens.firstOrNull() ?: continue
                    var cursor = lineStart + indent + host.length
                    for (token in tokens.drop(1)) {
                        val eq = token.indexOf('=')
                        val tokenStart = rawLine.indexOf(token, cursor - lineStart).let {
                            if (it < 0) cursor else lineStart + it
                        }
                        cursor = tokenStart + token.length
                        if (eq <= 0) continue
                        out.add(
                            token.substring(0, eq), tokenStart, VarScope.HOST_VARS,
                            host, token.substring(eq + 1),
                        )
                    }
                }
            }
        }
    }

    // ---- helpers -------------------------------------------------------------

    private fun topLevelSequence(psi: YAMLFile): YAMLSequence? =
        psi.documents.firstNotNullOfOrNull { it.topLevelValue as? YAMLSequence }

    private fun scalarText(kv: YAMLKeyValue): String? =
        (kv.value as? YAMLScalar)?.textValue?.trim()
}

/** Convenience for the resolution service. */
internal val PsiFile.asYaml: YAMLFile? get() = this as? YAMLFile
