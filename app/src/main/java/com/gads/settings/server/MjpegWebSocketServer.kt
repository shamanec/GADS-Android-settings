package com.gads.settings.server

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * WebSocket server that broadcasts JPEG screen frames to connected clients.
 *
 * Supports runtime configuration via text messages for FPS, JPEG quality, and scaling factor.
 * Configuration changes that affect the virtual display (scaling) trigger a capture restart.
 *
 * Message format: colon-separated key=value pairs.
 * Example: "targetFPS=30:jpegQuality=80:scalingFactor=50"
 */
class MjpegWebSocketServer(
    port: Int,
    private val server: MjpegServer
) : WebSocketServer(InetSocketAddress(port)) {

    private val TAG = "GADS MJPEG WS"

    fun hasClients(): Boolean = connections.isNotEmpty()

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        Log.d(TAG, "Client connected: ${conn?.remoteSocketAddress}")
        server.triggerScreenRefresh()
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "Client disconnected: ${conn?.remoteSocketAddress}, code: $code, reason: $reason")
    }

    /**
     * Handles text configuration messages from clients.
     *
     * Supported commands:
     * - targetFPS=<value>       : Set capture frame rate
     * - jpegQuality=<value>     : Set JPEG compression quality (0-100)
     * - scalingFactor=<value>   : Set resolution scale, 50 = half size, 25 = quarter size
     */
    override fun onMessage(conn: WebSocket?, message: String) {
        val splitMsg = message.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        var needsRestart = false

        for (msgPart in splitMsg) {
            when {
                msgPart.startsWith("targetFPS=") -> {
                    try {
                        val fps = msgPart.split("=")[1].toInt()
                        server.setTargetFPS(fps)
                        Log.d(TAG, "Set target FPS to: $fps")
                    } catch (e: Exception) {
                        Log.e(TAG, "Invalid FPS value received: $msgPart", e)
                    }
                }

                msgPart.startsWith("jpegQuality=") -> {
                    try {
                        val quality = msgPart.split("=")[1].toInt()
                        server.setJpegQuality(quality)
                        Log.d(TAG, "Set JPEG quality to: $quality")
                    } catch (e: Exception) {
                        Log.e(TAG, "Invalid JPEG quality value received: $msgPart", e)
                    }
                }

                msgPart.startsWith("scalingFactor=") -> {
                    try {
                        val scalingFactor = msgPart.split("=")[1].toInt()
                        server.setScalingFactor(scalingFactor)
                        needsRestart = true
                        Log.d(TAG, "Set scaling factor to: $scalingFactor")
                    } catch (e: Exception) {
                        Log.e(TAG, "Invalid scaling factor value received: $msgPart", e)
                    }
                }

                else -> {
                    Log.w(TAG, "Unknown configuration message: $msgPart")
                }
            }
        }

        if (needsRestart) {
            Log.d(TAG, "Triggering capture restart for configuration changes")
            server.restartCapture()
        }
    }

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
        Log.d(TAG, "MJPEG WebSocket server started on port ${address.port}")
    }
}
