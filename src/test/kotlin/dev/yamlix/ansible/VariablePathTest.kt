package dev.yamlix.ansible

import dev.yamlix.ansible.vars.VariablePath

/**
 * Reading `user.name` out of a Jinja expression, before any PSI is involved.
 *
 * Every rule here exists because getting it wrong is expensive in one of two
 * directions: a segment invented from Jinja's own vocabulary sends a reader
 * hunting for a key nobody wrote, and a segment dropped throws away a value
 * the plugin could have shown.
 */
class VariablePathTest : junit.framework.TestCase() {

    private fun path(expression: String, root: String): List<String> =
        VariablePath.segmentsAfter(expression, expression.indexOf(root) + root.length)

    fun testAPlainChainIsRead() {
        assertEquals(listOf("name"), path("{{ user.name }}", "user"))
        assertEquals(listOf("a", "b", "c"), path("{{ x.a.b.c }}", "x"))
    }

    fun testABareVariableHasNoPath() {
        assertEquals(emptyList<String>(), path("{{ user }}", "user"))
    }

    /** `path.split('/')` is a method on the value, not a key inside it. */
    fun testAMethodCallIsNotASegment() {
        assertEquals(emptyList<String>(), path("{{ p.split('/') }}", "p"))
        assertEquals(listOf("home"), path("{{ u.home.split('/') }}", "u"))
    }

    /** A filter ends the path, and its arguments are not part of it. */
    fun testAFilterEndsThePath() {
        assertEquals(listOf("group"), path("{{ user.group | default('a.b') }}", "user"))
    }

    /** An index is not a key, and the two are not silently merged. */
    fun testASubscriptStopsTheWalk() {
        assertEquals(emptyList<String>(), path("{{ hosts[0].name }}", "hosts"))
    }

    fun testTheChainIsBounded() {
        val deep = "{{ x" + ".a".repeat(40) + " }}"
        assertEquals(VariablePath.MAX_DEPTH, path(deep, "x").size)
    }

    /**
     * `| default(...)` beside a use says an absent key was intended. Nothing
     * acts on this yet; it is the exemption a "missing key" warning will need
     * before it can be shown without accusing idiomatic Ansible.
     */
    fun testADefaultFilterIsRecognised() {
        assertTrue(VariablePath.hasDefaultFilter("{{ user.group | default(omit) }}"))
        assertTrue(VariablePath.hasDefaultFilter("{{ user.group|default(user.name) }}"))
        assertFalse(VariablePath.hasDefaultFilter("{{ user.group }}"))
    }
}
