package dev.yamlix.ansible.vars

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import dev.yamlix.ansible.psi.PlayStructure
import dev.yamlix.ansible.refs.AnsibleTargets
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * One executed task, in play order.
 *
 * [roleName] is the role whose tasks file this came from, or null for the play's
 * own `pre_tasks` / `tasks` / `post_tasks`.
 */
data class FlowStep(
    val index: Int,
    val file: VirtualFile,
    val taskOffset: Int,
    val roleName: String?,
)

/**
 * The linear execution order of a play — rule R7.
 *
 * ```
 * pre_tasks
 *   + for each role: meta dependencies (depth first) then its tasks
 *   + tasks + post_tasks
 * ```
 *
 * `import_tasks`/`include_tasks` with a literal path and `include_role`/
 * `import_role` with a literal name are expanded in place, so a position inside
 * an included file gets a real flow index. A templated include cannot be
 * expanded and is recorded in [unexpandable] rather than silently dropped.
 */
/**
 * A file that an `include_vars` may load.
 *
 * [template] is the path as written. When it contains Jinja, this file is one of
 * [siblings] candidates and only a known fact can say which one actually loads.
 */
data class IncludeVarsLoad(
    val step: Int,
    val template: String,
    val siblings: Int,
)

class PlayFlow(
    val steps: List<FlowStep>,
    /** Files loaded by `include_vars`, keyed by path. */
    val includeVarsLoads: Map<String, IncludeVarsLoad>,
    /** Descriptions of includes that could not be expanded statically. */
    val unexpandable: List<String>,
) {

    /** The flow index of the task containing [element], or null if outside the play. */
    fun indexOf(element: PsiElement): Int? {
        val file = PlayStructure.sourceFile(element) ?: return null
        val task = enclosingTaskOffset(element) ?: return null
        return steps.firstOrNull { it.file == file && it.taskOffset == task }?.index
            // Position is in a file the flow reached, but on a non-task line
            // (a `vars:` block, the file header). Fall back to the first step
            // in that file so the answer is still ordered sensibly.
            ?: steps.firstOrNull { it.file == file }?.index
    }

    private fun enclosingTaskOffset(element: PsiElement): Int? {
        var item = PsiTreeUtil.getParentOfType(element, YAMLSequenceItem::class.java, false)
        var last: YAMLSequenceItem? = null
        while (item != null) {
            last = item
            item = PsiTreeUtil.getParentOfType(item, YAMLSequenceItem::class.java, true)
        }
        val outermost = last ?: return null
        // The task is the outermost sequence item unless we are inside a block,
        // in which case the innermost item is the real task.
        val innermost = PsiTreeUtil.getParentOfType(element, YAMLSequenceItem::class.java, false)
        val candidate = innermost ?: outermost
        return (candidate.value as? YAMLMapping)?.textOffset
    }

    companion object {

        private val INCLUDE_TASKS = setOf("include_tasks", "import_tasks", "include")
        private val INCLUDE_ROLE = setOf("include_role", "import_role")
        private val INCLUDE_VARS = setOf("include_vars")
        private const val MAX_DEPTH = 12

        /** Builds the flow for the first play of [playbook] that matches [play]. */
        fun build(project: Project, playbook: VirtualFile, play: YAMLMapping): PlayFlow =
            Builder(project, playbook).build(play)
    }

    private class Builder(private val project: Project, private val playbook: VirtualFile) {

        private val steps = ArrayList<FlowStep>()
        private val includeVarsLoads = LinkedHashMap<String, IncludeVarsLoad>()
        private val unexpandable = ArrayList<String>()
        private val roleStack = ArrayList<String>()

        fun build(play: YAMLMapping): PlayFlow {
            emitPhase(play, "pre_tasks")
            (play.getKeyValueByKey("roles")?.value as? YAMLSequence)?.items?.forEach { item ->
                roleNameOf(item)?.let { emitRole(it, playbook, 0) }
            }
            emitPhase(play, "tasks")
            emitPhase(play, "post_tasks")
            return PlayFlow(steps, includeVarsLoads, unexpandable)
        }

        private fun emitPhase(play: YAMLMapping, phase: String) {
            val sequence = play.getKeyValueByKey(phase)?.value as? YAMLSequence ?: return
            emitTasks(sequence, playbook, null, 0)
        }

        private fun roleNameOf(item: YAMLSequenceItem): String? =
            when (val value = item.value) {
                is YAMLMapping -> value.getKeyValueByKey("role")?.valueText?.trim()
                    ?: value.getKeyValueByKey("name")?.valueText?.trim()
                else -> (value as? org.jetbrains.yaml.psi.YAMLScalar)?.textValue?.trim()
            }?.takeIf { it.isNotEmpty() }

        private fun emitRole(name: String, from: VirtualFile, depth: Int) {
            if (depth > MAX_DEPTH) return
            if (name.contains("{{")) {
                unexpandable += "templated role name '$name'"
                return
            }
            if (name in roleStack) return // dependency cycle guard
            val roleDir = AnsibleTargets.resolveRoleDirs(name, from, project).firstOrNull() ?: return

            roleStack += name
            try {
                // meta dependencies run before the role's own tasks
                roleDir.findFileByRelativePath("meta/main.yml")?.let { metaFile ->
                    val meta = psi(metaFile) ?: return@let
                    val root = meta.documents.mapNotNull { it.topLevelValue as? YAMLMapping }
                        .firstOrNull() ?: return@let
                    (root.getKeyValueByKey("dependencies")?.value as? YAMLSequence)
                        ?.items?.forEach { dep ->
                            roleNameOf(dep)?.let { emitRole(it, metaFile, depth + 1) }
                        }
                }
                val tasksFile = roleDir.findFileByRelativePath("tasks/main.yml")
                    ?: roleDir.findFileByRelativePath("tasks/main.yaml")
                if (tasksFile != null) emitTaskFile(tasksFile, name, depth + 1)
            } finally {
                roleStack.removeAt(roleStack.lastIndex)
            }
        }

        private fun emitTaskFile(file: VirtualFile, roleName: String?, depth: Int) {
            if (depth > MAX_DEPTH) return
            val yaml = psi(file) ?: return
            val sequence = yaml.documents.firstNotNullOfOrNull {
                it.topLevelValue as? YAMLSequence
            } ?: return
            emitTasks(sequence, file, roleName, depth)
        }

        private fun emitTasks(
            sequence: YAMLSequence,
            file: VirtualFile,
            roleName: String?,
            depth: Int,
        ) {
            if (depth > MAX_DEPTH) return
            for (item in sequence.items) {
                val task = item.value as? YAMLMapping ?: continue
                steps += FlowStep(steps.size, file, task.textOffset, roleName)
                expand(task, file, roleName, depth)
            }
        }

        private fun expand(
            task: YAMLMapping,
            file: VirtualFile,
            roleName: String?,
            depth: Int,
        ) {
            for (kv in task.keyValues) {
                val key = PlayStructure.bareModuleName(kv.keyText.trim())
                val argument = (kv.value as? YAMLMapping)

                when (key) {
                    in INCLUDE_TASKS -> {
                        val path = argument?.getKeyValueByKey("file")?.valueText?.trim()
                            ?: kv.valueText.trim()
                        if (path.contains("{{")) {
                            unexpandable += "templated include_tasks '$path'"
                        } else {
                            AnsibleTargets.resolveFile(
                                path, AnsibleTargets.FileKind.TASKS, file, project,
                            ).firstOrNull()?.let { emitTaskFile(it, roleName, depth + 1) }
                        }
                    }

                    in INCLUDE_ROLE -> {
                        val name = argument?.getKeyValueByKey("name")?.valueText?.trim()
                        if (name != null) emitRole(name, file, depth + 1)
                    }

                    in INCLUDE_VARS -> {
                        val literalPath = argument?.getKeyValueByKey("file")?.valueText?.trim()
                            ?: kv.valueText.trim()
                        val templates = loopTemplatesFor(literalPath, task) ?: listOf(literalPath)
                        val kind = if (roleName != null) {
                            AnsibleTargets.FileKind.ROLE_VARS
                        } else {
                            AnsibleTargets.FileKind.PLAY_VARS
                        }

                        val stepIndex = steps.lastIndex
                        val perTemplateTargets = templates.map {
                            it to AnsibleTargets.resolveFile(it, kind, file, project)
                        }
                        val totalTargets = perTemplateTargets.sumOf { it.second.size }
                        if (templates.any(AnsibleTargets::isTemplated)) {
                            unexpandable += "fact-templated include_vars '${templates.joinToString(", ")}'"
                        }
                        perTemplateTargets.forEach { (template, targets) ->
                            targets.forEach {
                                includeVarsLoads.putIfAbsent(
                                    it.path,
                                    IncludeVarsLoad(stepIndex, template, totalTargets),
                                )
                            }
                        }
                    }

                    "block", "rescue", "always" ->
                        (kv.value as? YAMLSequence)?.let { emitTasks(it, file, roleName, depth + 1) }
                }
            }
        }

        private fun psi(file: VirtualFile): YAMLFile? =
            PsiManager.getInstance(project).findFile(file) as? YAMLFile

        /**
         * `include_vars: "{{ item }}"` paired with `with_first_found:` (or
         * `with_items:` / `loop:`) is the standard "load whichever file
         * matches" idiom — the value Ansible actually loads is one of the
         * loop's entries, never the loop-variable placeholder itself.
         *
         * Returns null when [includeVarsValue] is not a bare loop placeholder,
         * so the caller falls back to treating it as a literal/templated path
         * as before.
         */
        private fun loopTemplatesFor(includeVarsValue: String, task: YAMLMapping): List<String>? {
            if (!includeVarsValue.contains("{{")) return null
            val loopKey = task.getKeyValueByKey("with_first_found")
                ?: task.getKeyValueByKey("with_items")
                ?: task.getKeyValueByKey("loop")
                ?: return null

            fun scalarsOf(sequence: YAMLSequence): List<String> = sequence.items.mapNotNull { item ->
                (item.value as? YAMLScalar)?.textValue?.trim()
            }

            val entries = when (val v = loopKey.value) {
                is YAMLSequence -> v.items.flatMap { item ->
                    when (val itemValue = item.value) {
                        is YAMLScalar -> listOf(itemValue.textValue.trim())
                        is YAMLMapping ->
                            (itemValue.getKeyValueByKey("files")?.value as? YAMLSequence)
                                ?.let(::scalarsOf).orEmpty()
                        else -> emptyList()
                    }
                }
                else -> emptyList()
            }
            return entries.filter { it.isNotEmpty() }.ifEmpty { null }
        }
    }
}
