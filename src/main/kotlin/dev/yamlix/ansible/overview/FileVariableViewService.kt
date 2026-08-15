package dev.yamlix.ansible.overview

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import dev.yamlix.ansible.inventory.InventoryGraph
import dev.yamlix.ansible.inventory.InventoryGraphService
import dev.yamlix.ansible.layout.AnsibleLayoutService
import dev.yamlix.ansible.psi.PlayStructure
import dev.yamlix.ansible.refs.AnsibleVariableReference
import dev.yamlix.ansible.vars.AnsibleVarIndex
import dev.yamlix.ansible.vars.ValueKind
import dev.yamlix.ansible.vars.VarDefinitionData
import dev.yamlix.ansible.vars.VariableReport
import dev.yamlix.ansible.vars.VariableReportBuilder
import dev.yamlix.ansible.vars.VariableResolutionService
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Builds the [FileVariableView] for a file.
 *
 * Every variable in one file shares a position, so the expensive parts of
 * resolution — the play scopes, the inventory graphs, the role closure — are
 * computed once by the services underneath and reused for all of them. What is
 * left per variable is one index lookup and a sweep over its own definitions,
 * which is why a whole file costs far less than running Quick Documentation
 * once per variable would.
 *
 * Still not free, and callers render it from a background thread.
 */
@Service(Service.Level.PROJECT)
class FileVariableViewService(private val project: Project) {

    companion object {
        fun getInstance(project: Project): FileVariableViewService = project.service()

        /** Values shown on the summary line, before "…". */
        private const val MAX_SUMMARY_CHARS = 70

        /** Distinct values listed as `a · b · c` before collapsing to a count. */
        private const val MAX_DISTINCT_VALUES = 4

        /** A value longer than this is not worth showing beside its siblings. */
        private const val SIDE_BY_SIDE_CHARS = 20
    }

    fun build(file: VirtualFile): FileVariableView? {
        val layout = AnsibleLayoutService.getInstance(project)
        if (!layout.isAnsibleContext(file)) return null
        val psi = PsiManager.getInstance(project).findFile(file) as? YAMLFile ?: return null

        val reports = VariableReportBuilder.getInstance(project)
        val reachedBy = reports.playbooksFor(file)

        return FileVariableView(
            title = file.name,
            subtitle = subtitleFor(file),
            reachedBy = reachedBy,
            runsOn = runsOn(file, reachedBy),
            uses = usedVariables(psi).map { (name, usage) ->
                row(name, usage.element, usage.ranges, defined = false)
            },
            defines = definedVariables(file).map { (name, usage) ->
                row(name, usage.element, usage.ranges, defined = true)
            },
        )
    }

    // ---- header -------------------------------------------------------------

    private fun subtitleFor(file: VirtualFile): String? {
        PlayStructure.enclosingRoleDir(file)?.let { return it.name }
        val base = AnsibleLayoutService.getInstance(project).cfgFor(file)?.baseDir ?: return null
        return VfsUtilCore.getRelativePath(file, base)?.substringBeforeLast('/', "")?.ifEmpty { null }
    }

    /**
     * The hosts this file runs against, named by group where a group describes
     * them exactly.
     *
     * "runs on containers — 4 hosts" is the sentence a reader wants; the group
     * is what they think in, and the count is the blast radius. Null when any
     * reaching play has a pattern that cannot be evaluated, because a number
     * there would be a guess.
     */
    private fun runsOn(file: VirtualFile, reachedBy: List<VirtualFile>): String? {
        if (reachedBy.isEmpty()) return null
        val layout = AnsibleLayoutService.getInstance(project)
        val graphs = InventoryGraphService.getInstance(project)
        val resolver = VariableResolutionService.getInstance(project)
        val manager = PsiManager.getInstance(project)

        val roleName = PlayStructure.enclosingRoleDir(file)?.name
        val roots = layout.inventoryRoots(file)
        if (roots.isEmpty()) return null

        val hosts = LinkedHashSet<String>()
        val groups = LinkedHashSet<String>()

        for (playbook in reachedBy) {
            val playbookPsi = manager.findFile(playbook) as? YAMLFile ?: continue
            val plays = PlayStructure.plays(playbookPsi)
                .filter { it.getKeyValueByKey("hosts") != null }
                .filter { play ->
                    // Only the plays that actually run this role. A playbook's
                    // other plays say nothing about where this file executes.
                    roleName == null || roleName in staticRoleNames(play)
                }
            for (play in plays) {
                for (root in roots) {
                    val graph = graphs.graphFor(root)
                    val targeted = resolver.eligibleHosts(play, graph)
                        // A pattern that cannot be evaluated makes the whole
                        // count a guess. Say so — returning nothing would read
                        // as "this file has no host context at all".
                        ?: return "? hosts — some patterns cannot be evaluated"
                    targeted.forEach { hosts += "${root.name}/$it" }
                    groupNaming(graph, targeted)?.let { groups += it }
                }
            }
        }
        if (hosts.isEmpty()) return "no host — this file never runs"

        val count = "${hosts.size} ${if (hosts.size == 1) "host" else "hosts"}"
        val group = groups.singleOrNull()
        return if (group != null) "$group — $count" else count
    }

    /** The group whose membership is exactly [hosts], when exactly one is. */
    private fun groupNaming(graph: InventoryGraph, hosts: Set<String>): String? {
        if (hosts.isEmpty()) return null
        return graph.groups.keys
            .filter { it != InventoryGraph.ALL && graph.hostsInGroup(it) == hosts }
            .singleOrNull()
    }

    private fun staticRoleNames(play: org.jetbrains.yaml.psi.YAMLMapping): Set<String> {
        val sequence = play.getKeyValueByKey("roles")?.value as? org.jetbrains.yaml.psi.YAMLSequence
            ?: return emptySet()
        return sequence.items.mapNotNullTo(LinkedHashSet()) { item ->
            when (val value = item.value) {
                is org.jetbrains.yaml.psi.YAMLMapping -> value.getKeyValueByKey("role")?.valueText
                else -> (value as? YAMLScalar)?.textValue
            }?.trim()?.substringAfterLast('.')?.ifEmpty { null }
        }
    }

    // ---- which variables ----------------------------------------------------

    /** Where a variable appears in the file, and an element to resolve it at. */
    private class Usage(val element: com.intellij.psi.PsiElement) {
        val ranges = ArrayList<IntRange>()
    }

    /**
     * Every `{{ name }}` in the file, with *all* of its occurrences.
     *
     * All of them, not just the first: the caret has to be recognised wherever
     * the reader put it, and a variable used five times is five places they
     * might be standing.
     */
    private fun usedVariables(psi: YAMLFile): List<Pair<String, Usage>> {
        val found = LinkedHashMap<String, Usage>()
        for (scalar in PsiTreeUtil.findChildrenOfType(psi, YAMLScalar::class.java)) {
            if (!scalar.textContains('{')) continue
            for (range in AnsibleVariableReference.identifierRanges(scalar)) {
                val name = range.substring(scalar.text)
                val absolute = range.shiftRight(scalar.textOffset)
                found.getOrPut(name) { Usage(scalar) }.ranges += absolute.startOffset until absolute.endOffset
            }
        }
        return found.toList()
    }

    /** Every variable this file declares, from the index. */
    private fun definedVariables(file: VirtualFile): List<Pair<String, Usage>> {
        val data: Map<String, List<VarDefinitionData>> =
            FileBasedIndex.getInstance().getFileData(AnsibleVarIndex.NAME, file, project)
        val psi = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
        return data.entries.sortedBy { it.key }.mapNotNull { (name, definitions) ->
            val usage = definitions.mapNotNull { definition ->
                PsiTreeUtil.getParentOfType(
                    psi.findElementAt(definition.offset), YAMLKeyValue::class.java, false,
                )
            }.ifEmpty { return@mapNotNull null }
            val out = Usage(usage.first())
            usage.forEach { out.ranges += it.textRange.startOffset until it.textRange.endOffset }
            name to out
        }
    }

    // ---- one row ------------------------------------------------------------

    private fun row(
        name: String,
        element: com.intellij.psi.PsiElement,
        ranges: List<IntRange>,
        defined: Boolean,
    ): VariableRow {
        ProgressManager.checkCanceled()
        val builder = VariableReportBuilder.getInstance(project)
        val reports = builder.buildAll(name, element)
        val sites = sitesFor(name, element)

        val kinds = reports.flatMap { it.rows }.map { it.kind }.toSet()
        val magic = reports.firstNotNullOfOrNull { it.magic }

        // An ambiguous row deliberately carries no value — the candidates live
        // in its alternatives — so the values come off the sites instead.
        // Otherwise `retention_days` reads "no static value" when the whole
        // point is that it is one of 7, 14, 30 or 3.
        val values = reports.flatMap { it.rows }.mapNotNull { it.value }.distinct()
            .ifEmpty { sites.filter { it.status != SiteStatus.NOT_IN_SCOPE }.mapNotNull { it.value } }
            .distinct()

        val ownSite = if (defined) {
            sites.firstOrNull { it.file == element.containingFile?.virtualFile }
        } else {
            null
        }

        val status = when {
            defined && ownSite != null && ownSite.status == SiteStatus.OVERRIDDEN -> RowStatus.NEVER_WINS
            magic != null -> RowStatus.PROVIDED_BY_ANSIBLE
            kinds.isEmpty() || kinds == setOf(ValueKind.UNDEFINED) -> RowStatus.UNRESOLVED
            ValueKind.AMBIGUOUS in kinds -> RowStatus.AMBIGUOUS
            values.size > 1 -> RowStatus.VARIES
            else -> RowStatus.RESOLVED
        }

        return VariableRow(
            name = name,
            summary = summarise(values, status),
            status = status,
            note = note(status, reports, magic),
            sites = sites,
            ranges = ranges,
        )
    }

    private fun summarise(values: List<String>, status: RowStatus): String {
        if (status == RowStatus.PROVIDED_BY_ANSIBLE) return "provided by Ansible"
        if (status == RowStatus.UNRESOLVED) return "unresolved"
        if (values.isEmpty()) return "no static value"
        if (values.size == 1) return shorten(values.single())

        // Several short values read well side by side — `7 · 14 · 30 · 3` says
        // exactly what varies. Several long ones do not: three truncated URLs
        // are unreadable and tell you less than the bare count does. So they
        // are only listed when every one of them fits whole.
        val fitsWhole = values.all { it.length <= SIDE_BY_SIDE_CHARS }
        val joined = values.joinToString(" · ")
        return if (values.size <= MAX_DISTINCT_VALUES && fitsWhole && joined.length <= MAX_SUMMARY_CHARS) {
            joined
        } else {
            "${values.size} different values"
        }
    }

    private fun note(
        status: RowStatus,
        reports: List<VariableReport>,
        magic: dev.yamlix.ansible.vars.MagicVariable?,
    ): String? = when (status) {
        RowStatus.NEVER_WINS -> "never wins — something always overrides it"
        RowStatus.PROVIDED_BY_ANSIBLE -> magic?.description
        RowStatus.UNRESOLVED -> "not defined in this project"
        RowStatus.AMBIGUOUS ->
            reports.flatMap { it.rows }.firstNotNullOfOrNull { it.note } ?: "depends on run-time state"
        RowStatus.VARIES -> "differs by host"
        RowStatus.RESOLVED -> null
    }

    private fun shorten(value: String, max: Int = MAX_SUMMARY_CHARS): String {
        val flat = value.replace(WHITESPACE, " ").trim()
        return if (flat.length <= max) flat else flat.take(max).trimEnd() + "…"
    }

    // ---- the detail pane ----------------------------------------------------

    /**
     * Every definition site of [name], with whether it applies at [position].
     *
     * The same information the "Choose Declaration" popup shows, minus its
     * width budget: the panel has room for the full value and every inventory
     * a site holds on, so nothing here is truncated or folded into "+N more".
     */
    fun sitesFor(name: String, position: com.intellij.psi.PsiElement): List<VariableSite> {
        val builder = VariableReportBuilder.getInstance(project)
        val scopes = builder.siteScopes(name, position)
        val out = ArrayList<VariableSite>()

        FileBasedIndex.getInstance().processValues(
            AnsibleVarIndex.NAME, name, null,
            { file, definitions ->
                for (definition in definitions) {
                    val scope = scopes["${file.path}#${definition.offset}"]
                    val wins = scope?.winsOn.orEmpty()
                    val mayWin = scope?.mayWinOn.orEmpty()
                    val status = when {
                        wins.isNotEmpty() -> SiteStatus.WINS
                        mayWin.isNotEmpty() -> SiteStatus.MAY_WIN
                        scope?.inScope == true -> SiteStatus.OVERRIDDEN
                        else -> SiteStatus.NOT_IN_SCOPE
                    }
                    out += VariableSite(
                        status = status,
                        flowSensitive = definition.scope.isFlowSensitive,
                        value = definition.valueText ?: nestedValue(file, definition.offset),
                        file = file,
                        offset = definition.offset,
                        where = wins.ifEmpty { mayWin },
                        scopeLabel = definition.scope.display,
                    )
                }
                true
            },
            com.intellij.psi.search.GlobalSearchScope.allScope(project),
        )

        // Same rule as "Choose Declaration": a same-named variable in an
        // unrelated role is here only because Ansible's namespace is global,
        // and it can never be the one that applies. Flow-sensitive scopes stay,
        // because there the reason is position, not irrelevance.
        val relevant = out.filter { it.status != SiteStatus.NOT_IN_SCOPE || it.flowSensitive }
            .ifEmpty { out }

        // Winners first: the answer, then the things it beat.
        return relevant.sortedWith(compareBy({ it.status.ordinal }, { it.file.path }))
    }

    /** A mapping or sequence value, which the index does not store. */
    private fun nestedValue(file: VirtualFile, offset: Int): String? {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val keyValue = PsiTreeUtil.getParentOfType(
            psi.findElementAt(offset), YAMLKeyValue::class.java, false,
        ) ?: return null
        return keyValue.value?.text?.replace(WHITESPACE, " ")?.trim()?.ifBlank { null }
    }
}

private val WHITESPACE = Regex("\\s+")
