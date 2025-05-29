package com.gads.settings.webrtc.peer

import android.os.Build
import android.util.Log
import org.webrtc.EglBase
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoEncoderFactory

/**
 * Simplified video encoder factory that automatically uses software encoding
 * for problematic devices, including:
 * - Devices from manufacturers known to have issues
 * - Devices with chipsets known to have issues
 * - Devices running Android versions below SDK 26 (Android 8.0)
 */
class FallbackVideoEncoderFactory(
    private val eglBaseContext: EglBase.Context
) : VideoEncoderFactory {
    private val TAG = "GADS FallbackVideoEncoder"

    // Known problematic manufacturers that should use software encoding by default
    // Compiled through manual and AI research
    private val problematicManufacturers = setOf(
        "huawei", "xiaomi", "sony", "honor", "lenovo", "oppo", "vivo"
    )

    // Known problematic chipset manufacturers (often found in device model names or build props)
    // Compiled through manual and AI research
    private val problematicChipsets = setOf(
        "mediatek", "mtk", "hisilicon", "kirin", "rockchip", "rk",
        "spreadtrum", "sprd", "unisoc", "allwinner"
    )

    // Create hardware and software factories
    private val hardwareVideoEncoderFactory by lazy {
        HardwareVideoEncoderFactory(eglBaseContext, true, true)
    }

    private val softwareVideoEncoderFactory by lazy {
        SoftwareVideoEncoderFactory()
    }

    // Determine if we should use software encoding based on device characteristics
    private val shouldUseSoftwareEncoding = determineIfShouldUseSoftwareEncoding()

    init {
        if (shouldUseSoftwareEncoding) {
            Log.d(TAG, "Using software encoder by default due to problematic device detection")
            Log.d(TAG, "Device info - Manufacturer: ${Build.MANUFACTURER}, Model: ${Build.MODEL}, Android: ${Build.VERSION.SDK_INT}")
        } else {
            Log.d(TAG, "Using hardware encoder - Device passed compatibility checks")
            Log.d(TAG, "Device info - Manufacturer: ${Build.MANUFACTURER}, Model: ${Build.MODEL}, Android: ${Build.VERSION.SDK_INT}")
        }
    }

    /**
     * Determine if we should use software encoding based on device characteristics
     */
    private fun determineIfShouldUseSoftwareEncoding(): Boolean {
        // Check Android version - use software encoding for Android 7.1.1 and below
        if (Build.VERSION.SDK_INT < 26) {
            Log.d(TAG, "Using software encoding due to Android version: ${Build.VERSION.SDK_INT}")
            return true
        }

        // Check manufacturer
        val manufacturer = Build.MANUFACTURER.lowercase()
        if (problematicManufacturers.any { manufacturer.contains(it) }) {
            Log.d(TAG, "Using software encoding due to problematic manufacturer: $manufacturer")
            return true
        }

        // Check device model for chipset hints
        val model = Build.MODEL.lowercase()
        for (chipset in problematicChipsets) {
            if (model.contains(chipset)) {
                Log.d(TAG, "Using software encoding due to problematic chipset in model name: $chipset")
                return true
            }
        }

        // Check CPU hardware info
        val hardware = try {
            android.os.Build::class.java
                .getDeclaredField("HARDWARE")
                .get(null)?.toString()?.lowercase()
        } catch (e: Exception) {
            ""
        }

        for (chipset in problematicChipsets) {
            if (hardware != null) {
                if (hardware.contains(chipset)) {
                    Log.d(TAG, "Using software encoding due to problematic chipset in hardware info: $chipset")
                    return true
                }
            }
        }

        // Check using system properties for SoC identification
        val processorInfo = try {
            Runtime.getRuntime().exec("getprop ro.board.platform").inputStream.bufferedReader().readText().lowercase()
        } catch (e: Exception) {
            ""
        }

        for (chipset in problematicChipsets) {
            if (processorInfo.contains(chipset)) {
                Log.d(TAG, "Using software encoding due to problematic chipset in system properties: $chipset")
                return true
            }
        }

        return false
    }

    override fun createEncoder(info: VideoCodecInfo): org.webrtc.VideoEncoder? {
        // If device is identified as problematic, always use software encoder
        if (shouldUseSoftwareEncoding) {
            Log.d(TAG, "Creating software encoder for ${info.name}")
            return softwareVideoEncoderFactory.createEncoder(info)
        }

        // Otherwise try hardware encoder first, but fallback to software if it fails
        Log.d(TAG, "Attempting hardware encoder for ${info.name}")
        try {
            val encoder = hardwareVideoEncoderFactory.createEncoder(info)
            if (encoder != null) {
                return encoder
            }

            Log.d(TAG, "Hardware encoder returned null for ${info.name}, falling back to software")
            return softwareVideoEncoderFactory.createEncoder(info)
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating hardware encoder for ${info.name}: ${e.message}")
            return softwareVideoEncoderFactory.createEncoder(info)
        }
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        // If we're using software encoding, only return software codecs
        if (shouldUseSoftwareEncoding) {
            return softwareVideoEncoderFactory.supportedCodecs
        }

        // Otherwise, combine hardware and software codecs
        // with hardware codecs taking precedence
        val hardwareCodecs = hardwareVideoEncoderFactory.supportedCodecs
        val softwareCodecs = softwareVideoEncoderFactory.supportedCodecs

        // Return all hardware codecs plus any software codecs not already covered
        return hardwareCodecs + softwareCodecs.filter { softwareCodec ->
            hardwareCodecs.none { it.name == softwareCodec.name }
        }.toTypedArray()
    }
}