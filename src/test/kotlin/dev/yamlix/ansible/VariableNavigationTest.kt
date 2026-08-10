package dev.yamlix.ansible

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import dev.yamlix.ansible.refs.AnsibleReferenceBase
import dev.yamlix.ansible.refs.AnsibleVariableReference
import dev.yamlix.ansible.refs.VarDefinitionTarget
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Case N13 — Ctrl+Click on a `{{ variable }}` inside a YAML scalar.
 *
 * The row documents this as a picker rather than a jump, ordered by precedence
 * with the winner first.
 */
class VariableNavigationTest : AnsibleFixtureTestCase() {

    /**
     * The variable reference for [name] as it appears *inside* `{{ … }}` on
     * [line].
     *
     * Deliberately not `line.indexOf(name)`: in `app_port={{ app_port }}` the
     * first occurrence is the literal label, not the Jinja identifier. Matching
     * on the reference's own range is what a Ctrl+Click actually does.
     */
    private fun variableReference(path: String, line: Int, name: String): AnsibleVariableReference {
        val virtualFile = file(path)
        val document: Document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        val lineRange = TextRange(
            document.getLineStartOffset(line - 1),
            document.getLineEndOffset(line - 1),
        )

        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)!!
        // The line may start with the key, so take any scalar intersecting it
        // rather than whatever sits at the first column.
        val scalar = PsiTreeUtil.findChildrenOfType(psiFile, YAMLScalar::class.java)
            .firstOrNull { it.textRange.intersects(lineRange) && it.text.contains("{{") }
            ?: error("no Jinja-bearing scalar at $path:$line")

        return scalar.references
            .filterIsInstance<AnsibleVariableReference>()
            .firstOrNull { reference ->
                val absolute = reference.rangeInElement.shiftRight(scalar.textOffset)
                reference.rangeInElement.substring(scalar.text) == name &&
                    absolute.startOffset >= lineRange.startOffset &&
                    absolute.endOffset <= lineRange.endOffset
            }
            ?: error(
                "no '$name' variable reference on $path:$line; refs on this scalar = " +
                    scalar.references.filterIsInstance<AnsibleReferenceBase>()
                        .map { it.rangeInElement.substring(scalar.text) },
            )
    }

    private fun targetPaths(reference: AnsibleVariableReference): List<String> =
        reference.targets().map {
            VfsUtilCore.getRelativePath(it.containingFile.virtualFile, projectRoot) ?: "?"
        }

    private fun rows(reference: AnsibleVariableReference): List<String> =
        reference.targets().filterIsInstance<VarDefinitionTarget>()
            .map { "${it.presentableText} | ${it.locationString}" }

    /**
     * Inside a folded (`>-`) scalar, which is where the offset arithmetic gets
     * interesting — the element text carries the block header and indentation.
     */
    fun testVariableInsideFoldedScalarResolves() {
        val reference = variableReference("roles/app/tasks/main.yml", 11, "app_port")
        assertEquals("app_port", reference.rangeInElement.substring(reference.element.text))

        val paths = targetPaths(reference)
        assertTrue("expected many definition sites, got $paths", paths.size >= 10)
        assertTrue(
            "both OS-family candidates must be offered: $paths",
            paths.containsAll(listOf("roles/app/vars/Darwin.yml", "roles/app/vars/RedHat.yml")),
        )
        assertTrue("role vars must be offered: $paths", paths.contains("roles/app/vars/main.yml"))
    }

    /**
     * The list must reflect the caret, not just the variable name.
     *
     * On line 11 the `include_vars` has run but the `set_fact` on line 34 has
     * not, so the OS-family files lead and the `set_fact` is explicitly out of
     * scope. Listing `set_fact = 8500` first here — as a purely name-based list
     * does — tells the reader something untrue about their current position.
     */
    fun testCandidatesReflectThePositionOfTheCaret() {
        val rows = rows(variableReference("roles/app/tasks/main.yml", 11, "app_port"))

        val leading = rows.take(2).sorted()
        assertEquals(
            listOf(
                "include_vars = 8100 | roles/app/vars/Darwin.yml  ·  rank 19  ·  may win on stag; prod",
                "include_vars = 8200 | roles/app/vars/RedHat.yml  ·  rank 19  ·  may win on stag; prod",
            ),
            leading,
        )
        assertTrue(
            "the set_fact has not run at line 11 and must say so: $rows",
            rows.any { it.startsWith("set_fact = 8500") && it.endsWith("not in scope here") },
        )
    }

    /** Four lines later the set_fact has run, and the ordering changes. */
    fun testSameVariableOrdersDifferentlyAfterTheSetFact() {
        val rows = rows(variableReference("roles/app/tasks/configure.yml", 4, "app_port"))

        assertEquals(
            "on stag the guarded set_fact now wins outright",
            "set_fact = 8500 | roles/app/tasks/main.yml  ·  rank 20  ·  WINS on stag",
            rows.first(),
        )
        assertTrue(
            "on prod the guard is false, so the OS-family files may still win: $rows",
            rows.any { it.startsWith("include_vars = 8100") && it.contains("may win on prod") },
        )
    }

    /** Sites are ordered winner, then tied, then in-scope, then out-of-scope. */
    fun testOrderingIsWinnersThenScopeThenRank() {
        val rows = rows(variableReference("roles/app/tasks/configure.yml", 4, "app_port"))
        fun statusOf(row: String) = when {
            row.contains("WINS on") -> 0
            row.contains("may win on") -> 1
            row.endsWith("in scope") -> 2
            else -> 3
        }
        val statuses = rows.map(::statusOf)
        assertEquals("statuses must be non-decreasing: $rows", statuses.sorted(), statuses)
    }

    /**
     * Every row of the "Choose Declaration" popup must be distinguishable.
     *
     * Returning bare PSI leaves made the platform render the variable name plus
     * the project directory, identically, once per site — nine rows saying
     * `app_workers  test-fixture`.
     */
    fun testPickerRowsAreDistinguishable() {
        val rows = rows(variableReference("roles/app/tasks/main.yml", 12, "app_workers"))
        assertEquals("every site must produce a distinct row", rows.size, rows.toSet().size)
        assertEquals(
            listOf(
                "host_vars[prod-web-1] = 16 | inventories/prod/host_vars/prod-web-1.yml  ·  rank 10  ·  WINS on prod (prod-web-1)",
                "host_vars[stag-web-1] = 6 | inventories/stag/host_vars/stag-web-1.yml  ·  rank 10  ·  WINS on stag (stag-web-1)",
                "group_vars[webservers] = 14 | inventories/prod/group_vars/webservers.yml  ·  rank 7  ·  WINS on prod (prod-web-2)",
                "group_vars[webservers] = 4 | inventories/stag/group_vars/webservers.yml  ·  rank 7  ·  WINS on stag (stag-web-2)",
                "group_vars[canary] = 5 | inventories/stag/group_vars/canary.yml  ·  rank 7  ·  in scope",
                "group_vars[platform] = 3 | inventories/stag/group_vars/platform.yml  ·  rank 7  ·  in scope",
                "group_vars/all[all] = 12 | inventories/prod/group_vars/all.yml  ·  rank 4  ·  in scope",
                "group_vars/all[all] = 2 | inventories/stag/group_vars/all.yml  ·  rank 4  ·  in scope",
                "role defaults[app] = 1 | roles/app/defaults/main.yml  ·  rank 2  ·  in scope",
            ),
            rows,
        )
    }

    /**
     * Several variables in one scalar must resolve independently.
     *
     * The `pre_tasks` message carries five `{{ … }}` expressions on a single
     * folded scalar. Caching resolved targets under the element alone gave all
     * five one shared slot, so whichever resolved first answered for the rest —
     * `inventory_hostname` reported `app_port`'s definition sites. The cache is
     * keyed by element *and* range; this pins that.
     */
    fun testEachVariableInOneScalarResolvesIndependently() {
        val playbook = "site-playbook.yml"

        // inventory_hostname is magic: its targets are inventory host origins.
        val hostname = variableReference(playbook, 20, "inventory_hostname")
        val hostnameRows = hostname.targets()
            .mapNotNull { (it as? com.intellij.navigation.NavigationItem)?.name }
        assertTrue(
            "inventory_hostname must resolve to hosts, got $hostnameRows",
            hostnameRows.isNotEmpty() && hostnameRows.all { it.startsWith("host:") },
        )

        // app_port, resolved from the same scalar, must not reuse that answer.
        val port = variableReference(playbook, 21, "app_port")
        assertTrue(
            "app_port must resolve to its own definition sites",
            targetPaths(port).contains("roles/app/vars/main.yml"),
        )

        // app_workers likewise, and the three answers must all differ.
        val workers = variableReference(playbook, 22, "app_workers")
        assertTrue(
            "app_workers must resolve to its own definition sites",
            targetPaths(workers).contains("roles/app/defaults/main.yml"),
        )

        assertNotSame(hostname.targets(), port.targets())
        assertFalse(
            "app_port and app_workers must not share a cache slot",
            targetPaths(port) == targetPaths(workers),
        )
    }

    /** A variable defined only in inventory still navigates. */
    fun testGroupOnlyVariableResolves() {
        val paths = targetPaths(variableReference("site-playbook.yml", 23, "app_log_level"))
        assertEquals(
            listOf(
                "inventories/prod/group_vars/all.yml",
                "inventories/prod/group_vars/webservers.yml",
                "inventories/stag/group_vars/canary.yml",
                "inventories/stag/group_vars/platform.yml",
                "inventories/stag/group_vars/webservers.yml",
            ),
            paths.sorted(),
        )
    }

    /** Jinja inside a value that is itself a variable definition. */
    fun testJinjaInsideAVariableValueResolves() {
        val reference = variableReference("inventories/stag/group_vars/all.yml", 9, "app_port")
        assertTrue(targetPaths(reference).contains("roles/app/vars/main.yml"))
    }

    /** Filter names and attribute access are not variables. */
    fun testFiltersAndAttributesAreNotTreatedAsVariables() {
        val file = myFixture.addFileToProject(
            "probe/tasks/main.yml",
            """
            - name: probe
              ansible.builtin.debug:
                msg: "{{ app_config.changed }} {{ app_port | int }} {{ 'app_port' }}"
            """.trimIndent(),
        )
        val scalar = PsiTreeUtil.findChildrenOfType(file, YAMLScalar::class.java)
            .first { it.textValue.contains("app_config") }
        val names = scalar.references
            .filterIsInstance<AnsibleVariableReference>()
            .map { it.rangeInElement.substring(scalar.text) }

        // `changed` is attribute access, `int` is a filter, the third is a
        // string literal — only the two real variables remain.
        assertEquals(listOf("app_config", "app_port"), names)
    }
}
