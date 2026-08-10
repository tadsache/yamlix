package dev.yamlix.ansible.vars

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.indexing.FileBasedIndex
import dev.yamlix.ansible.inventory.InventoryGraph
import dev.yamlix.ansible.inventory.InventoryGraphService
import dev.yamlix.ansible.layout.AnsibleLayoutService
import dev.yamlix.ansible.layout.AnsibleLayoutTracker
import dev.yamlix.ansible.psi.PlayStructure
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequence
import java.util.concurrent.ConcurrentHashMap

/**
 * Why a site is conditional. The distinction matters when listing alternatives:
 * a guarded site may simply not apply, so a lower-ranked site can still win;
 * a fact-selected site is one of a set that definitely loads, so the lower-ranked
 * site is not a possible outcome at all.
 */
enum class ConditionKind {
    NONE,

    /** A `when:` that could not be decided. The site may not apply. */
    GUARD,

    /** One of several `include_vars` candidates; exactly one of them will load. */
    FACT_SELECTED,
}

/** One definition site, hydrated with its file. */
data class VarSite(
    val file: VirtualFile,
    val offset: Int,
    val scope: VarScope,
    val qualifier: String,
    val valueText: String?,
    val guard: String?,
    /** Tie-break within a scope: group ordering, or flow order. */
    val subRank: Int,
    /**
     * True when this site only applies if a runtime condition holds — a `when:`
     * that could not be decided, or a fact-templated `include_vars` that might
     * have loaded a sibling file instead.
     */
    val conditional: Boolean,
    val conditionReason: String? = null,
    val conditionKind: ConditionKind = ConditionKind.NONE,
) {
    val rank: Int get() = scope.rank
}

/**
 * The answer to "what is this variable here".
 *
 * [sites] is ascending by precedence, matching the tables in
 * NAVIGATION-CASES.md §2. [winner] is the last unconditional site;
 * [conditionalWinner] outranks it but only fires when its condition holds.
 */
data class VarResolution(
    val name: String,
    val sites: List<VarSite>,
    val winner: VarSite?,
    val conditionalWinner: VarSite?,
    val caveats: List<String>,
) {
    val isAmbiguous: Boolean get() = conditionalWinner != null

    /** The value a user would see, when it is knowable; null when it is not. */
    val effectiveValue: String? get() = (conditionalWinner ?: winner)?.valueText
}

/**
 * Query parameters. [knownFacts] lets a caller supply facts it legitimately
 * knows (a test reproducing a verified run, or a future UI where the user picks
 * a target OS). Empty by default — the resolver never invents a fact.
 */
data class ResolutionContext(
    val host: String,
    val inventoryRoot: VirtualFile,
    val playbook: VirtualFile? = null,
    val position: PsiElement? = null,
    val knownFacts: Map<String, String> = emptyMap(),
)

/**
 * Applies Ansible's precedence rules (R3–R7) to the index and the inventory
 * graph.
 */
@Service(Service.Level.PROJECT)
class VariableResolutionService(private val project: Project) {

    companion object {
        fun getInstance(project: Project): VariableResolutionService = project.service()
        private const val MAX_NESTED_RESOLVE = 4

        private val FLOW_CACHE_KEY =
            Key.create<CachedValue<ConcurrentHashMap<String, PlayFlow>>>("yamlix.ansible.playFlows")
    }

    fun resolve(name: String, context: ResolutionContext): VarResolution =
        resolve(name, context, MAX_NESTED_RESOLVE)

    private fun resolve(name: String, context: ResolutionContext, budget: Int): VarResolution {
        val caveats = ArrayList<String>()
        val graph = InventoryGraphService.getInstance(project).graphFor(context.inventoryRoot)
        val groupOrder = graph.groupsForHost(context.host)
            .withIndex().associate { (index, group) -> group.name to index }

        val play = context.playbook?.let { firstPlay(it) }
        val flow = if (context.playbook != null && play != null) {
            cachedFlow(context.playbook, play)
        } else {
            null
        }
        flow?.unexpandable?.forEach { caveats += it }

        val positionIndex = context.position?.let { flow?.indexOf(it) } ?: Int.MAX_VALUE
        val playRoles = play?.let { staticRoleNames(it) } ?: emptySet()
        val varsFiles = play?.let { varsFilePaths(it, context.playbook!!) } ?: emptySet()

        val sites = ArrayList<VarSite>()
        FileBasedIndex.getInstance().processValues(
            AnsibleVarIndex.NAME,
            name,
            null,
            { file, definitions ->
                for (definition in definitions) {
                    admit(
                        file, definition, context, graph, groupOrder,
                        playRoles, varsFiles, flow, positionIndex,
                    )?.let(sites::add)
                }
                true
            },
            GlobalSearchScope.allScope(project),
        )

        // Decide the `when:` guards we legitimately can.
        val decided = sites.mapNotNull { site ->
            when (val verdict = evaluateGuard(site.guard, context, budget)) {
                Guard.FALSE -> null
                Guard.TRUE -> site.copy(conditional = site.conditional, conditionReason = null)
                Guard.UNKNOWN -> site.copy(
                    conditional = true,
                    conditionReason = site.conditionReason
                        ?: site.guard?.let { "guard '$it' is not statically decidable" },
                    conditionKind = if (site.conditionKind == ConditionKind.NONE) {
                        ConditionKind.GUARD
                    } else {
                        site.conditionKind
                    },
                )
            }
        }

        val ordered = decided.sortedWith(
            // File name last, so a same-rank tie is stable across index order.
            compareBy({ it.rank }, { it.subRank }, { it.file.name }),
        )
        val unconditional = ordered.lastOrNull { !it.conditional }
        val top = ordered.lastOrNull()
        val conditionalWinner = top?.takeIf {
            it.conditional && (unconditional == null || it.rank >= unconditional.rank)
        }

        conditionalWinner?.conditionReason?.let { caveats += it }
        caveats += "extra vars (-e) always win and cannot be seen from the repo"

        return VarResolution(name, ordered, unconditional, conditionalWinner, caveats.distinct())
    }

    /**
     * Decides whether a definition site applies at all. Returning null means
     * "this site exists but is not in scope here" — a different inventory, a
     * role not in the play, a `set_fact` that has not run yet.
     */
    private fun admit(
        file: VirtualFile,
        definition: VarDefinitionData,
        context: ResolutionContext,
        graph: InventoryGraph,
        groupOrder: Map<String, Int>,
        playRoles: Set<String>,
        varsFiles: Set<String>,
        flow: PlayFlow?,
        positionIndex: Int,
    ): VarSite? {
        fun site(
            subRank: Int = 0,
            conditional: Boolean = false,
            reason: String? = null,
            conditionKind: ConditionKind = ConditionKind.NONE,
        ) = VarSite(
            file, definition.offset, definition.scope, definition.qualifier,
            definition.valueText, definition.guard, subRank, conditional, reason, conditionKind,
        )

        return when (definition.scope) {
            VarScope.GROUP_VARS_ALL, VarScope.GROUP_VARS -> {
                if (!VfsUtilCore.isAncestor(context.inventoryRoot, file, false)) return null
                val order = groupOrder[definition.qualifier] ?: return null
                site(subRank = order)
            }

            VarScope.HOST_VARS -> {
                if (!VfsUtilCore.isAncestor(context.inventoryRoot, file, false)) return null
                if (definition.qualifier != context.host) return null
                site()
            }

            VarScope.PLAY_VARS -> {
                if (context.playbook != null && file != context.playbook) return null
                site()
            }

            VarScope.VARS_FILE -> {
                if (context.playbook != null && file.path !in varsFiles) return null
                site()
            }

            // R5: role defaults and role vars of a statically listed role are
            // visible to the WHOLE play, not just inside that role.
            VarScope.ROLE_DEFAULTS, VarScope.ROLE_VARS, VarScope.ROLE_PARAM -> {
                if (playRoles.isNotEmpty() && definition.qualifier !in playRoles) return null
                site()
            }

            VarScope.INCLUDE_VARS -> {
                val load = flow?.includeVarsLoads?.get(file.path) ?: return null
                if (load.step > positionIndex) return null
                if (load.siblings <= 1) return site(subRank = load.step)

                // Several files match the template. If the caller supplied the
                // facts the template needs, the answer becomes determinate;
                // otherwise every candidate stays conditional.
                when (renderTemplate(load.template, context.knownFacts)) {
                    null -> site(
                        subRank = load.step,
                        conditional = true,
                        reason = "include_vars '${load.template}' depends on a fact; " +
                            "${file.name} is one of ${load.siblings} candidates",
                        conditionKind = ConditionKind.FACT_SELECTED,
                    )
                    file.name -> site(subRank = load.step)
                    else -> null
                }
            }

            VarScope.SET_FACT, VarScope.REGISTERED -> {
                val definingStep = flow?.steps?.lastOrNull {
                    it.file == file && it.taskOffset <= definition.offset
                } ?: return null
                if (definingStep.index > positionIndex) return null
                site(subRank = definingStep.index)
            }

            VarScope.BLOCK_VARS, VarScope.TASK_VARS -> site()
        }
    }

    /**
     * Substitutes known facts into a Jinja path. Returns null when any
     * placeholder is unknown — never a partially rendered guess.
     */
    private fun renderTemplate(template: String, knownFacts: Map<String, String>): String? {
        if (!template.contains("{{")) return template
        val placeholder = Regex("""\{\{\s*([A-Za-z_][\w.]*)\s*}}""")
        var unresolved = false
        val rendered = placeholder.replace(template) { match ->
            knownFacts[match.groupValues[1]] ?: run { unresolved = true; "" }
        }
        return if (unresolved || rendered.contains("{{")) null else rendered
    }

    // ---- guards ---------------------------------------------------------------

    private enum class Guard { TRUE, FALSE, UNKNOWN }

    private val COMPARISON = Regex("""^\s*([A-Za-z_][\w.]*)\s*(==|!=)\s*['"]([^'"]*)['"]\s*$""")

    /**
     * Evaluates only the shape we can be certain about: a comparison between a
     * variable and a string literal, where the variable itself resolves
     * unconditionally. Anything else is [Guard.UNKNOWN] — a `when:` touching
     * facts, `hostvars` or a lookup is exactly what §3 says we must not guess.
     */
    private fun evaluateGuard(guard: String?, context: ResolutionContext, budget: Int): Guard {
        if (guard == null) return Guard.TRUE
        if (budget <= 0) return Guard.UNKNOWN
        val match = COMPARISON.matchEntire(guard) ?: return Guard.UNKNOWN
        val (variable, operator, literal) = match.destructured

        context.knownFacts[variable]?.let { known ->
            return verdict(known == literal, operator)
        }

        val nested = resolve(variable, context.copy(position = null), budget - 1)
        if (nested.isAmbiguous) return Guard.UNKNOWN
        val value = nested.winner?.valueText ?: return Guard.UNKNOWN
        if (value.contains("{{")) return Guard.UNKNOWN
        return verdict(value == literal, operator)
    }

    private fun verdict(equal: Boolean, operator: String): Guard =
        if ((operator == "==") == equal) Guard.TRUE else Guard.FALSE

    // ---- flow cache -----------------------------------------------------------

    /**
     * Linearising a play walks every role and every included task file. Quick
     * Documentation resolves once per host per inventory, so without this the
     * same walk would run dozens of times for one Ctrl+Q.
     */
    private fun cachedFlow(playbook: VirtualFile, play: YAMLMapping): PlayFlow =
        CachedValuesManager.getManager(project).getCachedValue(
            project,
            FLOW_CACHE_KEY,
            {
                CachedValueProvider.Result.create(
                    ConcurrentHashMap<String, PlayFlow>(),
                    PsiModificationTracker.MODIFICATION_COUNT,
                    AnsibleLayoutTracker,
                )
            },
            false,
        ).getOrPut(playbook.path) { PlayFlow.build(project, playbook, play) }

    // ---- play helpers ---------------------------------------------------------

    private fun firstPlay(playbook: VirtualFile): YAMLMapping? {
        val psi = PsiManager.getInstance(project).findFile(playbook) as? YAMLFile ?: return null
        return PlayStructure.plays(psi).firstOrNull()
    }

    private fun staticRoleNames(play: YAMLMapping): Set<String> {
        val sequence = play.getKeyValueByKey("roles")?.value as? YAMLSequence ?: return emptySet()
        val names = LinkedHashSet<String>()
        for (item in sequence.items) {
            when (val value = item.value) {
                is YAMLMapping -> value.getKeyValueByKey("role")?.valueText?.trim()
                else -> (value as? org.jetbrains.yaml.psi.YAMLScalar)?.textValue?.trim()
            }?.let { names += it.substringAfterLast('.') }
        }
        // Roles pulled in by meta dependencies are equally in the play.
        val layout = AnsibleLayoutService.getInstance(project)
        val playbookFile = play.containingFile?.virtualFile
        if (playbookFile != null) {
            val queue = ArrayDeque(names)
            while (queue.isNotEmpty()) {
                val roleName = queue.removeFirst()
                val roleDir = dev.yamlix.ansible.refs.AnsibleTargets
                    .resolveRoleDirs(roleName, playbookFile, project).firstOrNull() ?: continue
                val meta = roleDir.findFileByRelativePath("meta/main.yml") ?: continue
                val metaPsi = PsiManager.getInstance(project).findFile(meta) as? YAMLFile ?: continue
                val root = metaPsi.documents.mapNotNull { it.topLevelValue as? YAMLMapping }
                    .firstOrNull() ?: continue
                val deps = root.getKeyValueByKey("dependencies")?.value as? YAMLSequence ?: continue
                for (dep in deps.items) {
                    val depName = when (val value = dep.value) {
                        is YAMLMapping -> value.getKeyValueByKey("role")?.valueText?.trim()
                        else -> (value as? org.jetbrains.yaml.psi.YAMLScalar)?.textValue?.trim()
                    } ?: continue
                    if (names.add(depName)) queue.addLast(depName)
                }
            }
        }
        @Suppress("UNUSED_EXPRESSION") layout
        return names
    }

    private fun varsFilePaths(play: YAMLMapping, playbook: VirtualFile): Set<String> {
        val sequence = play.getKeyValueByKey("vars_files")?.value as? YAMLSequence
            ?: return emptySet()
        val paths = LinkedHashSet<String>()
        for (item in sequence.items) {
            val scalar = item.value as? org.jetbrains.yaml.psi.YAMLScalar ?: continue
            dev.yamlix.ansible.refs.AnsibleTargets.resolveFile(
                scalar.textValue,
                dev.yamlix.ansible.refs.AnsibleTargets.FileKind.PLAY_VARS,
                playbook,
                project,
            ).forEach { paths += it.path }
        }
        return paths
    }
}
