package dev.yamlix.ansible.refs

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.yamlix.ansible.layout.AnsibleLayoutService
import dev.yamlix.ansible.layout.AnsibleLayoutTracker
import dev.yamlix.ansible.psi.PlayStructure
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import java.util.concurrent.ConcurrentHashMap

/**
 * Variable names this project uses to carry an inventory group.
 *
 * ### The convention
 *
 * A widespread Ansible pattern parameterises a shared playbook by its target:
 *
 * ```
 * # shared/noop.yml
 * - hosts: "{{ hostgroup | default('all') }}"
 *
 * # site-container-mon.yml
 * - import_playbook: shared/noop.yml
 *   vars:
 *     hostgroup: containers      <- a group name, but Ansible syntax cannot say so
 * ```
 *
 * `containers` there is an inventory group, and a reader wants to jump to it —
 * but nothing in the *syntax* marks it as one. It is a plain string assigned to
 * a plain variable.
 *
 * ### Why this is discovered rather than configured
 *
 * The obvious alternatives are both worse. Hard-coding `hostgroup` bakes one
 * project's naming into a general-purpose plugin, and — because a value that
 * names no group would then be reported as an unresolved reference — paints
 * false errors on every project that uses the same key for something else
 * (`theforeman.foreman`'s `hostgroup:` is a Foreman entity, not an inventory
 * group). A settings list works but asks the user to restate something their
 * playbooks already say unambiguously.
 *
 * So the project is asked instead: whatever variable a `hosts:` expression
 * actually interpolates *is* a group-valued key here, whether it is called
 * `hostgroup`, `target_group` or anything else. A project that never templates
 * `hosts:` yields an empty set and is left completely alone.
 *
 * ### Deliberately narrow
 *
 * Only the leading identifier of a `hosts:` template counts, and only
 * playbooks — plus the playbooks they `import_playbook` — are scanned. Guessing
 * more aggressively would trade the one property that makes an implicit rule
 * acceptable: that it is quiet and wrong rarely, rather than loud and wrong
 * occasionally. References inferred this way never report as unresolved.
 */
@Service(Service.Level.PROJECT)
class GroupKeyConvention(private val project: Project) {

    companion object {
        fun getInstance(project: Project): GroupKeyConvention = project.service()

        private val CACHE_KEY =
            Key.create<CachedValue<ConcurrentHashMap<String, Set<String>>>>(
                "yamlix.ansible.groupKeyConvention",
            )

        /**
         * The variable a `hosts:` expression interpolates: the first identifier
         * inside `{{ … }}`, before any filter. Matches `{{ hostgroup }}` and
         * `{{ hostgroup | default('all') }}`, and deliberately not an
         * expression that starts with a literal or a function call.
         */
        private val HOSTS_TEMPLATE = Regex("""\{\{\s*([A-Za-z_]\w*)\s*(?:\||}})""")

        /** Bounds the `import_playbook` walk; playbooks nest, but not deeply. */
        private const val MAX_IMPORT_DEPTH = 3
    }

    /**
     * Group-valued variable names for the project [from] belongs to, or an
     * empty set when this project does not use the convention.
     */
    fun keys(from: VirtualFile): Set<String> {
        val base = AnsibleLayoutService.getInstance(project).cfgFor(from)?.baseDir ?: return emptySet()
        return cache().getOrPut(base.path) { discover(from) }
    }

    /** Whether [key] is used as a group name somewhere in this project. */
    fun isGroupValued(key: String, from: VirtualFile): Boolean = key in keys(from)

    /**
     * The playbooks whose `hosts:` templates produced [key].
     *
     * An inferred rule has to be answerable when someone asks "why is this a
     * group?" — this is what a caller shows them.
     */
    fun sources(key: String, from: VirtualFile): List<VirtualFile> =
        scan(from).filter { (_, keys) -> key in keys }.map { it.first }

    private fun discover(from: VirtualFile): Set<String> =
        scan(from).flatMapTo(LinkedHashSet()) { it.second }

    /** Each scanned playbook, with the group-valued keys its `hosts:` lines imply. */
    private fun scan(from: VirtualFile): List<Pair<VirtualFile, Set<String>>> {
        val layout = AnsibleLayoutService.getInstance(project)
        val manager = PsiManager.getInstance(project)

        val out = ArrayList<Pair<VirtualFile, Set<String>>>()
        val seen = HashSet<String>()
        var frontier = layout.playbooks(from)
        var depth = 0

        while (frontier.isNotEmpty() && depth <= MAX_IMPORT_DEPTH) {
            val next = ArrayList<VirtualFile>()
            for (playbook in frontier) {
                if (!seen.add(playbook.path)) continue
                val psi = manager.findFile(playbook) as? YAMLFile ?: continue
                val plays = PlayStructure.plays(psi)

                out += playbook to plays.flatMapTo(LinkedHashSet()) { keysIn(it) }
                // The convention's *consumer* is typically a shared playbook
                // pulled in by `import_playbook`, which lives outside the
                // directories `layout.playbooks()` scans.
                next += plays.mapNotNull { imported(it, playbook) }
            }
            frontier = next
            depth++
        }
        return out
    }

    private fun keysIn(play: YAMLMapping): Set<String> {
        val pattern = play.getKeyValueByKey("hosts")?.valueText ?: return emptySet()
        if (!pattern.contains("{{")) return emptySet()
        return HOSTS_TEMPLATE.findAll(pattern).mapTo(LinkedHashSet()) { it.groupValues[1] }
    }

    private fun imported(play: YAMLMapping, from: VirtualFile): VirtualFile? {
        val path = play.getKeyValueByKey("import_playbook")?.valueText?.trim()
            ?: return null
        if (path.isEmpty() || path.contains("{{")) return null
        return AnsibleTargets.resolveFile(
            path, AnsibleTargets.FileKind.TASKS, from, project,
        ).firstOrNull()
    }

    private fun cache(): ConcurrentHashMap<String, Set<String>> =
        CachedValuesManager.getManager(project).getCachedValue(
            project,
            CACHE_KEY,
            {
                CachedValueProvider.Result.create(
                    ConcurrentHashMap<String, Set<String>>(),
                    PsiModificationTracker.MODIFICATION_COUNT,
                    AnsibleLayoutTracker,
                )
            },
            false,
        )
}
