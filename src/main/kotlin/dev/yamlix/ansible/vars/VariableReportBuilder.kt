package dev.yamlix.ansible.vars

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import dev.yamlix.ansible.inventory.InventoryGraphService
import dev.yamlix.ansible.layout.AnsibleLayoutService
import dev.yamlix.ansible.psi.PlayStructure
import dev.yamlix.ansible.refs.AnsibleTargets
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence

/**
 * Turns a variable and a position into the table Quick Documentation renders:
 * one report per applicable playbook, one row per distinct outcome.
 */
@Service(Service.Level.PROJECT)
class VariableReportBuilder(private val project: Project) {

    companion object {
        fun getInstance(project: Project): VariableReportBuilder = project.service()

        /** Guards against a huge dynamic inventory freezing the popup. */
        const val MAX_HOSTS_PER_INVENTORY = 40

        /** Expressions whose value can never be known without running Ansible. */
        private val RUNTIME_ONLY = listOf("hostvars", "lookup(", "query(", "vault", "ansible_facts")
    }

    fun buildAll(name: String, position: PsiElement): List<VariableReport> {
        val file = PlayStructure.sourceFile(position) ?: return emptyList()
        val playbooks = playbooksFor(file)
        return if (playbooks.isEmpty()) {
            listOf(build(name, position, null))
        } else {
            playbooks.map { build(name, position, it) }
        }
    }

    private fun build(name: String, position: PsiElement, playbook: VirtualFile?): VariableReport {
        val file = PlayStructure.sourceFile(position)
        val layout = AnsibleLayoutService.getInstance(project)
        val graphs = InventoryGraphService.getInstance(project)
        val resolver = VariableResolutionService.getInstance(project)

        val rows = ArrayList<ReportRow>()
        val caveats = LinkedHashSet<String>()

        val inventoryRoots = file?.let { layout.inventoryRoots(it) } ?: emptyList()
        for (root in inventoryRoots) {
            val graph = graphs.graphFor(root)
            val allHosts = graph.hosts.sorted()
            val hosts = allHosts.take(MAX_HOSTS_PER_INVENTORY)
            if (hosts.size < allHosts.size) {
                caveats += "${root.name}: showing ${hosts.size} of ${allHosts.size} hosts"
            }
            if (hosts.isEmpty()) continue

            // Hosts that resolve identically collapse into one row.
            val grouped = LinkedHashMap<String, Pair<VarResolution, MutableList<String>>>()
            for (host in hosts) {
                val resolution = resolver.resolve(
                    name,
                    ResolutionContext(host, root, playbook, position),
                )
                resolution.caveats.forEach(caveats::add)
                val key = signature(resolution)
                grouped.getOrPut(key) { resolution to ArrayList() }.second += host
            }

            for ((resolution, groupHosts) in grouped.values) {
                rows += row(root.name, groupHosts, groupHosts.size == hosts.size, resolution)
            }
        }

        if (inventoryRoots.isEmpty()) {
            caveats += "no inventory found; values shown without a host context"
        }
        val magic = if (rows.all { it.kind == ValueKind.UNDEFINED }) {
            AnsibleMagicVariables.lookup(name)
        } else {
            null
        }
        return VariableReport(name, playbook, rows, caveats.toList(), magic)
    }

    /**
     * Whether a definition site applies at a given position, and where it wins.
     *
     * Navigation uses this so the "Choose Declaration" list reflects the caret's
     * position rather than being the same everywhere: a `set_fact` that has not
     * run yet is listed as out of scope, and the sites that actually win say so.
     */
    data class SiteScope(
        val inScope: Boolean,
        /** Inventories where this site definitely wins. */
        val winsOn: List<String>,
        /** Inventories where it is one of several tied candidates. */
        val mayWinOn: List<String>,
    )

    fun siteScopes(name: String, position: PsiElement): Map<String, SiteScope> {
        val file = PlayStructure.sourceFile(position) ?: return emptyMap()
        val layout = AnsibleLayoutService.getInstance(project)
        val graphs = InventoryGraphService.getInstance(project)
        val resolver = VariableResolutionService.getInstance(project)

        val inScope = HashSet<String>()
        val wins = LinkedHashMap<String, MutableMap<String, MutableList<String>>>()
        val mayWin = LinkedHashMap<String, MutableMap<String, MutableList<String>>>()
        val hostCount = HashMap<String, Int>()

        fun record(
            into: MutableMap<String, MutableMap<String, MutableList<String>>>,
            site: VarSite,
            inventory: String,
            host: String,
        ) {
            into.getOrPut(key(site)) { LinkedHashMap() }
                .getOrPut(inventory) { ArrayList() }
                .add(host)
        }

        val playbooks = layout.playbooksOrNull(file) ?: listOf(null)
        for (playbook in playbooks) {
            for (root in layout.inventoryRoots(file)) {
                val hosts = graphs.graphFor(root).hosts.sorted().take(MAX_HOSTS_PER_INVENTORY)
                hostCount[root.name] = hosts.size
                for (host in hosts) {
                    val resolution = resolver.resolve(
                        name, ResolutionContext(host, root, playbook, position),
                    )
                    resolution.sites.forEach { inScope += key(it) }

                    val top = resolution.conditionalWinner
                    if (top == null) {
                        resolution.winner?.let { record(wins, it, root.name, host) }
                        continue
                    }
                    // Ambiguous: every site tied at the top rank is a possible
                    // winner. Crowning the one that happens to sort last would be
                    // the same alphabetical guess the doc renderer refuses to make.
                    resolution.sites.filter { it.rank == top.rank }
                        .forEach { record(mayWin, it, root.name, host) }
                    if (top.conditionKind != ConditionKind.FACT_SELECTED) {
                        resolution.winner
                            ?.takeIf { it.rank < top.rank }
                            ?.let { record(mayWin, it, root.name, host) }
                    }
                }
            }
        }

        fun labels(source: Map<String, MutableList<String>>?): List<String> =
            source.orEmpty().map { (inventory, hosts) ->
                // Name the hosts only when the site does not apply to all of them.
                if (hosts.distinct().size == hostCount[inventory]) inventory
                else "$inventory (${hosts.distinct().joinToString(", ")})"
            }

        val all = inScope + wins.keys + mayWin.keys
        return all.associateWith { siteKey ->
            SiteScope(
                inScope = siteKey in inScope,
                winsOn = labels(wins[siteKey]),
                mayWinOn = labels(mayWin[siteKey]),
            )
        }
    }

    private fun key(site: VarSite): String = "${site.file.path}#${site.offset}"

    /** Two hosts share a row when the winner and the value both match. */
    private fun signature(resolution: VarResolution): String {
        val winner = resolution.conditionalWinner ?: resolution.winner
        return listOf(
            winner?.file?.path,
            winner?.offset,
            winner?.valueText,
            resolution.isAmbiguous,
        ).joinToString("|")
    }

    private fun row(
        inventory: String,
        hosts: List<String>,
        whole: Boolean,
        resolution: VarResolution,
    ): ReportRow {
        val winner = resolution.conditionalWinner ?: resolution.winner
        val value = winner?.valueText

        val kind = when {
            winner == null -> ValueKind.UNDEFINED
            resolution.isAmbiguous -> ValueKind.AMBIGUOUS
            winner.scope == VarScope.REGISTERED -> ValueKind.RUNTIME
            value == null -> ValueKind.RUNTIME
            value.contains("{{") -> ValueKind.TEMPLATE
            else -> ValueKind.LITERAL
        }

        val note = when (kind) {
            ValueKind.UNDEFINED -> AnsibleMagicVariables.lookup(resolution.name)?.description
                ?: ("not defined anywhere in this project — a gathered fact, a magic " +
                    "variable, or supplied with -e")
            ValueKind.RUNTIME ->
                "registered at run time; the value exists only during the play"
            ValueKind.TEMPLATE -> templateNote(value!!)
            ValueKind.AMBIGUOUS -> winner?.conditionReason
                ?: "several sites could win; the outcome depends on run-time state"
            ValueKind.LITERAL -> null
        }

        // For an ambiguous outcome, every possible candidate is listed and NONE
        // is promoted to "the" value. Picking one — the top of a sort, say —
        // would be a guess dressed up as an answer.
        //
        // Which candidates are possible depends on *why* it is ambiguous. A
        // fact-selected `include_vars` definitely loads exactly one of its
        // siblings, so a lower-ranked site can never win and listing it would
        // mislead. An undecided `when:` may not apply at all, so the
        // unconditional site below it is a genuine outcome.
        val alternatives = when {
            kind != ValueKind.AMBIGUOUS || winner == null -> emptyList()
            winner.conditionKind == ConditionKind.FACT_SELECTED ->
                resolution.sites.filter { it.rank == winner.rank }
            else ->
                resolution.sites.filter { it.rank == winner.rank } +
                    listOfNotNull(resolution.winner?.takeIf { it.rank < winner.rank })
        }

        return ReportRow(
            inventory = inventory,
            hosts = hosts,
            coversWholeInventory = whole,
            kind = kind,
            // Deliberately null when ambiguous: the candidates live in `alternatives`.
            value = if (kind == ValueKind.AMBIGUOUS) null else value,
            winner = winner,
            alternatives = alternatives,
            note = note,
        )
    }

    private fun templateNote(value: String): String {
        val runtime = RUNTIME_ONLY.firstOrNull { value.contains(it) }
        return if (runtime != null) {
            "value uses '$runtime', which only exists at run time — shown unexpanded"
        } else {
            "value is a Jinja template; Ansible expands it lazily at each use site"
        }
    }

    // ---- which playbook applies ----------------------------------------------

    /**
     * The playbooks a position belongs to.
     *
     * A file inside a role can be reached by several playbooks, and a
     * `group_vars` file by all of them. Rather than pick one and pretend, every
     * applicable playbook gets its own report and the caller renders them all.
     */
    fun playbooksFor(file: VirtualFile): List<VirtualFile> {
        val layout = AnsibleLayoutService.getInstance(project)
        val manager = PsiManager.getInstance(project)

        val asPlaybook = manager.findFile(file) as? YAMLFile
        if (asPlaybook != null && PlayStructure.isPlaybook(asPlaybook)) return listOf(file)

        val all = layout.playbooks(file)
        val roleDir = PlayStructure.enclosingRoleDir(file) ?: return all
        val roleName = roleDir.name
        return all.filter { playbook -> roleName in roleClosure(playbook) }.ifEmpty { all }
    }

    /** Every role a playbook pulls in, including via `meta` dependencies. */
    private fun roleClosure(playbook: VirtualFile): Set<String> {
        val manager = PsiManager.getInstance(project)
        val psi = manager.findFile(playbook) as? YAMLFile ?: return emptySet()
        val names = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()

        for (play in PlayStructure.plays(psi)) {
            val roles = play.getKeyValueByKey("roles")?.value as? YAMLSequence ?: continue
            for (item in roles.items) {
                roleNameOf(item.value)?.let { if (names.add(it)) queue += it }
            }
        }
        // Roles reached through meta dependencies and include_role are equally
        // "in" the playbook for documentation purposes.
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 200) {
            val current = queue.removeFirst()
            val dir = AnsibleTargets.resolveRoleDirs(current, playbook, project).firstOrNull()
                ?: continue
            names += dir.name
            val meta = dir.findFileByRelativePath("meta/main.yml") ?: continue
            val metaPsi = manager.findFile(meta) as? YAMLFile ?: continue
            val root = metaPsi.documents.mapNotNull { it.topLevelValue as? YAMLMapping }
                .firstOrNull() ?: continue
            val deps = root.getKeyValueByKey("dependencies")?.value as? YAMLSequence ?: continue
            for (dep in deps.items) {
                roleNameOf(dep.value)?.let { if (names.add(it)) queue += it }
            }
        }
        return names.map { it.substringAfterLast('.') }.toSet()
    }

    private fun roleNameOf(value: PsiElement?): String? = when (value) {
        is YAMLMapping -> value.getKeyValueByKey("role")?.valueText?.trim()
            ?: value.getKeyValueByKey("name")?.valueText?.trim()
        is YAMLScalar -> value.textValue.trim()
        else -> null
    }?.takeIf { it.isNotEmpty() }
}
