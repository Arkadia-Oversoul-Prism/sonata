package com.arkadia.sonata.engine

/**
 * The installed-model catalogue.
 *
 * Models are **bundled or side-loaded, never downloaded at runtime**. There is no
 * `download` method here on purpose: the offline boundary is a property of the
 * type. A first-run import from device storage is the only path in, and it is
 * [install] with a local file.
 */
interface ModelRegistry {

    /** Every installed model. */
    fun installed(): List<ModelDescriptor>

    /** The installed base synthesizers, best-quality first. */
    fun baseSynthesizers(): List<ModelDescriptor>

    /** The installed tone-colour converters. */
    fun toneConverters(): List<ModelDescriptor>

    /** Whether [descriptor] can be loaded on [device]. */
    fun isCompatible(descriptor: ModelDescriptor, device: DeviceProfile): Boolean

    /** The best base synthesizer this device can run, or null. */
    fun bestBaseFor(device: DeviceProfile): ModelDescriptor?

    /** Record a model as installed from a local file. */
    fun install(descriptor: ModelDescriptor)

    /** Remove a model from the catalogue. */
    fun remove(descriptor: ModelDescriptor)
}

/**
 * An in-memory [ModelRegistry].
 *
 * The catalogue is small (a handful of models) and is rebuilt from the models
 * present on disk at startup, so an in-memory implementation is the right shape:
 * there is no second source of truth to keep in sync, and no migration to write.
 */
class InMemoryModelRegistry(
    initial: List<ModelDescriptor> = emptyList(),
) : ModelRegistry {

    private val models = initial.toMutableList()

    override fun installed(): List<ModelDescriptor> = models.toList()

    override fun baseSynthesizers(): List<ModelDescriptor> =
        models.filter { it.role == ModelRole.BASE_SYNTHESIZER }
            // Larger models are the higher-quality ones in this family; the
            // specification's priority is quality, so they sort first.
            .sortedByDescending { it.sizeBytes }

    override fun toneConverters(): List<ModelDescriptor> =
        models.filter { it.role == ModelRole.TONE_CONVERTER }

    override fun isCompatible(descriptor: ModelDescriptor, device: DeviceProfile): Boolean =
        ModelCompatibility.canLoad(descriptor, device)

    override fun bestBaseFor(device: DeviceProfile): ModelDescriptor? =
        baseSynthesizers().firstOrNull { isCompatible(it, device) }

    override fun install(descriptor: ModelDescriptor) {
        if (models.none { it.id == descriptor.id }) models.add(descriptor)
    }

    override fun remove(descriptor: ModelDescriptor) {
        models.removeAll { it.id == descriptor.id }
    }
}
