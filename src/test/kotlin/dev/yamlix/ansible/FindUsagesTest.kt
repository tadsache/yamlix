package dev.yamlix.ansible

import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * The reverse direction: from a definition to every `{{ … }}` that uses it.
 *
 * This works through the platform's own Find Usages, which matches by calling
 * `isReferenceTo` on candidate references. Our references resolve to a
 * presentation wrapper rather than the underlying `YAMLKeyValue`, so the
 * wrapper has to declare itself equivalent to what it wraps — otherwise a
 * variable with a dozen live uses reports "No usages found".
 */
class FindUsagesTest : AnsibleFixtureTestCase() {

    private fun definition(path: String, key: String): YAMLKeyValue {
        val psi = PsiManager.getInstance(project).findFile(file(path))!!
        return PsiTreeUtil.findChildrenOfType(psi, YAMLKeyValue::class.java)
            .first { it.keyText.trim() == key }
    }

    private fun usages(path: String, key: String): List<String> =
        ReferencesSearch.search(definition(path, key), GlobalSearchScope.allScope(project))
            .findAll()
            .map { "${it.element.containingFile.name}:${it.rangeInElement.substring(it.element.text)}" }

    fun testUsagesOfAHostVarsDefinition() {
        val found = usages("inventories/prod/host_vars/prod-web-1.yml", "app_port")
        assertTrue("expected the {{ app_port }} uses, found $found", found.size >= 10)
        assertTrue("all matches must be the variable itself", found.all { it.endsWith(":app_port") })
        assertTrue(
            "must reach uses inside an included task file",
            found.any { it.startsWith("configure.yml") },
        )
    }

    /** A definition in one place is used from the playbook and from roles. */
    fun testUsagesSpanPlaybookAndRoles() {
        val files = usages("roles/app/defaults/main.yml", "app_workers")
            .map { it.substringBefore(':') }
            .toSet()
        assertTrue("expected uses in the playbook, got $files", files.contains("site-playbook.yml"))
        assertTrue("expected uses in a role, got $files", files.contains("main.yml"))
    }

    /** A variable nothing references has no usages, and that is not an error. */
    fun testUnusedDefinitionHasNoUsages() {
        assertEquals(emptyList<String>(), usages("inventories/stag/group_vars/platform.yml", "platform_owner")
            .filter { it.endsWith(":platform_owner") }
            .filterNot { it.startsWith("app.conf.j2") })
    }
}
