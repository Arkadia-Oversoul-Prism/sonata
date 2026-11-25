package com.arkadia.sonata.reliability

import com.arkadia.sonata.data.Narration
import com.arkadia.sonata.data.NarrationStatus
import com.arkadia.sonata.export.FileStore

/**
 * Accounts for the storage Sonata uses.
 *
 * The specification requires **storage accounting**, and the reason is trust: an
 * app that quietly accumulates hundreds of megabytes of audio the user cannot see
 * is an app the user will uninstall. The accounting is per-narration so the user
 * can see *what* is using the space, not just how much.
 *
 * Pure over the [FileStore] seam, so the arithmetic is testable without a device.
 */
class StorageAccountant(private val fileStore: FileStore) {

    /**
     * A breakdown of what Sonata is using.
     *
     * [orphanedBytes] is the important number: audio on disk with no narration
     * record. It should always be zero, because [com.arkadia.sonata.export.LibraryFileCoordinator]
     * removes the file and the record together. A non-zero value means a crash
     * happened between the two, and [findOrphans] is how it is found and cleaned.
     */
    data class Report(
        val totalBytes: Long,
        val narrationCount: Int,
        val largestBytes: Long,
        val orphanedBytes: Long,
        val orphanCount: Int,
    ) {
        /** A human-readable size, e.g. "12.4 MB". */
        fun humanTotal(): String = humanBytes(totalBytes)

        /** A human-readable largest item. */
        fun humanLargest(): String = humanBytes(largestBytes)
    }

    /**
     * Account for [narrations].
     *
     * [knownIds] is the set of narration ids that have a record; any file whose id
     * is not in it is an orphan.
     */
    fun report(narrations: List<Narration>, knownIds: Set<Long>): Report {
        val sizes = narrations.associate { it.id to fileStore.bytesFor(it.id) }
        val total = sizes.values.sum()
        val largest = sizes.values.maxOrNull() ?: 0L
        val orphans = findOrphans(knownIds)
        return Report(
            totalBytes = total,
            narrationCount = narrations.size,
            largestBytes = largest,
            orphanedBytes = orphans.sumOf { it.second },
            orphanCount = orphans.size,
        )
    }

    /**
     * Find audio files with no narration record.
     *
     * Walks the ids the store knows about and keeps the ones not in [knownIds].
     * This is the recovery half of the file/record pairing: the coordinator removes
     * both together, and this finds the residue if a crash interrupted it.
     */
    fun findOrphans(knownIds: Set<Long>): List<Pair<Long, Long>> =
        fileStore.allIds()
            .filter { it !in knownIds }
            .map { it to fileStore.bytesFor(it) }

    /**
     * Delete every orphaned file.
     *
     * Returns how many were removed and how many bytes were reclaimed, so the user
     * can be told what happened rather than just that something did.
     */
    fun cleanOrphans(knownIds: Set<Long>): CleanupResult {
        val orphans = findOrphans(knownIds)
        var removed = 0
        var bytes = 0L
        for ((id, size) in orphans) {
            if (fileStore.delete(id)) {
                removed++
                bytes += size
            }
        }
        return CleanupResult(removed, bytes)
    }

    companion object {
        /** A human-readable byte count. */
        fun humanBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

/** Result of an orphan cleanup. */
data class CleanupResult(val removed: Int, val bytesReclaimed: Long) {
    /** A one-line summary for the user. */
    fun summary(): String =
        if (removed == 0) "Nothing to clean up"
        else "Removed $removed unused file${if (removed == 1) "" else "s"} " +
            "(${StorageAccountant.humanBytes(bytesReclaimed)})"
}

/**
 * The crash-recovery contract.
 *
 * The specification requires that an interrupted generation is **recoverable or
 * clearly marked**. Phase 1 marks the rows; this is the user-facing half — what the
 * library shows and what the user can do about it.
 */
object CrashRecovery {

    /** What the user can do with an interrupted narration. */
    enum class Action { RETRY, DISCARD }

    /**
     * Whether [narration] needs the user's attention.
     *
     * An interrupted narration does; a failed one does too, but for a different
     * reason, so they are distinguished. A complete narration never does.
     */
    fun needsAttention(narration: Narration): Boolean =
        narration.status == NarrationStatus.INTERRUPTED ||
            narration.status == NarrationStatus.FAILED

    /**
     * The message to show for [narration].
     *
     * An interruption is described as an interruption, not a failure, because the
     * user did nothing wrong and the fix is simply to try again.
     */
    fun message(narration: Narration): String? = when (narration.status) {
        NarrationStatus.INTERRUPTED ->
            "This narration was interrupted. You can retry it or discard it."

        NarrationStatus.FAILED ->
            narration.errorReason?.let { "This narration failed: $it" }
                ?: "This narration failed. You can retry it or discard it."

        else -> null
    }

    /**
     * The actions available for [narration].
     *
     * Both are always offered for an interrupted or failed narration: retrying is
     * the likely intent, but discarding must be possible without retrying first,
     * because a user who does not want the narration should not have to run a
     * synthesis to be rid of it.
     */
    fun actions(narration: Narration): List<Action> =
        if (needsAttention(narration)) listOf(Action.RETRY, Action.DISCARD) else emptyList()
}
