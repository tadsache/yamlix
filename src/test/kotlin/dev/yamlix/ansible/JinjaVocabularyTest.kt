package dev.yamlix.ansible

import com.intellij.psi.util.PsiTreeUtil
import dev.yamlix.ansible.refs.AnsibleVariableReference
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Jinja's own vocabulary is not a set of undefined variables.
 *
 * Measured over eight public Ansible repositories, `lookup`, `q`, `defined`
 * and `changed` were among the most frequently "undefined variables" reported
 * — every one of them a Jinja function or test, none of them a variable, and
 * each one an accusation against correct code.
 */
class JinjaVocabularyTest : FleetFixtureTestCase() {

    private fun namesIn(expression: String): List<String> {
        val file = myFixture.configureByText("probe.yml", "key: \"$expression\"")
        val scalar = PsiTreeUtil.findChildrenOfType(file, YAMLScalar::class.java).last()
        return AnsibleVariableReference.identifierRanges(scalar)
            .map { it.substring(scalar.text) }
    }

    fun testLookupIsACallNotAVariable() {
        assertEquals(listOf("path"), namesIn("{{ lookup('file', path) }}"))
    }

    fun testTheQShorthandIsACallToo() {
        assertEquals(listOf("items"), namesIn("{{ q('flattened', items) }}"))
    }

    fun testTestsAfterIsAreNotVariables() {
        assertEquals(listOf("result"), namesIn("{{ result is defined }}"))
        assertEquals(listOf("result"), namesIn("{{ result is not defined }}"))
        assertEquals(listOf("result"), namesIn("{{ result is changed }}"))
    }

    fun testFiltersAreStillSkipped() {
        assertEquals(listOf("port"), namesIn("{{ port | int }}"))
    }

    /** And a genuine variable is still found, including inside a call. */
    fun testRealVariablesSurvive() {
        assertEquals(listOf("base_dir", "name"), namesIn("{{ base_dir }}/{{ name }}"))
        assertEquals(listOf("config_path"), namesIn("{{ lookup('file', config_path) }}"))
    }
}
