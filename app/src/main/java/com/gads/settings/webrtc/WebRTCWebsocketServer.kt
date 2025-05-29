package com.gads.settings.webrtc

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class WebRTCWebsocketServer(port: Int, private val webRTCScreenCaptureService: WebRTCScreenCaptureService) :
    WebSocketServer(
        InetSocketAddress(port)
    ) {
    private val clients = mutableSetOf<WebSocket>()

    fun sendMessage(message: String) {
        clients.forEach { client ->
            if (client.isOpen) {
                client.send(message)
            }
        }
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        clients.add(conn)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        clients.remove(conn)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        Log.d("WEBSOCKET1", message)
        if (message.contains("offer")) {
            Log.d("WEBSOCKET1", "handling offer")
            webRTCScreenCaptureService.setReceivedOffer(message)
        }
        if (message.contains("\"candidate\"")) {
            Log.d("WEBSOCKET1", "handling ice")
            webRTCScreenCaptureService.handleIce(message)
        }
        if (message.equals("hangup")) {
            Log.d("WEBSOCKET1", "client hung up")
            webRTCScreenCaptureService.handleHangup()
        }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
    }

    override fun onError(conn: WebSocket, ex: Exception) {
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