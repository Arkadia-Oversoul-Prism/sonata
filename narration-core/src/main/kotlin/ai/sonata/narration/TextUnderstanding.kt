package ai.sonata.narration

/**
 * Stage 4, layer 1 — text understanding.
 *
 * This module answers "what is this text made of?" and nothing else. It has no
 * knowledge of styles, no knowledge of SSML, and no knowledge of any engine. Its
 * output, [DocumentStructure], is a pure description of the input.
 *
 * The reason for the separation is testability: every rule here can be asserted
 * against a string, with no audio involved. When a style later sounds wrong, the
 * question "did we misread the text or mis-deliver it?" has a definite answer.
 */
object TextUnderstanding {

    /** Abbreviations that end in a period but do not end a sentence. */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e",
        "fig", "no", "vol", "ch", "pp", "approx", "dept", "est", "inc", "ltd", "co",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec"
    )

    /** Sentence terminators. */
    private const val TERMINATORS = ".!?…"

    /**
     * Split a paragraph into sentences.
     *
     * Deliberately conservative: a false split inserts a pause in the middle of a
     * thought, which is far more damaging to listening than a missed split. So a
     * period only ends a sentence when the next non-space character is upper-case,
     * a quote, or a digit — and never after a known abbreviation or a single
     * initial.
     */
    fun splitSentences(paragraph: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < paragraph.length) {
            val c = paragraph[i]
            sb.append(c)
            if (c in TERMINATORS) {
                // Consume runs of terminators ("?!", "...") as one boundary.
                var j = i + 1
                while (j < paragraph.length && paragraph[j] in TERMINATORS) {
                    sb.append(paragraph[j]); j++
                }
                // Consume a closing quote or bracket that belongs to this sentence.
                while (j < paragraph.length && paragraph[j] in "\"')]”’") {
                    sb.append(paragraph[j]); j++
                }
                val next = paragraph.drop(j).firstOrNull { !it.isWhitespace() }
                // A terminator between two digits is a decimal point, not a sentence end.
                val prevChar = sb.dropLast(1).lastOrNull { !it.isWhitespace() }
                val isDecimal = prevChar != null && prevChar.isDigit() && next != null && next.isDigit()
                val endsHere = !isDecimal && next != null &&
                    (next.isUpperCase() || next in "\"'(“'" || next.isDigit())
                if (endsHere && !isAbbreviation(sb.toString())) {
                    out += sb.toString().trim()
                    sb.setLength(0)
                }
                i = j
                continue
            }
            i++
        }
        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) out += tail
        return out.filter { it.isNotBlank() }
    }

    /** True when the token before the final period is a known abbreviation or an initial. */
    private fun isAbbreviation(sentence: String): Boolean {
        val body = sentence.trimEnd('.', '!', '?', '…', '"', '\'', ')', ']', '”', '’')
        // The last token, whether or not the sentence contains a space. Using
        // substringAfterLast with a default of "" silently returned empty for a
        // single-token sentence like "J.", which made every initial look like a
        // sentence end.
        val lastWord = body.substringAfterLast(' ').lowercase().trimEnd('.')
        if (lastWord.isEmpty()) return false
        if (lastWord in ABBREVIATIONS) return true
        // A single letter followed by a period is an initial ("J. R. R. Tolkien").
        if (lastWord.length == 1 && lastWord[0].isLetter()) return true
        return false
    }

    /**
     * Split a document into paragraphs, preserving blank-line structure.
     *
     * A line that is entirely a list marker or a heading is its own paragraph, because
     * it needs its own pause and its own delivery.
     */
    fun splitParagraphs(text: String): List<Paragraph> {
        val normalised = text.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalised.split(Regex("\n[ \t]*\n"))
        val out = mutableListOf<Paragraph>()
        var cursor = 0
        for (block in blocks) {
            val trimmed = block.trim('\n')
            if (trimmed.isBlank()) {
                cursor += block.length + 2
                continue
            }
            val start = normalised.indexOf(trimmed, cursor).let { if (it < 0) cursor else it }
            val lines = trimmed.split('\n')
            val kind = classifyParagraph(lines)
            out += Paragraph(
                text = trimmed,
                kind = kind,
                sourceStart = start,
                sourceEnd = start + trimmed.length,
                lines = lines
            )
            cursor = start + trimmed.length
        }
        return out
    }

    private fun classifyParagraph(lines: List<String>): ParagraphKind {
        val first = lines.first().trim()
        if (first.startsWith("#")) return ParagraphKind.HEADING
        if (isListItem(first)) return ParagraphKind.LIST
        if (first.startsWith(">")) return ParagraphKind.QUOTE
        if (lines.size == 1 && first.length < 80 && !first.endsWith(".") && first.any { it.isLetter() }) {
            // A short standalone line with no terminator reads as a heading.
            return ParagraphKind.HEADING
        }
        return ParagraphKind.PROSE
    }

    /** Recognise "1.", "1)", "-", "*", "•", "a)" and similar list markers. */
    fun isListItem(line: String): Boolean {
        val t = line.trimStart()
        if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") || t.startsWith("– ")) return true
        val m = Regex("^(\\d{1,3}|[a-zA-Z])[.)]\\s+").find(t)
        return m != null
    }

    /** Strip a list marker, returning the item body. */
    fun stripListMarker(line: String): String {
        val t = line.trimStart()
        for (p in listOf("- ", "* ", "• ", "– ")) if (t.startsWith(p)) return t.removePrefix(p).trim()
        val m = Regex("^(\\d{1,3}|[a-zA-Z])[.)]\\s+").find(t)
        return if (m != null) t.substring(m.range.last + 1).trim() else t
    }

    /**
     * Detect a dialogue line: a quoted span, or a line beginning with a dash used as
     * a speech dash. Dialogue needs a different pause shape — a beat before the
     * speaker changes, and no long pause inside the quotation.
     */
    fun isDialogue(text: String): Boolean {
        val t = text.trimStart()
        if (t.startsWith("\"") || t.startsWith("“") || t.startsWith("‘")) return true
        if (t.startsWith("—") || t.startsWith("–")) return true
        // A quotation occupying most of the sentence.
        val quoted = Regex("[\"“][^\"”]{12,}[\"”]").find(t)
        return quoted != null && quoted.range.first < 4
    }

    /**
     * Detect a technical passage: code-like tokens, symbols, or dense numerals.
     *
     * Technical text must be read more slowly and with fewer dramatic pauses, because
     * a listener parsing "SHA-256" or "0x1F" has no redundancy to fall back on.
     */
    fun isTechnical(text: String): Boolean {
        val t = text
        if (Regex("[{}<>;=]|\\b0x[0-9A-Fa-f]+\\b|\\b[A-Z]{2,}[-_]\\d+").containsMatchIn(t)) return true
        val digits = t.count { it.isDigit() }
        val letters = t.count { it.isLetter() }
        if (letters > 0 && digits.toDouble() / letters > 0.35) return true
        val symbols = t.count { it in "/\\|@#$%^&*_+=~`" }
        return symbols > 0 && symbols.toDouble() / t.length.coerceAtLeast(1) > 0.06
    }

    /** Classify a single sentence for delivery. */
    fun classifySentence(sentence: String): SegmentKind {
        val t = sentence.trim()
        return when {
            t.endsWith("?") -> SegmentKind.QUESTION
            t.endsWith("!") -> SegmentKind.EXCLAMATION
            isTechnical(t) -> SegmentKind.TECHNICAL
            isDialogue(t) -> SegmentKind.DIALOGUE_LINE
            else -> SegmentKind.SENTENCE
        }
    }

    /**
     * Find emphasis markers and return the sentence with markers removed plus the
     * emphasis level. Supports *italic*, _italic_, **bold**, __bold__, and ALL-CAPS
     * words of three or more letters (which are read as emphasis, not as shouting).
     */
    fun extractEmphasis(sentence: String): Pair<String, Emphasis> {
        var s = sentence
        var level = Emphasis.NONE
        // Strong and light markers can coexist in one sentence ("**very** important and
        // *quite* clear"). The first version returned after handling strong emphasis,
        // leaving the light markers in the spoken text — so the engine read the
        // asterisks aloud. Both passes now always run.
        if (Regex("\\*\\*[^*]+\\*\\*").containsMatchIn(s) || Regex("__[^_]+__").containsMatchIn(s)) {
            level = Emphasis.STRONG
            s = s.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1").replace(Regex("__([^_]+)__"), "$1")
        }
        if (Regex("\\*[^*]+\\*").containsMatchIn(s) || Regex("(?<![\\w_])_[^_]+_(?![\\w_])").containsMatchIn(s)) {
            if (level == Emphasis.NONE) level = Emphasis.LIGHT
            s = s.replace(Regex("\\*([^*]+)\\*"), "$1").replace(Regex("(?<![\\w_])_([^_]+)_(?![\\w_])"), "$1")
        }
        if (level == Emphasis.NONE) {
            val caps = Regex("\\b[A-Z]{3,}\\b").findAll(s).count()
            if (caps > 0 && caps <= 3) level = Emphasis.LIGHT
        }
        return s to level
    }

    /**
     * Count syllables. Used for duration estimation and for the speech-rate metric,
     * so it must be deterministic and cheap rather than linguistically perfect.
     */
    fun countSyllables(word: String): Int {
        val w = word.lowercase().filter { it.isLetter() }
        if (w.isEmpty()) return 0
        if (w.length <= 3) return 1
        var count = 0
        var prevVowel = false
        for (c in w) {
            val vowel = c in "aeiouy"
            if (vowel && !prevVowel) count++
            prevVowel = vowel
        }
        if (w.endsWith("e") && !w.endsWith("le") && count > 1) count--
        return count.coerceAtLeast(1)
    }

    /** Syllables across a whole passage. */
    fun countSyllablesIn(text: String): Int =
        Regex("[A-Za-z']+").findAll(text).sumOf { countSyllables(it.value) }

    /**
     * Full structural analysis of a document.
     *
     * This is the single entry point layer 2 consumes. It is pure: same input, same
     * output, no I/O, no clock, no randomness.
     */
    fun analyse(text: String): DocumentStructure {
        val paragraphs = splitParagraphs(text)
        val analysed = paragraphs.mapIndexed { pIndex, p ->
            val rawLines = if (p.kind == ParagraphKind.LIST) p.lines else listOf(p.text)
            val sentences = mutableListOf<AnalysedSentence>()
            var lineCursor = p.sourceStart
            for (line in rawLines) {
                val body = if (p.kind == ParagraphKind.LIST) stripListMarker(line) else line
                val lineStart = text.indexOf(body, lineCursor).let { if (it < 0) lineCursor else it }
                val kind = when {
                    p.kind == ParagraphKind.HEADING -> SegmentKind.HEADING
                    p.kind == ParagraphKind.LIST -> SegmentKind.LIST_ITEM
                    else -> null
                }
                for (s in splitSentences(body)) {
                    val (clean, emphasis) = extractEmphasis(s)
                    val sStart = text.indexOf(clean, lineStart).let { if (it < 0) lineStart else it }
                    sentences += AnalysedSentence(
                        text = clean,
                        kind = kind ?: classifySentence(clean),
                        emphasis = emphasis,
                        sourceStart = sStart,
                        sourceEnd = sStart + clean.length,
                        syllables = countSyllables(clean),
                        wordCount = Regex("[A-Za-z']+").findAll(clean).count()
                    )
                    lineCursor = sStart + clean.length
                }
                lineCursor = lineStart + body.length
            }
            AnalysedParagraph(
                index = pIndex,
                kind = p.kind,
                sourceStart = p.sourceStart,
                sourceEnd = p.sourceEnd,
                sentences = sentences
            )
        }
        return DocumentStructure(
            sourceText = text,
            paragraphs = analysed,
            totalSentences = analysed.sumOf { it.sentences.size },
            totalSyllables = analysed.sumOf { p -> p.sentences.sumOf { it.syllables } },
            totalWords = analysed.sumOf { p -> p.sentences.sumOf { it.wordCount } }
        )
    }
}

enum class ParagraphKind { PROSE, HEADING, LIST, QUOTE }

data class Paragraph(
    val text: String,
    val kind: ParagraphKind,
    val sourceStart: Int,
    val sourceEnd: Int,
    val lines: List<String>
)

data class AnalysedSentence(
    val text: String,
    val kind: SegmentKind,
    val emphasis: Emphasis,
    val sourceStart: Int,
    val sourceEnd: Int,
    val syllables: Int,
    val wordCount: Int
)

data class AnalysedParagraph(
    val index: Int,
    val kind: ParagraphKind,
    val sourceStart: Int,
    val sourceEnd: Int,
    val sentences: List<AnalysedSentence>
)

data class DocumentStructure(
    val sourceText: String,
    val paragraphs: List<AnalysedParagraph>,
    val totalSentences: Int,
    val totalSyllables: Int,
    val totalWords: Int
) {
    val sentences: List<AnalysedSentence> get() = paragraphs.flatMap { it.sentences }
}
