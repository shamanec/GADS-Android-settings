package com.gads.settings.webrtc

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface.ROTATION_0
import android.view.WindowManager
import androidx.core.util.Pair
import com.gads.settings.webrtc.peer.StreamPeerConnection
import com.gads.settings.webrtc.peer.StreamPeerConnectionFactory
import com.gads.settings.webrtc.peer.StreamPeerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID


class WebRTCScreenCaptureService : Service() {
    private var projectionIntent: Intent? = null
    private var mHandler: Handler? = null
    private var metrics: DisplayMetrics = DisplayMetrics()
    private var streamWidth: Int = 0
    private var streamHeight: Int = 0

    var server: WebRTCWebsocketServer = WebRTCWebsocketServer(1991, this)
    private var orientationEventListener: OrientationEventListener? = null
    private var lastKnownRotation: Int = ROTATION_0  // default

    private lateinit var offer: SessionDescription
    var peerConnectionFactory: StreamPeerConnectionFactory = StreamPeerConnectionFactory(this)
    private val sessionManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _localVideoTrackFlow = MutableSharedFlow<VideoTrack>()

    private val mediaConstraints = MediaConstraints().apply {
        mandatory.addAll(
            listOf(
                MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"),
                MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")
            )
        )
    }

    private var surfaceTextureHelper = SurfaceTextureHelper.create(
        "SurfaceTextureHelperThread",
        peerConnectionFactory.eglBaseContext
    )
    private lateinit var screenCapturer: ScreenCapturerAndroid

    private lateinit var videoSource: VideoSource
    private lateinit var localVideoTrack: VideoTrack

    private var peerConnection: StreamPeerConnection? = null

    init {
        server.setReuseAddr(true)
        server.start()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        // start capture handling thread
        object : Thread() {
            override fun run() {
                Looper.prepare()
                mHandler = Handler()
                Looper.loop()
            }
        }.start()

        orientationEventListener = object : OrientationEventListener(
            this,
            android.hardware.SensorManager.SENSOR_DELAY_NORMAL
        ) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val rotation = wm.defaultDisplay.rotation

                if (rotation != lastKnownRotation) {
                    Log.d("ScreenCaptureService", "Orientation changed.")
                    lastKnownRotation = rotation
                    updateCaptureResolution()
                }
            }
        }

        // Enable listening to orientation changes
        orientationEventListener?.enable()
    }

    // The onStartCommand() method is called by the Android system when the service is started
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d("GADS-WebRTC", "onStartCommand called with $intent")
        // Keep the intent for multiple projection start ups
        val data = intent.getParcelableExtra<Intent>("DATA")
        projectionIntent = data

        // Start the foreground service
        if (isStartCommand(intent)) {
            // Create notification
            val notification: Pair<Int, Notification> = WebRTCNotificationUtils.getNotification(
                this
            )
            startForeground(notification.first, notification.second)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Disable the orientation listener to prevent leaks
        orientationEventListener?.disable()
    }

    fun setReceivedOffer(offerMsg: String) {
        Log.d("GADS-WebRTC", "Received offer: " + offerMsg)
        // Parse the offer SDP from the received message from the React UI client
        val offerJson = JSONObject(offerMsg)
        val sdp = offerJson.getString("sdp")
        offer = SessionDescription(SessionDescription.Type.OFFER, sdp)

        // Start the projection if we currently don't have a peerConnection
        // This is needed because we close the peer connection on disconnect of the client
        if (peerConnection == null) {
            startProjection(projectionIntent)
        }

        runBlocking {
            peerConnection?.connection?.close()
            peerConnection = null

            // Create a StreamPeerConnection
            peerConnection = peerConnectionFactory.makePeerConnection(
                coroutineScope = sessionManagerScope,
                configuration = peerConnectionFactory.rtcConfig,
                type = StreamPeerType.SUBSCRIBER,
                mediaConstraints = mediaConstraints,
                onIceCandidateRequest = { iceCandidate, _ ->
                    val jsonCandidate = JSONObject().apply {
                        put("type", "candidate")
                        put("candidate", iceCandidate.sdp)
                        put("sdpMid", iceCandidate.sdpMid)
                        put("sdpMLineIndex", iceCandidate.sdpMLineIndex)
                    }
                    server.sendMessage(jsonCandidate.toString())
                }
            )

            // Send an answer to the React UI client
            sendAnswer()
        }
    }

    /**
     * Handle ICE candidate messages from the React UI client
     */
    fun handleIce(iceMessage: String) {
        val json = JSONObject(iceMessage)
        val candidateObj = json.getJSONObject("candidate")
        runBlocking {
            peerConnection?.addIceCandidate(
                IceCandidate(
                    candidateObj.getString("sdpMid"),
                    candidateObj.getInt("sdpMLineIndex"),
                    candidateObj.getString("candidate")
                )
            )
        }
    }

    /**
     * Send an answer to the React UI client
     */
    suspend fun sendAnswer() {
        // Set the offer from the React UI client as remote description
        val setRemoteResult = peerConnection?.setRemoteDescription(offer)

        if (!::localVideoTrack.isInitialized) {
            Log.e("WebRTC", "❌ localVideoTrack is not initialized!")
            return
        }

        val mediaStreamLabels = listOf("ARDAMS")
        // Add the video streaming track to the peer connection
        // Or no video will be displayed in the React UI client
        peerConnection?.connection?.addTrack(localVideoTrack, mediaStreamLabels)

        // Update the transceivers direction because we only send video, we don't expect to receive
        peerConnection?.connection?.transceivers?.forEach { transceiver ->
            transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
        }

        setRemoteResult?.onSuccess {
            // Create an answer SessionDescription for the React UI client
            val answer = peerConnection?.createAnswer()?.getOrThrow()
            // Set the answer as a local description
            val result = peerConnection?.setLocalDescription(answer!!)

            peerConnection?.connection?.setBitrate(300_000, 300_000, 4_000_000)

            // If setting answer as local description was successful
            result?.onSuccess {
//                sessionManagerScope.launch {
//                    observeStatsPeriodically()
//                }

                // Build the answer json
                val jsonAnswer = JSONObject().apply {
                    put("type", answer?.type?.canonicalForm())
                    put("sdp", answer?.description)
                }.toString()
                // Send the answer JSON over the websocket to the signalling server
                server.sendMessage(jsonAnswer)
                Log.d("WebRTC", "Answer created and sent.")
            }?.onFailure {
                Log.e("WebRTC", "❌ Failed to set local description: ${it.message}")
            }
        }
            ?.onFailure {
                Log.e("WebRTC", "❌ Failed to set remote description: ${it.message}")
            }
    }

    /**
     * Update the screen capture resolution when orientation changes.
     * We stop and restart capturing, reconfigure width/height, and
     * let the same localVideoTrack continue streaming.
     */
    private fun updateCaptureResolution() {
        // First, stop the current capture
        try {
            screenCapturer.stopCapture()
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error stopping capture: ${e.message}")
        }

        // Recompute the display metrics
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getRealMetrics(metrics)

        Thread.sleep(500)
        Log.d("CHANGE", "$streamWidth : $streamHeight")
        Log.d("CHANGE", "$lastKnownRotation")
        streamWidth = metrics.widthPixels / 3
        streamHeight = metrics.heightPixels / 3
        // Now restart capturing with the new resolution
        try {
            Log.d(
                "ScreenCaptureService",
                "Restarting capture at ${streamWidth}x${streamHeight}"
            )
            screenCapturer.startCapture(streamWidth, streamHeight, 40)
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error restarting capture: ${e.message}")
        }
    }

    suspend fun observeStatsPeriodically() {
        // Example: check stats every 5 seconds
        while (true) {
            delay(5000)

            // Get the raw PeerConnection
            val nativePc = peerConnection?.connection

            nativePc?.getStats { statsReport ->
                // statsReport is an RTCStatsReport
                // The statsMap property is a Map<String, RTCStats>
                val statsMap = statsReport.statsMap

                // Iterate over the map entries (or just the values)
                statsMap.values.forEach { singleStat ->
                    // singleStat is an RTCStats
                    if (singleStat.type == "outbound-rtp") {
                        // Check if it's video, or parse fields
                        val qualityReason = singleStat.members["qualityLimitationReason"]?.toString()
                        val framesEncoded = singleStat.members["framesEncoded"]?.toString()
                        val framesDropped = singleStat.members["framesDropped"]?.toString()

                        Log.d("WebRTC-Stats", """
                [Outbound RTP] 
                qualityLimitationReason: $qualityReason
                framesEncoded: $framesEncoded
                framesDropped: $framesDropped
            """.trimIndent())
                    }
                }
            }
        }
    }

    fun handleHangup() {
        Log.d("ScreenCaptureService", "Handling hangup...")

        sessionManagerScope.launch {
            peerConnection?.connection?.close()
            peerConnection = null
        }

        try {
            screenCapturer.stopCapture()
        } catch (e: Exception) {
            Log.e("WebRTC", "Failed to stop screen capture: ${e.message}")
        }

        localVideoTrack.setEnabled(false)
        localVideoTrack.dispose()
        videoSource.dispose()
        surfaceTextureHelper.dispose()

        surfaceTextureHelper = SurfaceTextureHelper.create(
            "SurfaceTextureHelperThread",
            peerConnectionFactory.eglBaseContext
        )

        Log.d("ScreenCaptureService", "Hangup handled, stream stopped.")
    }

    // Set up a media projection by obtaining a MediaProjection object and creating a virtual display
    private fun startProjection(data: Intent?) {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val mDisplay = windowManager.getDefaultDisplay()
        mDisplay.getRealMetrics(metrics)
        streamWidth = metrics.widthPixels / 3
        streamHeight = metrics.heightPixels / 3

        screenCapturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d("KOLEO", "SHARING STOPPED")
            }
        })

        videoSource = peerConnectionFactory.makeVideoSource(screenCapturer.isScreencast).apply {
            screenCapturer.initialize(surfaceTextureHelper, this@WebRTCScreenCaptureService, capturerObserver)
            try {
                screenCapturer.startCapture(streamWidth, streamHeight, 40)
            } catch (e: Exception) {
                Log.e("WebRTC", "❌ ScreenCapturer failed to start: ${e.message}")
            }
        }

        localVideoTrack = peerConnectionFactory.makeVideoTrack(
            source = videoSource,
            trackId = "ScreenTrack${UUID.randomUUID()}"
        )
        localVideoTrack.setEnabled(true)

        sessionManagerScope.launch {
            _localVideoTrackFlow.emit(localVideoTrack)
        }
    }

    companion object {
        fun getStartIntent(context: Context?, resultCode: Int, data: Intent?): Intent {
            val intent = Intent(
                context,
                WebRTCScreenCaptureService::class.java
            )
            intent.putExtra("ACTION", "START")
            intent.putExtra("RESULT_CODE", resultCode)
            intent.putExtra("DATA", data)
            return intent
        }

        fun getStopIntent(context: Context?): Intent {
            val intent = Intent(
                context,
                WebRTCScreenCaptureService::class.java
            )
            intent.putExtra("ACTION", "STOP")
            return intent
        }

        private fun isStartCommand(intent: Intent): Boolean {
            return intent.hasExtra("RESULT_CODE") && intent.hasExtra("DATA")
                    && intent.hasExtra("ACTION") && intent.getStringExtra("ACTION") == "START"
        }

        private fun isStopCommand(intent: Intent): Boolean {
            return intent.hasExtra("ACTION") && intent.getStringExtra("ACTION") == "STOP"
        }
    }
}