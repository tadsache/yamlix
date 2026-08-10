package dev.yamlix.ansible

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import dev.yamlix.ansible.vars.ResolutionContext
import dev.yamlix.ansible.vars.VarResolution
import dev.yamlix.ansible.vars.VarScope
import dev.yamlix.ansible.vars.VariableResolutionService

/**
 * Asserts the resolution tables of NAVIGATION-CASES.md §2, per host, per scope.
 *
 * The expected values in this file are the ones a real `ansible-playbook` run
 * produced against the fixture — they were not derived from this implementation.
 */
class VariableResolutionTest : AnsibleFixtureTestCase() {

    private val service: VariableResolutionService
        get() = VariableResolutionService.getInstance(project)

    /** `ansible_os_family` on the machine the fixture was verified on. */
    private val darwin = mapOf("ansible_os_family" to "Darwin")

    private fun context(
        host: String,
        inventory: String,
        position: PsiElement? = null,
        facts: Map<String, String> = emptyMap(),
    ) = ResolutionContext(
        host = host,
        inventoryRoot = file("inventories/$inventory"),
        playbook = file("site-playbook.yml"),
        position = position,
        knownFacts = facts,
    )

    /** Each site as `value@scope(file)`, ascending by precedence. */
    private fun trace(resolution: VarResolution): List<String> =
        resolution.sites.map { site ->
            val where = VfsUtilCore.getRelativePath(site.file, projectRoot) ?: site.file.name
            val conditional = if (site.conditional) "?" else ""
            "${site.valueText}$conditional@${site.scope.display}($where)"
        }

    private fun elementAt(path: String, line: Int, token: String): PsiElement {
        val virtualFile = file(path)
        val document: Document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        val start = document.getLineStartOffset(line - 1)
        val lineText = document.getText(TextRange(start, document.getLineEndOffset(line - 1)))
        val column = lineText.indexOf(token)
        require(column >= 0) { "token '$token' not on line $line of $path" }
        return PsiManager.getInstance(project).findFile(virtualFile)!!
            .findElementAt(start + column)!!
    }

    // ---- §2c app_workers: inventory only, so the group graph decides ---------

    fun testAppWorkersStagWeb1() {
        val resolution = service.resolve("app_workers", context("stag-web-1", "stag"))
        assertEquals(
            listOf(
                "1@role defaults(roles/app/defaults/main.yml)",
                "2@group_vars/all(inventories/stag/group_vars/all.yml)",
                "3@group_vars(inventories/stag/group_vars/platform.yml)",
                "4@group_vars(inventories/stag/group_vars/webservers.yml)",
                "5@group_vars(inventories/stag/group_vars/canary.yml)",
                "6@host_vars(inventories/stag/host_vars/stag-web-1.yml)",
            ),
            trace(resolution),
        )
        assertEquals("6", resolution.effectiveValue)
        assertEquals(VarScope.HOST_VARS, resolution.winner?.scope)
    }

    /** Not a member of `canary`, and no host_vars file: webservers wins. */
    fun testAppWorkersStagWeb2() {
        val resolution = service.resolve("app_workers", context("stag-web-2", "stag"))
        assertEquals(
            listOf(
                "1@role defaults(roles/app/defaults/main.yml)",
                "2@group_vars/all(inventories/stag/group_vars/all.yml)",
                "3@group_vars(inventories/stag/group_vars/platform.yml)",
                "4@group_vars(inventories/stag/group_vars/webservers.yml)",
            ),
            trace(resolution),
        )
        assertEquals("4", resolution.effectiveValue)
    }

    fun testAppWorkersProd() {
        assertEquals("16", service.resolve("app_workers", context("prod-web-1", "prod")).effectiveValue)

        val web2 = service.resolve("app_workers", context("prod-web-2", "prod"))
        assertEquals(
            listOf(
                "1@role defaults(roles/app/defaults/main.yml)",
                "12@group_vars/all(inventories/prod/group_vars/all.yml)",
                "14@group_vars(inventories/prod/group_vars/webservers.yml)",
            ),
            trace(web2),
        )
        assertEquals("14", web2.effectiveValue)
    }

    // ---- §2d app_log_level: the group tie-break, isolated --------------------

    /** canary and webservers are both depth 2; priority 10 breaks the tie. */
    fun testAppLogLevelGroupPriorityWins() {
        val resolution = service.resolve("app_log_level", context("stag-web-1", "stag"))
        assertEquals(
            listOf(
                "warning@group_vars(inventories/stag/group_vars/platform.yml)",
                "info@group_vars(inventories/stag/group_vars/webservers.yml)",
                "debug@group_vars(inventories/stag/group_vars/canary.yml)",
            ),
            trace(resolution),
        )
        assertEquals("debug", resolution.effectiveValue)
    }

    fun testAppLogLevelDepthWins() {
        assertEquals("info", service.resolve("app_log_level", context("stag-web-2", "stag")).effectiveValue)
        assertEquals("warning", service.resolve("app_log_level", context("prod-web-1", "prod")).effectiveValue)
        assertEquals("warning", service.resolve("app_log_level", context("prod-web-2", "prod")).effectiveValue)
    }

    // ---- §2a app_port: the full eleven-site gauntlet -------------------------

    fun testAppPortEveryDefinitionSiteStag() {
        val end = elementAt("site-playbook.yml", 42, "app_url")
        val resolution = service.resolve(
            "app_port",
            context("stag-web-1", "stag", position = end, facts = darwin),
        )
        assertEquals(
            listOf(
                "8000@role defaults(roles/app/defaults/main.yml)",
                "8010@group_vars/all(inventories/stag/group_vars/all.yml)",
                "8020@group_vars(inventories/stag/group_vars/platform.yml)",
                "8030@group_vars(inventories/stag/group_vars/webservers.yml)",
                "8040@group_vars(inventories/stag/group_vars/canary.yml)",
                "8050@host_vars(inventories/stag/host_vars/stag-web-1.yml)",
                "8060@play vars(site-playbook.yml)",
                "8070@vars_files(vars/common.yml)",
                "8090@role vars(roles/app/vars/main.yml)",
                "8100@include_vars(roles/app/vars/Darwin.yml)",
                "8500@set_fact(roles/app/tasks/main.yml)",
            ),
            trace(resolution),
        )
        assertEquals("8500", resolution.effectiveValue)
    }

    /**
     * §2b: the winner is a function of *where in the play you are*, not of the
     * host. All four values below came from the verified run.
     */
    fun testAppPortWinnerChangesWithPositionStag() {
        fun at(path: String, line: Int, token: String): String? = service.resolve(
            "app_port",
            context("stag-web-1", "stag", elementAt(path, line, token), darwin),
        ).effectiveValue

        // pre_tasks — role vars of `app` are already in scope. Rule R5.
        assertEquals("8090", at("site-playbook.yml", 21, "app_port"))
        // inside role `app`, after include_vars has run
        assertEquals("8100", at("roles/app/tasks/main.yml", 11, "app_port"))
        // after the guarded set_fact
        assertEquals("8500", at("roles/app/tasks/configure.yml", 4, "app_port"))
        assertEquals("8500", at("site-playbook.yml", 41, "app_port"))
    }

    /** The same positions on prod, where the `when:` guard is false. */
    fun testAppPortWinnerChangesWithPositionProd() {
        fun at(path: String, line: Int, token: String): String? = service.resolve(
            "app_port",
            context("prod-web-1", "prod", elementAt(path, line, token), darwin),
        ).effectiveValue

        assertEquals("8090", at("site-playbook.yml", 21, "app_port"))
        assertEquals("8100", at("roles/app/tasks/main.yml", 11, "app_port"))
        // set_fact is skipped on prod, so include_vars still holds
        assertEquals("8100", at("roles/app/tasks/configure.yml", 4, "app_port"))
        assertEquals("8100", at("site-playbook.yml", 41, "app_port"))
    }

    /**
     * R5, stated as its own test because it is the rule most likely to be got
     * wrong: role vars leak into a *different* role in the same play.
     */
    fun testRoleVarsAreVisibleInsideADifferentRole() {
        val inCommon = elementAt("roles/common/tasks/main.yml", 6, "app_port")
        val resolution = service.resolve(
            "app_port",
            context("stag-web-1", "stag", inCommon, darwin),
        )
        assertEquals("8090", resolution.effectiveValue)
        assertEquals(VarScope.ROLE_VARS, resolution.winner?.scope)
        assertEquals("app", resolution.winner?.qualifier)
    }

    // ---- honesty checks ------------------------------------------------------

    /**
     * §3: with no facts supplied, the fact-templated include_vars is a set. The
     * resolver must report ambiguity rather than pick Darwin over RedHat.
     */
    fun testWithoutFactsTheIncludeVarsTargetIsAmbiguous() {
        val end = elementAt("site-playbook.yml", 42, "app_url")
        val resolution = service.resolve("app_port", context("prod-web-1", "prod", end))

        val candidates = resolution.sites
            .filter { it.scope == VarScope.INCLUDE_VARS }
            .map { "${it.file.name}=${it.valueText}" }
            .sorted()
        assertEquals(listOf("Darwin.yml=8100", "RedHat.yml=8200"), candidates)
        assertTrue("both candidates must be marked conditional", resolution.sites
            .filter { it.scope == VarScope.INCLUDE_VARS }
            .all { it.conditional })
        assertTrue("resolution must be flagged ambiguous", resolution.isAmbiguous)
        assertEquals(
            "the unconditional winner falls back to role vars",
            "8090",
            resolution.winner?.valueText,
        )
    }

    /** Extra vars can override anything and are invisible from the repo. */
    fun testExtraVarsAreAlwaysReportedAsACaveat() {
        val resolution = service.resolve("app_port", context("stag-web-1", "stag"))
        assertTrue(
            "expected an extra-vars caveat, got ${resolution.caveats}",
            resolution.caveats.any { it.contains("extra vars") },
        )
    }

    /** A Jinja-valued variable is stored unexpanded; it is not a literal. */
    fun testJinjaValuedVariableIsNotPreExpanded() {
        val resolution = service.resolve("app_url", context("stag-web-1", "stag"))
        assertEquals(
            "http://{{ inventory_hostname }}:{{ app_port }}/{{ app_name }}",
            resolution.effectiveValue,
        )
    }

    fun testRegisteredVariableIsIndexed() {
        val end = elementAt("site-playbook.yml", 42, "app_url")
        val resolution = service.resolve(
            "app_config",
            context("stag-web-1", "stag", end, darwin),
        )
        assertEquals(VarScope.REGISTERED, resolution.winner?.scope)
        assertEquals("roles/app/tasks/main.yml", VfsUtilCore.getRelativePath(
            resolution.winner!!.file, projectRoot,
        ))
    }

    fun testRoleParamFromMetaDependency() {
        val inCommon = elementAt("roles/common/tasks/main.yml", 5, "common_reason")
        val resolution = service.resolve(
            "common_reason",
            context("stag-web-1", "stag", inCommon, darwin),
        )
        assertEquals(VarScope.ROLE_PARAM, resolution.winner?.scope)
        assertEquals("dependency-of-app", resolution.effectiveValue)
    }
}
