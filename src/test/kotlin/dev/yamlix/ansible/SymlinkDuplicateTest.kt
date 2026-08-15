package dev.yamlix.ansible

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

/**
 * A role reachable through a symlink is one role, and its uses are one set.
 *
 * The fleet fixture has a `playbooks/fleet/roles -> ../../roles` symlink, but
 * `copyDirectoryToProject` does not reproduce symlinks — in the test VFS that
 * directory does not exist at all. So every symlink case in the suite is
 * really testing the non-symlinked path, and the duplication this pins was
 * invisible to all of them. This builds the tree on disk instead.
 */
class SymlinkDuplicateTest : BasePlatformTestCase() {

    private lateinit var dir: File
    private var root: VirtualFile? = null

    /**
     * The light fixture shares one project across the methods of a class, so a
     * content root added by one test is still registered in the next. Left in,
     * the second test saw two projects' worth of files and the usage count was
     * a sum rather than an answer.
     */
    override fun tearDown() {
        try {
            root?.let { PsiTestUtil.removeContentEntry(myFixture.module, it) }
        } finally {
            super.tearDown()
        }
    }

    private fun buildProjectOnDisk(): VirtualFile {
        dir = Files.createTempDirectory("symlinked-ansible").toFile()
        File(dir, "ansible.cfg").writeText("[defaults]\nroles_path = ./roles\n")

        File(dir, "roles/probe/defaults").mkdirs()
        File(dir, "roles/probe/defaults/main.yml")
            .writeText("---\nprobe_value: \"one\"\n")
        File(dir, "roles/probe/tasks").mkdirs()
        File(dir, "roles/probe/tasks/main.yml").writeText(
            "---\n- name: use it\n  ansible.builtin.debug:\n    msg: \"{{ probe_value }}\"\n"
        )
        File(dir, "site.yml").writeText("---\n- hosts: all\n  roles:\n    - probe\n")

        // The duplicate path: the same roles/ tree, seen again from elsewhere.
        File(dir, "playbooks/fleet").mkdirs()
        Files.createSymbolicLink(
            File(dir, "playbooks/fleet/roles").toPath(),
            File("../../roles").toPath(),
        )

        val created = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)
            ?: error("project not visible")
        PsiTestUtil.addContentRoot(myFixture.module, created)
        root = created
        return created
    }

    fun testUsesOfAVariableAreNotDoubledByTheSymlink() {
        val root = buildProjectOnDisk()

        // The premise. If the VFS ever stops exposing the symlinked subtree as
        // its own files, this test proves nothing and should say so loudly
        // rather than pass for the wrong reason.
        val direct = root.findFileByRelativePath("roles/probe/tasks/main.yml")
        val viaLink = root.findFileByRelativePath("playbooks/fleet/roles/probe/tasks/main.yml")
        assertNotNull("the canonical path", direct)
        assertNotNull("and the same file through the symlink", viaLink)
        assertFalse("which the VFS treats as two files", direct === viaLink)

        val defaults = root.findFileByRelativePath("roles/probe/defaults/main.yml")!!
        val psi = myFixture.psiManager.findFile(defaults)!!
        val declaration = com.intellij.psi.util.PsiTreeUtil
            .findChildrenOfType(psi, org.jetbrains.yaml.psi.YAMLKeyValue::class.java)
            .single { it.keyText == "probe_value" }

        val usages = ReferencesSearch
            .search(declaration, GlobalSearchScope.projectScope(project))
            .findAll()

        assertEquals(
            "one use of probe_value exists; the symlink must not make it two: " +
                usages.joinToString { it.element.containingFile.virtualFile.path },
            1,
            usages.size,
        )
        assertEquals(
            "and the one kept is the canonical path",
            direct,
            usages.single().element.containingFile.virtualFile,
        )
    }

    /**
     * Declining the duplicate must not break the file it lives in.
     *
     * Someone browsing through `playbooks/fleet/roles/...` is looking at a real
     * file and expects Ctrl-click to work there. Only the claim "this is a
     * separate usage" is refused; resolution is untouched.
     */
    fun testNavigationStillWorksFromTheSymlinkedPath() {
        val root = buildProjectOnDisk()
        val viaLink = root.findFileByRelativePath("playbooks/fleet/roles/probe/tasks/main.yml")!!
        val psi = myFixture.psiManager.findFile(viaLink)!!

        val scalar = com.intellij.psi.util.PsiTreeUtil
            .findChildrenOfType(psi, org.jetbrains.yaml.psi.YAMLScalar::class.java)
            .single { it.textValue.contains("probe_value") }
        val reference = scalar.references
            .filterIsInstance<dev.yamlix.ansible.refs.AnsibleReferenceBase>()
            .single()

        assertNotNull(
            "Ctrl-click from the symlinked copy must still resolve",
            reference.resolve(),
        )
    }
}
