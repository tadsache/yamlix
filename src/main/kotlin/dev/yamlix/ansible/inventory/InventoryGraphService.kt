package dev.yamlix.ansible.inventory

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
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
import dev.yamlix.ansible.vars.VarFileRole
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds and caches one [InventoryGraph] per inventory directory.
 *
 * A project service rather than a file index on purpose: the graph is a
 * whole-directory aggregate — a host's group set depends on every source file in
 * the inventory at once — and file indexes are per-file by construction.
 */
@Service(Service.Level.PROJECT)
class InventoryGraphService(private val project: Project) {

    companion object {
        fun getInstance(project: Project): InventoryGraphService = project.service()

        private val CACHE_KEY =
            Key.create<CachedValue<ConcurrentHashMap<String, InventoryGraph>>>(
                "yamlix.ansible.inventoryGraphs",
            )
    }

    /** Every inventory reachable from [from], keyed by directory name. */
    fun inventories(from: VirtualFile): List<InventoryGraph> =
        AnsibleLayoutService.getInstance(project).inventoryRoots(from).map { graphFor(it) }

    fun inventoryNamed(from: VirtualFile, name: String): InventoryGraph? =
        AnsibleLayoutService.getInstance(project).inventoryRoots(from)
            .firstOrNull { it.name == name }
            ?.let { graphFor(it) }

    fun graphFor(inventoryRoot: VirtualFile): InventoryGraph =
        cache().getOrPut(inventoryRoot.path) { parse(inventoryRoot) }

    private fun cache(): ConcurrentHashMap<String, InventoryGraph> =
        CachedValuesManager.getManager(project).getCachedValue(
            project,
            CACHE_KEY,
            {
                CachedValueProvider.Result.create(
                    ConcurrentHashMap<String, InventoryGraph>(),
                    PsiModificationTracker.MODIFICATION_COUNT,
                    AnsibleLayoutTracker,
                )
            },
            false,
        )

    // ---- parsing -------------------------------------------------------------

    private fun parse(root: VirtualFile): InventoryGraph {
        val builder = InventoryGraphBuilder(root.name)
        val manager = PsiManager.getInstance(project)

        for (child in root.children.sortedBy { it.name }) {
            if (child.isDirectory) continue
            val psi = manager.findFile(child)
            if (psi is YAMLFile) {
                psi.documents.mapNotNull { it.topLevelValue as? YAMLMapping }
                    .forEach { parseYamlGroups(it, null, builder) }
            } else if (VarFileRole.isIniInventory(child)) {
                parseIni(LoadTextUtil.loadText(child).toString(), builder)
            }
        }
        return builder.build()
    }

    /**
     * YAML inventory: a mapping of group name to `{hosts:, children:, vars:}`.
     * Recurses through `children:` so nesting produces real depth.
     */
    private fun parseYamlGroups(
        mapping: YAMLMapping,
        parent: String?,
        builder: InventoryGraphBuilder,
    ) {
        for (kv in mapping.keyValues) {
            val groupName = kv.keyText.trim()
            if (groupName.isEmpty()) continue
            if (parent == null) builder.group(groupName) else builder.child(parent, groupName)

            val body = kv.value as? YAMLMapping ?: continue

            (body.getKeyValueByKey("hosts")?.value)?.let { hostsValue ->
                when (hostsValue) {
                    is YAMLMapping -> hostsValue.keyValues.forEach {
                        builder.host(it.keyText.trim(), groupName)
                    }
                    is YAMLSequence -> hostsValue.items
                        .mapNotNull { it.value as? YAMLScalar }
                        .forEach { builder.host(it.textValue.trim(), groupName) }
                    else -> Unit
                }
            }

            // Priority is read here and ONLY here — group_vars is a no-op.
            (body.getKeyValueByKey("vars")?.value as? YAMLMapping)
                ?.getKeyValueByKey(InventoryGraph.PRIORITY_VAR)
                ?.valueText?.trim()?.toIntOrNull()
                ?.let { builder.priority(groupName, it) }

            (body.getKeyValueByKey("children")?.value as? YAMLMapping)?.let {
                parseYamlGroups(it, groupName, builder)
            }
        }
    }

    private fun parseIni(text: String, builder: InventoryGraphBuilder) {
        var section = ""
        var kind = "hosts"
        for (rawLine in text.lineSequence()) {
            val line = rawLine.substringBefore('#').substringBefore(';').trim()
            if (line.isEmpty()) continue
            if (line.startsWith('[') && line.endsWith(']')) {
                val header = line.substring(1, line.length - 1).trim()
                section = header.substringBefore(':')
                kind = header.substringAfter(':', "hosts")
                builder.group(section)
                continue
            }
            when (kind) {
                "children" -> builder.child(section, line.split(Regex("\\s+")).first())
                "hosts" -> builder.host(line.split(Regex("\\s+")).first(), section)
                "vars" -> {
                    val eq = line.indexOf('=')
                    if (eq <= 0) continue
                    if (line.substring(0, eq).trim() == InventoryGraph.PRIORITY_VAR) {
                        line.substring(eq + 1).trim().toIntOrNull()
                            ?.let { builder.priority(section, it) }
                    }
                }
            }
        }
    }
}
