package com.gads.settings.server.utils

/**
 * Configuration data class for H.264 encoder settings.
 *
 * Provides default values that work well for most use cases and can be overridden
 * via command-line arguments or runtime configuration.
 */
data class EncoderConfig(
    /**
     * Target video width in pixels.
     * Will be aligned to 16-pixel boundaries for hardware encoder compatibility.
     */
    val width: Int = 1280,

    /**
     * Target video height in pixels.
     * Will be aligned to 16-pixel boundaries for hardware encoder compatibility.
     */
    val height: Int = 720,

    /**
     * Encoder bitrate in bits per second.
     * Default: 4 Mbps (4,000,000 bps)
     * - Higher = better quality, more bandwidth
     * - Lower = worse quality, less bandwidth
     */
    val bitrate: Int = 4000000,

    /**
     * Target frames per second.
     * Default: 30 FPS
     * - 30 FPS: Good balance of smoothness and performance
     * - 60 FPS: Smoother but requires more CPU and bandwidth
     */
    val fps: Int = 30,

    /**
     * Resolution scaling factor (downscale from device screen).
     * Default: 2 (half resolution)
     * - 1 = Full resolution (high quality, high CPU usage)
     * - 2 = Half resolution (balanced)
     * - 4 = Quarter resolution (low quality, low CPU usage)
     */
    val scalingFactor: Int = 2,

    /**
     * I-frame (keyframe) interval in seconds.
     * Default: 1 second
     * - Lower = more keyframes, better seeking, higher bitrate
     * - Higher = fewer keyframes, better compression, lower bitrate
     *
     * For live streaming, 1-2 seconds is recommended to allow clients to join quickly.
     */
    val iFrameInterval: Int = 1
) {
    companion object {
        /**
         * Parse encoder configuration from command-line arguments.
         *
         * Expected format: key=value (e.g., "width=1280", "fps=30")
         *
         * @param args Array of command-line arguments
         * @return EncoderConfig with values from args, using defaults for missing values
         */
        fun fromArgs(args: Array<String>): EncoderConfig {
            var width = 1280
            var height = 720
            var bitrate = 4000000
            var fps = 30
            var scalingFactor = 2
            var iFrameInterval = 1

            for (arg in args) {
                val parts = arg.split("=")
                if (parts.size != 2) continue

                when (parts[0].lowercase()) {
                    "width" -> width = parts[1].toIntOrNull() ?: width
                    "height" -> height = parts[1].toIntOrNull() ?: height
                    "bitrate" -> bitrate = parts[1].toIntOrNull() ?: bitrate
                    "fps" -> fps = parts[1].toIntOrNull() ?: fps
                    "scalingfactor" -> scalingFactor = parts[1].toIntOrNull() ?: scalingFactor
                    "iframeinterval" -> iFrameInterval = parts[1].toIntOrNull() ?: iFrameInterval
                }
            }

            return EncoderConfig(width, height, bitrate, fps, scalingFactor, iFrameInterval)
        }
    }

    override fun toString(): String {
        return "EncoderConfig(${width}x${height} @ ${fps}fps, ${bitrate}bps, scale=$scalingFactor, i-frame=${iFrameInterval}s)"
    }
}
