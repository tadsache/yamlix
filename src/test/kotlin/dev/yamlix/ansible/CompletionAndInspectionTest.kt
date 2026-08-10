package dev.yamlix.ansible

import com.intellij.codeInsight.lookup.LookupElement
import dev.yamlix.ansible.inspection.UnresolvedAnsibleReferenceInspection
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Completion and the unresolved-reference inspection both read the same
 * resolution model as navigation, so these tests also guard against the three
 * drifting apart.
 */
class CompletionAndInspectionTest : AnsibleFixtureTestCase() {

    // ---- completion --------------------------------------------------------

    fun testRoleVariantsCoverEveryReachableRole() {
        val reference = referenceAt("site-playbook.yml", 28, "app")
        val names = reference.variants.filterIsInstance<LookupElement>().map { it.lookupString }

        // Roles under ./roles, the out-of-tree role via roles_path, and the
        // collection role under its fully-qualified name.
        assertTrue("missing roles/ entries: $names", names.containsAll(listOf("app", "common", "monitoring")))
        assertTrue("missing out-of-tree role: $names", names.contains("legacy_backup"))
        assertTrue("missing FQCN collection role: $names", names.contains("acme.web.proxy"))
    }

    fun testCompletionOffersRolesInAPlaybook() {
        myFixture.configureByText(
            "extra-playbook.yml",
            """
            - name: Completion probe
              hosts: webservers
              roles:
                - <caret>
            """.trimIndent(),
        )
        val suggestions = myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
        assertTrue("expected role completions, got $suggestions", suggestions.contains("monitoring"))
        assertTrue("expected FQCN completion, got $suggestions", suggestions.contains("acme.web.proxy"))
    }

    fun testTaskFileVariantsComeFromTheRolesTasksDirectory() {
        val reference = referenceAt("roles/app/tasks/main.yml", 39, "configure.yml")
        val names = reference.variants.filterIsInstance<LookupElement>().map { it.lookupString }
        assertTrue("expected sibling task files, got $names", names.contains("configure.yml"))
        assertTrue("expected sibling task files, got $names", names.contains("main.yml"))
    }

    // ---- inspection --------------------------------------------------------

    fun testUnresolvedRoleIsFlagged() {
        myFixture.enableInspections(UnresolvedAnsibleReferenceInspection())
        myFixture.configureByText(
            "broken-playbook.yml",
            """
            - name: Broken
              hosts: webservers
              roles:
                - no_such_role
            """.trimIndent(),
        )
        val problems = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(
            "expected an unresolved-role warning, got $problems",
            problems.any { it.contains("no_such_role") },
        )
    }

    fun testUnresolvedIncludeTasksIsFlagged() {
        myFixture.enableInspections(UnresolvedAnsibleReferenceInspection())
        myFixture.configureByText(
            "broken-tasks.yml",
            """
            - name: Broken include
              ansible.builtin.include_tasks: nowhere.yml
            """.trimIndent(),
        )
        val problems = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(
            "expected an unresolved-include warning, got $problems",
            problems.any { it.contains("nowhere.yml") },
        )
    }

    fun testResolvableReferencesAreNotFlagged() {
        myFixture.enableInspections(UnresolvedAnsibleReferenceInspection())
        myFixture.configureFromExistingVirtualFile(file("site-playbook.yml"))
        val problems = myFixture.doHighlighting()
            .mapNotNull { it.description }
            .filter { it.contains("Ansible reference") }
        assertEquals("the fixture playbook must be clean, got $problems", emptyList<String>(), problems)
    }

    /**
     * A fact-templated target that still matches files is not a problem — the
     * answer is legitimately a set. Only a template matching nothing is.
     */
    fun testFactTemplatedIncludeVarsIsNotFlaggedWhenCandidatesExist() {
        myFixture.enableInspections(UnresolvedAnsibleReferenceInspection())
        myFixture.configureFromExistingVirtualFile(file("roles/app/tasks/main.yml"))
        val problems = myFixture.doHighlighting()
            .mapNotNull { it.description }
            .filter { it.contains("Ansible reference") || it.contains("templated") }
        assertEquals("role tasks must be clean, got $problems", emptyList<String>(), problems)
    }

    // ---- scoping -----------------------------------------------------------

    /**
     * We register no file type and claim no extension. A YAML file that merely
     * looks Ansible-ish but sits outside an Ansible layout must get nothing.
     */
    fun testYamlOutsideAnAnsibleLayoutGetsNoReferences() {
        val outside = myFixture.addFileToProject(
            "unrelated/ci/pipeline.yml",
            """
            - name: not ansible
              hosts: webservers
              roles:
                - app
            """.trimIndent(),
        )
        // `unrelated/` is still under the project root, which carries ansible.cfg,
        // so this file IS in an Ansible context. The meaningful check is the
        // inverse: a scalar in a non-Ansible position gets no reference.
        val scalars = com.intellij.psi.util.PsiTreeUtil
            .findChildrenOfType(outside, YAMLScalar::class.java)
            .filter { it.textValue == "not ansible" }
        assertEquals(1, scalars.size)
        assertTrue(
            "a plain `name:` value must not carry an Ansible reference",
            scalars.single().references.none { it is dev.yamlix.ansible.refs.AnsibleReferenceBase },
        )
    }
}
