package dev.yamlix.ansible.refs

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement

/**
 * A specific offset inside a non-YAML (INI-format) inventory file.
 *
 * Plain-text PSI is too coarse to give per-line elements: `findElementAt` on
 * an extension-less `hosts` file returns a single leaf spanning the *entire*
 * file, so a target built from it always navigates to offset 0 no matter
 * which line actually matched — "Go to Declaration" lands on line 1 instead
 * of on `[hostgroupname]`, and the declaration preview shows the whole file
 * from the top instead of the section that was found.
 *
 * This wraps the file and a manually computed offset and navigates with an
 * explicit [OpenFileDescriptor], bypassing PSI granularity entirely.
 */
class IniOffsetTarget(
    private val file: PsiFile,
    private val offset: Int,
    private val label: String,
) : FakePsiElement() {

    override fun getParent(): PsiElement = file

    override fun getContainingFile(): PsiFile = file

    override fun getTextOffset(): Int = offset

    override fun isValid(): Boolean = file.isValid

    override fun canNavigate(): Boolean = file.virtualFile != null

    override fun navigate(requestFocus: Boolean) {
        val vFile = file.virtualFile ?: return
        OpenFileDescriptor(file.project, vFile, offset).navigate(requestFocus)
    }

    override fun isEquivalentTo(another: PsiElement?): Boolean =
        another === this || (another is IniOffsetTarget && another.file == file && another.offset == offset)

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText() = label
        override fun getLocationString() = file.name
        override fun getIcon(unused: Boolean) = com.intellij.icons.AllIcons.Nodes.Folder
    }
}
