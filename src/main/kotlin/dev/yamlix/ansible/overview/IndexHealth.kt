package dev.yamlix.ansible.overview

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.FileBasedIndex
import dev.yamlix.ansible.layout.AnsibleLayoutService
import dev.yamlix.ansible.vars.AnsibleVarIndex

/**
 * Telling a broken index apart from a project that defines nothing.
 *
 * When the variable index is empty every lookup returns nothing, and the
 * honest-looking result is a file in which no variable is defined anywhere —
 * indistinguishable, on screen, from a genuine finding. It happened here: a
 * plugin classloader failed mid-session, left an empty index behind, and the
 * IDE then considered it up to date for every session after, so a completely
 * correct project reported every variable as undefined until the caches were
 * invalidated. A user has no way to guess that from "not defined in this
 * project", and no reason to doubt it.
 *
 * The signal is a contradiction: the index knows *no* variable at all, while
 * files that define variables are plainly sitting on disk.
 */
object IndexHealth {

    /**
     * Whether an empty index is a contradiction rather than a fact.
     *
     * Split out so the judgement is decided in plain booleans and tested
     * without an index. Every argument must hold: a project that really
     * declares nothing, or a file whose variables all resolve, says nothing
     * about the index's health.
     */
    fun looksBroken(
        everythingUnresolved: Boolean,
        indexKnowsNothing: Boolean,
        varFilesExistOnDisk: Boolean,
    ): Boolean = everythingUnresolved && indexKnowsNothing && varFilesExistOnDisk

    /**
     * The check, run against a project.
     *
     * Ordered by cost. [everythingUnresolved] is already known by the caller
     * and is false for virtually every healthy file, so the index enumeration
     * and the VFS walk below it almost never run.
     */
    fun looksBroken(project: Project, file: VirtualFile, everythingUnresolved: Boolean): Boolean {
        if (!everythingUnresolved) return false
        val indexKnowsNothing =
            FileBasedIndex.getInstance().getAllKeys(AnsibleVarIndex.NAME, project).isEmpty()
        return looksBroken(true, indexKnowsNothing, definesVariablesOnDisk(project, file))
    }

    /**
     * Whether anything in this project defines variables, judged from the VFS
     * alone — the index cannot be asked, since its emptiness is the question.
     *
     * A *file* is required, not merely the directory that usually holds one.
     * An empty `defaults/` defines nothing, and counting it would let the check
     * accuse a project whose index is perfectly correct in being empty — the
     * false positive this whole feature has to avoid.
     */
    internal fun definesVariablesOnDisk(project: Project, file: VirtualFile): Boolean {
        val layout = AnsibleLayoutService.getInstance(project)
        val base = layout.cfgFor(file)?.baseDir ?: return false

        val varDirs = sequence {
            yield(base.findChild("group_vars"))
            yield(base.findChild("host_vars"))
            layout.inventoryRoots(file).forEach {
                yield(it.findChild("group_vars"))
                yield(it.findChild("host_vars"))
            }
            base.findChild("roles")?.children.orEmpty().filter { it.isDirectory }.forEach { role ->
                yield(role.findChild("defaults"))
                yield(role.findChild("vars"))
            }
        }
        return varDirs.filterNotNull().any { it.isDirectory && holdsAFile(it) }
    }

    /** A file directly inside [dir], or one level down (`group_vars/web/a.yml`). */
    private fun holdsAFile(dir: VirtualFile): Boolean =
        dir.children.orEmpty().any { child ->
            if (child.isDirectory) child.children.orEmpty().any { !it.isDirectory } else true
        }
}
