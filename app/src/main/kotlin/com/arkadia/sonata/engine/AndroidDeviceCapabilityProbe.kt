package com.arkadia.sonata.engine

import android.content.Context
import android.content.pm.PackageManager
import android.app.ActivityManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs

/**
 * Reads device capabilities directly from Android.
 *
 * Call from application code with a Context. No permissions, network access,
 * device questionnaire, identifiers, or telemetry are required.
 */
class AndroidDeviceCapabilityProbe(context: Context) {
    private val appContext = context.applicationContext

    fun detect(): DeviceCapabilities {
        val activityManager =
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val storage = StatFs(appContext.filesDir.absolutePath)
        val packageManager = appContext.packageManager
        val powerManager =
            appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus
        } else {
            null
        }

        val hasVulkanFeature =
            packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) ||
                packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)

        return DeviceCapabilityDetector.from(
            DeviceCapabilitySnapshot(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                device = Build.DEVICE.orEmpty(),
                sdkInt = Build.VERSION.SDK_INT,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
                cpuCoreCount = Runtime.getRuntime().availableProcessors(),
                totalRamBytes = memoryInfo.totalMem,
                availableRamBytes = memoryInfo.availMem,
                freeStorageBytes = storage.availableBytes,
                vulkanFeaturePresent = hasVulkanFeature,
                thermalStatus = thermalStatus,
            ),
        )
    }
}
