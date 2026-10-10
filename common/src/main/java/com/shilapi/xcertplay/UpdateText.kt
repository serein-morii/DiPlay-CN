package com.shilapi.xcertplay

/** Tag comparison and release-note cleanup with no Android runtime. */
internal object UpdateText {
    fun normalizeTag(value: String): String =
        value.trim().removePrefix("v").removePrefix("V")

    fun plainNotes(markdown: String, limit: Int = 1800): String {
        val text = markdown
            .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
            .replace(Regex("""[*_`#>-]+"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        return if (text.length <= limit) text else text.take(limit).trimEnd() + "…"
    }
}
