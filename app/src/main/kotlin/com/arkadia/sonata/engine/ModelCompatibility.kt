package com.arkadia.sonata.engine

/**
 * Whether a model may be loaded on a device.
 *
 * Pure, so the gate is testable without a device and cannot be bypassed by a
 * caller that forgets to check. The specification's rule is that a capability is
 * not advertised before it is measured; this is where "measured" becomes a
 * decision.
 */
object ModelCompatibility {

    /**
     * Headroom kept free above a model's own requirement.
     *
     * A model that exactly fills RAM will be killed by the low-memory killer the
     * first time the user opens another app. The margin is what makes "it loaded
     * once in a test" different from "it works".
     */
    const val RAM_HEADROOM = 1.5

    /** Storage headroom, for the model plus its working files. */
    const val STORAGE_HEADROOM = 1.2

    /**
     * Whether [descriptor] can be loaded on [device].
     *
     * Both RAM and storage are checked, and both with headroom. A model that fails
     * either is refused *before* load, so the failure is a clear message rather
     * than an out-of-memory kill mid-synthesis.
     */
    fun canLoad(descriptor: ModelDescriptor, device: DeviceProfile): Boolean {
        val ramNeeded = (descriptor.minRamBytes * RAM_HEADROOM).toLong()
        val storageNeeded = (descriptor.sizeBytes * STORAGE_HEADROOM).toLong()
        return device.ramBytes >= ramNeeded && device.freeStorageBytes >= storageNeeded
    }

    /**
     * Whether on-device **adaptation** (training) may run on [device].
     *
     * Stricter than [canLoad] on purpose. Training holds the base model, the
     * adapter, the optimiser state and the dataset in memory at once, and it runs
     * long enough to heat the device. It therefore requires the thermal
     * measurement to have passed, which inference does not.
     *
     * Until the Phase 7 harness has run, [DeviceProfile.thermalValidated] is false
     * and this returns false — which is the correct, honest state: adaptation is
     * refused rather than attempted and failed.
     */
    fun canAdapt(device: DeviceProfile, base: ModelDescriptor, adapterBytes: Long): Boolean {
        if (!device.thermalValidated) return false
        val ramNeeded = ((base.minRamBytes + adapterBytes) * ADAPT_RAM_HEADROOM).toLong()
        val storageNeeded = ((base.sizeBytes + adapterBytes) * STORAGE_HEADROOM).toLong()
        return device.ramBytes >= ramNeeded && device.freeStorageBytes >= storageNeeded
    }

    /**
     * Training needs more headroom than inference: the optimiser state and the
     * activations are held alongside the weights.
     */
    const val ADAPT_RAM_HEADROOM = 2.5

    /** A user-presentable reason a model was refused, or null when it is allowed. */
    fun refusalReason(descriptor: ModelDescriptor, device: DeviceProfile): String? = when {
        device.ramBytes < (descriptor.minRamBytes * RAM_HEADROOM).toLong() ->
            "This device does not have enough memory for ${descriptor.id}"

        device.freeStorageBytes < (descriptor.sizeBytes * STORAGE_HEADROOM).toLong() ->
            "There is not enough free storage for ${descriptor.id}"

        else -> null
    }
}
