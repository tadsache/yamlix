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
 * Loads `fleet-fixture/` — the real-world-shaped reproduction described in
 * FLEET-FIXTURE-CASES.md — into the test project. See [AnsibleFixtureTestCase]
 * for the sibling fixture this mirrors the shape of.
 */
abstract class FleetFixtureTestCase : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        myFixture.copyDirectoryToProject("fleet-fixture", "")
    }

    protected val projectRoot: VirtualFile
        get() = myFixture.findFileInTempDir(".")
            ?: error("temp project root missing")

    protected fun file(path: String): VirtualFile =
        myFixture.findFileInTempDir(path) ?: error("fixture file not found: $path")

    /** The Ansible reference on [token], which must occur on 1-based [line] of [path]. */
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

    /** The variable reference for [name] as it appears *inside* `{{ … }}` on [line]. */
    protected fun variableReferenceAt(
        path: String,
        line: Int,
        name: String,
    ): dev.yamlix.ansible.refs.AnsibleVariableReference {
        val virtualFile = file(path)
        val document: Document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        val lineRange = TextRange(
            document.getLineStartOffset(line - 1),
            document.getLineEndOffset(line - 1),
        )
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)!!
        val scalar = PsiTreeUtil.findChildrenOfType(psiFile, YAMLScalar::class.java)
            .firstOrNull { it.textRange.intersects(lineRange) && it.text.contains("{{") }
            ?: error("no Jinja-bearing scalar at $path:$line")

        return scalar.references
            .filterIsInstance<dev.yamlix.ansible.refs.AnsibleVariableReference>()
            .firstOrNull { reference ->
                val absolute = reference.rangeInElement.shiftRight(scalar.textOffset)
                reference.rangeInElement.substring(scalar.text) == name &&
                    absolute.startOffset >= lineRange.startOffset &&
                    absolute.endOffset <= lineRange.endOffset
            }
            ?: error("no '$name' variable reference on $path:$line")
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
}
