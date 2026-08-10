package dev.yamlix.ansible.doc

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.yamlix.ansible.vars.ReportRow
import dev.yamlix.ansible.vars.ValueKind
import dev.yamlix.ansible.vars.VarSite
import dev.yamlix.ansible.vars.VariableReport

/**
 * Renders variable reports as the Quick Documentation popup.
 *
 * The contract from the brief: a table of *inventory | effective value |
 * defined in*, and anything not statically knowable shown as unresolved with
 * the raw template. There is deliberately no code path that produces a value
 * for [ValueKind.TEMPLATE], [ValueKind.RUNTIME] or [ValueKind.UNDEFINED] — the
 * raw text is passed through and labelled.
 */
object AnsibleVarDocumentation {

    fun render(name: String, reports: List<VariableReport>, base: VirtualFile?): String {
        val html = StringBuilder()

        html.append(DocumentationMarkup.DEFINITION_START)
        html.append("<code>{{ ").append(escape(name)).append(" }}</code>")
        html.append(DocumentationMarkup.DEFINITION_END)

        html.append(DocumentationMarkup.CONTENT_START)
        if (reports.isEmpty() || reports.all { it.rows.isEmpty() }) {
            html.append("<p>No inventory in this project, so there is no host to resolve against.</p>")
        }
        // A variable Ansible supplies itself has the same answer everywhere, and
        // that answer is not in the repo. A per-inventory table of "unresolved"
        // rows would be noise, so describe what it is instead.
        val magic = reports.firstNotNullOfOrNull { it.magic }
        if (magic != null) {
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

        for (report in reports) {
            if (reports.size > 1 && report.playbook != null) {
                html.append("<p><b>via ")
                    .append(escape(relative(report.playbook, base)))
                    .append("</b></p>")
            }
            html.append(table(report, base))
        }
        html.append(DocumentationMarkup.CONTENT_END)

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

    private fun table(report: VariableReport, base: VirtualFile?): String {
        if (report.rows.isEmpty()) return ""
        val html = StringBuilder()
        html.append("<table style='margin-top:4px' cellpadding='2' cellspacing='0'>")
        html.append("<tr>")
            .append(th("inventory")).append(th("effective value")).append(th("defined in"))
            .append("</tr>")

        for (row in report.rows) {
            html.append("<tr valign='top'>")
            html.append(td(escape(inventoryLabel(row))))
            html.append(td(value(row)))
            html.append(td(definedIn(row, base)))
            html.append("</tr>")

            if (row.note != null) {
                html.append("<tr valign='top'><td></td><td colspan='2'>")
                    .append(grey(escape(row.note)))
                    .append("</td></tr>")
            }
            for (alternative in row.alternatives) {
                html.append("<tr valign='top'><td></td><td colspan='2'>")
                    .append(grey("or " + escape(alternative.valueText ?: "?") + " from " +
                        escape(relative(alternative.file, base))))
                    .append("</td></tr>")
            }
        }
        html.append("</table>")
        return html.toString()
    }

    private fun inventoryLabel(row: ReportRow): String =
        if (row.coversWholeInventory) {
            row.inventory
        } else {
            "${row.inventory} (${row.hosts.joinToString(", ")})"
        }

    /**
     * Never fabricates. A template is shown verbatim inside `<code>`, prefixed
     * with a marker so it cannot be mistaken for a resolved value.
     */
    private fun value(row: ReportRow): String = when (row.kind) {
        ValueKind.LITERAL -> "<code>${escape(row.value ?: "")}</code>"
        ValueKind.TEMPLATE -> "<code>${escape(row.value ?: "")}</code> " + grey("(unresolved)")
        // No single value is shown: the candidates are listed underneath.
        ValueKind.AMBIGUOUS -> grey("unresolved &mdash; one of the candidates below")
        ValueKind.RUNTIME -> grey("unresolved &mdash; run-time value")
        ValueKind.UNDEFINED -> grey("unresolved &mdash; not defined here")
    }

    private fun definedIn(row: ReportRow, base: VirtualFile?): String {
        val winner: VarSite = row.winner ?: return grey("&mdash;")
        val path = relative(winner.file, base)
        val qualifier = if (winner.qualifier.isNotEmpty()) "[${winner.qualifier}]" else ""
        return "${escape(winner.scope.display)}${escape(qualifier)}<br>" +
            grey(escape(path) + "  ·  rank " + winner.scope.rank)
    }

    private fun relative(file: VirtualFile, base: VirtualFile?): String =
        base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.name

    private fun th(text: String) =
        "<td style='padding-right:12px'><b>$text</b></td>"

    private fun td(html: String) = "<td style='padding-right:12px'>$html</td>"

    private fun grey(html: String) =
        "<span class='${DocumentationMarkup.CLASS_GRAYED}'>$html</span>"

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)
}
