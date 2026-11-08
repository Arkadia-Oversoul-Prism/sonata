package com.arkadia.sonata.canvas

/**
 * The text cleaning contract.
 *
 * The specification's requirement is precise: extracted text is converted to a
 * natural, readable format **without emojis or symbols**, because a TTS engine
 * pronounces them oddly — it reads "😀" as nothing, "—" as "dash", and "e.g." as
 * "e g". The cleaning is therefore not cosmetic: it is what makes the narration
 * sound like a person reading rather than a machine decoding.
 *
 * Every rule is pure and total, so the contract is testable exhaustively without a
 * device. The rules are ordered, and the order matters: markup is removed before
 * symbols are normalised, because a markdown `**` is not a symbol to normalise but
 * markup to remove.
 *
 * The original text is **never** touched. This produces the cleaned representation
 * that Phase 1 stores in `Document.cleanedText`; the original stays in
 * `Document.originalText` so the canvas can show the user their own text while the
 * engine speaks the normalised one.
 */
object TextNormalizer {

    /**
     * Clean [raw] for narration.
     *
     * The pipeline is: strip markup → strip emoji → normalise symbols → expand
     * abbreviations → normalise whitespace. Each stage is a separate function so a
     * failure can be attributed to one rule rather than to "the cleaner".
     */
    fun clean(raw: String): String {
        var text = raw
        text = stripHtml(text)
        text = stripMarkdown(text)
        text = flattenTables(text)
        text = stripEmoji(text)
        text = normaliseSymbols(text)
        text = expandAbbreviations(text)
        text = normaliseWhitespace(text)
        return text.trim()
    }

    // ── Markup ───────────────────────────────────────────────────────────────

    /** Remove HTML tags and decode the entities that would otherwise be spoken. */
    fun stripHtml(text: String): String {
        val withoutTags = text.replace(Regex("<[^>]+>"), " ")
        return withoutTags
            .replace("&nbsp;", " ")
            .replace("&amp;", " and ")
            .replace("&lt;", " less than ")
            .replace("&gt;", " greater than ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&mdash;", "\u2014")
            .replace("&ndash;", "\u2013")
    }

    /**
     * Remove markdown syntax, keeping the words.
     *
     * Order matters: fenced code blocks are removed first, because their contents
     * may contain characters that look like other markdown.
     */
    fun stripMarkdown(text: String): String {
        var t = text
        t = t.replace(Regex("```[\\s\\S]*?```"), " ")          // fenced code
        t = t.replace(Regex("`([^`]*)`"), "$1")                 // inline code
        t = t.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "$1") // images
        t = t.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")  // links
        t = t.replace(Regex("^\\s{0,3}#{1,6}\\s*", RegexOption.MULTILINE), "") // headings
        t = t.replace(Regex("^\\s{0,3}>\\s?", RegexOption.MULTILINE), "")      // blockquote
        t = t.replace(Regex("^\\s{0,3}[-*+]\\s+", RegexOption.MULTILINE), "")  // bullets
        t = t.replace(Regex("^\\s{0,3}\\d+\\.\\s+", RegexOption.MULTILINE), "") // ordered
        t = t.replace(Regex("^\\s{0,3}([-*_])\\s*\\1\\s*\\1\\s*$", RegexOption.MULTILINE), " ") // hr
        t = t.replace(Regex("(\\*\\*|__)(.*?)\\1"), "$2")       // bold
        t = t.replace(Regex("(\\*|_)(.*?)\\1"), "$2")           // italic
        t = t.replace(Regex("~~(.*?)~~"), "$1")                 // strikethrough
        return t
    }

    /**
     * Flatten a markdown table into spoken sentences.
     *
     * A table read cell-by-cell is unintelligible. Each row becomes one sentence of
     * the form "Header: value, header: value." so the relationship between a value
     * and its column survives being spoken.
     */
    fun flattenTables(text: String): String {
        val lines = text.lines()
        val out = StringBuilder()
        var header: List<String>? = null
        for (line in lines) {
            val trimmed = line.trim()
            val isRow = trimmed.startsWith("|") && trimmed.endsWith("|")
            if (!isRow) {
                header = null
                out.append(line).append('\n')
                continue
            }
            val cells = trimmed.trim('|').split('|').map { it.trim() }
            // The separator row (|---|---|) carries no content.
            if (cells.all { it.matches(Regex(":?-{2,}:?")) }) continue
            if (header == null) {
                header = cells
                continue
            }
            val pairs = cells.mapIndexedNotNull { i, cell ->
                if (cell.isBlank()) null
                else "${header.getOrElse(i) { "Column ${i + 1}" }}: $cell"
            }
            if (pairs.isNotEmpty()) out.append(pairs.joinToString(", ")).append(".\n")
        }
        return out.toString()
    }

    // ── Emoji ────────────────────────────────────────────────────────────────

    /**
     * Remove emoji, including astral-plane ones.
     *
     * Walks **code points**, not chars. The previous implementation iterated
     * `Char`s, so an astral-plane emoji (which is a surrogate pair) was seen as two
     * unpaired surrogates and neither matched — the emoji survived and was spoken
     * as a replacement character. Walking code points is what fixes it.
     */
    fun stripEmoji(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val count = Character.charCount(cp)
            if (!isEmoji(cp)) out.appendCodePoint(cp)
            i += count
        }
        return out.toString()
    }

    /** Whether [codePoint] is an emoji or a pictographic symbol. */
    private fun isEmoji(codePoint: Int): Boolean = when (codePoint) {
        in 0x1F300..0x1FAFF -> true   // pictographs, emoticons, symbols, extended-A
        in 0x1F000..0x1F2FF -> true   // mahjong, dominoes, enclosed
        in 0x2600..0x27BF -> true     // misc symbols, dingbats
        in 0x2B00..0x2BFF -> true     // arrows, geometric shapes
        in 0xFE00..0xFE0F -> true     // variation selectors
        in 0x1F1E6..0x1F1FF -> true   // regional indicators (flags)
        0x200D -> true                // zero-width joiner
        0x20E3 -> true                // combining enclosing keycap
        0x2122, 0x2139, 0x2194, 0x2195, 0x2196, 0x2197, 0x2198, 0x2199 -> true
        else -> false
    }

    // ── Symbols ──────────────────────────────────────────────────────────────

    /**
     * Replace symbols a TTS engine mispronounces with words or with nothing.
     *
     * Each replacement is chosen for how it *sounds*, not for how it looks: an em
     * dash becomes a comma (a pause), not the word "dash"; a bullet becomes nothing
     * (the list structure is already gone); a percent sign becomes "percent" because
     * "50%" is read as "fifty" otherwise.
     */
    fun normaliseSymbols(text: String): String {
        var t = text
        // Dashes: a pause, not a word.
        t = t.replace(Regex("\\s*[\u2014\u2013]\\s*"), ", ")
        // Bullets and list markers: nothing.
        t = t.replace(Regex("[\u2022\u25CF\u25AA\u25AB\u2023\u2043]"), " ")
        // Quotes: keep the words, drop the marks (they are not spoken).
        t = t.replace(Regex("[\u201C\u201D\u2018\u2019\u00AB\u00BB]"), "")
        // Ellipsis: a pause.
        t = t.replace("\u2026", ", ")
        // Percent, ampersand, plus, equals: spoken as words.
        t = t.replace("%", " percent")
        t = t.replace(Regex("\\s*&\\s*"), " and ")
        t = t.replace(Regex("\\s*\\+\\s*"), " plus ")
        t = t.replace(Regex("\\s*=\\s*"), " equals ")
        // Slash between words: "and"; between numbers: "per".
        t = t.replace(Regex("(?<=\\d)\\s*/\\s*(?=\\d)"), " per ")
        t = t.replace(Regex("\\s*/\\s*"), " or ")
        // Currency and units that are read as symbols.
        t = t.replace("\u20AC", " euros ")
        t = t.replace("\u00A3", " pounds ")
        t = t.replace("\u00A5", " yen ")
        t = t.replace("\u00B0", " degrees ")
        // Arrows and math that would be read as nothing.
        t = t.replace(Regex("[\u2192\u21D2]"), " to ")
        t = t.replace(Regex("[\u00D7]"), " times ")
        t = t.replace(Regex("[\u00F7]"), " divided by ")
        // A stray underscore or pipe left by markup removal.
        t = t.replace(Regex("[_|]"), " ")
        return t
    }

    // ── Abbreviations ────────────────────────────────────────────────────────

    /**
     * Expand the abbreviations a TTS engine reads letter-by-letter.
     *
     * "e.g." is read "e g", "Dr." is read "doctor" only by some engines, and "etc."
     * is read "e t c". Expanding them is what makes the narration sound natural.
     * The map is deliberately small and explicit: a large automatic expander
     * guesses, and a wrong guess is worse than the abbreviation.
     */
    fun expandAbbreviations(text: String): String {
        var t = text
        for ((from, to) in ABBREVIATIONS) {
            // A leading word boundary only. A trailing `\b` would never match after
            // a final period, because a period and a space are both non-word
            // characters and so have no boundary between them — which is exactly
            // why the first version of this rule silently did nothing.
            t = t.replace(Regex("(?i)\\b" + Regex.escape(from)), to)
        }
        return t
    }

    private val ABBREVIATIONS = listOf(
        "e.g." to "for example",
        "i.e." to "that is",
        "etc." to "and so on",
        "vs." to "versus",
        "Mr." to "Mister",
        "Mrs." to "Missus",
        "Ms." to "Miss",
        "Dr." to "Doctor",
        "Prof." to "Professor",
        "St." to "Saint",
        "approx." to "approximately",
        "dept." to "department",
        "govt." to "government",
    )

    // ── Whitespace ───────────────────────────────────────────────────────────

    /**
     * Normalise whitespace and line spacing.
     *
     * The specification asks for **proper line spacing**, which for narration means
     * paragraph breaks are preserved (they are the pauses) while runs of spaces and
     * more than one blank line are collapsed. A paragraph break is a real prosodic
     * signal and is kept; three blank lines are not.
     */
    fun normaliseWhitespace(text: String): String {
        var t = text.replace("\r\n", "\n").replace('\r', '\n')
        t = t.replace(Regex("[ \\t]+"), " ")
        t = t.replace(Regex(" *\\n *"), "\n")
        t = t.replace(Regex("\\n{3,}"), "\n\n")
        return t
    }

    /**
     * Split [cleaned] into paragraphs.
     *
     * The unit the canvas renders and the unit the prosody planner treats as a
     * paragraph break.
     */
    fun paragraphs(cleaned: String): List<String> =
        cleaned.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
