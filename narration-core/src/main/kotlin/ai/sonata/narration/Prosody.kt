package ai.sonata.narration

/**
 * Stage 4 — the contract between style planning and acoustic synthesis.
 *
 * The three stages of narration are deliberately separate types:
 *
 *   1. TextUnderstanding  -> DocumentStructure   (what the text *is*)
 *   2. StylePlanner       -> ProsodyPlan         (how it should be *delivered*)
 *   3. AcousticSynthesis  -> audio               (what actually comes out)
 *
 * [ProsodyPlan] is the only thing that crosses from (2) to (3). Nothing in the
 * synthesiser knows about styles, and nothing in the planner knows about SSML or
 * about which engine is installed. That is what makes the modules swappable, and
 * it is what lets the validation harness measure the *audio* rather than the
 * parameters that were requested.
 *
 * ## Why pitch is expressed in semitones, and why there is no "range" parameter
 *
 * The first version of this file carried a `range` multiplier, mapped to the SSML
 * `range` attribute, on the assumption that it widened intonation. Measurement
 * disproved that: on espeak-ng 1.51, *any* `range` attribute collapses the pitch
 * contour to a near-monotone (measured 0.49 semitones of p5–p95 travel, against
 * 6.65 semitones with no `range` at all). It is destructive, not expressive.
 *
 * Measurement also showed that SSML `pitch` is a *global* offset: setting it per
 * segment inside one document shifts the whole utterance uniformly and does not
 * create sentence-to-sentence travel.
 *
 * What does work is rendering each segment as its own engine invocation with its
 * own pitch setting, then concatenating. That gives real, measurable register
 * movement between sentences — which is what "pitch travel" actually means to a
 * listener. So the plan carries [PlannedSegment.pitchSt] (an absolute register
 * offset in semitones) and [StyleParams.spreadSt] (how far consecutive sentences
 * are placed apart), and the engine renders segment by segment.
 *
 * Semitones rather than Hz because a semitone is a ratio: it means the same thing
 * at 90 Hz and at 200 Hz, so a style's definition survives a change of voice.
 */

/** A measurable property of rendered audio. Measured by the harness, never assumed. */
enum class Feature(val id: String, val unit: String) {
    /** Syllables per second of speech (pauses excluded) — the perceived reading speed. */
    SPEECH_RATE("speech_rate", "syllables/s"),
    /** Median F0 over voiced frames. */
    F0_MEAN("f0_mean", "Hz"),
    /** p5–p95 F0 travel *within* an utterance, in semitones. */
    F0_RANGE("f0_range", "st"),
    /** Spread of per-sentence median F0, in semitones — the register movement a style controls. */
    REGISTER_SPREAD("register_spread", "st"),
    /** Mean duration of detected pauses. */
    PAUSE_MEAN("pause_mean", "ms"),
    /** Mean frame energy. */
    ENERGY_MEAN("energy_mean", "dBFS"),
    /** Fraction of frames carrying a pitch. */
    VOICED_RATIO("voiced_ratio", "ratio")
}

/**
 * An absolute sanity bound on a measured feature.
 *
 * These are deliberately wide. A narrow bound would be a claim about the engine's
 * exact output, which is not something this project controls; a wide bound catches
 * "the style did not render at all" without pretending to precision it does not have.
 * The strong claims are the [OrderingClaim]s below, which are relative and therefore
 * robust to the engine's absolute calibration.
 */
data class Expectation(
    val feature: Feature,
    val min: Double?,
    val max: Double?,
    val note: String
) {
    fun accepts(v: Double): Boolean = (min == null || v >= min) && (max == null || v <= max)

    fun describe(): String {
        val lo = min?.let { ">= $it" } ?: ""
        val hi = max?.let { "<= $it" } ?: ""
        val bound = listOf(lo, hi).filter { it.isNotEmpty() }.joinToString(" and ")
        return "${feature.id} $bound ${feature.unit} ($note)"
    }
}

/**
 * A relative claim: [greater] must measure at least [minRatio] times [lesser] on [feature].
 *
 * This is the form of claim that can actually be defended. "Dramatic moves its register
 * more than Professional" survives a change of engine or voice; "Dramatic has an F0
 * range of 9.4 semitones" does not.
 */
data class OrderingClaim(
    val feature: Feature,
    val greater: DeliveryStyle,
    val lesser: DeliveryStyle,
    val minRatio: Double,
    val note: String
) {
    fun holds(greaterValue: Double, lesserValue: Double): Boolean =
        lesserValue > 0.0 && greaterValue / lesserValue >= minRatio
}

/** The prosodic parameters a style resolves to before per-sentence adjustment. */
data class StyleParams(
    /** Multiplier on the engine's base rate. 1.0 = the engine default. */
    val rate: Double,
    /** Register offset in semitones from the engine's base pitch. */
    val pitchSt: Double,
    /**
     * How far consecutive sentences are placed apart, in semitones. This is the
     * mechanism that produces audible pitch travel; see the file header.
     */
    val spreadSt: Double,
    /** Engine volume, 0..200. */
    val volume: Int,
    /** Multiplier on every computed pause duration. */
    val pauseScale: Double,
    /** How strongly register declines across a paragraph (prosodic continuity). */
    val declination: Double,
    /** Multiplier on emphasis-induced rate/volume change. */
    val emphasisGain: Double
)

/**
 * The eight approved delivery styles.
 *
 * Each carries (a) a stated intent, (b) human listening criteria, (c) machine-checkable
 * absolute bounds, and (d) the relative claims it participates in. The intent is a
 * *description of a prosodic configuration*, not a claim of emotional acting — see
 * [LIMITATION_NOTE].
 */
enum class DeliveryStyle(
    val id: String,
    val displayName: String,
    val params: StyleParams,
    val intent: String,
    val listeningCriteria: List<String>,
    val expectations: List<Expectation>
) {
    NATURAL(
        "natural", "Natural",
        StyleParams(rate = 1.00, pitchSt = 0.0, spreadSt = 0.6, volume = 175, pauseScale = 1.00, declination = 1.0, emphasisGain = 1.0),
        "Neutral baseline. Even pace, moderate register movement, no added colour. The reference every other style is compared against.",
        listOf(
            "Speech rate sits in the middle of the eight styles",
            "Register movement is present but not sing-song",
            "Sentence pauses are clearly audible but not theatrical",
            "No word is audibly stressed beyond its own punctuation"
        ),
        listOf(
            Expectation(Feature.SPEECH_RATE, 3.0, 7.5, "mid-band rate"),
            Expectation(Feature.REGISTER_SPREAD, 0.3, 4.0, "moderate movement"),
            Expectation(Feature.PAUSE_MEAN, 180.0, 750.0, "audible but plain")
        )
    ),

    CONVERSATIONAL(
        "conversational", "Conversational",
        StyleParams(rate = 1.10, pitchSt = 0.6, spreadSt = 1.2, volume = 175, pauseScale = 0.82, declination = 0.8, emphasisGain = 1.0),
        "Quicker and lighter, with more register movement and shorter gaps — the shape of someone talking to you rather than reading at you.",
        listOf(
            "Faster than Reflective and Professional",
            "Pauses are the shortest of the eight styles",
            "Register moves more than Natural",
            "Clause boundaries are bridged rather than separated"
        ),
        listOf(
            Expectation(Feature.SPEECH_RATE, 3.4, 8.0, "fast band"),
            Expectation(Feature.PAUSE_MEAN, 120.0, 620.0, "short gaps")
        )
    ),

    REFLECTIVE(
        "reflective", "Reflective",
        StyleParams(rate = 0.86, pitchSt = -1.2, spreadSt = 0.5, volume = 165, pauseScale = 1.40, declination = 1.3, emphasisGain = 0.9),
        "Slower, lower and narrower, with long pauses — the shape of someone thinking before they speak.",
        listOf(
            "Slowest or near-slowest of the eight styles",
            "Pauses are among the longest",
            "Pitch sits lower than Natural",
            "Register movement is narrower than Storytelling"
        ),
        listOf(
            Expectation(Feature.SPEECH_RATE, 2.4, 6.0, "slow band"),
            Expectation(Feature.PAUSE_MEAN, 280.0, 950.0, "long gaps")
        )
    ),

    WARM(
        "warm", "Warm",
        StyleParams(rate = 0.93, pitchSt = 0.3, spreadSt = 0.9, volume = 172, pauseScale = 1.12, declination = 1.0, emphasisGain = 1.0),
        "Slightly slower than Natural with a gentle lift and generous but not long pauses — close, unhurried, unforced.",
        listOf(
            "Slower than Natural but faster than Reflective",
            "Pauses longer than Natural, shorter than Reflective",
            "Register movement slightly wider than Natural",
            "No abrupt sentence onsets"
        ),
        listOf(
            Expectation(Feature.SPEECH_RATE, 2.8, 6.8, "gentle band"),
            Expectation(Feature.PAUSE_MEAN, 220.0, 850.0, "generous gaps")
        )
    ),

    CONFIDENT(
        "confident", "Confident",
        StyleParams(rate = 1.03, pitchSt = -0.5, spreadSt = 0.7, volume = 185, pauseScale = 0.88, declination = 0.7, emphasisGain = 1.15),
        "Steady and firm: near-Natural pace, low register, minimal hesitation, and emphasis that lands hard when it lands.",
        listOf(
            "Faster than Reflective",
            "Pauses shorter than Natural",
            "Pitch sits at or below Natural",
            "Emphasised words are louder than in Natural"
        ),
        listOf(
            Expectation(Feature.SPEECH_RATE, 3.0, 7.5, "steady band"),
            Expectation(Feature.PAUSE_MEAN, 150.0, 680.0, "little hesitation")
        )
    ),

    STORYTELLING(
        "storytelling", "Storytelling",
        StyleParams(rate = 0.95, pitchSt = 0.5, spreadSt = 2.2, volume = 178, pauseScale = 1.28, declination = 1.2, emphasisGain = 1.2),
        "Wide register travel and deliberate pauses — the shape of a narrator holding a scene open before moving on.",
        listOf(
            "Register movement clearly wider than Professional",
            "Pauses longer than Natural",
            "Sentence onsets vary more than in Professional",
            "Paragraph breaks are audibly longer than sentence breaks"
        ),
        listOf(
            Expectation(Feature.REGISTER_SPREAD, 1.5, 9.0, "wide travel"),
            Expectation(Feature.PAUSE_MEAN, 250.0, 950.0, "deliberate gaps")
        )
    ),

    DRAMATIC(
        "dramatic", "Dramatic",
        StyleParams(rate = 0.88, pitchSt = 1.2, spreadSt = 3.0, volume = 190, pauseScale = 1.45, declination = 1.5, emphasisGain = 1.35),
        "The widest register travel and the longest pauses of the eight — high contrast between stressed and unstressed material.",
        listOf(
            "Widest register movement of the eight styles",
            "Longest pauses of the eight styles",
            "Slower than Natural",
            "Emphasis is the most pronounced of the eight"
        ),
        listOf(
            Expectation(Feature.REGISTER_SPREAD, 2.0, 12.0, "widest travel"),
            Expectation(Feature.PAUSE_MEAN, 300.0, 1100.0, "longest gaps")
        )
    ),

    PROFESSIONAL(
        "professional", "Professional",
        StyleParams(rate = 1.00, pitchSt = -0.2, spreadSt = 0.4, volume = 180, pauseScale = 1.00, declination = 0.6, emphasisGain = 0.9),
        "Restrained and even: Natural pace with narrow register movement and flat declination — built for long technical listening.",
        listOf(
            "Register movement narrower than Storytelling and Dramatic",
            "Pace close to Natural",
            "Pauses even and predictable",
            "Least register drift across a paragraph"
        ),
        listOf(
            Expectation(Feature.SPEECH_RATE, 3.0, 7.5, "even band"),
            Expectation(Feature.REGISTER_SPREAD, 0.1, 3.6, "restrained travel"),
            Expectation(Feature.PAUSE_MEAN, 180.0, 750.0, "predictable gaps")
        )
    );

    companion object {
        fun byId(id: String): DeliveryStyle? = entries.firstOrNull { it.id == id }
        val all: List<DeliveryStyle> get() = entries.toList()
    }
}

/**
 * The relative claims this project actually tests. Each one is a statement that can
 * fail, and a failure is reported rather than removed.
 */
object StyleClaims {
    val ordering: List<OrderingClaim> = listOf(
        OrderingClaim(Feature.SPEECH_RATE, DeliveryStyle.CONVERSATIONAL, DeliveryStyle.REFLECTIVE, 1.10,
            "Conversational voices a larger fraction of each second than Reflective"),
        OrderingClaim(Feature.SPEECH_RATE, DeliveryStyle.CONFIDENT, DeliveryStyle.REFLECTIVE, 1.05,
            "Confident voices a larger fraction of each second than Reflective"),
        OrderingClaim(Feature.PAUSE_MEAN, DeliveryStyle.REFLECTIVE, DeliveryStyle.CONVERSATIONAL, 1.15,
            "Reflective pauses are at least 15% longer than Conversational"),
        OrderingClaim(Feature.PAUSE_MEAN, DeliveryStyle.DRAMATIC, DeliveryStyle.NATURAL, 1.10,
            "Dramatic pauses are at least 10% longer than Natural"),
        OrderingClaim(Feature.REGISTER_SPREAD, DeliveryStyle.DRAMATIC, DeliveryStyle.PROFESSIONAL, 1.50,
            "Dramatic register movement is at least 50% wider than Professional"),
        OrderingClaim(Feature.REGISTER_SPREAD, DeliveryStyle.STORYTELLING, DeliveryStyle.PROFESSIONAL, 1.30,
            "Storytelling register movement is at least 30% wider than Professional"),
        OrderingClaim(Feature.F0_MEAN, DeliveryStyle.DRAMATIC, DeliveryStyle.REFLECTIVE, 1.02,
            "Dramatic sits at least 2% higher in pitch than Reflective")
    )
}

/**
 * What this project can and cannot claim about the eight styles.
 *
 * The engine is a formant synthesiser. It can vary rate, register, register movement,
 * volume and pause structure, and those variations are real and measurable. It cannot
 * produce breathiness, vocal effort, smile, or any other paralinguistic cue, because it
 * has no mechanism for them. So the styles are validated as *prosodic configurations* —
 * and the claim that a style "sounds warm" is explicitly out of scope and marked
 * NOT TESTED.
 */
const val LIMITATION_NOTE: String =
    "Styles are validated as measurable prosodic configurations (rate, register, register " +
    "movement, volume, pause structure, declination). Emotional or paralinguistic affect — " +
    "warmth, intimacy, breathiness, vocal effort — is NOT claimed and NOT tested: the formant " +
    "synthesiser has no mechanism to produce it. Human listening criteria that require a " +
    "listener panel are marked NOT TESTED."

/** How a segment should be delivered, independent of any engine. */
enum class SegmentKind {
    SENTENCE, QUESTION, EXCLAMATION, HEADING, LIST_ITEM, DIALOGUE_LINE, TECHNICAL
}

/** Emphasis strength: 0 none, 1 light (italic/underscore), 2 strong (caps/bold). */
enum class Emphasis(val strength: Int) {
    NONE(0), LIGHT(1), STRONG(2)
}

/** One unit of speech handed to the synthesiser. */
data class PlannedSegment(
    val text: String,
    val kind: SegmentKind,
    val rate: Double,
    /** Absolute register offset for this segment, in semitones. */
    val pitchSt: Double,
    val volume: Int,
    val pauseBeforeMs: Int,
    val pauseAfterMs: Int,
    val emphasis: Emphasis,
    /** Offsets into the original document, so the canvas can highlight this segment. */
    val sourceStart: Int,
    val sourceEnd: Int
)

/** The complete plan for one document in one style. */
data class ProsodyPlan(
    val style: DeliveryStyle,
    val segments: List<PlannedSegment>,
    val totalPauseMs: Int,
    val estimatedDurationMs: Int
) {
    /**
     * Structural invariants. A plan that violates these is a bug in the planner, not a
     * matter of taste, so the pipeline refuses to synthesise it.
     */
    fun violations(): List<String> {
        val out = mutableListOf<String>()
        if (segments.isEmpty()) out += "plan has no segments"
        segments.forEachIndexed { i, s ->
            if (s.text.isBlank()) out += "segment $i is blank"
            if (s.rate < 0.5 || s.rate > 2.0) out += "segment $i rate ${s.rate} outside [0.5,2.0]"
            if (s.pitchSt < -12.0 || s.pitchSt > 12.0) out += "segment $i pitchSt ${s.pitchSt} outside [-12,12]"
            if (s.volume < 60 || s.volume > 200) out += "segment $i volume ${s.volume} outside [60,200]"
            if (s.pauseBeforeMs < 0 || s.pauseBeforeMs > 2000) out += "segment $i pauseBefore ${s.pauseBeforeMs} outside [0,2000]"
            if (s.pauseAfterMs < 0 || s.pauseAfterMs > 2000) out += "segment $i pauseAfter ${s.pauseAfterMs} outside [0,2000]"
            if (s.sourceEnd < s.sourceStart) out += "segment $i has inverted source range"
        }
        return out
    }

    /** Total characters of source text covered, for the coverage check. */
    fun coveredChars(): Int = segments.sumOf { it.sourceEnd - it.sourceStart }
}

/** Features measured from a rendered audio file. Produced by the harness, not by the app. */
data class AcousticFeatures(
    val speechRate: Double,
    val f0Mean: Double,
    val f0Range: Double,
    val registerSpread: Double,
    val pauseMean: Double,
    val energyMean: Double,
    val voicedRatio: Double
) {
    fun value(f: Feature): Double = when (f) {
        Feature.SPEECH_RATE -> speechRate
        Feature.F0_MEAN -> f0Mean
        Feature.F0_RANGE -> f0Range
        Feature.REGISTER_SPREAD -> registerSpread
        Feature.PAUSE_MEAN -> pauseMean
        Feature.ENERGY_MEAN -> energyMean
        Feature.VOICED_RATIO -> voicedRatio
    }
}
