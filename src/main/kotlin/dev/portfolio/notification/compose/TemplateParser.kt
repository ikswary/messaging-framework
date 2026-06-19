package dev.portfolio.notification.compose

object TemplateParser {
    /** Extraction & substitution shared (inner spaces allowed) — mirrors the real StringParameterReplacer. */
    val RE = Regex("""\{\{\s*([\w.]+)\s*}}""")

    fun variables(template: String): List<String> =
        RE.findAll(template).map { it.groupValues[1] }.distinct().toList()
}
