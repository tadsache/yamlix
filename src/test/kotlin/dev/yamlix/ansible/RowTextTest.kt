package dev.yamlix.ansible

import dev.yamlix.ansible.overview.RowText
import junit.framework.TestCase

/**
 * Fitting a row to the width it has.
 *
 * Measured in "pixels" of one unit per character so the rule can be checked
 * without a font or a screen.
 */
class RowTextTest : TestCase() {

    private val perChar: (String) -> Int = { it.length }

    fun testTextThatFitsIsUntouched() {
        assertEquals("group_vars", RowText.fit("group_vars", 20, perChar))
    }

    fun testTextIsShortenedWithAnEllipsis() {
        val fitted = RowText.fit("inventories/env-b/host_vars/b-host-03.yml", 10, perChar)
        assertEquals(10, fitted.length)
        assertTrue("marks what was dropped: $fitted", fitted.endsWith("…"))
        assertTrue("keeps the front: $fitted", fitted.startsWith("invent"))
    }

    /** Room for nothing is not room for an ellipsis pretending to be content. */
    fun testNoRoomYieldsNothing() {
        assertEquals("", RowText.fit("group_vars", 0, perChar))
        assertEquals("", RowText.fit("group_vars", -5, perChar))
    }

    fun testWidthOfOneFitsOnlyTheEllipsis() {
        assertEquals("…", RowText.fit("group_vars", 1, perChar))
    }

    /** Trailing space before the ellipsis reads as a gap, so it is trimmed. */
    fun testTrailingSpaceIsTrimmedBeforeTheEllipsis() {
        assertEquals("env-c…", RowText.fit("env-c (special_group)", 6, perChar))
    }

    /** A proportional font measures unevenly; the fit must still be maximal. */
    fun testUsesTheMeasurementRatherThanTheLength() {
        // "i" is thin, "W" is wide.
        val width: (String) -> Int = { text -> text.sumOf { if (it == 'W') 3L else 1L }.toInt() }
        assertEquals("iii…", RowText.fit("iiiWWW", 4, width))
    }

    // ---- how much width a row has -------------------------------------------

    fun testDeeperRowsHaveLessWidth() {
        val shallow = RowText.availableWidth(visibleWidth = 400, depth = 1, iconWidth = 16)
        val deep = RowText.availableWidth(visibleWidth = 400, depth = 3, iconWidth = 16)
        assertTrue("indentation costs width: $shallow vs $deep", deep < shallow)
        assertTrue("but there is still some left", deep > 0)
    }

    /** Before the first layout there is no width, and no basis for guessing. */
    fun testNoVisibleWidthYieldsNothing() {
        assertEquals(0, RowText.availableWidth(visibleWidth = 0, depth = 1, iconWidth = 16))
    }

    /**
     * Width comes from the model's depth, never from asking the tree where a
     * row is. `JTree.getRowBounds` called while rendering is a layout question
     * asked from inside layout, and the first version of this did exactly that:
     * the tree measured the row, the renderer asked for its bounds, and the two
     * recurred until the IDE died with a StackOverflowError. Nothing here may
     * take a tree.
     */
    fun testWidthIsComputedFromPlainNumbers() {
        val parameters = RowText::class.java
            .methods.single { it.name == "availableWidth" }
            .parameterTypes
        assertTrue(
            "no Swing component may be a parameter: ${parameters.map { it.simpleName }}",
            parameters.all { it == Int::class.javaPrimitiveType },
        )
    }
}
