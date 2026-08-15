package dev.yamlix.ansible.vars

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import dev.yamlix.ansible.inventory.InventoryGraph
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
         * Guards against a pathological inventory freezing the popup.
         *
         * Real inventories seen in production (NAVIGATION-CASES.md's fixtures
         * are tiny by comparison) run into the low hundreds of hosts — a
         * fab-wide `production` inventory alone can have 200+. The cap only
         * needs to catch dynamic inventories (cloud auto-scaling groups etc.)
         * that could have thousands; it must not silently drop hosts that are
         * a completely normal size for a static inventory file.
         *
         * Since [VariableResolutionService.resolveAcross] resolves once per
         * class of equivalent hosts rather than once per host, the marginal
         * cost of a host here is a group-membership lookup, not a resolution.
         * The cap is therefore about bounding the work of *listing* hosts, not
         * about bounding resolution.
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

        /**
         * How many inventories to name before folding the rest into "+N more".
         * A site winning on one host of each of sixteen inventories otherwise
         * produced sixteen fragments on a single row.
         */
        private const val MAX_INVENTORIES_NAMED_IN_LABEL = 3


        /**
         * Scopes whose visibility is decided by the play's `hosts:` pattern,
         * and which therefore win on exactly some group's hosts.
         */
        private val PATTERN_SCOPED = setOf(
            VarScope.ROLE_DEFAULTS, VarScope.ROLE_VARS, VarScope.ROLE_PARAM,
            VarScope.PLAY_VARS, VarScope.VARS_FILE,
        )

        /** Expressions whose value can never be known without running Ansible. */
        private val RUNTIME_ONLY = listOf("hostvars", "lookup(", "query(", "vault", "ansible_facts")
    }

    fun buildAll(name: String, position: PsiElement): List<VariableReport> {
        val file = PlayStructure.sourceFile(position) ?: return emptyList()
        val playbooks = playbooksFor(file)
        if (playbooks.isEmpty()) return listOf(build(name, position, null))
        return mergeIdenticalReports(playbooks.map { build(name, position, it) })
    }

    /**
     * Collapses reports that came out the same into one, listing the playbooks
     * it holds for.
     *
     * Two playbooks that run the same role against the same hosts resolve a
     * variable identically, so rendering both produced the same table twice —
     * and a shared play imported by a dozen sites produced it a dozen times.
     * The playbook only earns a heading when it actually changes the answer.
     */
    private fun mergeIdenticalReports(reports: List<VariableReport>): List<VariableReport> =
        reports.groupBy { listOf(it.rows, it.caveats, it.magic) }
            .values
            .map { group ->
                group.first().copy(playbooks = group.flatMap { it.playbooks }.distinct())
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
            val resolutions = resolver.resolveAcross(name, root, hosts, playbook, position)
            for (host in hosts) {
                val resolution = resolutions[host] ?: continue
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
        val merged = mergeRowsAcrossInventories(rows, inventoryRoots.size)
        val magic = if (merged.all { it.kind == ValueKind.UNDEFINED }) {
            AnsibleMagicVariables.lookup(name)
        } else {
            null
        }
        return VariableReport(name, listOfNotNull(playbook), merged, caveats.toList(), magic)
    }

    /**
     * Collapses per-inventory rows into one wherever the outcome is the same in
     * every inventory the project has.
     *
     * `group_vars/all.yml` is the obvious case: sixteen inventories would
     * otherwise render sixteen identical sections for a value that never
     * varies. But it is not only whole-inventory rows that repeat. A role bound
     * to a one-host group produces two rows per inventory — the targeted host,
     * and everyone else undefined — identical in every inventory, so eight rows
     * across four environments say exactly what two say.
     *
     * Merging is by outcome, and only when a signature appears once in every
     * inventory. A row that differs anywhere keeps its own line, because that
     * difference is the entire thing a reader is looking for.
     */
    private fun mergeRowsAcrossInventories(
        rows: List<ReportRow>,
        totalInventories: Int,
    ): List<ReportRow> {
        if (totalInventories <= 1) return rows
        fun signature(row: ReportRow) =
            listOf(row.kind, row.value, row.winner?.file?.path, row.winner?.offset, row.note)

        val merged = HashSet<Int>()
        val replacements = ArrayList<Pair<Int, ReportRow>>()

        for (group in rows.withIndex().groupBy { signature(it.value) }.values) {
            // Once per inventory, in every inventory — otherwise the rows are
            // not describing the same uniform outcome.
            if (group.size != totalInventories) continue
            if (group.map { it.value.inventory }.distinct().size != totalInventories) continue

            group.forEach { merged += it.index }
            replacements += group.first().index to group.first().value.copy(
                inventory = "all inventories",
                hosts = group.flatMap { it.value.hosts }.distinct(),
                coversWholeInventory = group.all { it.value.coversWholeInventory },
            )
        }
        if (merged.isEmpty()) return rows

        // Keep each surviving row where it was, so the reading order does not
        // shuffle just because something elsewhere collapsed.
        return (rows.withIndex().filterNot { it.index in merged }.map { it.index to it.value } + replacements)
            .sortedBy { it.first }
            .map { it.second }
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
            entry.scope = site.scope
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
        // Host lists depend only on the inventory, not on the playbook being
        // swept — computing them inside the playbook loop re-sorted every
        // inventory once per playbook.
        val hostsByRoot = inventoryRoots.associateWith { root ->
            cappedHosts(graphs.graphFor(root).hosts.sorted(), contextHost)
        }
        hostsByRoot.forEach { (root, hosts) -> hostCount[root.name] = hosts.size }

        for (playbook in playbooks) {
            for (root in inventoryRoots) {
                val hosts = hostsByRoot.getValue(root)
                val resolutions = resolver.resolveAcross(name, root, hosts, playbook, position)
                for (host in hosts) {
                    val resolution = resolutions[host] ?: continue
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

        val graphsByInventory = inventoryRoots.associate { it.name to graphs.graphFor(it) }

        fun labels(source: Map<String, WinRecord>?): List<String> {
            val entries = source.orEmpty()
            if (entries.isEmpty()) return emptyList()

            // The group each entry is really about: the site's own qualifier
            // when it has one, otherwise a group whose membership is exactly
            // the set of hosts won. See [groupNamed].
            val groups = entries.mapValues { (inventory, entry) ->
                entry.groupName ?: groupNamed(graphsByInventory[inventory], entry)
            }

            // Collapsing only pays for itself once naming the inventories would
            // actually be a wall of text. In a two- or three-inventory project
            // "stag; prod" is both shorter and more informative than "all
            // inventories", so leave it alone.
            val everyInventory = allInventoryNames.size > MAX_INVENTORIES_NAMED_IN_LABEL &&
                entries.keys == allInventoryNames
            fun coversWholly(inventory: String, entry: WinRecord) =
                entry.hosts.distinct().size == hostCount[inventory]

            if (everyInventory) {
                // Consistency is decided on the raw group name — a site that is
                // `all` in every inventory is exactly the case this collapses —
                // and only the *display* drops the uninformative `all`.
                val raw = groups.values.distinct()
                if (raw.size == 1 && raw.single() != null) {
                    return listOf(labelWithGroup("all inventories", qualifying(raw.single())))
                }
                // Not a group site, but it does win on every host of every
                // inventory — `may win on env-a; env-b; env-c; env-d` said the
                // same thing four times.
                if (entries.all { (inventory, entry) -> coversWholly(inventory, entry) }) {
                    return listOf("all inventories")
                }
            }

            val labels = entries.map { (inventory, entry) ->
                val group = qualifying(groups[inventory])
                val distinctHosts = entry.hosts.distinct()
                when {
                    group != null -> labelWithGroup(inventory, group)
                    coversWholly(inventory, entry) -> inventory
                    else -> "$inventory (${capped(distinctHosts, MAX_HOSTS_NAMED_IN_LABEL)})"
                }
            }
            // The per-host cap alone does not bound this: a role that wins on
            // one host in each of sixteen inventories produced sixteen
            // parenthesised fragments on one line.
            return if (labels.size > MAX_INVENTORIES_NAMED_IN_LABEL) {
                listOf(capped(labels, MAX_INVENTORIES_NAMED_IN_LABEL, separator = "; "))
            } else {
                labels
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
        var scope: VarScope? = null
    }

    /**
     * The group worth naming in a label, or null.
     *
     * `all` is never worth naming: it is the group every host is in, so it
     * says nothing a reader did not already assume, and the site it comes from
     * is invariably a file literally called `all.yml` — which the row already
     * shows. Spelling it out produced `group_vars/all[all] … WINS on env-a
     * (all)`, the same word three times in one line.
     */
    private fun qualifying(group: String?): String? =
        group?.takeIf { it != InventoryGraph.ALL }

    /**
     * The inventory group a site's win is really describing, when its own
     * scope does not name one.
     *
     * A role's variables are scoped by the play's `hosts:` pattern, so they win
     * on precisely the hosts of some group — but the *site* is role defaults,
     * which carries a role name, not a group. Labelling it by hosts produced
     * `env-a (a-host-01); env-b (b-host-01); env-c (c-host-07); +1 more` for
     * what is simply "the containers group, everywhere".
     *
     * The group is recovered from the data rather than threaded down from the
     * play: a group whose membership is *exactly* the set of hosts won is a
     * true description of that set, whatever put it there. Ambiguity — two
     * groups with identical membership — falls back to naming the hosts rather
     * than picking one and being confidently wrong.
     */
    private fun groupNamed(graph: InventoryGraph?, record: WinRecord): String? {
        if (graph == null) return null
        // Only for sites the play's `hosts:` pattern is what scoped. A
        // `host_vars` file wins because of the host and nothing else, so
        // naming a group would be false even when the sets coincide — and they
        // do coincide: a one-host group has the same membership as that host's
        // own `host_vars`, which labelled a per-host override `stag (canary)`.
        if (record.scope !in PATTERN_SCOPED) return null
        val hosts = record.hosts.toSet()
        if (hosts.isEmpty()) return null
        return graph.groups.keys
            .filter { it != InventoryGraph.ALL && graph.hostsInGroup(it) == hosts }
            .singleOrNull()
    }

    private fun labelWithGroup(scope: String, group: String?): String =
        if (group == null) scope else "$scope ($group)"

    /** [items], with everything past [limit] folded into a "+N more" tail. */
    private fun capped(items: List<String>, limit: Int, separator: String = ", "): String {
        val shown = items.take(limit)
        val rest = items.size - shown.size
        return if (rest > 0) {
            "${shown.joinToString(separator)}$separator+$rest more"
        } else {
            shown.joinToString(separator)
        }
    }

    private fun key(site: VarSite): String = "${site.file.path}#${site.offset}"

    /**
     * The host implied by [file] itself, when it *is* a `host_vars` file.
     *
     * A large inventory truncates its host sweep to [MAX_HOSTS_PER_INVENTORY]
     * hosts (NAVIGATION-CASES.md's freeze-guard), sorted alphabetically. That
     * silently drops any host whose name sorts past the cutoff — which, in a
     * several-thousand-host dynamic inventory, is most of them. If the
     * position being resolved lives inside that very host's
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
        val value = winner?.let(::displayValue)

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

    /**
     * The winning value as text.
     *
     * The index stores a scalar or nothing, so a variable whose value is a
     * mapping or a sequence — `artifact_repo:` with nested keys — arrives here
     * as null. Reading `null` as "no static value" then labelled it
     * *registered at run time*, telling the reader a plain literal only exists
     * during the play. It is read back off the PSI instead; genuinely
     * value-less scopes are recognised by their scope, not by a null.
     */
    private fun displayValue(site: VarSite): String? {
        site.valueText?.takeIf { it.isNotBlank() }?.let { return it }
        if (site.scope == VarScope.REGISTERED) return null
        val psi = PsiManager.getInstance(project).findFile(site.file) ?: return null
        val keyValue = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
            psi.findElementAt(site.offset), org.jetbrains.yaml.psi.YAMLKeyValue::class.java, false,
        ) ?: return null
        // One line: the popup lays values out as free-flowing HTML, where the
        // source indentation of a block mapping is lost anyway.
        return keyValue.value?.text?.replace(Regex("\\s+"), " ")?.trim()?.ifBlank { null }
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
    fun roleClosure(playbook: VirtualFile): Set<String> {
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
