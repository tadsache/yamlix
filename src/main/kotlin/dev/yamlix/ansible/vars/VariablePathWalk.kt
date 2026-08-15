package dev.yamlix.ansible.vars

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLValue

/** What following a path into a definition produced. */
sealed interface PathValue {

    /** The leaf, with the exact key it was found on so it can be navigated to. */
    data class Found(
        val value: String?,
        val file: VirtualFile,
        val offset: Int,
    ) : PathValue

    /**
     * The value was reached but has no such key.
     *
     * Reported as information and never as a defect: Ansible dictionaries are
     * routinely partial, and `| default(...)` beside the use says the absence
     * was intended. Deciding which absences are bugs needs the exemptions that
     * are not built yet.
     */
    data class NoSuchKey(val file: VirtualFile, val key: String) : PathValue

    /** The value is a scalar or a list where a mapping was expected. */
    data class NotAMapping(val file: VirtualFile, val key: String) : PathValue

    /** The trail ran into something not knowable statically. */
    object Unknown : PathValue
}

/**
 * Follows `user.name` from the definition of `user` to the `name:` inside it.
 *
 * The walk deliberately starts from the *winning* definition and from nowhere
 * else. Ansible replaces dictionaries rather than merging them (barring the
 * deprecated `hash_behaviour = merge`), so if `group_vars/all.yml` defines
 * `user` with a `group` key and a `host_vars` file replaces `user` without one,
 * then `user.group` is undefined on that host — no matter that the text
 * `group:` is plainly there in the other file. Assembling a path out of every
 * definition that mentions the name, which is what indexing flat `user.group`
 * keys would amount to, produces exactly that wrong answer with full
 * confidence. Walking the winner cannot.
 *
 * [rootOf] is supplied by the caller because resolving a name needs a position,
 * a playbook and a host, all of which the caller already has; this class only
 * knows how to walk what resolution hands it.
 */
class VariablePathWalk(
    private val project: Project,
    private val rootOf: (String) -> VarSite?,
) {

    /** A single `{{ name.a.b }}`, which is the only indirection worth chasing. */
    private val SOLE_TEMPLATE = Regex("""^\{\{\s*([A-Za-z_]\w*)((?:\.\w+)*)\s*}}$""")

    fun walk(site: VarSite, segments: List<String>): PathValue =
        walkFrom(site.file, site.offset, segments, VariablePath.MAX_DEPTH)

    /**
     * The same walk, started from a definition given as a file and an offset.
     *
     * Every definition of the root is walked, not only the winning one, so the
     * precedence ladder in the tool window keeps all of its rungs when the row
     * is a path: `artifact_repo.url` still shows the host_vars value beating
     * the group_vars one, with each rung pointing at its own `url:` line.
     */
    fun walkFrom(file: VirtualFile, offset: Int, segments: List<String>): PathValue =
        walkFrom(file, offset, segments, VariablePath.MAX_DEPTH)

    private fun walkFrom(
        file: VirtualFile,
        offset: Int,
        segments: List<String>,
        budget: Int,
    ): PathValue {
        if (budget <= 0) return PathValue.Unknown
        val key = keyAt(file, offset) ?: return PathValue.Unknown
        return descend(key.value, file, key.textOffset, segments, budget)
    }

    private fun descend(
        value: YAMLValue?,
        file: VirtualFile,
        offset: Int,
        segments: List<String>,
        budget: Int,
    ): PathValue {
        if (budget <= 0) return PathValue.Unknown
        if (segments.isEmpty()) {
            return PathValue.Found((value as? YAMLScalar)?.textValue?.trim(), file, offset)
        }
        val head = segments.first()
        val rest = segments.drop(1)

        return when (value) {
            is YAMLMapping -> {
                val child = value.getKeyValueByKey(head)
                    ?: return PathValue.NoSuchKey(file, head)
                descend(child.value, file, child.textOffset, rest, budget - 1)
            }

            // `user: "{{ addusers.kube }}"` — the dictionary is somewhere else
            // entirely, and the remaining path applies to it once found. This
            // is the shape that makes the whole feature worth having: without
            // it, every parameterised role stops at the first hop.
            is YAMLScalar -> hop(value.textValue.trim(), segments, budget)

            // A list is indexable but not by name. `x.0` is legal Jinja and is
            // followed; `x.name` on a list is a mistake in the template, not a
            // missing key, so the two are told apart rather than merged.
            is YAMLSequence -> {
                val index = head.toIntOrNull() ?: return PathValue.NotAMapping(file, head)
                val item = value.items.getOrNull(index) ?: return PathValue.NoSuchKey(file, head)
                descend(item.value, file, item.textOffset, rest, budget - 1)
            }

            else -> PathValue.Unknown
        }
    }

    /**
     * Continues the walk through a value that is itself a single template.
     *
     * Anything more involved than one bare `{{ name.path }}` — a filter, a
     * concatenation, two templates in one string — is left as unknown. Its
     * value depends on Jinja evaluation, and this plugin does not evaluate.
     */
    private fun hop(text: String, segments: List<String>, budget: Int): PathValue {
        val match = SOLE_TEMPLATE.matchEntire(text) ?: return PathValue.Unknown
        val name = match.groupValues[1]
        val inner = match.groupValues[2].split('.').filter { it.isNotEmpty() }
        val site = rootOf(name) ?: return PathValue.Unknown
        return walkFrom(site.file, site.offset, inner + segments, budget - 1)
    }

    private fun keyAt(file: VirtualFile, offset: Int): YAMLKeyValue? {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val leaf = psi.findElementAt(offset) ?: return null
        return PsiTreeUtil.getParentOfType(leaf, YAMLKeyValue::class.java, false)
    }
}
