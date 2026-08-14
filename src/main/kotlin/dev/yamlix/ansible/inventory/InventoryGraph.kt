package dev.yamlix.ansible.inventory

import java.util.concurrent.ConcurrentHashMap

/**
 * One inventory's host/group topology.
 *
 * Encodes rule R3 from NAVIGATION-CASES.md §4, including the finding that
 * `ansible_group_priority` is only honoured when it comes from the inventory
 * *source* — see [InventoryGroup.priority].
 */
data class InventoryGroup(
    val name: String,
    /**
     * Ansible's group depth. Not the shortest path: when a group is reachable by
     * several routes, `Group._check_children_depth` takes the **maximum**, so a
     * group nested one level deeper on any path sorts later everywhere.
     */
    val depth: Int,
    /**
     * `ansible_group_priority`, defaulting to 1.
     *
     * Only ever populated from a `vars:` block in the inventory source. A value
     * in `group_vars/<group>.yml` is a verified no-op — those files are merged
     * *using* this number, so setting it there is circular and Ansible ignores
     * it. See NAVIGATION-CASES.md §2d.
     */
    val priority: Int,
    val children: Set<String>,
    val hosts: Set<String>,
)

class InventoryGraph(
    val name: String,
    val groups: Map<String, InventoryGroup>,
    private val hostGroups: Map<String, Set<String>>,
) {
    val hosts: Set<String> get() = hostGroups.keys

    // A graph is immutable and cached per inventory, so these are computed at
    // most once per host/group for its whole lifetime. Resolution sweeps a
    // fleet-sized inventory once per playbook, and re-sorting every host's
    // group list on each pass was the dominant cost of that sweep.
    private val groupsCache = ConcurrentHashMap<String, List<InventoryGroup>>()
    private val signatureCache = ConcurrentHashMap<String, String>()
    private val hostsInGroupCache = ConcurrentHashMap<String, Set<String>>()

    /**
     * Every group a host belongs to, ordered exactly as Ansible merges them:
     * `(depth ASC, priority ASC, name ASC)`, later entries overriding earlier.
     */
    fun groupsForHost(host: String): List<InventoryGroup> =
        groupsCache.getOrPut(host) {
            (hostGroups[host] ?: emptySet())
                .mapNotNull { groups[it] }
                .sortedWith(
                    compareBy({ it.depth }, { it.priority }, { it.name }),
                )
        }

    /**
     * A stable string identifying a host's group membership *and* its merge
     * order — two hosts sharing one are interchangeable to variable resolution.
     */
    fun groupSignature(host: String): String =
        signatureCache.getOrPut(host) { groupsForHost(host).joinToString(",") { it.name } }

    fun hostsInGroup(group: String): Set<String> =
        hostsInGroupCache.getOrPut(group) { hostGroups.filterValues { group in it }.keys }

    companion object {
        const val ALL = "all"
        const val PRIORITY_VAR = "ansible_group_priority"
        const val DEFAULT_PRIORITY = 1
    }
}

/**
 * Accumulates groups while parsing, then computes depths the way Ansible does.
 */
internal class InventoryGraphBuilder(private val name: String) {

    private val children = HashMap<String, MutableSet<String>>()
    private val hosts = HashMap<String, MutableSet<String>>()
    private val priorities = HashMap<String, Int>()
    private val known = LinkedHashSet<String>()

    init {
        known += InventoryGraph.ALL
    }

    fun group(groupName: String) {
        known += groupName
        if (groupName != InventoryGraph.ALL) {
            // Every group is implicitly a child of `all` unless re-parented.
            children.getOrPut(InventoryGraph.ALL) { LinkedHashSet() } += groupName
        }
    }

    fun child(parent: String, childName: String) {
        group(parent)
        group(childName)
        children.getOrPut(parent) { LinkedHashSet() } += childName
        // A group with a real parent is no longer a direct child of `all`.
        if (parent != InventoryGraph.ALL) {
            children[InventoryGraph.ALL]?.remove(childName)
        }
    }

    fun host(hostName: String, groupName: String) {
        group(groupName)
        hosts.getOrPut(hostName) { LinkedHashSet() } += groupName
    }

    fun priority(groupName: String, value: Int) {
        group(groupName)
        priorities[groupName] = value
    }

    fun build(): InventoryGraph {
        val depths = computeDepths()

        // A host belongs to its direct groups and to every ancestor of those.
        val parents = HashMap<String, MutableSet<String>>()
        for ((parent, kids) in children) {
            for (kid in kids) parents.getOrPut(kid) { LinkedHashSet() } += parent
        }

        val hostGroups = hosts.mapValues { (_, direct) ->
            val closure = LinkedHashSet<String>()
            val queue = ArrayDeque(direct)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!closure.add(current)) continue
                parents[current]?.forEach(queue::addLast)
            }
            closure += InventoryGraph.ALL
            closure.toSet()
        }

        val groups = known.associateWith { groupName ->
            InventoryGroup(
                name = groupName,
                depth = depths[groupName] ?: 0,
                priority = priorities[groupName] ?: InventoryGraph.DEFAULT_PRIORITY,
                children = children[groupName]?.toSet() ?: emptySet(),
                hosts = hosts.filterValues { groupName in it }.keys,
            )
        }
        return InventoryGraph(name, groups, hostGroups)
    }

    /** `child.depth = max(child.depth, parent.depth + 1)`, iterated to a fixpoint. */
    private fun computeDepths(): Map<String, Int> {
        val depth = known.associateWith { if (it == InventoryGraph.ALL) 0 else 1 }.toMutableMap()
        // Bounded iteration: a cycle in `children` must not hang the IDE.
        repeat(known.size + 1) {
            var changed = false
            for ((parent, kids) in children) {
                val parentDepth = depth[parent] ?: continue
                for (kid in kids) {
                    val candidate = parentDepth + 1
                    if ((depth[kid] ?: 0) < candidate) {
                        depth[kid] = candidate
                        changed = true
                    }
                }
            }
            if (!changed) return depth
        }
        return depth
    }
}
