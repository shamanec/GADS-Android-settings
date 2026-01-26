package com.gads.settings.streaming

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import java.net.InetSocketAddress
import java.nio.ByteBuffer

// WebSocket server that broadcasts H.264 encoded screen frames to connected clients.
// Handles binary messages (H.264 NALUs) from the encoder service and text messages for configuration.
//
// On client connection, immediately sends cached SPS/PPS config frames so decoders can start processing.
// Supports runtime configuration via text messages for FPS, bitrate, scaling, and I-frame interval.
class H264WebSocketServer(port: Int, private val screenCaptureService: H264ScreenCaptureService): org.java_websocket.server.WebSocketServer(InetSocketAddress(port)) {
    private val TAG = "GADS H264 server"

    // Called when a new client connects to the WebSocket server.
    // SPS/PPS config frames are sent automatically with every I-frame,
    // so clients can start decoding at the next keyframe (every 2 seconds by default).
    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        Log.d(TAG, "Client connected: ${conn?.remoteSocketAddress}. Will receive SPS/PPS with next I-frame.")
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "Client disconnected: ${conn?.remoteSocketAddress}")
    }

    // Handles text configuration messages from clients.
    // Supports colon-separated commands for runtime encoder configuration.
    //
    // Supported commands:
    // - targetFPS=<value>       : Set encoder frame rate (e.g., "targetFPS=30")
    // - bitrate=<value>         : Set encoder bitrate in bps (e.g., "bitrate=2000000" for 2 Mbps)
    // - scalingFactor=<value>   : Set resolution scale, 50 = half size, 25 = quarter size
    // - iFrameInterval=<value>  : Set keyframe interval in seconds
    //
    // Example: "targetFPS=30:bitrate=3000000:scalingFactor=50"
    override fun onMessage(conn: WebSocket?, message: String) {
        val splitMsg = message.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

        for (i in splitMsg.indices) {
            val currentMsgPart = splitMsg[i]

            if (currentMsgPart.startsWith("targetFPS=")) {
                try {
                    val fps = currentMsgPart.split("=".toRegex()).dropLastWhile { it.isEmpty() }
                        .toTypedArray()[1].toInt()
                    screenCaptureService.setTargetFPS(fps)
                } catch (e: NumberFormatException) {
                    e.printStackTrace()
                    Log.e(TAG, "Invalid FPS value received: $currentMsgPart")
                }
            }
            if (currentMsgPart.startsWith("bitrate=")) {
                try {
                    val bitrate = currentMsgPart.split("=".toRegex()).dropLastWhile { it.isEmpty() }
                        .toTypedArray()[1].toInt()
                    screenCaptureService.setBitrate(bitrate)
                } catch (e: NumberFormatException) {
                    e.printStackTrace()
                    Log.e(TAG, "Invalid bitrate value received: $currentMsgPart")
                }
            }
            if (currentMsgPart.startsWith("scalingFactor=")) {
                try {
                    val scalingFactor = currentMsgPart.split("=".toRegex()).dropLastWhile { it.isEmpty() }
                        .toTypedArray()[1].toInt()
                    screenCaptureService.setScalingFactor(scalingFactor)
                } catch (e: NumberFormatException) {
                    e.printStackTrace()
                    Log.e(TAG, "Invalid scaling factor value received: $currentMsgPart")
                }
            }
            if (currentMsgPart.startsWith("iFrameInterval=")) {
                try {
                    val interval = currentMsgPart.split("=".toRegex()).dropLastWhile { it.isEmpty() }
                        .toTypedArray()[1].toInt()
                    screenCaptureService.setIFrameInterval(interval)
                } catch (e: NumberFormatException) {
                    e.printStackTrace()
                    Log.e(TAG, "Invalid I-frame interval value received: $currentMsgPart")
                }
            }
        }
    }

    // Binary messages are not used for client-to-server communication.
    // Only binary messages from server to client (H.264 NALUs) are sent.
    override fun onMessage(conn: WebSocket?, message: ByteBuffer?) {
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        ex.printStackTrace()
        if (conn != null) {
            return
            // TODO: Handle something here, like port binding failed or whatever
        }
    }

    override fun onStart() {
        connectionLostTimeout = 0
        connectionLostTimeout = 100
    }
}