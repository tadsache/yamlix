package dev.yamlix.ansible

import dev.yamlix.ansible.refs.GroupKeyConvention

/**
 * The negative half of [GroupKeyConventionTest]: a project whose playbooks
 * never interpolate `hosts:` establishes no group-valued keys at all, so
 * nothing here behaves any differently than before the convention existed.
 */
class GroupKeyConventionAbsentTest : AnsibleFixtureTestCase() {

    fun testNoConventionIsInventedForAProjectThatDoesNotUseOne() {
        val keys = GroupKeyConvention.getInstance(project).keys(file("site-playbook.yml"))
        assertEquals(
            "test-fixture writes `hosts: webservers` literally, so there is " +
                "no variable to infer anything from",
            emptySet<String>(),
            keys,
        )
    }
}
