package dev.yamlix.ansible

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import dev.yamlix.ansible.doc.AnsibleVarDocumentation
import dev.yamlix.ansible.doc.AnsibleVarDocumentationTargetProvider
import dev.yamlix.ansible.vars.ValueKind
import dev.yamlix.ansible.vars.VariableReport
import dev.yamlix.ansible.vars.VariableReportBuilder
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Milestone 3 — Quick Documentation.
 *
 * The test set is NAVIGATION-CASES.md §3: everything listed there as
 * statically unresolvable must render as unresolved, showing the raw template,
 * with no guessed value anywhere.
 */
class QuickDocumentationTest : AnsibleFixtureTestCase() {

    private val provider = AnsibleVarDocumentationTargetProvider()

    /** The absolute offset of [name] where it appears inside `{{ … }}` on [line]. */
    private fun jinjaOffset(path: String, line: Int, name: String): Pair<PsiFile, Int> {
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

        val marker = "{{ $name"
        val index = scalar.text.indexOf(marker)
        require(index >= 0) { "'$marker' not found in scalar at $path:$line" }
        return psiFile to scalar.textOffset + index + 3
    }

    /**
     * The rendered popup HTML.
     *
     * Goes through the renderer rather than `DocumentationResult`, whose HTML is
     * not exposed by the interface — asserting on its `toString` would be
     * coupling the tests to an implementation detail. That the target itself
     * produces a result is covered by [testTargetProducesDocumentation].
     */
    private fun documentation(path: String, line: Int, name: String): String {
        val (psiFile, offset) = jinjaOffset(path, line, name)
        val scalar = PsiTreeUtil.getParentOfType(
            psiFile.findElementAt(offset), YAMLScalar::class.java, false,
        )!!
        val reports = VariableReportBuilder.getInstance(project).buildAll(name, scalar)
        val base = dev.yamlix.ansible.layout.AnsibleLayoutService.getInstance(project)
            .cfgFor(psiFile.virtualFile)?.baseDir
        return AnsibleVarDocumentation.render(name, reports, base)
    }

    /** The extension point itself wires up and returns a result. */
    fun testTargetProducesDocumentation() {
        val (psiFile, offset) = jinjaOffset("roles/app/tasks/main.yml", 12, "app_workers")
        val target = provider.documentationTargets(psiFile, offset).single()
        assertNotNull("the EP must produce documentation", target.computeDocumentation())
        assertEquals("app_workers", target.computePresentation().presentableText)
    }

    private fun report(path: String, line: Int, name: String): VariableReport {
        val (psiFile, offset) = jinjaOffset(path, line, name)
        val scalar = PsiTreeUtil.getParentOfType(
            psiFile.findElementAt(offset), YAMLScalar::class.java, false,
        )!!
        return VariableReportBuilder.getInstance(project).buildAll(name, scalar).single()
    }

    private fun rowsAsText(report: VariableReport): List<String> = report.rows.map { row ->
        val hosts = if (row.coversWholeInventory) "*" else row.hosts.joinToString("+")
        "${row.inventory}[$hosts] ${row.kind} ${row.value ?: "-"} <- " +
            (row.winner?.let { "${it.scope.display}(${it.file.name})" } ?: "none")
    }

    // ---- the table itself ----------------------------------------------------

    /** The headline case: one variable, two inventories, four different answers. */
    fun testTableSplitsByInventoryAndHost() {
        assertEquals(
            listOf(
                "prod[prod-web-1] LITERAL 16 <- host_vars(prod-web-1.yml)",
                "prod[prod-web-2] LITERAL 14 <- group_vars(webservers.yml)",
                "stag[stag-web-1] LITERAL 6 <- host_vars(stag-web-1.yml)",
                "stag[stag-web-2] LITERAL 4 <- group_vars(webservers.yml)",
            ),
            rowsAsText(report("roles/app/tasks/main.yml", 12, "app_workers")).sorted(),
        )
    }

    /** Hosts that agree collapse into a single row rather than repeating. */
    fun testHostsThatAgreeCollapseIntoOneRow() {
        val rows = rowsAsText(report("site-playbook.yml", 23, "app_log_level"))
        assertTrue(
            "prod hosts agree and must collapse: $rows",
            rows.any { it.startsWith("prod[*] LITERAL warning") },
        )
    }

    fun testHtmlCarriesInventoryValueAndSource() {
        val html = documentation("roles/app/tasks/main.yml", 12, "app_workers")
        assertTrue(html, html.contains("effective value per inventory"))
        assertTrue("expected an inventory label", html.contains(">stag<"))
        assertTrue("expected the stag-web-1 value", html.contains("<code>6</code>"))
        assertTrue(html, html.contains("defined in "))
        assertTrue("expected the defining file", html.contains("host_vars/stag-web-1.yml"))
        assertTrue("expected the precedence rank", html.contains("rank 10"))
    }

    /**
     * The popup renders through Swing's HTMLEditorKit, which breaks words
     * mid-character to squeeze an over-wide table. A three-column layout turned
     * the header "inventory" into "inv / ent / ory", so the renderer must not
     * emit a table of its own — only the platform's two-column section grid.
     */
    fun testLayoutAvoidsTheRenderersTableSqueeze() {
        val html = documentation("site-playbook.yml", 24, "app_url")
        val ownTables = Regex("<table").findAll(html).count()
        val sectionGrids = Regex(Regex.escape(
            com.intellij.lang.documentation.DocumentationMarkup.SECTIONS_START,
        )).findAll(html).count()
        assertEquals(
            "every table must come from DocumentationMarkup.SECTIONS, not hand-rolled",
            sectionGrids,
            ownTables,
        )
        assertTrue("sections must actually be used", sectionGrids > 0)
    }

    // ---- §3: what must render as unresolved ----------------------------------

    /**
     * §3 case 1 — fact-templated `include_vars`. Without a fact, neither
     * `Darwin.yml` nor `RedHat.yml` may be presented as the answer.
     */
    fun testFactTemplatedIncludeVarsIsUnresolved() {
        val report = report("roles/app/tasks/configure.yml", 4, "app_port")
        val prod = report.rows.single { it.inventory == "prod" }

        assertEquals(ValueKind.AMBIGUOUS, prod.kind)
        assertNull("no value may be promoted when the answer is a set", prod.value)
        assertEquals(
            listOf("8100", "8200"),
            prod.alternatives.mapNotNull { it.valueText }.distinct().sorted(),
        )

        val html = documentation("roles/app/tasks/configure.yml", 4, "app_port")
        assertTrue("must be labelled unresolved", html.contains("unresolved"))
    }

    /**
     * §3 case 2 — a `when:`-guarded `set_fact`. The guard compares an inventory
     * variable, so this one IS decidable: it applies on stag and not on prod.
     */
    fun testGuardedSetFactIsDecidedWhenTheGuardIsStatic() {
        val report = report("roles/app/tasks/configure.yml", 4, "app_port")
        val stag = report.rows.single { it.inventory == "stag" }
        assertEquals(ValueKind.LITERAL, stag.kind)
        assertEquals("8500", stag.value)
        assertEquals("set_fact", stag.winner?.scope?.display)
    }

    /** §3 — a Jinja-valued variable is shown raw, never pre-expanded. */
    fun testJinjaValuedVariableIsShownUnexpanded() {
        val report = report("site-playbook.yml", 24, "app_url")
        val row = report.rows.first()
        assertEquals(ValueKind.TEMPLATE, row.kind)
        assertEquals(
            "http://{{ inventory_hostname }}:{{ app_port }}/{{ app_name }}",
            row.value,
        )
        assertTrue(row.note!!, row.note!!.contains("lazily"))

        val html = documentation("site-playbook.yml", 24, "app_url")
        assertTrue("raw template must appear", html.contains("inventory_hostname"))
        assertTrue("must be labelled unresolved", html.contains("unresolved template"))
        // A wrapping inline <code> gets one shaded box per line fragment, which
        // shredded this value into five boxes in the popup.
        assertFalse(
            "a long template must not be wrapped in <code>",
            html.contains("<code>http://"),
        )
    }

    /** §3 — a registered variable's contents only exist at run time. */
    fun testRegisteredVariableIsRuntimeOnly() {
        val file = myFixture.addFileToProject(
            "probe-registered/tasks/main.yml",
            """
            - name: probe
              ansible.builtin.debug:
                msg: "{{ app_config }}"
            """.trimIndent(),
        )
        val scalar = PsiTreeUtil.findChildrenOfType(file, YAMLScalar::class.java)
            .first { it.text.contains("app_config") }
        val report = VariableReportBuilder.getInstance(project)
            .buildAll("app_config", scalar).single()

        val row = report.rows.first()
        assertEquals(ValueKind.RUNTIME, row.kind)
        assertNull(row.value)
        assertTrue(row.note!!, row.note!!.contains("run time"))
    }

    /** §3 — a gathered fact has no definition site and must say so. */
    fun testGatheredFactIsUndefined() {
        val file = myFixture.addFileToProject(
            "probe-fact/tasks/main.yml",
            """
            - name: probe
              ansible.builtin.debug:
                msg: "{{ ansible_os_family }}"
            """.trimIndent(),
        )
        val scalar = PsiTreeUtil.findChildrenOfType(file, YAMLScalar::class.java)
            .first { it.text.contains("ansible_os_family") }
        val row = VariableReportBuilder.getInstance(project)
            .buildAll("ansible_os_family", scalar).single().rows.first()

        assertEquals(ValueKind.UNDEFINED, row.kind)
        assertNull(row.winner)
        assertTrue(row.note!!, row.note!!.contains("gathered fact"))
    }

    /** §3 — hostvars, lookups and vault are called out specifically. */
    fun testRuntimeOnlyExpressionsAreNamedInTheNote() {
        // A real inventory root, so the group_vars file is actually in scope.
        myFixture.addFileToProject(
            "inventories/probe/hosts.yml",
            "all:\n  hosts:\n    probe-1: {}\n",
        )
        val file = myFixture.addFileToProject(
            "inventories/probe/group_vars/all.yml",
            """
            peer_address: "{{ hostvars['other']['ansible_host'] }}"
            secret_token: "{{ lookup('env', 'TOKEN') }}"
            """.trimIndent(),
        )
        val scalar = PsiTreeUtil.findChildrenOfType(file, YAMLScalar::class.java)
            .first { it.text.contains("hostvars") }
        val builder = VariableReportBuilder.getInstance(project)

        val peer = builder.buildAll("peer_address", scalar).single()
            .rows.single { it.inventory == "probe" }
        assertEquals(ValueKind.TEMPLATE, peer.kind)
        assertTrue(peer.note!!, peer.note!!.contains("hostvars"))

        val secret = builder.buildAll("secret_token", scalar).single()
            .rows.single { it.inventory == "probe" }
        assertEquals(ValueKind.TEMPLATE, secret.kind)
        assertTrue(secret.note!!, secret.note!!.contains("lookup("))
    }

    /** §3 — extra vars outrank everything and are always disclosed. */
    fun testExtraVarsCaveatIsAlwaysPresent() {
        val html = documentation("roles/app/tasks/main.yml", 12, "app_workers")
        assertTrue("expected the extra-vars caveat", html.contains("extra vars"))
        assertTrue("expected a caveats section", html.contains("Cannot be certain"))
    }

    // ---- offset handling -----------------------------------------------------

    /** Inside a folded `>-` scalar, the caret must still find the right token. */
    fun testOffsetInsideFoldedScalarPicksTheRightVariable() {
        val (psiFile, offset) = jinjaOffset("roles/app/tasks/main.yml", 12, "app_workers")
        val targets = provider.documentationTargets(psiFile, offset)
        assertEquals(1, targets.size)
        assertEquals("app_workers", targets.single().computePresentation().presentableText)
    }

    /** The literal `app_workers=` label before the `{{` is not a variable. */
    fun testLiteralTextOutsideJinjaHasNoDocumentation() {
        val virtualFile = file("roles/app/tasks/main.yml")
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        val start = document.getLineStartOffset(11)
        val lineText = document.getText(TextRange(start, document.getLineEndOffset(11)))
        val labelOffset = start + lineText.indexOf("app_workers")

        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)!!
        assertEquals(emptyList<Any>(), provider.documentationTargets(psiFile, labelOffset))
    }

    /** A YAML scalar with no Jinja at all yields nothing. */
    fun testPlainScalarHasNoDocumentation() {
        val virtualFile = file("site-playbook.yml")
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        val start = document.getLineStartOffset(2)
        val lineText = document.getText(TextRange(start, document.getLineEndOffset(2)))
        val offset = start + lineText.indexOf("webservers")

        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)!!
        assertEquals(emptyList<Any>(), provider.documentationTargets(psiFile, offset))
    }
}
