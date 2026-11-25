package com.cosmod.launcherglass.update

internal object ReleaseNotes {
    private val languageHeader = Regex("""^#{2,6}\s+(Русский|English)\s*$""", RegexOption.IGNORE_CASE)
    private val buildHeader = Regex("""^###\s+(?:Сборка|Build)\s+(\d+)\b.*$""")
    private val linkOnly = Regex("""^\[[^]]+]\([^)]+\)$""")
    private val link = Regex("""\[([^]]+)]\([^)]+\)""")
    private val heading = Regex("""^#{1,6}\s+""")
    private val bullet = Regex("""^[-*]\s+""")

    fun published(markdown: String, language: String): List<String> =
        localized(markdown, language).mapNotNull(::format).take(30).toList()

    fun fromChangelog(markdown: String, build: Int, language: String): List<String> =
        localized(markdown, language)
            .dropWhile { buildHeader.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() != build }
            .drop(1)
            .takeWhile { !it.startsWith("### ") && !it.startsWith("## ") }
            .mapNotNull(::format).take(30).toList()

    private fun localized(markdown: String, language: String): Sequence<String> {
        val lines = markdown.lineSequence().map(String::trim).toList()
        val desired = if (language == "ru") "Русский" else "English"
        val available = lines.indexOfFirst { languageHeader.matches(it) }
        if (available == -1) return lines.asSequence()
        val preferred = lines.indexOfFirst {
            languageHeader.matchEntire(it)?.groupValues?.get(1).equals(desired, ignoreCase = true)
        }
        return lines.asSequence().drop((if (preferred == -1) available else preferred) + 1)
            .takeWhile { !languageHeader.matches(it) }
    }

    private fun format(line: String): String? {
        if (line.isBlank() || linkOnly.matches(line) || line in setOf("---", "***", "___")) return null
        return line.replace(heading, "").replace(bullet, "• ").replace(link, "$1")
            .replace("**", "").replace("__", "").replace("\u0060", "")
    }
}
