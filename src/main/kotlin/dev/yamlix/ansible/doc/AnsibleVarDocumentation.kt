package dev.yamlix.ansible.doc

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.yamlix.ansible.vars.ReportRow
import dev.yamlix.ansible.vars.ValueKind
import dev.yamlix.ansible.vars.VariableReport

/**
 * Renders variable reports as the Quick Documentation popup.
 *
 * Shows *inventory · effective value · defined in*, and anything not statically
 * knowable as unresolved with the raw template. There is deliberately no code
 * path that produces a value for [ValueKind.TEMPLATE], [ValueKind.RUNTIME] or
 * [ValueKind.UNDEFINED] — the raw text is passed through and labelled.
 *
 * Laid out for Swing's `HTMLEditorKit`, which is what the popup renders with.
 * See [sections] for why this is not a table.
 */
object AnsibleVarDocumentation {

    fun render(name: String, reports: List<VariableReport>, base: VirtualFile?): String {
        val html = StringBuilder()

        html.append(DocumentationMarkup.DEFINITION_START)
        html.append("<code>{{ ").append(escape(name)).append(" }}</code>")
        html.append(DocumentationMarkup.DEFINITION_END)

        // A variable Ansible supplies itself has the same answer everywhere, and
        // that answer is not in the repo. A per-inventory breakdown of
        // "unresolved" rows would be noise, so describe what it is instead.
        val magic = reports.firstNotNullOfOrNull { it.magic }
        if (magic != null) {
            html.append(DocumentationMarkup.CONTENT_START)
            html.append("<p>")
                .append(grey("Provided by Ansible &mdash; "))
                .append(
                    when (magic.origin) {
                        dev.yamlix.ansible.vars.MagicOrigin.INVENTORY -> grey("from the inventory")
                        dev.yamlix.ansible.vars.MagicOrigin.FACTS -> grey("from fact gathering")
                        dev.yamlix.ansible.vars.MagicOrigin.RUNTIME -> grey("from the run itself")
                    },
                )
                .append("</p><p>").append(escape(magic.description)).append("</p>")
                .append("<p>")
                .append(grey("No file declares it. Ctrl+Click offers the origin instead."))
                .append("</p>")
            html.append(DocumentationMarkup.CONTENT_END)
            return html.append(caveatSection(reports)).toString()
        }

        html.append(DocumentationMarkup.CONTENT_START)
        if (reports.isEmpty() || reports.all { it.rows.isEmpty() }) {
            html.append("<p>No inventory in this project, so there is no host to resolve against.</p>")
        } else {
            html.append("<p>").append(grey("effective value per inventory")).append("</p>")
        }
        html.append(DocumentationMarkup.CONTENT_END)

        for (report in reports) {
            if (reports.size > 1 && report.playbook != null) {
                html.append(DocumentationMarkup.CONTENT_START)
                    .append("<p>").append(grey("via "))
                    .append(escape(relative(report.playbook, base)))
                    .append("</p>").append(DocumentationMarkup.CONTENT_END)
            }
            html.append(sections(report, base))
        }

        return html.append(caveatSection(reports)).toString()
    }

    private fun caveatSection(reports: List<VariableReport>): String {
        val caveats = reports.flatMap { it.caveats }.distinct()
        if (caveats.isEmpty()) return ""
        val html = StringBuilder()
        html.append(DocumentationMarkup.SECTIONS_START)
        html.append(DocumentationMarkup.SECTION_HEADER_START).append("Cannot be certain")
        html.append(DocumentationMarkup.SECTION_SEPARATOR)
        html.append("<ul style='margin-top:0'>")
        caveats.forEach { html.append("<li>").append(escape(it)).append("</li>") }
        html.append("</ul>")
        html.append(DocumentationMarkup.SECTION_END)
        html.append(DocumentationMarkup.SECTIONS_END)
        return html.toString()
    }

    /**
     * One section per inventory, not a three-column table.
     *
     * The documentation popup renders through Swing's `HTMLEditorKit` — an
     * HTML 3.2 engine with no `white-space` support. Given a table too wide for
     * the popup it shrinks columns until words break mid-character, which turned
     * the "inventory" header into "inv / ent / ory". Long file paths in a third
     * column made that certain.
     *
     * The platform's own `SECTIONS` grid is two columns, short label on the left
     * and everything else on the right, so nothing competes for width. Long
     * values and paths each get a full-width line of their own.
     */
    private fun sections(report: VariableReport, base: VirtualFile?): String {
        if (report.rows.isEmpty()) return ""
        val html = StringBuilder()
        html.append(DocumentationMarkup.SECTIONS_START)

        for (row in report.rows) {
            html.append(DocumentationMarkup.SECTION_HEADER_START)
            html.append(escape(row.inventory))
            html.append(DocumentationMarkup.SECTION_SEPARATOR)

            if (!row.coversWholeInventory) {
                html.append(grey(escape(hostsLabel(row.hosts)))).append("<br>")
            }
            html.append(value(row))

            row.winner?.let { winner ->
                val qualifier =
                    if (winner.qualifier.isNotEmpty()) "[${winner.qualifier}]" else ""
                html.append("<br>")
                    .append(grey("defined in "))
                    .append(escape(winner.scope.display + qualifier))
                    .append(grey(" · rank ${winner.scope.rank}"))
                    .append("<br>")
                    .append(grey(escape(relative(winner.file, base))))
            }
            for (alternative in row.alternatives) {
                html.append("<br>").append(
                    grey(
                        "or " + escape(alternative.valueText ?: "?") +
                            " from " + escape(relative(alternative.file, base)),
                    ),
                )
            }
            row.note?.let { html.append("<br>").append(grey(escape(it))) }

            html.append(DocumentationMarkup.SECTION_END)
        }

        html.append(DocumentationMarkup.SECTIONS_END)
        return html.toString()
    }

    /**
     * Never fabricates: anything not statically knowable is labelled unresolved
     * and the raw template is shown verbatim.
     *
     * Long templates are rendered as plain monospace rather than `<code>`. An
     * inline `<code>` that wraps gets one shaded box per line fragment, which
     * shredded a long `app_url` into five separate boxes.
     */
    private fun value(row: ReportRow): String = when (row.kind) {
        ValueKind.LITERAL -> "<code>${escape(row.value ?: "")}</code>"
        ValueKind.TEMPLATE ->
            grey("unresolved template ") + "<br><tt>${escape(row.value ?: "")}</tt>"
        // No single value is shown: the candidates are listed underneath.
        ValueKind.AMBIGUOUS -> grey("unresolved &mdash; one of the candidates below")
        ValueKind.RUNTIME -> grey("unresolved &mdash; run-time value")
        ValueKind.UNDEFINED -> grey("unresolved &mdash; not defined here")
    }

    private fun relative(file: VirtualFile, base: VirtualFile?): String =
        base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.name

    /**
     * A row that does not cover a whole inventory names the hosts it does
     * apply to. Spelling out all of them for a large group turns the popup
     * into a wall of hostnames, so only the first few are named.
     */
    private fun hostsLabel(hosts: List<String>): String {
        val shown = hosts.take(MAX_HOSTS_NAMED)
        val rest = hosts.size - shown.size
        return if (rest > 0) "${shown.joinToString(", ")}, +$rest more" else shown.joinToString(", ")
    }

    private fun grey(html: String) =
        "<span class='${DocumentationMarkup.CLASS_GRAYED}'>$html</span>"

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)

    private const val MAX_HOSTS_NAMED = 2
}
