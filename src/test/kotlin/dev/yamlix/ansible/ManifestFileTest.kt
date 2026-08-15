package dev.yamlix.ansible

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import dev.yamlix.ansible.overview.FileVariableViewService
import dev.yamlix.ansible.overview.RowStatus
import dev.yamlix.ansible.overview.ViewState
import dev.yamlix.ansible.vars.AnsibleVarIndex

/**
 * F23 — a manifest's keys are its schema, not variables.
 *
 * Every plain-mapping YAML used to be indexed key by key, on the assumption
 * that it might be something a `vars_files:` names. Ansible repositories are
 * full of files where that is false. kubespray's `galaxy.yml` was indexed as
 * thirteen variables — `namespace`, `name`, `version`, `dependencies`,
 * `readme`, `description` — names common enough to be offered in completion
 * and to collide with real variables elsewhere in the project.
 *
 * Opening the file made it plainer still: thirteen rows under "Defines", each
 * marked "unresolved — not defined in this project", about a file that
 * defines all of them on screen.
 */
class ManifestFileTest : FleetFixtureTestCase() {

    private val galaxy = """
        ---
        namespace: kubernetes_sigs
        name: kubespray
        version: 2.32.0
        readme: README.md
        description: Deploy a production ready Kubernetes cluster
        dependencies:
          ansible.utils: '>=2.5.0'
    """.trimIndent()

    /**
     * Asked of the file rather than of the whole index: the index is keyed by
     * name across every project the test JVM has seen, so "is `version` a key
     * anywhere" answers a different and much weaker question.
     */
    private fun definesAnythingNamed(path: String, name: String): Boolean =
        FileBasedIndex.getInstance()
            .getContainingFiles(AnsibleVarIndex.NAME, name, GlobalSearchScope.allScope(project))
            .contains(file(path))

    fun testAGalaxyManifestDefinesNoVariables() {
        myFixture.addFileToProject("galaxy.yml", galaxy)
        for (invented in listOf("namespace", "version", "readme", "description", "dependencies")) {
            assertFalse(
                "`$invented` is galaxy.yml's schema, not a variable",
                definesAnythingNamed("galaxy.yml", invented),
            )
        }
    }

    /** ...and the tool window has nothing to say about it, rather than thirteen wrong things. */
    fun testTheToolWindowShowsNoDefinitionsForAManifest() {
        myFixture.addFileToProject("galaxy.yml", galaxy)
        val state = FileVariableViewService.getInstance(project).build(file("galaxy.yml"))
        val defines = (state as? ViewState.Ready)?.view?.defines.orEmpty()
        assertEquals("nothing in a manifest is a definition: $defines", 0, defines.size)
    }

    /** CI lives in the same repository and is not Ansible either. */
    fun testWorkflowFilesAreNotIndexed() {
        myFixture.addFileToProject(
            ".github/workflows/ci.yml",
            "---\njobs:\n  build:\n    steps:\n      - uses: actions/checkout@v4\n",
        )
        assertFalse(
            "a workflow's `jobs:` is not an Ansible variable",
            definesAnythingNamed(".github/workflows/ci.yml", "jobs"),
        )
    }

    /** A real vars file in an unusual place is still indexed — the list is narrow. */
    fun testAnOrdinaryVarsFileIsStillIndexed() {
        myFixture.addFileToProject("shared/tuning.yml", "---\nsome_tuning_knob: 42\n")
        assertTrue(
            "the manifest list must not swallow genuine vars files",
            definesAnythingNamed("shared/tuning.yml", "some_tuning_knob"),
        )
    }

    /**
     * A definition no play reaches says so, and shows what it says.
     *
     * "Not defined in this project", printed beside the line defining it, is a
     * contradiction the reader has to stop and argue with.
     */
    fun testAnUnreachedDefinitionIsNotCalledUndefined() {
        myFixture.addFileToProject("shared/tuning.yml", "---\nsome_tuning_knob: 42\n")
        val row = (FileVariableViewService.getInstance(project).build(file("shared/tuning.yml"))
            as ViewState.Ready).view.defines.single { it.name == "some_tuning_knob" }

        if (row.status == RowStatus.UNRESOLVED) {
            assertEquals("defined here, but no play brings this file into scope", row.note)
        }
        assertEquals("and it shows what the file actually says", "42", row.summary)
    }
}
