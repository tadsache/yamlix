package dev.yamlix.ansible.refs

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import dev.yamlix.ansible.layout.AnsibleLayoutService
import dev.yamlix.ansible.psi.PlayStructure
import dev.yamlix.ansible.vars.AnsibleMagicVariables
import dev.yamlix.ansible.vars.MagicOrigin
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import javax.swing.Icon

/**
 * Ctrl+Click destinations for variables Ansible provides itself.
 *
 * A magic variable has no declaration, so strictly the honest answer is "no
 * target". That is also a dead end. What a reader actually wants is the thing
 * that *produces* the value: for `inventory_hostname`, the host entries in the
 * inventory; for a gathered fact, the `gather_facts` setting that decides
 * whether it exists at all.
 *
 * These are labelled as origins, not declarations, so the distinction stays
 * visible in the picker.
 */
object MagicVariableOrigins {

    fun targets(name: String, from: VirtualFile, project: Project): List<PsiElement> {
        val magic = AnsibleMagicVariables.lookup(name) ?: return emptyList()
        return when (magic.origin) {
            MagicOrigin.INVENTORY -> inventoryOrigins(name, from, project)
            MagicOrigin.FACTS -> factOrigins(from, project)
            MagicOrigin.RUNTIME -> emptyList()
        }
    }

    /** Host or group entries in every inventory source. */
    private fun inventoryOrigins(
        name: String,
        from: VirtualFile,
        project: Project,
    ): List<PsiElement> {
        val wantsGroups = name == "group_names" || name == "groups"
        val layout = AnsibleLayoutService.getInstance(project)
        val manager = PsiManager.getInstance(project)
        val out = ArrayList<PsiElement>()

        for (root in layout.inventoryRoots(from)) {
            if (name == "inventory_dir" || name == "inventory_file") {
                manager.findDirectory(root)?.let {
                    out += OriginTarget(
                        it, "inventory root: ${root.name}", root.name, AllIcons.Nodes.Folder,
                    )
                }
                continue
            }
            for (child in root.children) {
                if (child.isDirectory) continue
                val psi = manager.findFile(child) as? YAMLFile ?: continue
                collectEntries(psi, wantsGroups, root.name, out)
            }
        }
        return out
    }

    /**
     * Walks an inventory mapping. Keys under a `hosts:` mapping are hosts;
     * every other mapping key that owns a group body is a group.
     */
    private fun collectEntries(
        file: YAMLFile,
        wantsGroups: Boolean,
        inventory: String,
        out: MutableList<PsiElement>,
    ) {
        fun walk(mapping: YAMLMapping, insideHosts: Boolean) {
            for (kv in mapping.keyValues) {
                val key = kv.keyText.trim()
                val body = kv.value as? YAMLMapping
                when {
                    insideHosts -> if (!wantsGroups) {
                        out += OriginTarget(
                            kv.key ?: kv, "host: $key", "$inventory · ${file.name}",
                            AllIcons.Nodes.Servlet,
                        )
                    }
                    key == "hosts" -> body?.let { walk(it, true) }
                    key == "children" -> body?.let { walk(it, false) }
                    key == "vars" -> Unit
                    else -> {
                        if (wantsGroups && key != "all") {
                            out += OriginTarget(
                                kv.key ?: kv, "group: $key", "$inventory · ${file.name}",
                                AllIcons.Nodes.Folder,
                            )
                        }
                        body?.let { walk(it, false) }
                    }
                }
            }
        }
        file.documents.mapNotNull { it.topLevelValue as? YAMLMapping }.forEach { walk(it, false) }
    }

    /** The `gather_facts` setting of every playbook that could produce the fact. */
    private fun factOrigins(from: VirtualFile, project: Project): List<PsiElement> {
        val layout = AnsibleLayoutService.getInstance(project)
        val manager = PsiManager.getInstance(project)
        val out = ArrayList<PsiElement>()

        val playbooks = layout.playbooks(from).ifEmpty {
            listOfNotNull((manager.findFile(from) as? YAMLFile)?.virtualFile)
        }
        for (playbook in playbooks) {
            val psi = manager.findFile(playbook) as? YAMLFile ?: continue
            for (play in PlayStructure.plays(psi)) {
                val gather = play.getKeyValueByKey("gather_facts")
                val anchor = gather ?: play.getKeyValueByKey("hosts") ?: continue
                val label = if (gather != null) {
                    "gather_facts: ${gather.valueText.trim()}"
                } else {
                    "gather_facts not set (defaults to true)"
                }
                out += OriginTarget(anchor, label, playbook.name, AllIcons.Nodes.Method)
            }
        }
        return out
    }

    /**
     * Presents itself in the picker, for the same reason [VarDefinitionTarget]
     * does: the platform would otherwise render every row as the element text
     * plus its parent directory.
     */
    private class OriginTarget(
        private val target: PsiElement,
        private val label: String,
        private val where: String,
        private val icon: Icon,
    ) : FakePsiElement() {

        override fun getParent(): PsiElement = target
        override fun getContainingFile(): PsiFile? = target.containingFile
        override fun getTextOffset(): Int = target.textOffset
        override fun canNavigate(): Boolean = target is Navigatable && target.canNavigate()
        override fun navigate(requestFocus: Boolean) {
            (target as? Navigatable)?.navigate(requestFocus)
        }

        override fun getPresentableText(): String = label

        override fun getLocationString(): String {
            val file = target.containingFile?.virtualFile ?: return "$where  ·  origin"
            val base = AnsibleLayoutService.getInstance(target.project).cfgFor(file)?.baseDir
            val path = base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.name
            return "$path  ·  origin, not a declaration"
        }

        override fun getName(): String = presentableText
        override fun getIcon(open: Boolean): Icon = icon
    }
}
