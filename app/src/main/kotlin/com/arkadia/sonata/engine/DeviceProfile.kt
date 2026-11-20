package com.arkadia.sonata.engine

/**
 * What the device can hold and do.
 *
 * A point-in-time capability snapshot. The Android probe builds this value
 * automatically; compatibility decisions remain pure functions and are testable
 * without a device. Sustained thermal validation is tracked separately and is
 * never inferred from a single platform reading.
 */
data class DeviceProfile(
    /** Total RAM in bytes. */
    val ramBytes: Long,
    /** Free storage in bytes. */
    val freeStorageBytes: Long,
    /** Whether the device has passed the sustained-thermal measurement. */
    val thermalValidated: Boolean,
    /** A human label, e.g. "mid-tier". Used in reports, never in logic. */
    val label: String = "unknown",
    /** Currently available RAM in bytes; defaults to total RAM for legacy callers. */
    val availableRamBytes: Long = ramBytes,
) {
    companion object {
        /**
         * A conservative default for a device that has not been measured.
         *
         * Deliberately pessimistic: an unmeasured device is treated as unable to
         * hold a large model and unable to train, which is the honest state until
         * the harness in Phase 7 has run. A device that is actually capable loses
         * nothing but a first-run measurement; a device that is not capable is
         * never promised something it cannot do.
         */
        val UNMEASURED = DeviceProfile(
            ramBytes = 2L * 1024 * 1024 * 1024,
            freeStorageBytes = 512L * 1024 * 1024,
            thermalValidated = false,
            label = "unmeasured",
        )
    }
}
