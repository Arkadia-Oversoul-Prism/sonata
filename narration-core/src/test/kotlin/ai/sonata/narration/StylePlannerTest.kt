package ai.sonata.narration

import ai.sonata.validation.Corpus
import org.junit.Assert.*
import org.junit.Test

/**
 * Stage 4, layer 2 — style planning.
 *
 * These tests assert the plan, not the audio. They answer "did the planner express the
 * style's intent?" — which is a different question from "did the engine realise it?",
 * and keeping them apart is what makes a failure diagnosable.
 */
class StylePlannerTest {

    private val prose = "The morning arrived without ceremony. Light moved across the table. " +
        "She had been awake for an hour. Nothing needed doing yet."

    private fun plan(text: String, style: DeliveryStyle) =
        StylePlanner.plan(TextUnderstanding.analyse(text), style)

    // ---------- structural invariants ----------

    @Test
    fun `every style produces a valid plan for prose`() {
        for (style in DeliveryStyle.all) {
            val p = plan(prose, style)
            assertTrue("${style.id} produced no segments", p.segments.isNotEmpty())
            assertEquals("${style.id} violated an invariant: ${p.violations()}", emptyList<String>(), p.violations())
        }
    }

    @Test
    fun `every style produces a valid plan for every corpus category`() {
        for (style in DeliveryStyle.all) {
            for (passage in ai.sonata.validation.Corpus.passages) {
                val p = plan(passage.text, style)
                assertEquals(
                    "${style.id}/${passage.id} violated an invariant: ${p.violations()}",
                    emptyList<String>(), p.violations()
                )
            }
        }
    }

    @Test
    fun `plan covers the whole document`() {
        val p = plan(prose, DeliveryStyle.NATURAL)
        val covered = p.segments.map { it.sourceStart to it.sourceEnd }.sortedBy { it.first }
        assertEquals(0, covered.first().first)
        assertEquals(prose.length, covered.last().second)
        for (i in 1 until covered.size) {
            assertEquals("gap or overlap at segment $i", covered[i - 1].second, covered[i].first)
        }
    }

    @Test
    fun `empty text produces an empty plan`() {
        val p = plan("", DeliveryStyle.NATURAL)
        assertTrue(p.segments.isEmpty())
    }

    // ---------- pause placement ----------

    @Test
    fun `paragraph break is longer than a sentence break`() {
        val text = "First sentence. Second sentence.\n\nNew paragraph here."
        val p = plan(text, DeliveryStyle.NATURAL)
        val sentencePause = p.segments[0].pauseAfterMs
        val paragraphPause = p.segments[1].pauseAfterMs
        assertTrue(
            "paragraph pause $paragraphPause should exceed sentence pause $sentencePause",
            paragraphPause > sentencePause
        )
    }

    @Test
    fun `paragraph pause is not double counted`() {
        val text = "First sentence. Second sentence.\n\nNew paragraph here."
        val p = plan(text, DeliveryStyle.NATURAL)
        // The paragraph-final segment gets the paragraph pause INSTEAD of its sentence
        // pause, so it must equal the paragraph constant, not the sum.
        assertEquals(StylePlanner.PausePolicy.PARAGRAPH, p.segments[1].pauseAfterMs)
    }

    @Test
    fun `question gets a longer pause than a statement`() {
        val text = "This is a statement. Is this a question?"
        val p = plan(text, DeliveryStyle.NATURAL)
        assertTrue(p.segments[1].pauseAfterMs >= p.segments[0].pauseAfterMs)
    }

    @Test
    fun `ellipsis gets the longest sentence pause`() {
        val text = "He stopped... and then he did not."
        val p = plan(text, DeliveryStyle.NATURAL)
        assertTrue(p.segments[0].pauseAfterMs >= StylePlanner.PausePolicy.ELLIPSIS)
    }

    @Test
    fun `list items get their own pause`() {
        val text = "1. First item\n2. Second item\n3. Third item"
        val p = plan(text, DeliveryStyle.NATURAL)
        assertTrue(p.segments.size >= 3)
        assertTrue(p.segments[0].pauseAfterMs >= StylePlanner.PausePolicy.LIST_ITEM)
    }

    @Test
    fun `pause scale changes every pause proportionally`() {
        val fast = plan(prose, DeliveryStyle.CONVERSATIONAL)   // pauseScale 0.82
        val slow = plan(prose, DeliveryStyle.DRAMATIC)         // pauseScale 1.45
        assertTrue(
            "dramatic total pause ${slow.totalPauseMs} should exceed conversational ${fast.totalPauseMs}",
            slow.totalPauseMs > fast.totalPauseMs
        )
    }

    // ---------- prosodic continuity ----------

    @Test
    fun `declination lowers later sentences within a paragraph`() {
        val text = "One. Two. Three. Four. Five."
        val p = plan(text, DeliveryStyle.NATURAL)
        val pitches = p.segments.map { it.pitchSt }
        assertTrue("pitch should decline across a paragraph, got $pitches", pitches.last() < pitches.first())
    }

    @Test
    fun `declination resets at a paragraph boundary`() {
        val text = "One. Two. Three.\n\nFour. Five. Six."
        val p = plan(text, DeliveryStyle.NATURAL)
        // The first sentence of the second paragraph must not be lower than the first
        // sentence of the first paragraph by more than the alternation spread.
        val firstOfP1 = p.segments[0].pitchSt
        val firstOfP2 = p.segments[3].pitchSt
        assertTrue(
            "second paragraph should reset, got $firstOfP1 then $firstOfP2",
            kotlin.math.abs(firstOfP2 - firstOfP1) < 2.0
        )
    }

    @Test
    fun `declination is capped`() {
        val long = (1..40).joinToString(" ") { "Sentence number $it is here." }
        val p = plan(long, DeliveryStyle.DRAMATIC)
        assertTrue("pitch must stay within the invariant range", p.violations().isEmpty())
        assertTrue(p.segments.all { it.pitchSt >= -12.0 && it.pitchSt <= 12.0 })
    }

    @Test
    fun `declination strength differs by style`() {
        val text = "One. Two. Three. Four. Five. Six."
        val flat = plan(text, DeliveryStyle.PROFESSIONAL)   // declination 0.6
        val steep = plan(text, DeliveryStyle.DRAMATIC)      // declination 1.5
        val flatDrop = flat.segments.first().pitchSt - flat.segments.last().pitchSt
        val steepDrop = steep.segments.first().pitchSt - steep.segments.last().pitchSt
        assertTrue("dramatic should decline more than professional ($steepDrop vs $flatDrop)", steepDrop > flatDrop)
    }

    // ---------- register movement ----------

    @Test
    fun `wider spread style places consecutive sentences further apart`() {
        val text = "One. Two. Three. Four."
        val narrow = plan(text, DeliveryStyle.PROFESSIONAL)  // spreadSt 0.4
        val wide = plan(text, DeliveryStyle.DRAMATIC)        // spreadSt 3.0
        fun alternationGap(p: ProsodyPlan): Double =
            kotlin.math.abs(p.segments[0].pitchSt - p.segments[1].pitchSt)
        assertTrue(
            "dramatic alternation ${alternationGap(wide)} should exceed professional ${alternationGap(narrow)}",
            alternationGap(wide) > alternationGap(narrow)
        )
    }

    @Test
    fun `consecutive sentences alternate around the centre`() {
        val text = "One. Two. Three. Four."
        val p = plan(text, DeliveryStyle.STORYTELLING)
        // With alternation, sentence 0 and sentence 2 should sit on the same side.
        val s0 = p.segments[0].pitchSt
        val s1 = p.segments[1].pitchSt
        assertTrue("consecutive sentences should differ, got $s0 and $s1", s0 != s1)
    }

    // ---------- per-kind adjustment ----------

    @Test
    fun `technical text is read more slowly than prose`() {
        val tech = plan("The manifest records 0x1F2A as the version.", DeliveryStyle.NATURAL)
        val plain = plan("The manifest records the version.", DeliveryStyle.NATURAL)
        assertTrue(
            "technical rate ${tech.segments[0].rate} should be below plain ${plain.segments[0].rate}",
            tech.segments[0].rate < plain.segments[0].rate
        )
    }

    @Test
    fun `question is pitched above a statement`() {
        val q = plan("Is this a question?", DeliveryStyle.NATURAL)
        val s = plan("This is a statement.", DeliveryStyle.NATURAL)
        assertTrue(q.segments[0].pitchSt > s.segments[0].pitchSt)
    }

    @Test
    fun `emphasis raises volume`() {
        val emph = plan("This is **very** important.", DeliveryStyle.NATURAL)
        val plain = plan("This is very important.", DeliveryStyle.NATURAL)
        assertTrue(
            "emphasised volume ${emph.segments[0].volume} should exceed plain ${plain.segments[0].volume}",
            emph.segments[0].volume > plain.segments[0].volume
        )
    }

    @Test
    fun `emphasis slows the segment slightly`() {
        val emph = plan("This is **very** important.", DeliveryStyle.NATURAL)
        val plain = plan("This is very important.", DeliveryStyle.NATURAL)
        assertTrue(emph.segments[0].rate < plain.segments[0].rate)
    }

    // ---------- long sentence splitting ----------

    @Test
    fun `long sentence is split at a clause boundary`() {
        val long = "This is a clause, and this is another clause, and this is a third clause " +
            "that keeps going, and a fourth clause, and a fifth clause to make it long enough."
        val pieces = StylePlanner.splitLongSentence(long, 60)
        assertTrue("expected several pieces, got ${pieces.size}", pieces.size > 1)
        assertTrue(pieces.all { it.length <= 60 })
        assertEquals("splitting must not lose text", long.replace(" ", ""), pieces.joinToString("").replace(" ", ""))
    }

    @Test
    fun `short sentence is not split`() {
        val s = "A short sentence."
        assertEquals(listOf(s), StylePlanner.splitLongSentence(s, 320))
    }

    @Test
    fun `splitting never cuts mid-word`() {
        val long = (1..60).joinToString(" ") { "word$it" }
        val pieces = StylePlanner.splitLongSentence(long, 50)
        for (p in pieces) {
            assertFalse("piece starts mid-word: $p", p.startsWith("ord"))
            assertTrue(p.split(" ").all { it.isNotEmpty() })
        }
    }

    // ---------- long-sentence path under the production cap ----------

    @Test
    fun `the corpus exercises the 320-character split path`() {
        // Stage 7a finding: the longest sentence in the corpus was 239 characters
        // against the 320-character cap, so no evaluation run ever entered
        // splitLongSentence. That made the cap untested at its real value and left
        // the long-sentence path invisible to the WER and pause measurements.
        // This asserts the corpus now contains a passage that crosses the cap.
        val cap = 320
        val over = Corpus.passages.filter { passage ->
            TextUnderstanding.analyse(passage.text)
                .paragraphs.flatMap { it.sentences }
                .any { it.text.length > cap }
        }
        assertTrue(
            "the corpus must contain at least one sentence longer than $cap chars, " +
                "or the split path is never measured; longest is " +
                Corpus.passages.maxOf { p ->
                    TextUnderstanding.analyse(p.text).paragraphs
                        .flatMap { it.sentences }.maxOfOrNull { s -> s.text.length } ?: 0
                },
            over.isNotEmpty()
        )
    }

    @Test
    fun `a corpus sentence over the cap is actually split into several segments`() {
        val cap = 320
        val passage = Corpus.passages.first { p ->
            TextUnderstanding.analyse(p.text)
                .paragraphs.flatMap { it.sentences }
                .any { it.text.length > cap }
        }
        val plan = StylePlanner.plan(TextUnderstanding.analyse(passage.text), DeliveryStyle.NATURAL)
        // The over-cap sentence must yield more than one segment, and no segment may
        // exceed the cap. Without this the cap is decorative.
        assertTrue(
            "no segment may exceed the ${cap}-char cap",
            plan.segments.all { it.text.length <= cap }
        )
        assertTrue(
            "passage ${passage.id} should produce more segments than it has sentences",
            plan.segments.size > TextUnderstanding.analyse(passage.text)
                .paragraphs.sumOf { it.sentences.size }
        )
    }

    // ---------- style definitions ----------

    @Test
    fun `all eight approved styles exist`() {
        val expected = listOf(
            "natural", "conversational", "reflective", "warm",
            "confident", "storytelling", "dramatic", "professional"
        )
        assertEquals(expected, DeliveryStyle.all.map { it.id })
    }

    @Test
    fun `every style documents intent and listening criteria`() {
        for (s in DeliveryStyle.all) {
            assertTrue("${s.id} has no intent", s.intent.length > 40)
            assertTrue("${s.id} has fewer than 3 listening criteria", s.listeningCriteria.size >= 3)
            assertTrue("${s.id} has no expectations", s.expectations.isNotEmpty())
        }
    }

    @Test
    fun `style lookup by id works`() {
        assertEquals(DeliveryStyle.DRAMATIC, DeliveryStyle.byId("dramatic"))
        assertNull(DeliveryStyle.byId("nonexistent"))
    }

    @Test
    fun `ordering claims reference real styles`() {
        for (c in StyleClaims.ordering) {
            assertTrue(c.minRatio > 1.0)
            assertNotEquals(c.greater, c.lesser)
        }
    }

    @Test
    fun `estimated duration is positive and plausible`() {
        val p = plan(prose, DeliveryStyle.NATURAL)
        assertTrue(p.estimatedDurationMs > 0)
        // 4 sentences of ~8 words: should be several seconds, not milliseconds.
        assertTrue("estimate ${p.estimatedDurationMs}ms is implausibly short", p.estimatedDurationMs > 3000)
    }
}
