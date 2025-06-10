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
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID

class WebRTCScreenCaptureService : Service() {
    private val TAG = "GADS WebRTC"
    // Socket server
    private var server: WebRTCWebsocketServer = WebRTCWebsocketServer(1991, this)

    // Handler
    private var mHandler: Handler? = null

    // Projection properties
    private var projectionIntent: Intent? = null
    private var orientationEventListener: OrientationEventListener? = null
    private var lastKnownRotation: Int = ROTATION_0  // default

    // Screen metrics
    private var metrics: DisplayMetrics = DisplayMetrics()
    private var streamWidth: Int = 0
    private var streamHeight: Int = 0

    // Session and connection
    private lateinit var offer: SessionDescription
    private var peerConnection: StreamPeerConnection? = null
    private var peerConnectionFactory: StreamPeerConnectionFactory = StreamPeerConnectionFactory(this)
    private val sessionManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Screen and video
    private lateinit var screenCapturer: ScreenCapturerAndroid
    private lateinit var videoSource: VideoSource
    private lateinit var localVideoTrack: VideoTrack
    private val _localVideoTrackFlow = MutableSharedFlow<VideoTrack>()
    private var surfaceTextureHelper = SurfaceTextureHelper.create(
        "SurfaceTextureHelperThread",
        peerConnectionFactory.eglBaseContext
    )
    private val mediaConstraints = MediaConstraints().apply {
        mandatory.addAll(
            listOf(
                MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"),
                MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")
            )
        )
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

    init {
        server.isReuseAddr = true
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
                    Log.d(TAG, "OrientationEventListener: Orientation changed from `$lastKnownRotation` to `$rotation`")
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
        Log.d(TAG, "setReceivedOffer: Received offer - $offerMsg")
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

    private fun updatePeerConnectionConstraints() {
        peerConnection?.let { pc ->
            // Find the video sender
            val videoSender = pc.connection.senders.find {
                it.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND
            }

            videoSender?.let { sender ->
                val parameters = sender.parameters

                // Update encoding parameters
                parameters.encodings?.forEach { encoding ->
                    encoding.maxBitrateBps = 1_000_000
                    encoding.minBitrateBps = 5_000_000
                    encoding.maxFramerate = 30

                    // Enable scalability for better adaptation
                    encoding.scalabilityMode = "L1T1" // 3 temporal, 3 spatial layers

                    // Ensure active
                    encoding.active = true
                }

                // Apply updated parameters
                sender.parameters = parameters
            }
        }
    }

    /**
     * Send an answer to the React UI client
     */
    private suspend fun sendAnswer() {
        // Set the offer from the React UI client as remote description
        val setRemoteResult = peerConnection?.setRemoteDescription(offer)

        if (!::localVideoTrack.isInitialized) {
            Log.e(TAG, "sendAnswer: localVideoTrack is not initialized!")
            return
        }

        val mediaStreamLabels = listOf("ARDAMS")
        // Add the video streaming track to the peer connection
        // Or no video will be displayed in the React UI client
        peerConnection?.connection?.addTrack(localVideoTrack, mediaStreamLabels)

        // Update the transceivers direction because we only send video, we don't expect to receive
        peerConnection?.connection?.transceivers?.forEach { transceiver ->
            transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
            // Configure single stream with adaptive bitrate
            val sender = transceiver.sender
            val parameters = sender.parameters

            // Get existing encoding or create new one
            val encoding = if (parameters.encodings.isNotEmpty()) {
                parameters.encodings[0]
            } else {
                // Create new encoding with default values
                RtpParameters.Encoding(null, true, 1.0).also {
                    parameters.encodings.add(it)
                }
            }

            // Configure the encoding
            encoding.apply {
                // Bitrate settings
                minBitrateBps = 1_000_000
                maxBitrateBps = 5_000_000
                maxFramerate = 30
                // No resolution scaling for screen share
                scaleResolutionDownBy = 1.0

                // Ensure encoding is active
                active = true

                // Try to set scalability mode if supported
                try {
                    scalabilityMode = "L1T1"
                } catch (e: Exception) {
                    Log.w(TAG, "sendAnswer: Scalability mode L1T1 not supported: ${e.message}")
                }
            }

            // Set degradation preference if supported
            try {
                // Maintain framerate at all costs for responsiveness
                RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            } catch (e: Exception) {
                Log.w(TAG, "sendAnswer: Degradation preference not supported: ${e.message}")
            }

            // Apply the parameters
            sender.parameters = parameters
        }

        setRemoteResult?.onSuccess {
            // Create an answer SessionDescription for the React UI client
            val answer = peerConnection?.createAnswer()?.getOrThrow()
            // Set the answer as a local description
            val result = peerConnection?.setLocalDescription(answer!!)

            peerConnection?.connection?.setBitrate(1_000_000, 300_000, 5_000_000)
            updatePeerConnectionConstraints()

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
                Log.d(TAG, "sendAnswer: Answer created and sent.")
            }?.onFailure {
                Log.e(TAG, "sendAnswer: Failed to set local description: ${it.message}")
            }
        }?.onFailure {
                Log.e(TAG, "sendAnswer: Failed to set remote description: ${it.message}")
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
            Log.e(TAG, "updateCaptureResolution: Error stopping capture: ${e.message}")
        }

        // Recompute the display metrics
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getRealMetrics(metrics)

        Thread.sleep(500)
        streamWidth = metrics.widthPixels / 2
        streamHeight = metrics.heightPixels / 2
        // Now restart capturing with the new resolution
        try {
            Log.d(TAG, "updateCaptureResolution: Restarting capture at ${streamWidth}x${streamHeight}")
            screenCapturer.startCapture(streamWidth, streamHeight, 30)
        } catch (e: Exception) {
            Log.e(TAG, "updateCaptureResolution: Error restarting capture: ${e.message}")
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

                        Log.d(TAG, """
                observeStatsPeriodically: [Outbound RTP] 
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
        Log.d(TAG, "Handling hangup...")

        sessionManagerScope.launch {
            peerConnection?.connection?.close()
            peerConnection = null
        }

        try {
            screenCapturer.stopCapture()
        } catch (e: Exception) {
            Log.e(TAG, "handleHangup: Failed to stop screen capture: ${e.message}")
        }

        localVideoTrack.setEnabled(false)
        localVideoTrack.dispose()
        videoSource.dispose()
        surfaceTextureHelper.dispose()

        surfaceTextureHelper = SurfaceTextureHelper.create(
            "SurfaceTextureHelperThread",
            peerConnectionFactory.eglBaseContext
        )

        Log.d(TAG, "handleHangup: Successfully handled hangup, stream stopped.")
    }

    // Set up a media projection by obtaining a MediaProjection object and creating a virtual display
    private fun startProjection(data: Intent?) {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val mDisplay = windowManager.defaultDisplay
        mDisplay.getRealMetrics(metrics)
        streamWidth = metrics.widthPixels / 2
        streamHeight = metrics.heightPixels / 2

        screenCapturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "startProjection: Stopped sharing")
            }
        })

        videoSource = peerConnectionFactory.makeVideoSource(screenCapturer.isScreencast).apply {
            adaptOutputFormat(
                streamWidth,
                streamHeight,
                30
            )
            screenCapturer.initialize(surfaceTextureHelper, this@WebRTCScreenCaptureService, capturerObserver)
            try {
                screenCapturer.startCapture(streamWidth, streamHeight, 30)
            } catch (e: Exception) {
                Log.e(TAG, "startProjection: ScreenCapturer failed to start: ${e.message}")
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
}