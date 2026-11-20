package com.arkadia.sonata.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilityDetectorTest {

    private fun snapshot(
        abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
        totalRam: Long = 6L * 1024 * 1024 * 1024,
        availableRam: Long = 2L * 1024 * 1024 * 1024,
        freeStorage: Long = 12L * 1024 * 1024 * 1024,
        thermalStatus: Int? = 0,
        vulkan: Boolean = true,
    ) = DeviceCapabilitySnapshot(
        manufacturer = "Samsung",
        model = "SM-A235F",
        device = "a23",
        sdkInt = 33,
        supportedAbis = abis,
        cpuCoreCount = 8,
        totalRamBytes = totalRam,
        availableRamBytes = availableRam,
        freeStorageBytes = freeStorage,
        vulkanFeaturePresent = vulkan,
        thermalStatus = thermalStatus,
    )

    @Test
    fun `maps platform facts without user input`() {
        val result = DeviceCapabilityDetector.from(snapshot())

        assertEquals("Samsung", result.manufacturer)
        assertEquals("SM-A235F", result.model)
        assertEquals(33, result.sdkInt)
        assertEquals(8, result.cpuCoreCount)
        assertEquals(6L * 1024 * 1024 * 1024, result.profile.ramBytes)
        assertEquals(12L * 1024 * 1024 * 1024, result.profile.freeStorageBytes)
        assertEquals(2L * 1024 * 1024 * 1024, result.profile.availableRamBytes)
        assertTrue(result.supportsArm64)
        assertTrue(result.vulkanFeaturePresent)
    }

    @Test
    fun `does not treat a current thermal reading as sustained validation`() {
        val result = DeviceCapabilityDetector.from(snapshot(thermalStatus = 0))

        assertEquals(0, result.thermalStatus)
        assertFalse(result.profile.thermalValidated)
    }

    @Test
    fun `does not infer a speech runtime from Vulkan presence`() {
        val withVulkan = DeviceCapabilityDetector.from(snapshot(vulkan = true))
        val withoutVulkan = DeviceCapabilityDetector.from(snapshot(vulkan = false))

        assertTrue(withVulkan.vulkanFeaturePresent)
        assertFalse(withoutVulkan.vulkanFeaturePresent)
        // Runtime availability is intentionally not represented by this contract.
    }

    @Test
    fun `normalizes missing resource values and ABI entries safely`() {
        val result = DeviceCapabilityDetector.from(
            snapshot(
                abis = listOf("", "armeabi-v7a", "armeabi-v7a"),
                totalRam = -1L,
                availableRam = -1L,
                freeStorage = -1L,
                thermalStatus = null,
                vulkan = false,
            ),
        )

        assertEquals(listOf("armeabi-v7a"), result.supportedAbis)
        assertEquals(0L, result.totalRamBytes)
        assertEquals(0L, result.availableRamBytes)
        assertEquals(0L, result.freeStorageBytes)
        assertEquals(1, result.cpuCoreCount)
        assertFalse(result.supportsArm64)
        assertFalse(result.profile.thermalValidated)
    }

    @Test
    fun `profile label remains descriptive and is not used as a capability gate`() {
        val result = DeviceCapabilityDetector.from(snapshot())

        assertEquals("Samsung SM-A235F", result.profile.label)
    }

    @Test
    fun `model compatibility uses currently available RAM not just installed RAM`() {
        val descriptor = ModelDescriptor(
            id = "test-model",
            family = "test",
            version = "1",
            sizeBytes = 10L * 1024 * 1024,
            minRamBytes = 100L * 1024 * 1024,
            sampleRate = 24_000,
            languages = listOf("en"),
            role = ModelRole.BASE_SYNTHESIZER,
        )
        val result = DeviceCapabilityDetector.from(
            snapshot(
                totalRam = 8L * 1024 * 1024 * 1024,
                availableRam = 100L * 1024 * 1024,
            ),
        )

        assertFalse(ModelCompatibility.canLoad(descriptor, result.profile))
        assertEquals(
            "This device does not have enough memory for test-model",
            ModelCompatibility.refusalReason(descriptor, result.profile),
        )
    }
}
