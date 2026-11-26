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
        text = normaliseNumbers(text)
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
        //
        // The dollar sign was missing from this list while the euro, pound and
        // yen were present, so "$1,250" reached the engine as a bare symbol and
        // was read as nothing at all — the amount simply vanished from the
        // narration. It is placed before the digit so the number that follows is
        // still normalised by [normaliseNumbers].
        t = t.replace("\$", " dollars ")
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

    // ── Numbers ───────────────────────────────────────────────────────────────

    /**
     * Make numbers read the way a person says them.
     *
     * Three rules, each fixing something the engine gets audibly wrong:
     *
     *  - **Thousands separators.** "1,250" is read as "one comma two five zero"
     *    by engines that treat the comma as punctuation. The comma is removed so
     *    the digits are read as one number.
     *  - **Ordinals.** "1st", "2nd", "3rd" are read as "one s t" by engines that
     *    do not recognise the suffix. The small ones are spelled out.
     *  - **Years.** "2026" is read "two thousand and twenty six" by some engines.
     *    Splitting it into two pairs gives "twenty twenty six", which is what a
     *    person says.
     *
     * Deliberately conservative: it does not attempt to spell out arbitrary
     * numbers as words. That is a large, locale-dependent problem, and a wrong
     * expansion is worse than a number the engine reads correctly on its own.
     */
    fun normaliseNumbers(text: String): String {
        var t = text

        // Thousands separators: 1,250,000 -> 1250000. Only between digits, so a
        // genuine comma in a list ("3, 4 and 5") is untouched.
        //
        // The lookahead is `\d{3}` with no trailing guard, and the replacement
        // runs repeatedly. A single pass with `(?!\d)` after the group only
        // matched the *last* group of a multi-group number, so "1,250,000"
        // became "1,250000" and then "1250000" only by luck of ordering — while
        // "1,250" became "12 50", because the comma was consumed and the space
        // that followed the earlier `\s*` rule was left behind. Removing one
        // separator per pass, repeatedly, is what makes every group behave the
        // same way.
        while (Regex("(?<=\\d),(?=\\d{3})").containsMatchIn(t)) {
            t = t.replace(Regex("(?<=\\d),(?=\\d{3})"), "")
        }

        // Ordinals. Only the small ones: "21st" would need a full
        // number-to-words implementation, and the engine reads "21" correctly.
        for ((digits, word) in ORDINALS) {
            t = t.replace(Regex("(?<![\\d.])" + digits + "(?![\\d])"), word)
        }

        // Years: 2026 -> "20 26", which engines read as "twenty twenty six".
        //
        // Bounded to 1900-2099, not 1000-2099. A bare four-digit number in the
        // wider range is far more often a quantity than a year — "1,250" is a
        // price, and splitting it produced "12 50", which is not a number at
        // all. The narrower window still covers every year a reader is likely to
        // meet, and the cost of the restriction is that a year like 1066 is read
        // as digits, which is correct rather than wrong.
        //
        // Not adjacent to other digits or a decimal point, so "3.2026" and
        // "12026" are left alone.
        t = t.replace(Regex("(?<![\\d.])(19[0-9]{2}|20[0-9]{2})(?![\\d.])")) { m ->
            val year = m.value
            val hi = year.substring(0, 2)
            val lo = year.substring(2)
            // A year ending in 00 is said "nineteen hundred", not "nineteen zero
            // zero", so the trailing pair is only split when it is not "00".
            if (lo == "00") "$hi hundred" else "$hi $lo"
        }

        return t
    }

    /**
     * The ordinals worth spelling out.
     *
     * Only the irregular ones and the small regular ones. "21st" is left as
     * digits because "twenty first" would need a full number-to-words
     * implementation, and the engine reads "21" correctly on its own.
     */
    private val ORDINALS = listOf(
        "1st" to "first",
        "2nd" to "second",
        "3rd" to "third",
        "4th" to "fourth",
        "5th" to "fifth",
        "6th" to "sixth",
        "7th" to "seventh",
        "8th" to "eighth",
        "9th" to "ninth",
        "10th" to "tenth",
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
