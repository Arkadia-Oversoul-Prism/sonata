package com.arkadia.sonata.engine

/**
 * Raw facts collected from the Android platform.
 *
 * This is deliberately independent of a speech runtime. In particular,
 * [vulkanFeaturePresent] is only a platform feature signal; it does not prove
 * that a particular inference backend, delegate, or model can use the GPU.
 */
data class DeviceCapabilitySnapshot(
    val manufacturer: String,
    val model: String,
    val device: String,
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val cpuCoreCount: Int,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val freeStorageBytes: Long,
    val vulkanFeaturePresent: Boolean,
    val thermalStatus: Int?,
)

/**
 * Automatically discovered device facts exposed to runtime-selection code.
 *
 * This type contains no user-entered device information and performs no
 * runtime/model selection. A later runtime selector must verify its own
 * backend and model compatibility rather than treating platform hints as proof.
 */
data class DeviceCapabilities(
    val manufacturer: String,
    val model: String,
    val device: String,
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val cpuCoreCount: Int,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val freeStorageBytes: Long,
    val vulkanFeaturePresent: Boolean,
    val thermalStatus: Int?,
    val profile: DeviceProfile,
) {
    /** True when the platform advertises the 64-bit ARM ABI. */
    val supportsArm64: Boolean
        get() = supportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) }

    /** True only when Android reports no available RAM; not a performance tier. */
    val hasNoAvailableRam: Boolean
        get() = availableRamBytes == 0L
}

/** Pure mapping from platform facts to Sonata's compatibility profile. */
object DeviceCapabilityDetector {
    fun from(snapshot: DeviceCapabilitySnapshot): DeviceCapabilities {
        val totalRam = snapshot.totalRamBytes.coerceAtLeast(0L)
        val availableRam = snapshot.availableRamBytes.coerceAtLeast(0L)
        val freeStorage = snapshot.freeStorageBytes.coerceAtLeast(0L)
        val cores = snapshot.cpuCoreCount.coerceAtLeast(1)
        val abis = snapshot.supportedAbis.filter { it.isNotBlank() }.distinct()

        return DeviceCapabilities(
            manufacturer = snapshot.manufacturer,
            model = snapshot.model,
            device = snapshot.device,
            sdkInt = snapshot.sdkInt,
            supportedAbis = abis,
            cpuCoreCount = cores,
            totalRamBytes = totalRam,
            availableRamBytes = availableRam,
            freeStorageBytes = freeStorage,
            vulkanFeaturePresent = snapshot.vulkanFeaturePresent,
            thermalStatus = snapshot.thermalStatus,
            // A point-in-time thermal reading is not a sustained thermal test.
            // Training remains disabled until a separate benchmark validates it.
            profile = DeviceProfile(
                ramBytes = totalRam,
                freeStorageBytes = freeStorage,
                thermalValidated = false,
                label = listOf(snapshot.manufacturer, snapshot.model)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { "unknown" },
                availableRamBytes = availableRam,
            ),
        )
    }
}
