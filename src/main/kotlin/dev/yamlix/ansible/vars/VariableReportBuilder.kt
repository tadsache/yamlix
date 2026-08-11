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

        /**
         * Guards against a huge dynamic inventory freezing the popup.
         *
         * Real inventories seen in production (NAVIGATION-CASES.md's fixtures
         * are tiny by comparison) run into the low hundreds of hosts — a
         * fab-wide `production` inventory alone can have 200+. The cap only
         * needs to catch pathological/dynamic inventories (cloud auto-scaling
         * groups etc.) that could have thousands; it must not silently drop
         * hosts that are a completely normal size for a static inventory file.
         */
        const val MAX_HOSTS_PER_INVENTORY = 750

        /**
         * How many host names to spell out in a "wins on ... / may win on ..."
         * label before collapsing the rest into "+N more". A site that only
         * applies to part of an inventory can otherwise list every one of its
         * dozens of hosts, turning "Choose Declaration" into an unreadable wall
         * of names.
         */
        private const val MAX_HOSTS_NAMED_IN_LABEL = 2


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

        val contextHost = file?.let(::contextHostFor)
        val inventoryRoots = file?.let { layout.inventoryRoots(it) } ?: emptyList()
        for (root in inventoryRoots) {
            val graph = graphs.graphFor(root)
            val allHosts = graph.hosts.sorted()
            val hosts = cappedHosts(allHosts, contextHost)
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
        val merged = mergeRowsCoveringEveryInventory(rows, inventoryRoots.size)
        val magic = if (merged.all { it.kind == ValueKind.UNDEFINED }) {
            AnsibleMagicVariables.lookup(name)
        } else {
            null
        }
        return VariableReport(name, playbook, merged, caveats.toList(), magic)
    }

    /**
     * Collapses per-inventory rows into one when they are all the exact same
     * outcome for literally every inventory the project has.
     *
     * `group_vars/all.yml` is the common case: with sixteen inventories the
     * popup would otherwise render sixteen identical sections, one header per
     * inventory, for a single value that never actually varies.
     */
    private fun mergeRowsCoveringEveryInventory(
        rows: List<ReportRow>,
        totalInventories: Int,
    ): List<ReportRow> {
        if (totalInventories <= 1) return rows
        fun signature(row: ReportRow) =
            listOf(row.kind, row.value, row.winner?.file?.path, row.winner?.offset, row.note)

        val group = rows.filter { it.coversWholeInventory }
            .groupBy(::signature)
            .values
            .firstOrNull { it.size == totalInventories }
            ?: return rows

        val merged = group.first().copy(inventory = "all inventories", hosts = emptyList())
        return listOf(merged) + rows.filterNot { it in group }
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
        val wins = LinkedHashMap<String, MutableMap<String, WinRecord>>()
        val mayWin = LinkedHashMap<String, MutableMap<String, WinRecord>>()
        val hostCount = HashMap<String, Int>()

        fun record(
            into: MutableMap<String, MutableMap<String, WinRecord>>,
            site: VarSite,
            inventory: String,
            host: String,
        ) {
            val entry = into.getOrPut(key(site)) { LinkedHashMap() }
                .getOrPut(inventory) { WinRecord() }
            entry.hosts += host
            // A group_vars site applies by group membership, not by which
            // individual hosts happen to be in it — naming the group is both
            // more precise and shorter than enumerating (or capping) hosts.
            if (site.scope == VarScope.GROUP_VARS || site.scope == VarScope.GROUP_VARS_ALL) {
                entry.groupName = site.qualifier
            }
        }

        val contextHost = contextHostFor(file)
        // `playbooksFor`, not the unfiltered `playbooksOrNull`: sweeping every
        // playbook in the project regardless of whether the role we're
        // actually inside is reachable from it lets that *other* playbook's
        // own unrelated roles get admitted as "winning" candidates here too —
        // a second role defining the same variable name, used by a completely
        // different play, would otherwise show up as a false winner just
        // because some playbook happens to declare it.
        val resolvedPlaybooks = playbooksFor(file)
        val playbooks: List<VirtualFile?> = if (resolvedPlaybooks.isEmpty()) listOf(null) else resolvedPlaybooks
        val inventoryRoots = layout.inventoryRoots(file)
        for (playbook in playbooks) {
            for (root in inventoryRoots) {
                val hosts = cappedHosts(graphs.graphFor(root).hosts.sorted(), contextHost)
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
        val allInventoryNames = inventoryRoots.map { it.name }.toSet()

        fun labels(source: Map<String, WinRecord>?): List<String> {
            val entries = source.orEmpty()
            if (entries.isEmpty()) return emptyList()

            // A site that applies identically across literally every inventory
            // the project has (`group_vars/all.yml`, most commonly) otherwise
            // prints one line per inventory — "WINS on production; staging;
            // development; test; ..." for all sixteen of them — which is
            // exactly the wall of text a reader is trying to avoid. Collapse
            // it to a single summary line instead.
            //
            // Restricted to sites that carry a real, consistent group
            // qualifier (i.e. an actual `group_vars` site): a host-based site
            // that merely *happens* to win everywhere in a small, two- or
            // three-inventory project is still worth spelling out, since
            // there is no "this project" wall of text to avoid there.
            if (allInventoryNames.isNotEmpty() && entries.keys == allInventoryNames) {
                val groupNames = entries.values.map { it.groupName }.distinct()
                val group = groupNames.singleOrNull()
                if (group != null) return listOf("all inventories ($group)")
            }

            return entries.map { (inventory, entry) ->
                val group = entry.groupName
                val distinctHosts = entry.hosts.distinct()
                when {
                    group != null -> "$inventory ($group)"
                    distinctHosts.size == hostCount[inventory] -> inventory
                    else -> {
                        val shown = distinctHosts.take(MAX_HOSTS_NAMED_IN_LABEL)
                        val rest = distinctHosts.size - shown.size
                        val names = if (rest > 0) {
                            "${shown.joinToString(", ")}, +$rest more"
                        } else {
                            shown.joinToString(", ")
                        }
                        "$inventory ($names)"
                    }
                }
            }
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

    /** Per (site, inventory): the hosts it won on, and — for a group-scoped site — the group name. */
    private class WinRecord {
        val hosts = ArrayList<String>()
        var groupName: String? = null
    }

    private fun key(site: VarSite): String = "${site.file.path}#${site.offset}"

    /**
     * The host implied by [file] itself, when it *is* a `host_vars` file.
     *
     * A large inventory truncates its host sweep to [MAX_HOSTS_PER_INVENTORY]
     * hosts for performance (NAVIGATION-CASES.md's freeze-guard), sorted
     * alphabetically. That silently drops any host whose name sorts past the
     * cutoff — which, in a several-hundred-host production inventory, is most
     * of them. If the position being resolved lives inside that very host's
     * `host_vars` file, the cap must not be allowed to exclude it: the user is
     * looking directly at that host's variables, not doing a project-wide
     * sweep.
     */
    private fun contextHostFor(file: VirtualFile): String? =
        (VarFileRole.fromPath(file) as? VarFileRole.FlatVars)
            ?.takeIf { it.scope == VarScope.HOST_VARS }
            ?.qualifier

    /**
     * [allHosts], capped to [MAX_HOSTS_PER_INVENTORY] for the sweep, but with
     * [contextHost] force-included when it exists and would otherwise have
     * been cut — see [contextHostFor].
     */
    private fun cappedHosts(allHosts: List<String>, contextHost: String?): List<String> {
        val capped = allHosts.take(MAX_HOSTS_PER_INVENTORY)
        if (contextHost == null || contextHost !in allHosts || contextHost in capped) return capped
        return (capped.dropLast(1) + contextHost).sorted()
    }

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
