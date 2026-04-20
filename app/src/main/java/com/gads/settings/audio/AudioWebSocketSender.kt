package com.gads.settings.audio

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * AudioWebSocketSender sends PCM audio frames via WebSocket to the provider.
 *
 * Protocol: [8 bytes PTS big-endian][PCM data]
 * Port: 1992 (audio stream)
 *
 * This matches the video stream protocol used on port 1991.
 */
class AudioWebSocketSender(private val port: Int = 1992) {
    private var webSocketServer: WebSocketServer? = null
    private var audioClient: WebSocket? = null
    private var isRunning = false
    private var droppedFrames = 0

    companion object {
        private const val TAG = "AudioWebSocketSender"
    }

    /**
     * Start the WebSocket server
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "Audio WebSocket server already running")
            return
        }

        try {
            webSocketServer = object : WebSocketServer(InetSocketAddress(port)) {
                override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                    Log.i(TAG, "Audio client connected: ${conn.remoteSocketAddress}")
                    audioClient = conn
                }

                override fun onClose(
                    conn: WebSocket,
                    code: Int,
                    reason: String,
                    remote: Boolean
                ) {
                    if (conn == audioClient) {
                        Log.i(TAG, "Audio client disconnected: $reason (code=$code, remote=$remote)")
                        audioClient = null
                    }
                }

                override fun onMessage(conn: WebSocket, message: String) {
                    // Not expected to receive messages from client
                    Log.d(TAG, "Received unexpected message from client: $message")
                }

                override fun onError(conn: WebSocket?, ex: Exception) {
                    Log.e(TAG, "WebSocket error", ex)
                }

                override fun onStart() {
                    Log.i(TAG, "Audio WebSocket server started on port $port")
                    isRunning = true
                }
            }

            // Allow rebind during kernel TIME_WAIT after AudioService reset; otherwise
            // the next start() throws BindException and leaves port 1992 unbound.
            webSocketServer?.isReuseAddr = true
            webSocketServer?.start()
            Log.i(TAG, "Starting audio WebSocket server on port $port")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio WebSocket server", e)
        }
    }

    /**
     * Send an audio frame to the connected client
     * @param pcmData PCM audio data (16-bit samples)
     * @param pts Presentation timestamp in microseconds
     */
    fun sendAudioFrame(pcmData: ByteArray, pts: Long) {
        val client = audioClient
        if (client == null || !client.isOpen) {
            droppedFrames++
            // Rate-limit: log first occurrence and then every 1000 drops (~20s @50fps)
            // to surface silent-stream regressions without spamming during startup.
            if (droppedFrames == 1 || droppedFrames % 1000 == 0) {
                Log.w(TAG, "Dropping PCM frame: no connected audio client (total=$droppedFrames)")
            }
            return
        }
        droppedFrames = 0

        try {
            // Create buffer: [8 bytes PTS][PCM data]
            val buffer = ByteBuffer.allocate(8 + pcmData.size)
            buffer.putLong(pts) // 8 bytes PTS (big-endian)
            buffer.put(pcmData)

            // Send to client
            client.send(buffer.array())

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio frame", e)
        }
    }

    /**
     * Stop the WebSocket server and release resources
     */
    fun stop() {
        if (!isRunning) {
            return
        }

        Log.i(TAG, "Stopping audio WebSocket server")
        isRunning = false

        try {
            audioClient?.close()
            audioClient = null

            webSocketServer?.stop(1000)
            webSocketServer = null

            Log.i(TAG, "Audio WebSocket server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio WebSocket server", e)
        }
    }

}
