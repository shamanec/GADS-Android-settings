package com.gads.settings.server

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * WebSocket server that broadcasts H.264 encoded screen frames to connected clients.
 * Handles binary messages (H.264 NALUs) from the scrcpy-style encoder and text messages for configuration.
 *
 * On client connection, SPS/PPS config frames are sent automatically with the next I-frame,
 * so clients can start decoding at the next keyframe.
 *
 * Supports runtime configuration via text messages for FPS, bitrate, scaling, and I-frame interval.
 *
 * Port 1992 (different from existing H264ScreenCaptureService on port 1991).
 *
 * Based on H264WebsocketServer.kt with adaptations for ScrcpyH264Server.
 */
class H264WebSocketServer(
    port: Int = 1991,
    private val server: H264Server
) : WebSocketServer(InetSocketAddress(port)) {

    private val TAG = "GADS WS"

    /**
     * Called when a new client connects to the WebSocket server.
     * Triggers a screen refresh to ensure the client receives a frame immediately.
     */
    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        Log.d(TAG, "Client connected: ${conn?.remoteSocketAddress}. Triggering screen refresh...")
        server.triggerScreenRefresh()
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "Client disconnected: ${conn?.remoteSocketAddress}, code: $code, reason: $reason")
    }

    /**
     * Handles text configuration messages from clients.
     * Supports colon-separated commands for runtime encoder configuration.
     *
     * Supported commands:
     * - targetFPS=<value>       : Set encoder frame rate (e.g., "targetFPS=30")
     * - bitrate=<value>         : Set encoder bitrate in bps (e.g., "bitrate=2000000" for 2 Mbps)
     * - scalingFactor=<value>   : Set resolution scale, 50 = half size, 25 = quarter size
     * - iFrameInterval=<value>  : Set keyframe interval in seconds
     *
     * Example: "targetFPS=30:bitrate=3000000:scalingFactor=50"
     */
    override fun onMessage(conn: WebSocket?, message: String) {
        val splitMsg = message.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        var needsRestart = false

        // First pass: set all configuration values
        for (msgPart in splitMsg) {
            when {
                msgPart.startsWith("targetFPS=") -> {
                    try {
                        val fps = msgPart.split("=")[1].toInt()
                        server.setTargetFPS(fps)
                        needsRestart = true
                        Log.d(TAG, "Set target FPS to: $fps")
                    } catch (e: Exception) {
                        Log.e(TAG, "Invalid FPS value received: $msgPart", e)
                    }
                }

                msgPart.startsWith("bitrate=") -> {
                    try {
                        val bitrate = msgPart.split("=")[1].toInt()
                        server.setBitrate(bitrate)
                        Log.d(TAG, "Set bitrate to: $bitrate")
                    } catch (e: Exception) {
                        Log.e(TAG, "Invalid bitrate value received: $msgPart", e)
                    }
                }

                msgPart.startsWith("scalingFactor=") -> {
                    try {
                        val scalingFactor = msgPart.split("=")[1].toInt()
                        if (scalingFactor == 50) {
                            server.setScalingFactor(2)
                        } else if (scalingFactor == 25) {
                            server.setScalingFactor(4)
                        } else {
                            server.setScalingFactor(2)
                        }
                        needsRestart = true
                        Log.d(TAG, "Set scaling factor to: $scalingFactor")
                    } catch (e: Exception) {
                        Log.e(TAG, "Invalid scaling factor value received: $msgPart", e)
                    }
                }

                msgPart.startsWith("iFrameInterval=") -> {
                    try {
                        val interval = msgPart.split("=")[1].toInt()
                        server.setIFrameInterval(interval)
                        needsRestart = true
                        Log.d(TAG, "Set I-frame interval to: $interval seconds")
                    } catch (e: Exception) {
                        Log.e(TAG, "Invalid I-frame interval value received: $msgPart", e)
                    }
                }

                else -> {
                    Log.w(TAG, "Unknown configuration message: $msgPart")
                }
            }
        }

        // After all values are set, trigger a single restart if needed
        if (needsRestart) {
            Log.d(TAG, "Triggering encoder restart for configuration changes")
            server.restartEncoder()
        }
    }

    /**
     * Binary messages are not used for client-to-server communication.
     * Only binary messages from server to client (H.264 NALUs) are sent.
     */
    override fun onMessage(conn: WebSocket?, message: ByteBuffer?) {
        // Not used
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        if (conn != null) {
            Log.e(TAG, "WebSocket error for connection ${conn.remoteSocketAddress}", ex)
        } else {
            Log.e(TAG, "WebSocket server error", ex)
        }
    }

    override fun onStart() {
        connectionLostTimeout = 100
        Log.d(TAG, "WebSocket server started on port ${address.port}")
    }
}
