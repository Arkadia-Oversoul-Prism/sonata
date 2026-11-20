package com.arkadia.sonata.engine

/**
 * What the device can hold and do.
 *
 * Recorded rather than probed at the point of use, so the compatibility decision
 * is a pure function of two values and is testable without a device. The
 * specification forbids advertising on-device training before it is measured
 * against RAM, storage, thermal limits and speed; this is the value those
 * measurements land in.
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
