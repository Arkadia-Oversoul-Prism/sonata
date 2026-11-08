package com.arkadia.sonata.export

/**
 * Turns a user-facing title into a file name that is safe on every filesystem
 * Sonata writes to.
 *
 * Pure, so the rules are testable without a device. The rules exist because a
 * title is free text and a file name is not: a title may contain a path
 * separator, a NUL, a control character, a reserved Windows device name, or be
 * longer than the filesystem allows. A name that is not sanitised is a name that
 * either fails to write or, worse, writes somewhere the user did not intend.
 */
object ExportNaming {

    /** The longest base name kept, before the extension. */
    const val MAX_BASE_LENGTH = 64

    /** Used when a title sanitises to nothing. */
    const val FALLBACK = "narration"

    /**
     * Characters that are illegal in a file name on at least one platform Sonata
     * exports to, plus the path separators.
     */
    private val ILLEGAL = charArrayOf(
        '/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000',
    )

    /** Windows reserved device names, which are illegal as a base name. */
    private val RESERVED = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )

    /**
     * A safe base name for [title].
     *
     * Illegal characters become `_` rather than being dropped, so two different
     * titles cannot collapse to the same name by deletion. Control characters are
     * dropped (they carry no meaning in a name), runs of whitespace collapse to a
     * single space, and a trailing dot or space is removed because both are
     * illegal at the end of a name on Windows.
     */
    fun baseName(title: String): String {
        val replaced = buildString(title.length) {
            for (ch in title) {
                when {
                    ch in ILLEGAL -> append('_')
                    ch.isISOControl() -> Unit
                    else -> append(ch)
                }
            }
        }
        val collapsed = replaced.trim().replace(Regex("\\s+"), " ")
        val trimmed = collapsed.trimEnd('.', ' ')
        val bounded = if (trimmed.length <= MAX_BASE_LENGTH) {
            trimmed
        } else {
            trimmed.take(MAX_BASE_LENGTH).trimEnd('.', ' ')
        }
        // A name that is only underscores is what a title of pure illegal
        // characters sanitises to; it is not a usable name, so it falls back.
        if (bounded.isEmpty() || bounded.all { it == '_' }) return FALLBACK
        if (bounded.lowercase() in RESERVED) return "${bounded}_"
        return bounded
    }

    /** The full file name for [title] in [format]. */
    fun fileName(title: String, format: AudioFormat): String =
        "${baseName(title)}.${format.extension}"

    /**
     * A file name that is unique within a set of already-used names.
     *
     * Appends ` (2)`, ` (3)`, … before the extension, the way a desktop file
     * manager does, so exporting two narrations with the same title does not
     * silently overwrite the first.
     */
    fun uniqueName(title: String, extension: String, taken: Set<String>): String {
        val base = baseName(title)
        val first = "$base.$extension"
        if (first !in taken) return first
        var n = 2
        while (true) {
            val candidate = "$base ($n).$extension"
            if (candidate !in taken) return candidate
            n++
        }
    }

    /** [uniqueName] for an audio [format]. */
    fun uniqueName(title: String, format: AudioFormat, taken: Set<String>): String =
        uniqueName(title, format.extension, taken)
}
