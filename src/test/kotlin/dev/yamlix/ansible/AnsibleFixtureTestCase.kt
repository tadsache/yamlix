package dev.yamlix.ansible

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.yamlix.ansible.refs.AnsibleReferenceBase
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Loads the fixture repo — the specification — into the test project.
 *
 * The fixture is copied verbatim, so tests cannot use `<caret>` markers. They
 * address positions the way NAVIGATION-CASES.md does instead: by file, line and
 * the exact token the user would Ctrl+Click.
 */
abstract class AnsibleFixtureTestCase : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        myFixture.copyDirectoryToProject("fixture", "")
    }

    protected val projectRoot: VirtualFile
        get() = myFixture.findFileInTempDir(".")
            ?: error("temp project root missing")

    protected fun file(path: String): VirtualFile =
        myFixture.findFileInTempDir(path) ?: error("fixture file not found: $path")

    /**
     * The Ansible reference on [token], which must occur on 1-based [line] of
     * [path]. Fails loudly if the position carries no Ansible reference, so a
     * classifier regression cannot silently turn into "resolves to nothing".
     */
    protected fun referenceAt(path: String, line: Int, token: String): AnsibleReferenceBase {
        val virtualFile = file(path)
        val document: Document = FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: error("no document for $path")
        require(line >= 1 && line <= document.lineCount) {
            "$path has ${document.lineCount} lines, asked for line $line"
        }
        val start = document.getLineStartOffset(line - 1)
        val end = document.getLineEndOffset(line - 1)
        val lineText = document.getText(TextRange(start, end))
        val column = lineText.indexOf(token)
        require(column >= 0) { "token '$token' not on line $line of $path: '$lineText'" }

        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: error("no PSI for $path")
        val leaf = psiFile.findElementAt(start + column)
            ?: error("no PSI element at $path:$line:$column")
        val scalar = PsiTreeUtil.getParentOfType(leaf, YAMLScalar::class.java, false)
            ?: error("$path:$line token '$token' is not inside a YAML scalar")

        val ansibleRefs = scalar.references.filterIsInstance<AnsibleReferenceBase>()
        assertEquals(
            "expected exactly one Ansible reference on '$token' at $path:$line",
            1,
            ansibleRefs.size,
        )
        return ansibleRefs.single()
    }

    /** Resolved targets as project-relative paths, in resolution order. */
    protected fun resolvedPaths(reference: AnsibleReferenceBase): List<String> =
        reference.targets().map { relativePath(it) }

    protected fun relativePath(element: PsiElement): String {
        val virtualFile = element.containingFile?.virtualFile
            ?: (element as? com.intellij.psi.PsiFileSystemItem)?.virtualFile
            ?: error("element has no virtual file: $element")
        return VfsUtilCore.getRelativePath(virtualFile, projectRoot)
            ?: virtualFile.path
    }

    protected fun assertResolvesTo(
        expected: String,
        path: String,
        line: Int,
        token: String,
    ) {
        val reference = referenceAt(path, line, token)
        assertEquals(
            "$path:$line '$token' should resolve to a single target",
            listOf(expected),
            resolvedPaths(reference),
        )
    }

    protected fun assertResolvesTo(
        expected: List<String>,
        path: String,
        line: Int,
        token: String,
    ) {
        val reference = referenceAt(path, line, token)
        assertEquals(
            "$path:$line '$token' candidates, in fallback order",
            expected,
            resolvedPaths(reference),
        )
    }
}
