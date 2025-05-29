package com.gads.settings.streaming

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class MjpegWebsocketServer(port: Int, private val screenCaptureService: MjpegScreenCaptureService): org.java_websocket.server.WebSocketServer(InetSocketAddress(port)) {
    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
    }

    override fun onMessage(conn: WebSocket?, message: String) {
        val splitMsg = message.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

        for (i in splitMsg.indices) {
            val currentMsgPart = splitMsg[i]

            // Check if the message contains "targetFPS="
            if (currentMsgPart.startsWith("targetFPS=")) {
                try {
                    // Parse the FPS value from the message
                    val fps = currentMsgPart.split("=".toRegex()).dropLastWhile { it.isEmpty() }
                        .toTypedArray()[1].toInt()
                    // Update the targetFPS in ScreenCaptureService
                    screenCaptureService?.setTargetFPS(fps)
                } catch (e: NumberFormatException) {
                    e.printStackTrace()
                    Log.e(
                        "LocalWebsocketServer",
                        "Invalid FPS value received: $currentMsgPart"
                    )
                }
            }
            if (currentMsgPart.startsWith("jpegQuality=")) {
                try {
                    // Parse the jpegQuality value from the message
                    val jpegQuality =
                        currentMsgPart.split("=".toRegex()).dropLastWhile { it.isEmpty() }
                            .toTypedArray()[1].toInt()
                    // Update the jpegQuality in ScreenCaptureService
                    screenCaptureService?.setJpegQuality(jpegQuality)
                } catch (e: NumberFormatException) {
                    e.printStackTrace()
                    Log.e(
                        "LocalWebsocketServer",
                        "Invalid JPEG quality value received: $currentMsgPart"
                    )
                }
            }
            if (currentMsgPart.startsWith("scalingFactor=")) {
                try {
                    // Parse the scalingFactor value from the message
                    val scalingFactor =
                        currentMsgPart.split("=".toRegex()).dropLastWhile { it.isEmpty() }
                            .toTypedArray()[1].toInt()
                    // Update the scalingFactor in ScreenCaptureService
                    screenCaptureService?.setScalingFactor(scalingFactor)
                } catch (e: NumberFormatException) {
                    e.printStackTrace()
                    Log.e(
                        "LocalWebsocketServer",
                        "Invalid scaling factor value received: $currentMsgPart"
                    )
                }
            }
        }
    }

    override fun onMessage(conn: WebSocket?, message: ByteBuffer?) {
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        ex.printStackTrace()
        if (conn != null) {
            // TODO: Handle something here, like port binding failed or whatever
        }
    }

    override fun onStart() {
        connectionLostTimeout = 0
        connectionLostTimeout = 100
    }
}