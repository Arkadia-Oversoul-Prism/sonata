package ai.sonata.validation

import java.security.MessageDigest

/**
 * Stage 6 — the fixed evaluation corpus.
 *
 * Fixed means: the text is in this file, it never changes, and its SHA-256 is
 * recorded. Every listening claim in the report is tied to a hash, so a later run
 * can prove it evaluated the same material.
 *
 * Seven categories, chosen because they stress different parts of the pipeline:
 * ordinary prose (the baseline), long-form (continuity over time), dialogue and
 * fiction (speaker changes), technical (dense tokens), lists and punctuation
 * (pause placement), adversarial (things that break naive splitters), and
 * phonetically diverse (pronunciation coverage).
 */
object Corpus {

    data class Passage(
        val id: String,
        val category: Category,
        val text: String,
        val why: String
    ) {
        val sha256: String by lazy {
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
        val chars: Int get() = text.length
        val words: Int get() = Regex("[A-Za-z']+").findAll(text).count()
        /** Syllable count, used as the numerator of the measured speech rate. */
        val syllables: Int by lazy { ai.sonata.narration.TextUnderstanding.countSyllablesIn(text) }
    }

    enum class Category(val id: String, val label: String) {
        PROSE("prose", "Ordinary prose"),
        LONGFORM("longform", "Long-form article"),
        DIALOGUE("dialogue", "Dialogue and fiction"),
        TECHNICAL("technical", "Technical document"),
        LISTS("lists", "Lists and punctuation"),
        ADVERSARIAL("adversarial", "Adversarial text"),
        PHONETIC("phonetic", "Phonetically diverse")
    }

    val passages: List<Passage> = listOf(

        Passage(
            "prose_01", Category.PROSE,
            "The morning arrived without ceremony. Light moved across the kitchen table, " +
                "found the rim of a cup, and stopped there. She had been awake for an hour " +
                "already, listening to the house settle. Nothing needed doing yet. That was " +
                "the whole of it, and it was enough.",
            "Baseline prose: short declarative sentences, one paragraph, no unusual tokens. " +
                "Any style that cannot read this cleanly has failed at the simplest case."
        ),

        Passage(
            "longform_01", Category.LONGFORM,
            "For most of the twentieth century, the question of how cities pay for the " +
                "infrastructure they depend on was treated as a technical matter, best left " +
                "to engineers and bond lawyers. That consensus has not survived the last two " +
                "decades. As maintenance costs have risen faster than tax receipts, and as " +
                "the climate has begun to impose its own schedule on repairs that were " +
                "previously deferrable, the arithmetic has become impossible to keep out of " +
                "public argument.\n\n" +
                "The result is a set of choices that are not really technical at all. A city " +
                "can raise revenue, defer maintenance, or shrink the network it maintains. " +
                "Each of these is defensible in isolation. Together they describe a political " +
                "position, whether or not the person holding it would use that word. What has " +
                "changed is that the deferral option, which was for many years the cheapest " +
                "and quietest, is no longer available at the same price.\n\n" +
                "None of this is unique to infrastructure. It is the ordinary shape of a " +
                "problem that has been postponed until the postponement itself became the " +
                "most expensive choice on the table.",
            "Long-form: three paragraphs, long sentences with subordinate clauses, and a " +
                "paragraph-level argument. Tests prosodic continuity across a paragraph and " +
                "the audibility of paragraph breaks over several minutes of speech."
        ),

        Passage(
            "dialogue_01", Category.DIALOGUE,
            "\"You're late,\" she said, without looking up.\n\n" +
                "\"I know.\" He set the bag down and did not sit. \"The bridge was closed.\"\n\n" +
                "\"The bridge is always closed.\"\n\n" +
                "\"Not always.\" He waited. \"Sometimes it's the tunnel.\"\n\n" +
                "She laughed, once, and finally raised her head. \"Sit down, Elias. You look " +
                "like you've been walking since Tuesday.\"",
            "Dialogue: speaker turns, short lines, quotation marks, and a name. Tests that " +
                "turn changes get a beat and that short lines are not over-paused into " +
                "fragments."
        ),

        Passage(
            "technical_01", Category.TECHNICAL,
            "The integrity check computes a SHA-256 digest over each chunk and compares it " +
                "with the value stored in the row. A mismatch means the file changed after " +
                "it was written, so the chunk is marked for regeneration rather than played. " +
                "The manifest records 0x1F2A as the format version, and the reader rejects " +
                "any file whose header does not match. Latency is bounded at 200 ms for the " +
                "95th percentile; throughput must hold at 10x the nominal rate.",
            "Technical: hex literals, a hyphenated identifier, percentages, and numeric " +
                "thresholds. Tests slower delivery and reduced pitch movement on dense text, " +
                "and that 'SHA-256' is not split into three words."
        ),

        Passage(
            "lists_01", Category.LISTS,
            "Before you begin, check the following:\n\n" +
                "1. The device is charged above twenty percent.\n" +
                "2. The voice model is installed and verified.\n" +
                "3. Storage has at least five hundred megabytes free.\n" +
                "4. No other app is using the microphone.\n\n" +
                "If all four are true, you can proceed. If not, fix the first one that " +
                "fails, then start again — do not skip ahead.",
            "Lists and punctuation: a numbered list, a colon, an em dash, and a " +
                "semicolon-free but comma-heavy final sentence. Tests list-item pauses, " +
                "the pause after a colon, and that the em dash is not read aloud."
        ),

        Passage(
            "adversarial_01", Category.ADVERSARIAL,
            "Dr. J. R. R. Tolkien wrote to Mr. C. S. Lewis on 12 Jan. 1937, i.e. before the " +
                "war, about the e.g. problem of sequels... and then he stopped. Was it worth " +
                "it? He said it was! The U.S. edition (published by Allen & Unwin, est. 1914) " +
                "sold 3,000 copies — approx. 12% of the print run. \"Fine,\" he wrote. " +
                "\"Fine.\" Then: nothing.",
            "Adversarial: abbreviations that end in periods, initials, ellipsis, a question " +
                "and an exclamation mid-paragraph, an ampersand, a parenthetical, and " +
                "repeated short quotes. Tests that the sentence splitter does not cut at " +
                "'Dr.' or 'J. R. R.' and that '&' does not break the SSML."
        ),

        Passage(
            "phonetic_01", Category.PHONETIC,
            "The thoroughbred shire horse chewed thoughtfully through the thicket, while " +
                "the squirrel squeezed through a narrow gap in the fence. Ruth's twelfth " +
                "birthday photograph showed a wreath of azaleas beside a wrought-iron gate. " +
                "Szymon and Priya argued about whether the sixth sheikh's schedule was " +
                "feasible; the queue outside the theatre suggested it was not. " +
                "Extraordinary, the whole affair — genuinely extraordinary.",
            "Phonetically diverse: consonant clusters (thoroughbred, twelfth, wrought), " +
                "digraphs (th, sh, ch), a possessive, a non-English name, and a word with " +
                "four syllables. Tests pronunciation coverage and that long words are not " +
                "clipped by the segmenter."
        ),

        Passage(
            "longsentence_01", Category.LONGFORM,
            "When the survey team finally reached the ridge above the valley, after eleven " +
                "days of walking through country that the maps still marked as unsurveyed, " +
                "they found that the river they had been following since the second morning " +
                "did not in fact rise where any of the three competing accounts had placed " +
                "it, but instead emerged from a chain of small lakes fed by meltwater from " +
                "a glacier that had retreated almost two kilometres since the last expedition " +
                "had come this way, which meant that every route description in the archive " +
                "was now wrong in a way that would take another full season to correct, and " +
                "that the crossing they had planned for the following week would have to be " +
                "abandoned entirely in favour of a longer approach from the eastern side.",
            "A single sentence of roughly 800 characters, deliberately far beyond the " +
                "320-character segment cap. Stage 7a finding: the longest sentence in the " +
                "corpus was 239 characters, so the long-sentence split path in " +
                "StylePlanner.splitLongSentence was never exercised by any evaluation run. " +
                "This passage exists so that path is measured, not merely compiled."
        )
    )

    fun byId(id: String): Passage? = passages.firstOrNull { it.id == id }

    fun byCategory(c: Category): List<Passage> = passages.filter { it.category == c }

    /** A stable manifest line per passage, for the report. */
    fun manifest(): List<String> = passages.map { "${it.id}\t${it.category.id}\t${it.chars}\t${it.words}\t${it.sha256}" }

    /** The corpus as one document, for long-session tests. */
    fun concatenated(): String = passages.joinToString("\n\n") { it.text }
}
