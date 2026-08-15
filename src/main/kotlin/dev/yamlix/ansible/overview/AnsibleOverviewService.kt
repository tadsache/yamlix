package dev.yamlix.ansible.overview

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import dev.yamlix.ansible.inventory.InventoryGraph
import dev.yamlix.ansible.inventory.InventoryGraphService
import dev.yamlix.ansible.layout.AnsibleLayoutService
import dev.yamlix.ansible.psi.PlayStructure
import dev.yamlix.ansible.refs.AnsibleTargets
import dev.yamlix.ansible.vars.AnsibleVarIndex
import dev.yamlix.ansible.vars.VarScope
import dev.yamlix.ansible.vars.VariableReportBuilder
import dev.yamlix.ansible.vars.VariableResolutionService
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequence

/**
 * Builds an [AnsibleOverview] for a project.
 *
 * ### Two speeds, on purpose
 *
 * [build] is structural: inventory graphs, playbook shapes, role closures and
 * the findings that fall out of comparing names against the layout. It touches
 * no variable resolution and is fast enough to run whenever the panel opens.
 *
 * [neverWinningDefinitions] is not. Deciding that a definition never wins means
 * resolving it against every host of every inventory for every playbook that
 * reaches it — the same work Quick Documentation does, once per definition site
 * in the project rather than once. On a fleet-sized project that is tens of
 * seconds, so it takes a [ProgressIndicator], reports progress, and is only
 * ever run when someone asks for it.
 */
@Service(Service.Level.PROJECT)
class AnsibleOverviewService(private val project: Project) {

    companion object {
        fun getInstance(project: Project): AnsibleOverviewService = project.service()
    }

    /** The structural overview. Cheap enough to run on open and on refresh. */
    fun build(anchor: VirtualFile): AnsibleOverview {
        val layout = AnsibleLayoutService.getInstance(project)
        val base = layout.cfgFor(anchor)?.baseDir ?: return AnsibleOverview.empty(anchor)
        val graphs = InventoryGraphService.getInstance(project)

        val inventories = layout.inventoryRoots(anchor).map { root ->
            val graph = graphs.graphFor(root)
            InventorySummary(
                name = root.name,
                root = root,
                hosts = graph.hosts.sorted(),
                groups = graph.groups.keys.sorted().map { GroupSummary(it, graph.hostsInGroup(it).size) },
            )
        }
        val graphsByName = inventories.associate { it.name to graphs.graphFor(it.root) }

        val playbooks = layout.playbooks(anchor).map { summarise(it, graphsByName) }
        val roles = summariseRoles(anchor, playbooks)

        return AnsibleOverview(
            base = base,
            inventories = inventories,
            playbooks = playbooks,
            roles = roles,
            findings = findings(anchor, inventories, playbooks, roles),
        )
    }

    // ---- structure ----------------------------------------------------------

    private fun summarise(
        playbook: VirtualFile,
        graphs: Map<String, InventoryGraph>,
    ): PlaybookSummary {
        val psi = PsiManager.getInstance(project).findFile(playbook) as? YAMLFile
            ?: return PlaybookSummary(playbook, emptyList())
        val resolver = VariableResolutionService.getInstance(project)

        val plays = PlayStructure.plays(psi)
            .filter { it.getKeyValueByKey("hosts") != null }
            .map { play ->
                PlaySummary(
                    pattern = play.getKeyValueByKey("hosts")?.valueText?.trim().orEmpty(),
                    targeted = graphs.mapValues { (_, graph) ->
                        // Null from eligibleHosts means "cannot be evaluated",
                        // which must stay distinct from an empty match here too.
                        resolver.eligibleHosts(play, graph)
                            // A null pattern means "every host", not "unknown".
                            ?: if (play.getKeyValueByKey("hosts")?.valueText?.trim()
                                    ?.let { it == "all" || it == "*" } == true
                            ) {
                                graph.hosts
                            } else {
                                null
                            }
                    },
                    roles = staticRoles(play),
                )
            }
        return PlaybookSummary(playbook, plays)
    }

    private fun staticRoles(play: YAMLMapping): List<String> {
        val sequence = play.getKeyValueByKey("roles")?.value as? YAMLSequence ?: return emptyList()
        return sequence.items.mapNotNull { item ->
            when (val value = item.value) {
                is YAMLMapping -> value.getKeyValueByKey("role")?.valueText?.trim()
                else -> (value as? org.jetbrains.yaml.psi.YAMLScalar)?.textValue?.trim()
            }?.takeIf { it.isNotEmpty() }?.substringAfterLast('.')
        }
    }

    private fun summariseRoles(
        anchor: VirtualFile,
        playbooks: List<PlaybookSummary>,
    ): List<RoleSummary> {
        val layout = AnsibleLayoutService.getInstance(project)
        val reports = VariableReportBuilder.getInstance(project)

        val closures = playbooks.associate { it.file to reports.roleClosure(it.file) }
        val dirs = LinkedHashMap<String, VirtualFile>()
        for (searchDir in layout.roleSearchPath(anchor)) {
            for (child in searchDir.children) {
                if (child.isDirectory && isRole(child)) dirs.putIfAbsent(child.name, child)
            }
        }

        return dirs.map { (name, dir) ->
            val usedBy = playbooks.filter { name in closures.getValue(it.file) }.map { it.file }
            RoleSummary(
                name = name,
                dir = dir,
                usedBy = usedBy,
                totalTargeted = targetedBy(usedBy, playbooks, name, closures),
            )
        }.sortedBy { it.name }
    }

    /**
     * Hosts a role ever runs on: the plays that list it, across every playbook
     * that reaches it. Null when any of those plays cannot be evaluated —
     * "unknown" must not render as a number.
     */
    private fun targetedBy(
        usedBy: List<VirtualFile>,
        playbooks: List<PlaybookSummary>,
        role: String,
        closures: Map<VirtualFile, Set<String>>,
    ): Int? {
        if (usedBy.isEmpty()) return 0
        val reached = LinkedHashSet<String>()
        for (playbook in playbooks.filter { it.file in usedBy }) {
            // A role reached only through `meta` dependencies has no play
            // listing it by name; fall back to every play of that playbook.
            val plays = playbook.plays.filter { role in it.roles }
                .ifEmpty { if (role in closures.getValue(playbook.file)) playbook.plays else emptyList() }
            for (play in plays) {
                reached += play.qualifiedHosts() ?: return null
            }
        }
        return reached.size
    }

    private fun isRole(dir: VirtualFile): Boolean =
        ROLE_MARKERS.any { dir.findChild(it)?.isDirectory == true }

    // ---- cheap findings -----------------------------------------------------

    private fun findings(
        anchor: VirtualFile,
        inventories: List<InventorySummary>,
        playbooks: List<PlaybookSummary>,
        roles: List<RoleSummary>,
    ): List<Finding> {
        val out = ArrayList<Finding>()
        val allGroups = inventories.flatMapTo(HashSet()) { summary -> summary.groups.map { it.name } }
        val allHosts = inventories.flatMapTo(HashSet()) { it.hosts }

        for (inventory in inventories) {
            if (inventory.hosts.isEmpty()) {
                out += Finding(
                    FindingKind.EMPTY_INVENTORY,
                    "${inventory.name} — no hosts",
                    inventory.root,
                    detail = "The inventory parsed, but declares no hosts. Either it is a " +
                        "dynamic source the IDE cannot execute, or it is genuinely empty.",
                )
            }
        }

        for (dir in varsDirs(anchor, inventories)) {
            val wantsGroup = dir.name == "group_vars"
            for (child in dir.children) {
                val name = if (child.isDirectory) child.name else child.nameWithoutExtension
                val known = if (wantsGroup) name in allGroups else name in allHosts
                if (known) continue
                out += Finding(
                    FindingKind.ORPHAN_VARS_FILE,
                    "$name — no such ${if (wantsGroup) "group" else "host"} in any inventory",
                    child,
                    detail = "Ansible loads ${dir.name} files by name. Nothing in any inventory " +
                        "is called '$name', so this file is never read — usually a rename that " +
                        "left the vars behind, or a typo.",
                )
            }
        }

        for (role in roles) {
            if (role.usedBy.isEmpty()) {
                out += Finding(
                    FindingKind.UNUSED_ROLE,
                    "${role.name} — not reached by any playbook",
                    role.dir,
                    detail = "No playbook lists it under `roles:`, and no reached role depends " +
                        "on it via meta. It may still be used by an `include_role` this does " +
                        "not model, or by a playbook outside the scanned directories.",
                )
            }
        }

        for (playbook in playbooks) {
            for (play in playbook.plays) {
                if (play.totalTargeted == 0) {
                    out += Finding(
                        FindingKind.PLAY_TARGETS_NOTHING,
                        "${playbook.file.name} — `hosts: ${play.pattern}` matches no host",
                        playbook.file,
                        detail = "The pattern was evaluated against every inventory and matched " +
                            "nothing anywhere. Patterns that cannot be evaluated are not " +
                            "reported here.",
                    )
                }
            }
        }
        return out
    }

    private fun varsDirs(anchor: VirtualFile, inventories: List<InventorySummary>): List<VirtualFile> {
        val layout = AnsibleLayoutService.getInstance(project)
        val bases = ArrayList<VirtualFile>()
        layout.cfgFor(anchor)?.baseDir?.let(bases::add)
        bases += inventories.map { it.root }
        return bases.flatMap { base ->
            listOfNotNull(base.findChild("group_vars"), base.findChild("host_vars"))
        }.filter { it.isDirectory }
    }

    // ---- the expensive one --------------------------------------------------

    /**
     * Definitions that never win, for any host, under any playbook.
     *
     * Genuinely dead configuration: the value is written, indexed, and then
     * always beaten by something of higher precedence. That is invisible in
     * review and expensive to discover by hand, which is the whole reason to
     * offer it.
     *
     * Costs one full resolution sweep per definition site, so it is on demand
     * only and checks [indicator] between sites so it can be cancelled.
     */
    fun neverWinningDefinitions(
        anchor: VirtualFile,
        indicator: ProgressIndicator? = null,
    ): List<Finding> {
        val manager = PsiManager.getInstance(project)
        val reports = VariableReportBuilder.getInstance(project)
        val index = FileBasedIndex.getInstance()
        val scope = GlobalSearchScope.projectScope(project)

        val names = index.getAllKeys(AnsibleVarIndex.NAME, project).sorted()
        val out = ArrayList<Finding>()

        for ((position, name) in names.withIndex()) {
            indicator?.let {
                it.checkCanceled()
                it.fraction = position.toDouble() / names.size
                it.text2 = name
            }

            index.processValues(
                AnsibleVarIndex.NAME, name, null,
                { file, definitions ->
                    for (definition in definitions) {
                        // Only definitions someone wrote to take effect. A role
                        // default is *meant* to be overridden, so it losing is
                        // the design working, not a defect.
                        if (definition.scope in EXPECTED_TO_LOSE) continue
                        val psi = manager.findFile(file) ?: continue
                        val element = PsiTreeUtil.getParentOfType(
                            psi.findElementAt(definition.offset), YAMLKeyValue::class.java, false,
                        ) ?: continue

                        val scopes = reports.siteScopes(name, element)
                        val self = scopes["${file.path}#${definition.offset}"] ?: continue
                        if (self.winsOn.isNotEmpty() || self.mayWinOn.isNotEmpty()) continue

                        out += Finding(
                            FindingKind.NEVER_WINS,
                            "$name — never wins anywhere",
                            file,
                            definition.offset,
                            detail = "Defined here at ${definition.scope.display} precedence, but " +
                                "for every host of every inventory something of higher precedence " +
                                "always wins. Deleting it would change nothing.",
                        )
                    }
                    true
                },
                scope,
            )
        }
        return out
    }

    /** Runs [neverWinningDefinitions] under the platform's progress UI. */
    fun neverWinningDefinitionsWithProgress(anchor: VirtualFile): List<Finding> =
        ProgressManager.getInstance().runProcess<List<Finding>>(
            { neverWinningDefinitions(anchor, ProgressManager.getInstance().progressIndicator) },
            ProgressManager.getInstance().progressIndicator,
        )
}

private val ROLE_MARKERS = listOf("tasks", "defaults", "meta", "vars", "handlers")

/**
 * Scopes whose whole purpose is to be overridden. Reporting a role default as
 * "never wins" would flag correct, idiomatic Ansible on every project.
 */
private val EXPECTED_TO_LOSE = setOf(VarScope.ROLE_DEFAULTS)
