package dev.yamlix.ansible

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import dev.yamlix.ansible.doc.AnsibleVarDocumentation
import dev.yamlix.ansible.layout.AnsibleLayoutService
import dev.yamlix.ansible.refs.AnsibleVariableReference
import dev.yamlix.ansible.vars.AnsibleMagicVariables
import dev.yamlix.ansible.vars.MagicOrigin
import dev.yamlix.ansible.vars.VariableReportBuilder
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Variables Ansible supplies itself.
 *
 * Nothing in a repository declares `inventory_hostname`, so "no declaration
 * found" is technically correct and practically a dead end. These tests pin the
 * behaviour that replaces it: Ctrl+Click offers the *origin* of the value, and
 * Ctrl+Q says what the variable is rather than "not defined here".
 */
class MagicVariableTest : AnsibleFixtureTestCase() {

    private fun reference(path: String, line: Int, name: String): AnsibleVariableReference {
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
        return scalar.references.filterIsInstance<AnsibleVariableReference>()
            .first { it.rangeInElement.substring(scalar.text) == name }
    }

    // ---- the catalogue --------------------------------------------------------

    fun testKnownMagicVariablesAreClassified() {
        assertEquals(MagicOrigin.INVENTORY, AnsibleMagicVariables.lookup("inventory_hostname")?.origin)
        assertEquals(MagicOrigin.INVENTORY, AnsibleMagicVariables.lookup("group_names")?.origin)
        assertEquals(MagicOrigin.RUNTIME, AnsibleMagicVariables.lookup("playbook_dir")?.origin)
        assertEquals(MagicOrigin.FACTS, AnsibleMagicVariables.lookup("ansible_facts")?.origin)
    }

    /** An unrecognised `ansible_*` name is almost always a gathered fact. */
    fun testUnknownAnsiblePrefixedNamesAreTreatedAsFacts() {
        assertEquals(MagicOrigin.FACTS, AnsibleMagicVariables.lookup("ansible_os_family")?.origin)
        assertEquals(MagicOrigin.FACTS, AnsibleMagicVariables.lookup("ansible_distribution")?.origin)
    }

    /**
     * Connection settings are normally *set* in inventory, so they must resolve
     * through the index rather than being swallowed by the fact fallback.
     */
    fun testConnectionSettingsAreNotTreatedAsMagic() {
        assertNull(AnsibleMagicVariables.lookup("ansible_host"))
        assertNull(AnsibleMagicVariables.lookup("ansible_connection"))
        assertNull(AnsibleMagicVariables.lookup("ansible_python_interpreter"))
        assertNull(AnsibleMagicVariables.lookup("app_port"))
    }

    fun testConnectionSettingStillResolvesToItsInventoryDefinition() {
        val reference = reference("roles/app/tasks/main.yml", 10, "inventory_hostname")
        assertTrue("sanity: the magic path is in use", reference.targets().isNotEmpty())

        // ansible_python_interpreter IS defined, in group_vars/all.yml.
        val builder = VariableReportBuilder.getInstance(project)
        val scalar = PsiTreeUtil.findChildrenOfType(
            PsiManager.getInstance(project).findFile(file("inventories/stag/group_vars/all.yml"))!!,
            YAMLScalar::class.java,
        ).first { it.text.contains("ansible_playbook_python") }
        val report = builder.buildAll("ansible_python_interpreter", scalar).single()
        assertNull("a defined connection var is not magic", report.magic)
    }

    // ---- Ctrl+Click -----------------------------------------------------------

    /** The case from the bug report: it must not be a dead end. */
    fun testInventoryHostnameOffersTheInventoryHosts() {
        val targets = reference("roles/app/tasks/main.yml", 10, "inventory_hostname").targets()
        assertTrue("expected inventory host origins, got none", targets.isNotEmpty())

        val rows = targets.mapNotNull { (it as? com.intellij.navigation.NavigationItem)?.name }
        assertTrue(
            "every fixture host should be offered: $rows",
            rows.any { it.contains("stag-web-1") } &&
                rows.any { it.contains("stag-web-2") } &&
                rows.any { it.contains("prod-web-1") } &&
                rows.any { it.contains("prod-web-2") },
        )
        assertTrue("rows must be labelled as hosts: $rows", rows.all { it.startsWith("host:") })
    }

    fun testGroupNamesOffersTheInventoryGroups() {
        val file = myFixture.addFileToProject(
            "probe-groups/tasks/main.yml",
            """
            - name: probe
              ansible.builtin.debug:
                msg: "{{ group_names }}"
            """.trimIndent(),
        )
        val scalar = PsiTreeUtil.findChildrenOfType(file, YAMLScalar::class.java)
            .first { it.text.contains("group_names") }
        val reference = scalar.references.filterIsInstance<AnsibleVariableReference>().single()

        val rows = reference.targets()
            .mapNotNull { (it as? com.intellij.navigation.NavigationItem)?.name }
        assertTrue("expected group origins: $rows", rows.any { it == "group: webservers" })
        assertTrue("expected the nested group: $rows", rows.any { it == "group: canary" })
        assertFalse("`all` is implicit, not a declared group: $rows", rows.any { it == "group: all" })
    }

    /** A gathered fact points at the setting that decides whether it exists. */
    fun testGatheredFactOffersGatherFacts() {
        val file = myFixture.addFileToProject(
            "probe-osfamily/tasks/main.yml",
            """
            - name: probe
              ansible.builtin.debug:
                msg: "{{ ansible_os_family }}"
            """.trimIndent(),
        )
        val scalar = PsiTreeUtil.findChildrenOfType(file, YAMLScalar::class.java)
            .first { it.text.contains("ansible_os_family") }
        val reference = scalar.references.filterIsInstance<AnsibleVariableReference>().single()

        val rows = reference.targets()
            .mapNotNull { (it as? com.intellij.navigation.NavigationItem)?.name }
        assertEquals(listOf("gather_facts: true"), rows)
    }

    /** A purely runtime variable genuinely has no origin, and that is fine. */
    fun testRuntimeOnlyMagicVariableHasNoOrigin() {
        val file = myFixture.addFileToProject(
            "probe-dir/tasks/main.yml",
            """
            - name: probe
              ansible.builtin.debug:
                msg: "{{ playbook_dir }}"
            """.trimIndent(),
        )
        val scalar = PsiTreeUtil.findChildrenOfType(file, YAMLScalar::class.java)
            .first { it.text.contains("playbook_dir") }
        val reference = scalar.references.filterIsInstance<AnsibleVariableReference>().single()
        assertEquals(emptyList<Any>(), reference.targets())
    }

    // ---- Ctrl+Q ---------------------------------------------------------------

    fun testDocumentationDescribesTheMagicVariable() {
        val scalar = reference("roles/app/tasks/main.yml", 10, "inventory_hostname").element
        val reports = VariableReportBuilder.getInstance(project)
            .buildAll("inventory_hostname", scalar)
        assertNotNull("must be recognised as magic", reports.first().magic)

        val base = AnsibleLayoutService.getInstance(project)
            .cfgFor(file("roles/app/tasks/main.yml"))?.baseDir
        val html = AnsibleVarDocumentation.render("inventory_hostname", reports, base)

        assertTrue(html, html.contains("Provided by Ansible"))
        assertTrue(html, html.contains("from the inventory"))
        assertTrue(html, html.contains("as written in the inventory"))
        assertTrue("must not claim it is simply undefined", !html.contains("not defined here"))
        assertTrue("must not render a per-inventory table", !html.contains("effective value"))
    }

    /** An ordinary project variable is unaffected by any of this. */
    fun testOrdinaryVariableStillRendersTheTable() {
        val scalar = reference("roles/app/tasks/main.yml", 12, "app_workers").element
        val reports = VariableReportBuilder.getInstance(project).buildAll("app_workers", scalar)
        assertNull(reports.first().magic)

        val base = AnsibleLayoutService.getInstance(project)
            .cfgFor(file("roles/app/tasks/main.yml"))?.baseDir
        val html = AnsibleVarDocumentation.render("app_workers", reports, base)
        assertTrue(html, html.contains("effective value"))
    }
}
