package dev.yamlix.ansible.vars

/**
 * The attribute chain written after a variable — `user` in `{{ user.name }}`
 * carries the path `["name"]`.
 *
 * Kept as plain string arithmetic with no PSI in sight so the rules below can
 * be stated and tested one at a time. Getting them wrong is expensive in both
 * directions: a segment invented from a Jinja method call sends the reader
 * looking for a key that was never meant to exist, and a segment dropped means
 * a value the plugin could have shown reads as unknown.
 */
object VariablePath {

    /** How deep a path is followed before the answer is not worth the work. */
    const val MAX_DEPTH = 8

    private val SEGMENT = Regex("""\.(\w+)""")

    /**
     * The path written immediately after [rootEnd] in [expression].
     *
     * Stops at the first thing that is not a bare `.name`, which is what makes
     * this safe on real Jinja: `x.y | default('a.b')` yields `["y"]` and not
     * the contents of the string, and `x[0].y` stops at the bracket rather
     * than pretending an index is a key.
     *
     * A segment followed by `(` is dropped: `path.split('/')` is a Python
     * method on the value, not a key inside it, and offering to navigate to a
     * `split:` key would be an invention. The same is true of `items`, `keys`
     * and friends, which is why the rule is syntactic rather than a list of
     * known method names.
     */
    fun segmentsAfter(expression: String, rootEnd: Int): List<String> {
        val out = ArrayList<String>()
        var at = rootEnd
        while (out.size < MAX_DEPTH) {
            val match = SEGMENT.matchAt(expression, at) ?: break
            val after = expression.drop(match.range.last + 1).trimStart()
            if (after.startsWith('(')) break
            out += match.groupValues[1]
            at = match.range.last + 1
        }
        return out
    }

    /** `user` + `["name"]` reads as `user.name` wherever a name is displayed. */
    fun render(root: String, segments: List<String>): String =
        if (segments.isEmpty()) root else root + "." + segments.joinToString(".")

    /**
     * Whether [expression] guards the value with a `default(...)` filter.
     *
     * A key that is absent on purpose is idiomatic Ansible — `user.group |
     * default(omit)` says outright that `group` may not be there — so a missing
     * key must never be reported as a problem when this is true.
     */
    fun hasDefaultFilter(expression: String): Boolean =
        DEFAULT_FILTER.containsMatchIn(expression)

    /**
     * The `{{ … }}` block [offset] falls inside, or null when it falls outside
     * every block.
     *
     * The block and not the whole scalar: `"{{ a.b }} and {{ c.d | default(1) }}"`
     * defaults only the second one, and judging the string as a whole would
     * quietly excuse the first.
     */
    fun enclosingBlock(text: String, offset: Int): String? {
        val open = text.lastIndexOf("{{", offset)
        if (open < 0) return null
        val close = text.indexOf("}}", open)
        if (close < 0 || close < offset) return null
        return text.substring(open, close + 2)
    }

    private val DEFAULT_FILTER = Regex("""\|\s*default\s*\(""")
}
