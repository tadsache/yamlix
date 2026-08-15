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
import java.util.Optional
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
    /** Null for a project with no inventory at all. */
    val inventoryRoot: VirtualFile?,
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
        /**
         * The host used when there is no inventory to name one.
         *
         * Never matches a `host_vars` file or a group, which is exactly right:
         * those cannot apply when nothing says which host this is.
         */
        const val NO_HOST = ""

        private const val MAX_NESTED_RESOLVE = 4

        private val FLOW_CACHE_KEY =
            Key.create<CachedValue<ConcurrentHashMap<String, PlayFlow>>>("yamlix.ansible.playFlows")

        private val DEFINITION_CACHE_KEY =
            Key.create<CachedValue<ConcurrentHashMap<String, List<IndexedDefinition>>>>(
                "yamlix.ansible.varDefinitions",
            )

        private val PLAY_SCOPE_CACHE_KEY =
            Key.create<CachedValue<ConcurrentHashMap<String, List<PlayScope>>>>(
                "yamlix.ansible.playScopes",
            )

        private val HOST_VARS_OWNER_CACHE_KEY =
            Key.create<CachedValue<ConcurrentHashMap<String, Set<String>>>>(
                "yamlix.ansible.hostVarsOwners",
            )

        private val UNINDEXED_VARS_CACHE_KEY =
            Key.create<CachedValue<ConcurrentHashMap<String, Map<String, VarDefinitionData>>>>(
                "yamlix.ansible.unindexedVarsFiles",
            )

        private val LEAD_PLAY_CACHE_KEY =
            Key.create<CachedValue<ConcurrentHashMap<String, Optional<YAMLMapping>>>>(
                "yamlix.ansible.leadPlay",
            )

        /** `group_vars`/`host_vars` next to the inventory source: the lower of the pair. */
        private const val ADJACENT_TO_INVENTORY = 0

        /** `group_vars`/`host_vars` next to the playbook: outranks the inventory's own. */
        private const val ADJACENT_TO_PLAYBOOK = 1

        /**
         * Characters that make a `hosts:` token something other than a literal
         * group or host name. `[`/`]` cover index slices (`web[0:2]`).
         */
        private val PATTERN_OPERATORS = charArrayOf('!', '&', '~', '*', '?', '[', ']')

        /** Targets Ansible provides implicitly, which appear in no inventory. */
        private val IMPLICIT_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    }

    fun resolve(name: String, context: ResolutionContext): VarResolution =
        Sweep(
            name, context.inventoryRoot, context.playbook, context.position, context.knownFacts,
        ).resolve(context.host, MAX_NESTED_RESOLVE)

    /**
     * Resolves [name] for many hosts of one inventory in a single pass.
     *
     * Callers that sweep a whole inventory — the documentation popup and the
     * "Choose Declaration" list both do — must use this rather than calling
     * [resolve] in a loop. Everything except the host itself (the index query,
     * the play's roles, its `vars_files`, its host pattern, the linearised
     * flow) is identical across the sweep, and hosts that are equivalent to
     * the resolver are resolved once and shared. On a fleet-sized project that
     * is the difference between a visible freeze and an instant popup.
     */
    fun resolveAcross(
        name: String,
        inventoryRoot: VirtualFile,
        hosts: List<String>,
        playbook: VirtualFile? = null,
        position: PsiElement? = null,
        knownFacts: Map<String, String> = emptyMap(),
    ): Map<String, VarResolution> {
        if (hosts.isEmpty()) return emptyMap()
        val sweep = Sweep(name, inventoryRoot, playbook, position, knownFacts)
        val byClass = HashMap<String, VarResolution>()
        val out = LinkedHashMap<String, VarResolution>(hosts.size)
        for (host in hosts) {
            // The key names every input `resolve` actually branches on, so a
            // cache hit is an identical answer, not a similar one.
            val resolution = byClass.getOrPut(sweep.equivalenceKey(host)) {
                sweep.resolve(host, MAX_NESTED_RESOLVE)
            }
            out[host] = resolution
        }
        return out
    }

    /**
     * What a variable resolves to in a project that has no inventory.
     *
     * Most public Ansible projects are like this — a role repository, a
     * collection, anything run with `-i` given on the command line. Resolution
     * used to be organised strictly per inventory, so those projects got no
     * answers at all: measured over eight public repositories, five reported
     * every variable in every file as undefined.
     *
     * Plenty is knowable without a host. A role's `defaults/main.yml` says what
     * it says regardless of where the role runs; so do `vars/main.yml`, a play's
     * `vars:`, and anything `set_fact` puts in scope. What is *not* knowable is
     * skipped rather than guessed: `group_vars` and `host_vars` need an
     * inventory to place them, and with none they simply do not apply.
     */
    fun resolveHostless(
        name: String,
        playbook: VirtualFile? = null,
        position: PsiElement? = null,
        knownFacts: Map<String, String> = emptyMap(),
    ): VarResolution =
        Sweep(name, null, playbook, position, knownFacts)
            .resolve(NO_HOST, MAX_NESTED_RESOLVE)

    /**
     * Tasks that produce [name] at run time, near [from].
     *
     * A variable a task `register:`s is not undefined — it simply does not
     * exist yet. Resolution proper admits one only when the play flow proves it
     * has already run, which is right for deciding a *value*; but when that
     * cannot be proved the answer is "produced by that task, at run time", not
     * "not defined in this project". On debops, which registers in one task
     * file and reads in another, that wrong answer accounted for most of what
     * the plugin could not explain.
     *
     * Restricted to the role in hand, so an unrelated role's `register:` of the
     * same name never claims authorship.
     */
    fun runtimeOrigins(name: String, from: VirtualFile?): List<VirtualFile> {
        if (from == null) return emptyList()
        val roleDir = PlayStructure.enclosingRoleDir(from)
        return definitionsFor(name)
            .filter { it.definition.scope == VarScope.REGISTERED ||
                it.definition.scope == VarScope.SET_FACT }
            .map { it.file }
            .filter { file ->
                file == from ||
                    (roleDir != null && VfsUtilCore.isAncestor(roleDir, file, false))
            }
            .distinct()
    }

    /**
     * Tasks elsewhere that bind [name] as a loop variable over the role [from]
     * belongs to.
     *
     * The counterpart to [LoopVariables.localBinding], for the case it cannot
     * see: the binding lives in the *caller*, often in a different repository
     * area entirely, and the role being read has no record of it. Matching is
     * by role directory name, which is what `include_role: name:` names.
     *
     * Returns nothing for a file outside any role — without a role there is
     * nothing to match on, and matching every loop in the project by name
     * alone would attribute one role's `user` to another's.
     */
    fun loopBindings(name: String, from: VirtualFile?): List<LoopOrigin> {
        if (from == null) return emptyList()
        val roleName = PlayStructure.enclosingRoleDir(from)?.name ?: return emptyList()
        return rawDefinitionsFor(name)
            .filter { it.definition.scope == VarScope.LOOP_VAR }
            .filter { it.definition.qualifier == roleName }
            .map { LoopOrigin(it.file, it.definition.offset, it.definition.valueText) }
            .distinctBy { it.file.path to it.offset }
    }

    /** Where a loop binding was written, and what it loops over. */
    data class LoopOrigin(val file: VirtualFile, val offset: Int, val collection: String?)

    /**
     * Definitions read straight out of a `vars_files:` the index cannot see.
     *
     * Ansible loads a `vars_files:` entry as YAML whatever it is called, and
     * projects use that: algo keeps its entire configuration in `config.cfg`,
     * a YAML mapping with an extension no IDE associates with YAML. The index
     * never saw the file, so every variable in it was undefined — and worse
     * than undefined, because the resolver then settled on whatever *did*
     * mention the name, which for `cloud_providers` was a list in
     * `tests/fixtures/`. The report went from "not defined" to a confident
     * walk into the wrong dictionary.
     *
     * Read here rather than indexed globally, and that is the whole point.
     * Which files are vars files is a fact about a *playbook*, not about a
     * path: indexing every `.cfg` in every project would invent variables out
     * of the INI files that name is usually attached to. Only a file some play
     * actually names is read, which is also what makes the ordinary
     * [VarScope.VARS_FILE] admission below correct for it.
     */
    fun unindexedVarsFileSites(name: String, playbook: VirtualFile?):
        List<Pair<VirtualFile, VarDefinitionData>> =
        unindexedVarsFileDefinitions(name, playbook).map { it.file to it.definition }

    private fun unindexedVarsFileDefinitions(
        name: String,
        playbook: VirtualFile?,
    ): List<IndexedDefinition> {
        if (playbook == null) return emptyList()
        val psi = PsiManager.getInstance(project).findFile(playbook) as? YAMLFile
            ?: return emptyList()
        val out = ArrayList<IndexedDefinition>()
        for (play in PlayStructure.plays(psi)) {
            for (file in varsFileTargets(play, playbook)) {
                if (isIndexable(file)) continue
                topLevelKeysOf(file)[name]?.let { out += IndexedDefinition(file, it) }
            }
        }
        return out
    }

    /**
     * Whether the variable index would already have seen [file].
     *
     * Deliberately mirrors [AnsibleVarIndex.getInputFilter]: reading a file the
     * index also holds would list every one of its definitions twice, and a
     * duplicate site is indistinguishable on screen from two real ones.
     */
    private fun isIndexable(file: VirtualFile): Boolean =
        file.fileType == org.jetbrains.yaml.YAMLFileType.YML ||
            VarFileRole.isIniInventory(file) ||
            VarFileRole.isExtensionlessVarFile(file)

    /**
     * Every top-level `name: value` in [file], parsed as YAML regardless of
     * what the file is called, and cached until something changes.
     */
    private fun topLevelKeysOf(file: VirtualFile): Map<String, VarDefinitionData> =
        genericCache(UNINDEXED_VARS_CACHE_KEY).getOrPut(file.path) {
            val text = com.intellij.openapi.fileEditor.impl.LoadTextUtil.loadText(file)
            val yaml = com.intellij.psi.PsiFileFactory.getInstance(project).createFileFromText(
                file.name, org.jetbrains.yaml.YAMLLanguage.INSTANCE, text,
            ) as? YAMLFile ?: return@getOrPut emptyMap()

            val out = LinkedHashMap<String, VarDefinitionData>()
            for (document in yaml.documents) {
                val mapping = document.topLevelValue as? YAMLMapping ?: continue
                for (entry in mapping.keyValues) {
                    val key = entry.keyText.trim()
                    if (key.isEmpty()) continue
                    out[key] = VarDefinitionData(
                        entry.textOffset,
                        VarScope.VARS_FILE,
                        qualifier = "",
                        valueText = (entry.value as? org.jetbrains.yaml.psi.YAMLScalar)
                            ?.textValue?.trim(),
                        guard = null,
                    )
                }
            }
            out
        }

    /** One indexed definition, hydrated with the file it came from. */
    private data class IndexedDefinition(val file: VirtualFile, val definition: VarDefinitionData)

    /**
     * The `roles:`, `vars_files:` and host pattern of a single play.
     *
     * A playbook's plays are modelled individually rather than collapsed into
     * one: a role listed by two plays with different `hosts:` patterns is in
     * scope for the union of their hosts, and asking "does any play admit this
     * role for this host" is the only way to get that right.
     */
    private class PlayScope(
        val roles: Set<String>,
        val varsFiles: Set<String>,
        /** Null when the pattern is dynamic or otherwise not safely narrowable. */
        val hosts: Set<String>?,
    ) {
        fun admits(host: String): Boolean = hosts == null || host in hosts
    }

    /**
     * One resolution query, minus the host.
     *
     * Splitting the host out is what makes [resolveAcross] possible: everything
     * held here is derived once and reused for every host in the inventory.
     */
    private inner class Sweep(
        private val name: String,
        /** Null when the project has no inventory; see [resolveHostless]. */
        private val inventoryRoot: VirtualFile?,
        private val playbook: VirtualFile?,
        private val position: PsiElement?,
        private val knownFacts: Map<String, String>,
    ) {
        val graph: InventoryGraph = inventoryRoot
            ?.let { InventoryGraphService.getInstance(project).graphFor(it) }
            ?: InventoryGraph.EMPTY
        private val definitions: List<IndexedDefinition> =
            definitionsFor(name) + unindexedVarsFileDefinitions(name, playbook)
        private val plays: List<PlayScope> = playbook
            ?.let { book -> inventoryRoot?.let { playScopesFor(book, it, graph) } }
            ?: emptyList()

        private val flow: PlayFlow? = playbook
            ?.let { book -> firstPlay(book, position)?.let { cachedFlow(book, it) } }
        private val positionIndex: Int = position?.let { flow?.indexOf(it) } ?: Int.MAX_VALUE

        /**
         * Hosts with a `host_vars` file of their own, from the filesystem
         * rather than the index — a `host_vars` definition of *any* variable
         * makes a host non-interchangeable, including one only read while
         * deciding a `when:` guard for some other name.
         */
        private val hostsWithOwnVars: Set<String> =
            inventoryRoot?.let { hostVarsOwners(it, playbook) } ?: emptySet()

        fun equivalenceKey(host: String): String {
            val own = if (host in hostsWithOwnVars) host else ""
            val admitted = plays.joinToString("") { if (it.admits(host)) "1" else "0" }
            return "${graph.groupSignature(host)}|$own|$admitted"
        }

        fun resolve(host: String, budget: Int): VarResolution {
            val caveats = ArrayList<String>()
            flow?.unexpandable?.forEach { caveats += it }

            val groupOrder = graph.groupsForHost(host)
                .withIndex().associate { (index, group) -> group.name to index }
                // With no inventory there are no groups — except `all`, which
                // every host is in by definition, so `group_vars/all` applies
                // to whatever ends up running this. Skipping it left projects
                // that keep their configuration there resolving nothing.
                .ifEmpty { if (inventoryRoot == null) mapOf(InventoryGraph.ALL to 0) else emptyMap() }
            val context = ResolutionContext(host, inventoryRoot, playbook, position, knownFacts)

            val sites = definitions.mapNotNull { (file, definition) ->
                admit(file, definition, context, groupOrder, plays, flow, positionIndex)
            }

            // Decide the `when:` guards we legitimately can.
            val decided = sites.mapNotNull { site ->
                when (evaluateGuard(site.guard, context, budget)) {
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
    }

    /**
     * Every indexed definition of [name], project-wide, cached until the PSI
     * changes.
     *
     * The index query does not depend on the host, the inventory or the
     * playbook, so a sweep across a fleet-sized project would otherwise run the
     * identical query tens of thousands of times.
     *
     * Symlink dedup happens here rather than on the resolved sites: a directory
     * symlinked into the project tree (`playbooks/foo/roles -> ../../roles`, a
     * common way to make a sub-playbook's relative role references work) gives
     * the same physical file two logical VFS paths and both get indexed.
     */
    /**
     * Definitions that compete for a value.
     *
     * [VarScope.LOOP_VAR] is filtered out here rather than at each call site so
     * that no future caller can accidentally let a loop variable take part in
     * precedence — it has no value to contribute, and one that "won" would be
     * a value that never exists. [loopBindings] is the way to ask about them.
     */
    private fun definitionsFor(name: String): List<IndexedDefinition> =
        rawDefinitionsFor(name).filter { it.definition.scope != VarScope.LOOP_VAR }

    private fun rawDefinitionsFor(name: String): List<IndexedDefinition> =
        genericCache(DEFINITION_CACHE_KEY).getOrPut(name) {
            val raw = ArrayList<IndexedDefinition>()
            FileBasedIndex.getInstance().processValues(
                AnsibleVarIndex.NAME,
                name,
                null,
                { file, definitions ->
                    definitions.forEach { raw += IndexedDefinition(file, it) }
                    true
                },
                GlobalSearchScope.allScope(project),
            )
            raw.distinctBy {
                Triple(
                    it.file.canonicalPath ?: it.file.path,
                    it.definition.offset,
                    it.definition.scope,
                )
            }
        }

    /**
     * The [PlayScope] of every play in [playbook], cached per playbook and
     * inventory.
     *
     * Resolving a role's `roles:` list walks role directories and their `meta`
     * dependencies; doing that once per host made it the dominant cost of a
     * fleet-wide sweep.
     */
    private fun playScopesFor(
        playbook: VirtualFile,
        inventoryRoot: VirtualFile,
        graph: InventoryGraph,
    ): List<PlayScope> =
        genericCache(PLAY_SCOPE_CACHE_KEY)
            .getOrPut(cacheKey(playbook.path, inventoryRoot.path)) {
            val psi = PsiManager.getInstance(project).findFile(playbook) as? YAMLFile
                ?: return@getOrPut emptyList()
            PlayStructure.plays(psi)
                // `import_playbook` steps are counted as plays by PlayStructure
                // so their `vars:` can be scoped, but they carry no `roles:`,
                // no `vars_files:` and no host pattern of their own.
                .filter { it.getKeyValueByKey("hosts") != null }
                .map { play ->
                    PlayScope(
                        roles = staticRoleNames(play),
                        varsFiles = varsFilePaths(play, playbook),
                        hosts = eligibleHosts(play, graph),
                    )
                }
        }

    /**
     * Hosts that have a `host_vars` file, looked up by path.
     *
     * Both locations Ansible loads from are consulted — next to the inventory
     * and next to the playbook — because either makes a host distinguishable
     * from its group-mates during a sweep.
     */
    private fun hostVarsOwners(inventoryRoot: VirtualFile, playbook: VirtualFile?): Set<String> =
        genericCache(HOST_VARS_OWNER_CACHE_KEY).getOrPut(
            cacheKey(inventoryRoot.path, playbook?.parent?.path ?: ""),
        ) {
            val bases = listOfNotNull(
                inventoryRoot,
                playbook?.parent,
                AnsibleLayoutService.getInstance(project).cfgFor(inventoryRoot)?.baseDir,
            )
            val owners = HashSet<String>()
            for (base in bases) {
                val dir = base.findChild("host_vars") ?: continue
                for (child in dir.children) {
                    owners += if (child.isDirectory) child.name else child.nameWithoutExtension
                }
            }
            owners
        }

    /**
     * A project-level map that empties whenever the PSI or the Ansible layout
     * changes. Everything cached through it is derived from the sources alone,
     * so staleness is impossible and the tracker pair is the whole invalidation
     * story.
     */
    /** Joins parts into a map key on a separator no VFS path can contain. */
    private fun cacheKey(vararg parts: String): String = parts.joinToString("\u0000")

    private fun <V : Any> genericCache(
        key: Key<CachedValue<ConcurrentHashMap<String, V>>>,
    ): ConcurrentHashMap<String, V> =
        CachedValuesManager.getManager(project).getCachedValue(
            project,
            key,
            {
                CachedValueProvider.Result.create(
                    ConcurrentHashMap<String, V>(),
                    PsiModificationTracker.MODIFICATION_COUNT,
                    AnsibleLayoutTracker,
                )
            },
            false,
        )

    /**
     * Decides whether a definition site applies at all. Returning null means
     * "this site exists but is not in scope here" — a different inventory, a
     * role not in the play, a `set_fact` that has not run yet.
     */
    private fun admit(
        file: VirtualFile,
        definition: VarDefinitionData,
        context: ResolutionContext,
        groupOrder: Map<String, Int>,
        plays: List<PlayScope>,
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

        /** Some play in the playbook runs against this host. */
        fun anyPlayTargetsHost(): Boolean = plays.isEmpty() || plays.any { it.admits(context.host) }

        return when (definition.scope) {
            VarScope.GROUP_VARS_ALL, VarScope.GROUP_VARS -> {
                val adjacency = varsAdjacency(file, context) ?: return null
                val order = groupOrder[definition.qualifier] ?: return null
                // Ansible orders these `inventory group_vars/all` < `playbook
                // group_vars/all` < `inventory group_vars/*` < `playbook
                // group_vars/*`. The enum rank covers the all-vs-specific half;
                // adjacency is the tie-break within it, so a playbook-adjacent
                // file beats the inventory-adjacent one for the same group
                // instead of the two landing in an arbitrary alphabetical tie.
                site(subRank = order * 2 + adjacency)
            }

            VarScope.HOST_VARS -> {
                val adjacency = varsAdjacency(file, context) ?: return null
                if (definition.qualifier != context.host) return null
                site(subRank = adjacency)
            }

            VarScope.PLAY_VARS -> {
                if (context.playbook != null && file != context.playbook) return null
                if (!anyPlayTargetsHost()) return null
                site()
            }

            VarScope.VARS_FILE -> {
                // Which files a play loads is written in the playbook and needs
                // no inventory to read. Without this fallback the whole scope
                // was unreachable on any project resolving hostlessly — a play
                // scope list is only built when there is an inventory, so an
                // empty one rejected every `vars_files:` definition rather than
                // admitting it. algo, whose entire configuration arrives that
                // way through `hosts: localhost`, resolved none of it.
                val declared = if (plays.isNotEmpty()) {
                    plays.filter { it.admits(context.host) }.flatMapTo(HashSet()) { it.varsFiles }
                } else {
                    playbookVarsFiles(context.playbook)
                }
                if (context.playbook != null && file.path !in declared) return null
                if (!anyPlayTargetsHost()) return null
                site()
            }

            // R5: role defaults and role vars of a statically listed role are
            // visible to the WHOLE play, not just inside that role — but only
            // for hosts the play actually targets. A play's `hosts:` pattern
            // can be a small group inside a much larger inventory (`hosts:
            // docker` matching one host out of hundreds); without this check
            // every host in the inventory looked "in scope" for the role's
            // vars, real Ansible never runs this role for.
            //
            // Asked per play, not against a merged role list: a playbook whose
            // first play runs `roles: [monitoring]` on `hosts: web` and whose
            // second runs the same role on `hosts: db` must put that role in
            // scope for both sets, and for neither set's hosts alone.
            VarScope.ROLE_DEFAULTS, VarScope.ROLE_VARS, VarScope.ROLE_PARAM -> {
                val rolePlays = plays.filter { it.roles.isNotEmpty() }
                when {
                    rolePlays.isNotEmpty() ->
                        if (rolePlays.none {
                                definition.qualifier in it.roles && it.admits(context.host)
                            }
                        ) {
                            return null
                        }
                    // No play declares `roles:` at all (include_role only, say);
                    // fall back to the host restriction on its own.
                    !anyPlayTargetsHost() -> return null
                }
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

            // Unreachable — `definitionsFor` filters these out before anything
            // gets here. Kept explicit rather than folded into an `else` so
            // that adding a scope later is a compile error, not a silent
            // admission of something that should never win.
            VarScope.LOOP_VAR -> null
        }
    }

    /**
     * Where a `group_vars`/`host_vars` file sits relative to [context], or null
     * when it does not apply here at all.
     *
     * Ansible loads these from two places: next to the specific inventory
     * source (`inventories/<env>/group_vars/`) and next to the playbook
     * (`playbooks/group_vars/`), the latter applying across every inventory.
     * Only the first form was recognized before — a root-level
     * `group_vars/all.yml`, which typically defines the value used in the
     * overwhelming majority of cases with per-inventory files only overriding
     * specific groups, was invisible to resolution entirely.
     *
     * The `ansible.cfg` directory is accepted alongside the playbook's own
     * directory. That is deliberately a superset of Ansible's rule: a project
     * that keeps its playbooks in `playbooks/` almost always still means a
     * root-level `group_vars/` to apply, and resolution has no playbook at all
     * when the caret sits in a plain vars file.
     *
     * @return [ADJACENT_TO_INVENTORY] or [ADJACENT_TO_PLAYBOOK] — the values
     *   order correctly as a precedence tie-break — or null when out of scope.
     */
    private fun varsAdjacency(file: VirtualFile, context: ResolutionContext): Int? {
        if (context.inventoryRoot != null &&
            VfsUtilCore.isAncestor(context.inventoryRoot, file, false)
        ) {
            return ADJACENT_TO_INVENTORY
        }
        var dir = file.parent
        while (dir != null) {
            if (dir.name == "group_vars" || dir.name == "host_vars") break
            dir = dir.parent
        }
        val holder = dir?.parent ?: return null

        val layout = AnsibleLayoutService.getInstance(project)
        val bases = listOfNotNull(
            context.playbook?.parent,
            context.inventoryRoot?.let { layout.cfgFor(it)?.baseDir },
        )
        return if (bases.any { it == holder }) ADJACENT_TO_PLAYBOOK else null
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

        // A guard reads a *different* variable, so it needs its own sweep. The
        // position is dropped: a `when:` is evaluated before the task runs, not
        // at the caret.
        val nested = Sweep(
            variable, context.inventoryRoot, context.playbook, null, context.knownFacts,
        ).resolve(context.host, budget - 1)
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

    /**
     * The play whose `roles:`/`vars_files:` apply for this query.
     *
     * `PlayStructure.plays()` counts an `import_playbook` step as a "play" too
     * (needed so `vars:` under it — e.g. `hostgroup:` — can be scoped by
     * [dev.yamlix.ansible.psi.PlayStructure.enclosingPlay]), but it never
     * carries `roles:`/`vars_files:` itself. A playbook that opens with one —
     * a common pattern in this project — would otherwise have its *real* play
     * skipped in favor of that leading step, leaving [staticRoleNames] empty
     * and silently disabling R5's role scoping: every role's defaults in the
     * whole project become "in scope" instead of just the ones actually
     * reachable from here.
     */
    private fun firstPlay(playbook: VirtualFile, position: PsiElement?): YAMLMapping? {
        // A position inside this very playbook file may sit in one of several
        // real plays; use the one that actually encloses it. Not cached — it
        // depends on the caret, and it only fires when the caret is literally
        // in this playbook.
        if (position?.containingFile?.virtualFile == playbook) {
            PlayStructure.enclosingPlay(position)
                ?.takeIf { it.getKeyValueByKey("hosts") != null }
                ?.let { return it }
        }
        // Otherwise the answer depends only on the playbook. A fleet-wide sweep
        // asks once per inventory per playbook, and this walks the file's PSI.
        return genericCache(LEAD_PLAY_CACHE_KEY).getOrPut(playbook.path) {
            val psi = PsiManager.getInstance(project).findFile(playbook) as? YAMLFile
            val plays = psi?.let(PlayStructure::plays).orEmpty()
            Optional.ofNullable(
                plays.firstOrNull { it.getKeyValueByKey("hosts") != null } ?: plays.firstOrNull(),
            )
        }.orElse(null)
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

    /**
     * Every file [playbook]'s plays name in `vars_files:`, read without an
     * inventory. Consulted when the play-scope list is empty, which is exactly
     * the case an inventory would otherwise have covered.
     */
    private fun playbookVarsFiles(playbook: VirtualFile?): Set<String> {
        val book = playbook ?: return emptySet()
        val psi = PsiManager.getInstance(project).findFile(book) as? YAMLFile
            ?: return emptySet()
        return PlayStructure.plays(psi).flatMapTo(HashSet()) { varsFilePaths(it, book) }
    }

    private fun varsFilePaths(play: YAMLMapping, playbook: VirtualFile): Set<String> =
        varsFileTargets(play, playbook).mapTo(LinkedHashSet()) { it.path }

    /** The files a play's `vars_files:` names, resolved against the playbook. */
    private fun varsFileTargets(play: YAMLMapping, playbook: VirtualFile): List<VirtualFile> {
        val sequence = play.getKeyValueByKey("vars_files")?.value as? YAMLSequence
            ?: return emptyList()
        val out = ArrayList<VirtualFile>()
        for (item in sequence.items) {
            val scalar = item.value as? org.jetbrains.yaml.psi.YAMLScalar ?: continue
            out += dev.yamlix.ansible.refs.AnsibleTargets.resolveFile(
                scalar.textValue,
                dev.yamlix.ansible.refs.AnsibleTargets.FileKind.PLAY_VARS,
                playbook,
                project,
            )
        }
        return out
    }

    /**
     * The hosts a play's `hosts:` pattern actually targets, or null when the
     * pattern is dynamic, matches everyone, or otherwise not something we can
     * be sure about without guessing.
     *
     * Ansible's host patterns support far more than this (`web:!excluded`,
     * `web:&staging`, globs, `~regex`, index slices).
     *
     * There are three outcomes, and conflating the last two is the trap:
     *
     *  - **null — cannot be evaluated.** Templated, or built from operators
     *    this has no matcher for. Do not restrict: excluding every host would
     *    make the play's roles, `vars:` and `vars_files:` resolve to nothing,
     *    and "defined nowhere" is indistinguishable from "defined but hidden".
     *  - **an empty set — evaluated, and it matches nothing here.** A literal
     *    group name that simply does not exist in *this* inventory
     *    (`hosts: legacy_hosts` against an inventory with no such group) is
     *    fully understood: the play does not run here. Restricting to nothing
     *    is the correct answer, not a guess.
     *  - **a populated set** — the ordinary case.
     */
    fun eligibleHosts(play: YAMLMapping?, graph: InventoryGraph): Set<String>? {
        val pattern = play?.getKeyValueByKey("hosts")?.valueText?.trim()
        if (pattern.isNullOrEmpty() || pattern.contains("{{")) return null
        if (pattern == InventoryGraph.ALL || pattern == "*") return null

        val tokens = pattern.split(Regex("[:,]")).map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val result = LinkedHashSet<String>()
        for (token in tokens) {
            // Exclusions and intersections change the meaning in ways a plain
            // union cannot express; globs, regexes and slices need a matcher
            // this does not have.
            if (token.any { it in PATTERN_OPERATORS }) return null
            if (token == InventoryGraph.ALL) return null
            // Ansible always supplies an implicit `localhost` that appears in
            // no inventory. The play really does run, so restricting it to the
            // inventory's hosts would be wrong in the other direction.
            if (token in IMPLICIT_HOSTS) return null

            when {
                token in graph.groups -> result += graph.hostsInGroup(token)
                token in graph.hosts -> result += token
                // A literal name this inventory does not have. Contributes no
                // hosts — which is the answer, not a reason to give up.
                else -> Unit
            }
        }
        return result
    }
}
