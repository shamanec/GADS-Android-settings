package com.gads.settings.webrtc

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class WebRTCWebsocketServer(port: Int, private val webRTCScreenCaptureService: WebRTCScreenCaptureService) :
    WebSocketServer(
        InetSocketAddress(port)
    ) {
    private val TAG = "GADS-WS"
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
        Log.d(TAG, "Received message - $message")

        if (message.startsWith("{") && message.contains("\"turn\"")) {
            try {
                val json = JSONObject(message)
                if (json.getString("type") == "turn") {
                    val server = json.getString("server")
                    val port = json.getInt("port")
                    val username = json.getString("username")
                    val password = json.getString("password")

                    Log.d(TAG, "Message was TURN config, updating TURN servers")
                    webRTCScreenCaptureService.updateTURNConfig(server, port, username, password)
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse TURN config: ${e.message}")
            }
        }

        if (message.contains("offer")) {
            Log.d(TAG, "Message was WebRTC offer, handling offer")
            webRTCScreenCaptureService.setReceivedOffer(message)
        }
        if (message.contains("\"candidate\"")) {
            Log.d(TAG, "Message was WebRTC ICE candidate, handling candidate")
            webRTCScreenCaptureService.handleIce(message)
        }
        if (message == "hangup") {
            Log.d(TAG, "Messages was hangup, handling hangup")
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