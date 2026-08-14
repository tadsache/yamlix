package dev.yamlix.ansible

import dev.yamlix.ansible.refs.GroupKeyConvention

/**
 * FLEET-FIXTURE-CASES.md F7 — a key the project itself establishes as holding
 * an inventory group name.
 *
 * The positive and negative cases matter equally. An inferred rule is only
 * defensible if it stays silent on projects that do not use the convention:
 * `hostgroup:` is also an ordinary field of `theforeman.foreman`'s modules,
 * where treating it as a group name would be wrong.
 */
class GroupKeyConventionTest : FleetFixtureTestCase() {

    fun testKeyIsDiscoveredFromTheHostsExpressionThatConsumesIt() {
        val convention = GroupKeyConvention.getInstance(project)
        val anchor = file("site-container-mon.yml")

        assertEquals(
            "only `hostgroup`, and only because shared/noop.yml interpolates it",
            setOf("hostgroup"),
            convention.keys(anchor),
        )
        assertEquals(
            "the discovery must be answerable — this is what tells a reader why",
            listOf("noop.yml"),
            convention.sources("hostgroup", anchor).map { it.name },
        )
    }

    /** F7 — and the value under it navigates to the group in every inventory. */
    fun testHostgroupValueResolvesToTheGroupDeclarations() {
        val reference = referenceAt("site-container-mon.yml", 8, "containers")
        assertEquals(
            "one `[containers]` header per INI inventory",
            4,
            reference.targets().size,
        )
        assertFalse(
            "inferred from a convention, not from syntax — never flag it as unresolved",
            reference.reportWhenUnresolved,
        )
    }
}
