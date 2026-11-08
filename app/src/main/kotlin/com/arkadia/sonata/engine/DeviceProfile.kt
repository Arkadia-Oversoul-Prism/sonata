package com.arkadia.sonata.engine

/**
 * What the device can hold and do.
 *
 * The specification forbids advertising on-device training until it has been
 * measured on the target device class. This type is that measurement, and
 * [UNMEASURED] is the default — so a build that has never run the S0 harness
 * refuses adaptation rather than attempting it and failing.
 */
data class DeviceProfile(
    /** Total RAM in bytes. */
    val totalRamBytes: Long,
    /** Free RAM at the moment of measurement, in bytes. */
    val availableRamBytes: Long,
    /** CPU cores available to the app. */
    val cpuCores: Int,
    /** Whether the device has an NNAPI / QNN accelerator the runtime can use. */
    val hasAccelerator: Boolean,
    /** Android API level. */
    val apiLevel: Int,
    /**
     * Whether the S0 harness has measured this device class and passed.
     *
     * False until proven otherwise. This is the single flag that gates on-device
     * adaptation, and it is deliberately pessimistic: an unmeasured device is
     * treated as incapable, because the alternative is shipping a training run that
     * thermally aborts on a user's phone.
     */
    val thermalValidated: Boolean = false,
) {
    /** Whether the device has enough headroom to load a model of [sizeBytes]. */
    fun canHold(sizeBytes: Long): Boolean = availableRamBytes > sizeBytes * 2

    companion object {
        /**
         * A device that has not been measured.
         *
         * Deliberately conservative: no accelerator, no validation. A caller that
         * uses this gets the safe answer (no adaptation) rather than an optimistic
         * one.
         */
        val UNMEASURED = DeviceProfile(
            totalRamBytes = 0,
            availableRamBytes = 0,
            cpuCores = 1,
            hasAccelerator = false,
            apiLevel = 29,
            thermalValidated = false,
        )
    }
}
