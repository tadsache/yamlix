package dev.yamlix.ansible.overview

/**
 * Fitting row text to the width there actually is.
 *
 * A tree row does not wrap and does not shrink: it grows, and the tool window
 * scrolls sideways. Docked to the right at its default width that meant the
 * end of every row — the inventories, the path, the part that says *where* —
 * sat past the edge where nothing suggested it existed.
 *
 * Measuring is passed in rather than taken from a font, so the rule is decided
 * here and tested without a screen.
 */
internal object RowText {

    private const val ELLIPSIS = "…"

    /** Kept clear of the right edge so the last glyph is never clipped. */
    private const val RIGHT_MARGIN = 8

    /** One level of tree indentation, in unscaled pixels. */
    private const val INDENT_PER_LEVEL = 20

    /**
     * Pixels a row has, from where its text starts to the right edge.
     *
     * Derived from the model's [depth], never by asking the tree where the row
     * is. `JTree.getRowBounds` during rendering is a layout question asked from
     * inside layout: the tree calls the renderer to measure the row, the
     * renderer asks for the row's bounds, and the two recur until the stack
     * ends. That is exactly how this method was first written, and it took down
     * the IDE with a StackOverflowError.
     */
    fun availableWidth(visibleWidth: Int, depth: Int, iconWidth: Int): Int {
        if (visibleWidth <= 0) return 0
        val indent = depth.coerceAtLeast(0) * scale(INDENT_PER_LEVEL)
        return visibleWidth - indent - iconWidth - scale(RIGHT_MARGIN)
    }

    private fun scale(px: Int): Int = com.intellij.util.ui.JBUI.scale(px)

    /**
     * [text] shortened until [width] says it fits in [maxPx], with an ellipsis
     * marking what was dropped. Empty when there is no room at all.
     */
    fun fit(text: String, maxPx: Int, width: (String) -> Int): String {
        if (maxPx <= 0) return ""
        if (width(text) <= maxPx) return text
        if (width(ELLIPSIS) > maxPx) return ""

        // Longest prefix that still fits, by bisection: the measurement is a
        // font metric, so it is not proportional to length and cannot be
        // divided out.
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (width(text.take(mid) + ELLIPSIS) <= maxPx) low = mid else high = mid - 1
        }
        return text.take(low).trimEnd() + ELLIPSIS
    }
}
