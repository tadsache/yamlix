package dev.yamlix.ansible

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.yamlix.ansible.inventory.InventoryGraphService
import dev.yamlix.ansible.vars.ResolutionContext
import dev.yamlix.ansible.vars.VariableResolutionService

/**
 * INI-format inventory coverage.
 *
 * The specification fixture is YAML-only, so this uses a separate minimal
 * project under `testData/ini` rather than editing the fixture. `[group:vars]`,
 * `[group:children]` and inline host variables all have to work.
 */
class IniInventoryTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        myFixture.copyDirectoryToProject("ini", "")
    }

    private fun inventoryRoot() = myFixture.findFileInTempDir("inventories/dev")!!

    private fun resolve(name: String, host: String) =
        VariableResolutionService.getInstance(project).resolve(
            name,
            ResolutionContext(
                host = host,
                inventoryRoot = inventoryRoot(),
                playbook = myFixture.findFileInTempDir("site.yml"),
            ),
        )

    fun testChildrenSectionProducesRealDepth() {
        val graph = InventoryGraphService.getInstance(project).graphFor(inventoryRoot())
        assertEquals(setOf("dev-web-1", "dev-web-2"), graph.hosts)

        val ordered = graph.groupsForHost("dev-web-1").map { "${it.name}@${it.depth}" }
        assertEquals(listOf("all@0", "platform@1", "webservers@2"), ordered)
    }

    /** `[all:vars]` < `[platform:vars]` < `[webservers:vars]` < inline host var. */
    fun testInlineHostVarBeatsEveryGroupSection() {
        val resolution = resolve("app_port", "dev-web-1")
        assertEquals(
            listOf("7000", "7010", "7020", "7030", "7050"),
            resolution.sites.map { it.valueText },
        )
        assertEquals("7050", resolution.effectiveValue)
    }

    fun testHostWithoutInlineVarsFallsBackToItsDeepestGroup() {
        val resolution = resolve("app_port", "dev-web-2")
        assertEquals(listOf("7000", "7010", "7020", "7030"), resolution.sites.map { it.valueText })
        assertEquals("7030", resolution.effectiveValue)
    }

    fun testAllVarsSectionIsIndexed() {
        assertEquals("dev", resolve("app_env", "dev-web-2").effectiveValue)
    }
}
