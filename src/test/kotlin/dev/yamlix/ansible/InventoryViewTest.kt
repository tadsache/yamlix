package dev.yamlix.ansible

import dev.yamlix.ansible.overview.FileVariableView
import dev.yamlix.ansible.overview.FileVariableViewService
import dev.yamlix.ansible.overview.FileViewTree
import dev.yamlix.ansible.overview.GroupNode
import dev.yamlix.ansible.overview.SectionNode
import dev.yamlix.ansible.overview.ViewState

/**
 * What the tool window says when you are standing in an inventory.
 *
 * A group's name is written in one file and consumed in another, with nothing
 * linking the two — "who targets this group" has no answer anywhere else in
 * the IDE.
 */
class InventoryViewTest : FleetFixtureTestCase() {

    private fun view(path: String): FileVariableView =
        when (val state = FileVariableViewService.getInstance(project).build(file(path))) {
            is ViewState.Ready -> state.view
            else -> error("no view for $path: $state")
        }

    /**
     * An INI inventory is not YAML, and the YAML gate used to come first — so
     * standing in `inventories/env-a/hosts` was told to open a file inside an
     * Ansible project, while inside one.
     */
    fun testIniInventoryIsRecognised() {
        val view = view("inventories/env-a/hosts")
        assertEquals(listOf("containers", "web_app"), view.groups.map { it.name })
    }

    fun testGroupNamesThePlaysThatTargetIt() {
        val group = view("inventories/env-a/hosts").groups.single { it.name == "web_app" }
        assertEquals(2, group.hostCount)
        assertEquals(
            listOf("site-probe-adjacent.yml", "site-probe-multiplay.yml"),
            group.targetedBy.map { it.playbook.name }.sorted(),
        )
        assertTrue("each targets it squarely", group.targetedBy.all { it.exact })
    }

    /** A group nothing aims at is worth noticing — it may be dead. */
    fun testGroupTargetedByNothingSaysSo() {
        val group = view("inventories/env-c/hosts").groups.single { it.name == "special_group" }
        assertTrue(group.targetedBy.isEmpty())
        assertTrue(
            "says so in the row",
            GroupNode(group).detail.contains("targeted by nothing"),
        )
    }

    /** The `group_vars` that apply are listed with it. */
    fun testGroupVarsFilesAreListed() {
        val group = view("inventories/env-c/hosts").groups.single { it.name == "special_group" }
        assertEquals(listOf("special_group.yml"), group.varsFiles.map { it.name })
    }

    /**
     * Plays whose pattern cannot be evaluated are counted, not dropped.
     *
     * The fixture has a glob, a templated pattern and `localhost`. Omitting
     * them silently would present the list of plays as complete when one of
     * them may well target the group.
     */
    fun testUnevaluablePatternsAreCountedRatherThanDropped() {
        val view = view("inventories/env-a/hosts")
        assertEquals(3, view.groups.first().unevaluatedPlays)

        val section = FileViewTree.build(ViewState.Ready(view))
            .single { (it.node as? SectionNode)?.text == "Groups" }
        assertTrue(
            "and the caveat is stated once, on the section: ${section.node.detail}",
            section.node.detail!!.contains("cannot be evaluated"),
        )
    }

    /**
     * `hosts: all` targets every group squarely.
     *
     * `eligibleHosts` returns null both for "unrestricted" and for "cannot be
     * worked out"; treating the two alike files a play that runs on every host
     * under "cannot be evaluated" and hides it.
     */
    fun testHostsAllIsTreatedAsTargetingEveryGroup() {
        val group = view("inventories/env-a/hosts").groups.single { it.name == "containers" }
        assertEquals(3, group.targetedBy.size)
    }

    /** The caret standing on a group header selects that group. */
    fun testCaretInsideAGroupHeaderFindsIt() {
        val view = view("inventories/env-a/hosts")
        val text = file("inventories/env-a/hosts").contentsToByteArray().decodeToString()
        assertEquals("web_app", view.groupAt(text.indexOf("[web_app]") + 2)?.name)
    }

    /** Ordinary lines are not group headers, and are not guessed at. */
    fun testCaretOutsideAnyHeaderMatchesNothing() {
        val view = view("inventories/env-a/hosts")
        val text = file("inventories/env-a/hosts").contentsToByteArray().decodeToString()
        assertNull(view.groupAt(text.indexOf("a-host-02")))
    }

    // ---- Ctrl-click from a group ---------------------------------------------

    /**
     * Ctrl-click on `[web_app]` goes to the plays that run on it.
     *
     * Inverted from the usual direction on purpose: the caret is already on the
     * definition, so the platform said "Cannot find declaration to go to". The
     * jump worth having from a group is to where it is consumed.
     */
    fun testGotoFromAGroupLandsOnThePlaysThatTargetIt() {
        val inventory = file("inventories/env-a/hosts")
        val text = inventory.contentsToByteArray().decodeToString()
        val handler = dev.yamlix.ansible.refs.AnsibleGroupGotoDeclarationHandler()

        val source = myFixture.psiManager.findFile(inventory)!!
        val targets = handler.getGotoDeclarationTargets(
            source, text.indexOf("[web_app]") + 2, null,
        )

        assertNotNull("a group with plays behind it must offer them", targets)
        assertEquals(
            listOf("site-probe-adjacent.yml", "site-probe-multiplay.yml"),
            targets!!.map { it.containingFile.virtualFile.name }.sorted(),
        )
        assertTrue(
            "and lands on the hosts: line, not the top of the file",
            targets.all { (it as org.jetbrains.yaml.psi.YAMLKeyValue).keyText == "hosts" },
        )
    }

    /** A caret that is not on a group name offers nothing rather than guessing. */
    fun testGotoFromAHostLineOffersNothing() {
        val inventory = file("inventories/env-a/hosts")
        val text = inventory.contentsToByteArray().decodeToString()
        val source = myFixture.psiManager.findFile(inventory)!!

        assertNull(
            dev.yamlix.ansible.refs.AnsibleGroupGotoDeclarationHandler()
                .getGotoDeclarationTargets(source, text.indexOf("a-host-02"), null),
        )
    }

    /** A group no play targets offers nothing, rather than an empty popup. */
    fun testGotoFromAnUnusedGroupOffersNothing() {
        val inventory = file("inventories/env-c/hosts")
        val text = inventory.contentsToByteArray().decodeToString()
        val source = myFixture.psiManager.findFile(inventory)!!

        assertNull(
            dev.yamlix.ansible.refs.AnsibleGroupGotoDeclarationHandler()
                .getGotoDeclarationTargets(source, text.indexOf("[special_group]") + 2, null),
        )
    }

    /**
     * The panel and Ctrl-click must not be able to disagree: both ask the same
     * service, and this pins that they answer alike.
     */
    fun testNavigationAndThePanelAgree() {
        val inventory = file("inventories/env-a/hosts")
        val text = inventory.contentsToByteArray().decodeToString()
        val source = myFixture.psiManager.findFile(inventory)!!

        val navigated = dev.yamlix.ansible.refs.AnsibleGroupGotoDeclarationHandler()
            .getGotoDeclarationTargets(source, text.indexOf("[containers]") + 2, null)!!
            .map { it.containingFile.virtualFile.name }.sorted()
        val shown = view("inventories/env-a/hosts").groups
            .single { it.name == "containers" }
            .targetedBy.map { it.playbook.name }.sorted()

        assertEquals(shown, navigated)
    }
}
