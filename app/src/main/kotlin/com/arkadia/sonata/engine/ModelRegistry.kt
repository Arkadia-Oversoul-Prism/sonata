package com.arkadia.sonata.engine

/**
 * The installed-model catalogue.
 *
 * Models are bundled or side-loaded, never downloaded at runtime — the offline
 * boundary forbids a download path. This registry is the list of what is present,
 * and it is the only place that decides which model to use for a device.
 */
class ModelRegistry(
    private val models: List<ModelDescriptor> = BundledModels.ALL,
) {

    /** Every model present in this build. */
    fun installed(): List<ModelDescriptor> = models

    /** The models that can synthesise speech. */
    fun baseModels(): List<ModelDescriptor> =
        models.filter { it.role == ModelDescriptor.Role.BASE }

    /** The models that adapt a base voice to a timbre. */
    fun toneConverters(): List<ModelDescriptor> =
        models.filter { it.role == ModelDescriptor.Role.TONE_CONVERTER }

    /** A model by id, or null. */
    fun byId(id: String): ModelDescriptor? = models.firstOrNull { it.id == id }

    /**
     * The best base model this device can load.
     *
     * "Best" is highest quality first, which is the specification's priority: the
     * highest possible natural voice quality while remaining offline. A device that
     * cannot hold the quality model falls back to the speed model rather than to
     * nothing.
     */
    fun bestBaseFor(device: DeviceProfile): ModelDescriptor? =
        baseModels()
            .filter { ModelCompatibility.canLoad(it, device) }
            .maxByOrNull { it.sizeBytes }

    /** Whether [model] can be loaded on [device]. */
    fun isCompatible(model: ModelDescriptor, device: DeviceProfile): Boolean =
        ModelCompatibility.canLoad(model, device)

    /**
     * Whether on-device adaptation is possible on [device].
     *
     * Requires both a trainable converter and a thermally validated device. The
     * conjunction is the gate: either alone is not enough.
     */
    fun canAdaptOn(device: DeviceProfile): Boolean =
        device.thermalValidated && toneConverters().any {
            ModelCompatibility.canAdapt(it, device)
        }
}
