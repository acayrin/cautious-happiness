package com.overlaytranslator.translate

/**
 * Replaces known Chinese terms with their preferred English rendering.
 *
 * Applied *before* machine translation so that proper nouns survive the round
 * trip (伊莎 → "Isa" rather than drifting between "Isa" / "Yisha" / "Isha").
 */
class Glossary(entries: Map<String, String>) {

    /** Longest-match-first so 伊莎贝尔 wins over 伊莎. */
    private val sorted: List<Pair<String, String>> = entries
        .filterKeys { it.isNotBlank() }
        .filterValues { it.isNotBlank() }
        .entries
        .sortedByDescending { it.key.length }
        .map { it.key to it.value }

    fun apply(text: String): String {
        if (sorted.isEmpty()) return text
        var out = text
        for ((zh, en) in sorted) {
            if (out.contains(zh)) {
                out = out.replace(zh, en)
            }
        }
        return out
    }

    fun isEmpty(): Boolean = sorted.isEmpty()

    fun toMap(): Map<String, String> = sorted.toMap()

    companion object {
        val EMPTY = Glossary(emptyMap())

        /** Parses one `chinese\tenglish` per line. Empty lines and `#` comments ignored. */
        fun parse(text: String): Glossary {
            val entries = mutableMapOf<String, String>()
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val tab = trimmed.indexOf('\t')
                if (tab <= 0 || tab == trimmed.length - 1) continue
                val zh = trimmed.substring(0, tab).trim()
                val en = trimmed.substring(tab + 1).trim()
                if (zh.isNotEmpty() && en.isNotEmpty()) entries[zh] = en
            }
            return Glossary(entries)
        }
    }
}
