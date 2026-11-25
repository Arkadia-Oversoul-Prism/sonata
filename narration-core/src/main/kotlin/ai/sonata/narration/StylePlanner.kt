package ai.sonata.narration

/**
 * Stage 4, layer 2 — style planning.
 *
 * Consumes a [DocumentStructure] and a [DeliveryStyle] and produces a [ProsodyPlan].
 * It knows nothing about SSML, nothing about espeak-ng, and nothing about audio. Its
 * entire output is a list of [PlannedSegment]s with numeric prosody attached.
 *
 * Two things here are easy to get wrong and are therefore explicit:
 *
 *  - **Pause placement.** A pause is not "a gap after every sentence". A paragraph
 *    break, a list item, a speaker change and a clause comma all need different
 *    durations, and a question needs a different *shape* of pause than a statement.
 *    [PausePolicy] owns that decision and is tested on its own.
 *
 *  - **Prosodic continuity.** Reading each sentence at the style's base pitch makes
 *    the voice reset audibly at every full stop — the single most common cause of
 *    "it sounds like a machine". [Declination] carries a running pitch offset across
 *    a paragraph so the voice drifts down and only resets at a real paragraph break.
 */
object StylePlanner {

    /** Base pause durations in milliseconds, before the style's pause scale. */
    object PausePolicy {
        const val SENTENCE = 320
        const val QUESTION = 380
        const val EXCLAMATION = 300
        const val CLAUSE = 150
        const val LIST_ITEM = 260
        const val HEADING_BEFORE = 420
        const val HEADING_AFTER = 480
        const val PARAGRAPH = 620
        const val DIALOGUE_TURN = 400
        const val TECHNICAL = 300
        const val ELLIPSIS = 520

        /**
         * Pause after a segment, given its kind and whether it ends its paragraph.
         *
         * A paragraph-final segment gets the paragraph pause *instead of* its own
         * sentence pause, not in addition — otherwise a paragraph break becomes
         * 320 + 620 ms and the reading drags.
         */
        fun after(kind: SegmentKind, endsParagraph: Boolean, endsWithEllipsis: Boolean, scale: Double): Int {
            if (endsWithEllipsis) return (ELLIPSIS * scale).toInt()
            if (endsParagraph) return (PARAGRAPH * scale).toInt()
            val base = when (kind) {
                SegmentKind.SENTENCE -> SENTENCE
                SegmentKind.QUESTION -> QUESTION
                SegmentKind.EXCLAMATION -> EXCLAMATION
                SegmentKind.HEADING -> HEADING_AFTER
                SegmentKind.LIST_ITEM -> LIST_ITEM
                SegmentKind.DIALOGUE_LINE -> DIALOGUE_TURN
                SegmentKind.TECHNICAL -> TECHNICAL
            }
            return (base * scale).toInt()
        }

        /** Pause before a segment, driven by what it is. */
        fun before(kind: SegmentKind, startsParagraph: Boolean, scale: Double): Int {
            if (startsParagraph) return 0 // the previous segment already paid the paragraph pause
            return when (kind) {
                SegmentKind.HEADING -> (HEADING_BEFORE * scale).toInt()
                SegmentKind.LIST_ITEM -> (CLAUSE * scale).toInt()
                else -> 0
            }
        }
    }

    /**
     * Prosodic continuity: a running pitch offset across a paragraph.
     *
     * Each sentence sits slightly below the previous one, so a paragraph has a single
     * falling contour rather than N independent ones. The offset is capped so a long
     * paragraph cannot drift into unintelligibility, and it resets at a paragraph
     * boundary — which is exactly where a listener expects a reset.
     */
    object Declination {
        const val MAX_DRIFT_ST = 2.0

        fun offsetFor(sentenceIndexInParagraph: Int, sentenceCount: Int, strength: Double): Double {
            if (sentenceCount <= 1) return 0.0
            val progress = sentenceIndexInParagraph.toDouble() / (sentenceCount - 1)
            return -(progress * MAX_DRIFT_ST * strength)
        }
    }

    /**
     * Build a plan.
     *
     * @param structure output of [TextUnderstanding.analyse]
     * @param style the approved delivery style
     * @param maxSegmentChars hard cap so one enormous sentence cannot become one
     *        unseekable audio chunk; longer sentences are split at clause boundaries
     */
    fun plan(structure: DocumentStructure, style: DeliveryStyle, maxSegmentChars: Int = 320): ProsodyPlan {
        val p = style.params
        val segments = mutableListOf<PlannedSegment>()
        var totalPause = 0

        for (paragraph in structure.paragraphs) {
            val sentences = paragraph.sentences
            if (sentences.isEmpty()) continue
            for ((idx, s) in sentences.withIndex()) {
                val isFirst = idx == 0
                val isLast = idx == sentences.size - 1
                val drift = Declination.offsetFor(idx, sentences.size, p.declination)

                // Per-kind adjustment on top of the style baseline.
                val kindRate = when (s.kind) {
                    SegmentKind.TECHNICAL -> p.rate * 0.90   // technical text needs time
                    SegmentKind.HEADING -> p.rate * 0.94
                    SegmentKind.LIST_ITEM -> p.rate * 0.97
                    SegmentKind.QUESTION -> p.rate * 1.02
                    SegmentKind.DIALOGUE_LINE -> p.rate * 1.03
                    else -> p.rate
                }
                val kindPitch = when (s.kind) {
                    SegmentKind.QUESTION -> p.pitchSt + 1.5
                    SegmentKind.EXCLAMATION -> p.pitchSt + 1.0
                    SegmentKind.HEADING -> p.pitchSt + 0.5
                    SegmentKind.TECHNICAL -> p.pitchSt - 0.5
                    else -> p.pitchSt
                }

                // Register movement: alternate sentences above and below the style's
                // centre, so consecutive sentences are audibly placed apart. This is
                // the mechanism that produces measurable pitch travel — see the note
                // in Prosody.kt on why SSML `range` cannot be used for it.
                val alternation = if (idx % 2 == 0) 1.0 else -1.0
                val spread = alternation * p.spreadSt * 0.5

                // Emphasis: louder and slightly slower, scaled by the style's gain.
                val emphRate = if (s.emphasis == Emphasis.NONE) 1.0 else 1.0 - (0.05 * s.emphasis.strength * p.emphasisGain)
                val emphVolume = if (s.emphasis == Emphasis.NONE) 0 else (6 * s.emphasis.strength * p.emphasisGain).toInt()

                val endsEllipsis = s.text.trimEnd().endsWith("…") || s.text.trimEnd().endsWith("...")
                val pauseAfter = PausePolicy.after(s.kind, isLast, endsEllipsis, p.pauseScale)
                val pauseBefore = PausePolicy.before(s.kind, isFirst, p.pauseScale)

                val pieces = splitLongSentence(s.text, maxSegmentChars)
                pieces.forEachIndexed { pi, piece ->
                    val pieceStart = s.sourceStart + s.text.indexOf(piece).coerceAtLeast(0)
                    val lastPiece = pi == pieces.size - 1
                    segments += PlannedSegment(
                        text = piece,
                        kind = s.kind,
                        rate = clamp(kindRate * emphRate, 0.5, 2.0),
                        pitchSt = clamp(kindPitch + spread + drift, -12.0, 12.0),
                        volume = clampInt(p.volume + emphVolume, 60, 200),
                        pauseBeforeMs = if (pi == 0) pauseBefore else (PausePolicy.CLAUSE * p.pauseScale).toInt(),
                        pauseAfterMs = if (lastPiece) pauseAfter else (PausePolicy.CLAUSE * p.pauseScale).toInt(),
                        emphasis = s.emphasis,
                        sourceStart = pieceStart,
                        sourceEnd = pieceStart + piece.length
                    )
                    totalPause += if (lastPiece) pauseAfter else (PausePolicy.CLAUSE * p.pauseScale).toInt()
                }
            }
        }

        val speechMs: Long = segments.sumOf { seg ->
            val syl = TextUnderstanding.countSyllablesIn(seg.text)
            // ~4.6 syllables/second at rate 1.0, scaled by the segment's rate.
            (syl / (4.6 * seg.rate) * 1000.0).toLong()
        }
        // Extend each segment's end over the whitespace that follows it, so the plan's
        // source ranges tile the document exactly. Without this, the space between two
        // sentences belongs to no segment and the coverage check reports a one-character
        // gap — which is a real defect for the canvas, because that character would never
        // be highlighted.
        val tiled = tileCoverage(segments, structure.sourceText.length)
        return ProsodyPlan(
            style = style,
            segments = tiled,
            totalPauseMs = totalPause,
            estimatedDurationMs = (speechMs + totalPause).toInt()
        )
    }

    /**
     * Make segment source ranges contiguous and complete.
     *
     * Each segment's end is pushed forward to the next segment's start (absorbing the
     * whitespace between them), and the last segment is pushed to the end of the
     * document. The text of a segment is never changed — only the range it claims — so
     * highlighting covers every character exactly once.
     */
    fun tileCoverage(segments: List<PlannedSegment>, documentLength: Int): List<PlannedSegment> {
        if (segments.isEmpty()) return segments
        val out = segments.toMutableList()
        for (i in 0 until out.size - 1) {
            val cur = out[i]
            val nextStart = out[i + 1].sourceStart
            if (nextStart > cur.sourceEnd) {
                out[i] = cur.copy(sourceEnd = nextStart)
            }
        }
        val last = out.last()
        if (last.sourceEnd < documentLength) {
            out[out.size - 1] = last.copy(sourceEnd = documentLength)
        }
        return out
    }

    /**
     * Split an over-long sentence at clause boundaries, never mid-word.
     *
     * Preference order: semicolon, colon, em dash, comma, then whitespace. If no
     * boundary exists the sentence is left whole rather than cut arbitrarily — a
     * mid-word cut is worse than a long chunk.
     */
    fun splitLongSentence(sentence: String, maxChars: Int): List<String> {
        if (sentence.length <= maxChars) return listOf(sentence)
        val out = mutableListOf<String>()
        var rest = sentence
        while (rest.length > maxChars) {
            val window = rest.substring(0, maxChars)
            val cut = listOf("; ", ": ", " — ", ", ").map { window.lastIndexOf(it) }
                .filter { it > maxChars / 3 }.maxOrNull()
                ?: window.lastIndexOf(' ').takeIf { it > maxChars / 3 }
            if (cut == null || cut <= 0) {
                out += rest
                return out
            }
            out += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    private fun clamp(v: Double, lo: Double, hi: Double) = v.coerceIn(lo, hi)
    private fun clampInt(v: Int, lo: Int, hi: Int) = v.coerceIn(lo, hi)
}
