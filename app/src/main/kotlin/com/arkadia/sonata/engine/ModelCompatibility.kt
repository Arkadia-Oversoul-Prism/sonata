package com.arkadia.sonata.engine

/**
 * Whether a model may be loaded, and whether it may be trained, on a device.
 *
 * Two separate questions, deliberately kept separate. Loading a model is a memory
 * question; training one is a thermal question. Conflating them would either block
 * inference on an unmeasured device (too strict) or allow training on one (too
 * loose).
 */
object ModelCompatibility {

    /**
     * Whether [model] can be loaded on [device].
     *
     * Inference is allowed on any device with the RAM for it. It does not require
     * thermal validation, because a single synthesis is short and the S0 gate is
     * about sustained training, not about one utterance.
     */
    fun canLoad(model: ModelDescriptor, device: DeviceProfile): Boolean {
        if (device.totalRamBytes <= 0) return false
        return device.availableRamBytes >= model.minRamBytes
    }

    /**
     * Whether [model] can be **trained** on [device].
     *
     * Requires [DeviceProfile.thermalValidated]. This is the specification's
     * non-negotiable: on-device training is not advertised until it is measured.
     * An unmeasured device gets `false`, which is the safe answer.
     */
    fun canAdapt(model: ModelDescriptor, device: DeviceProfile): Boolean {
        if (!model.isTrainable) return false
        if (!device.thermalValidated) return false
        // Training needs more headroom than inference: gradients, optimizer state
        // and the checkpoint all live alongside the model.
        return device.availableRamBytes >= model.minRamBytes * 2
    }

    /**
     * Why [model] cannot be adapted on [device], for the UI to show.
     *
     * A refusal the user cannot understand is indistinguishable from a bug, so the
     * reason is a first-class return value rather than a log line.
     */
    fun adaptationRefusal(model: ModelDescriptor, device: DeviceProfile): String? = when {
        !model.isTrainable ->
            "This model is a base voice and is never trained on-device."
        !device.thermalValidated ->
            "On-device training has not been validated on this device yet. " +
                "Enrollment works now; training unlocks once the device passes the benchmark."
        device.availableRamBytes < model.minRamBytes * 2 ->
            "This device does not have enough free memory to train a voice model."
        else -> null
    }
}
