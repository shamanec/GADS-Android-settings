package com.gads.settings.webrtc

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.CapturerObserver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoFrame
import org.webrtc.VideoTrack
import java.util.UUID

class WebRTCScreenCaptureService : Service() {
    private val TAG_BUFFER = "GADS-Buffer"
    private val TAG_CONN = "GADS-Conn"
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

    // First frame detection
    @Volatile
    private var firstFrameCaptured = false
    private var pendingForceFrame = false

    // Streaming state
    @Volatile
    private var isIceConnected = false
    @Volatile
    private var firstFrameSentAfterConnect = false

    // Capture state
    @Volatile
    private var isCaptureRunning = false
    private val captureMutex = Mutex()

    // FPS constant
    private val TARGET_FPS = 30

    // Debug timing
    private var serviceStartTime = 0L
    private fun logBuffer(event: String) {
        val elapsed = if (serviceStartTime > 0) System.currentTimeMillis() - serviceStartTime else 0
        Log.d(TAG_BUFFER, "[${elapsed}ms] $event")
    }
    private fun logConn(event: String) {
        val elapsed = if (serviceStartTime > 0) System.currentTimeMillis() - serviceStartTime else 0
        Log.d(TAG_CONN, "[${elapsed}ms] $event")
    }
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
                    Log.d(TAG_BUFFER, "Orientation changed: $lastKnownRotation -> $rotation")
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
        // Reset timing for debug
        serviceStartTime = System.currentTimeMillis()
        logBuffer("Service starting")

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
            logBuffer("Foreground service started")
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Disable the orientation listener to prevent leaks
        orientationEventListener?.disable()
    }

    fun setReceivedOffer(offerMsg: String) {
        logConn("Offer received")

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

            logConn("Creating PeerConnection...")
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
                },
                onIceConnectionChange = { state ->
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            isIceConnected = true
                            logConn("*** ICE CONNECTED - Ready to send frames ***")
                            // Force frame delivery - WebRTC will request keyframes via RTCP as needed
                            forceKeyframe()
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.FAILED,
                        PeerConnection.IceConnectionState.CLOSED -> {
                            isIceConnected = false
                            firstFrameSentAfterConnect = false
                        }
                        else -> {}
                    }
                }
            )
            logConn("PeerConnection created")

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
        logConn("sendAnswer (buffer ready: $firstFrameCaptured)")

        // Set the offer from the React UI client as remote description
        val setRemoteResult = peerConnection?.setRemoteDescription(offer)

        if (!::localVideoTrack.isInitialized) {
            Log.e(TAG_CONN, "sendAnswer: localVideoTrack is not initialized!")
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
                    Log.w(TAG_CONN, "Scalability mode L1T1 not supported: ${e.message}")
                }
            }

            // Set degradation preference if supported
            try {
                // Maintain framerate at all costs for responsiveness
                RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            } catch (e: Exception) {
                Log.w(TAG_CONN, "Degradation preference not supported: ${e.message}")
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
                val iceState = peerConnection?.connection?.iceConnectionState()
                logConn("Answer ready, ICE state: $iceState")

                // Build the answer json
                val jsonAnswer = JSONObject().apply {
                    put("type", answer?.type?.canonicalForm())
                    put("sdp", answer?.description)
                }.toString()
                // Send the answer JSON over the websocket to the signalling server
                server.sendMessage(jsonAnswer)

                // Force initial frame to prevent black screen on connect
                forceInitialFrame()
            }?.onFailure {
                Log.e(TAG_CONN, "Failed to set local description: ${it.message}")
            }
        }?.onFailure {
            Log.e(TAG_CONN, "Failed to set remote description: ${it.message}")
        }
    }

    private fun forceInitialFrame() {
        val helper = surfaceTextureHelper
        if (helper == null) {
            Log.e(TAG_BUFFER, "surfaceTextureHelper is NULL, cannot force frame")
            return
        }

        // With adaptOutputFormat, buffer is preserved
        if (firstFrameCaptured) {
            logBuffer("Buffer ready, forcing frame")
            helper.forceFrame()
        } else {
            logBuffer("Buffer not ready, scheduling delayed forceFrame")
            pendingForceFrame = true
            // Fallback: small delay if frame not yet captured
            helper.handler?.postDelayed({
                logBuffer("Delayed forceFrame (buffer ready: $firstFrameCaptured)")
                helper.forceFrame()
            }, 100)
        }
    }

    /**
     * Force frame delivery to WebRTC.
     *
     * Strategy:
     * - Android 13 and below: Restart capture (stop/start) - first frame is always keyframe
     * - Android 14+: Only use forceFrame() - trust RTCP PLI/FIR for keyframes
     *
     * On Android 14+, we cannot manipulate MediaProjection/VirtualDisplay after creation.
     * The browser will automatically request keyframes via RTCP PLI/FIR when needed.
     */
    private fun forceKeyframe() {
        sessionManagerScope.launch {
            captureMutex.withLock {
                if (!isCaptureRunning) {
                    logBuffer("Cannot force keyframe - capture not running")
                    return@withLock
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // Android 14+: Cannot manipulate VirtualDisplay - just force frame delivery
                        // Browser will request keyframes via RTCP PLI/FIR automatically
                        logBuffer("Android 14+: Forcing frame delivery (trusting RTCP for keyframes)")
                        surfaceTextureHelper?.forceFrame()
                    } else {
                        // Android 13 and below: Restart capture (proven to work)
                        logBuffer("Android 13-: Forcing keyframe by restarting capture")
                        screenCapturer.stopCapture()
                        delay(50)
                        screenCapturer.startCapture(streamWidth, streamHeight, TARGET_FPS)
                        delay(100)
                        surfaceTextureHelper?.forceFrame()
                    }
                    logBuffer("Frame forced successfully")
                } catch (e: Exception) {
                    Log.e(TAG_BUFFER, "Error forcing frame: ${e.message}")
                    surfaceTextureHelper?.forceFrame()
                }
            }
        }
    }

    /**
     * Update the screen capture resolution when orientation changes.
     *
     * Strategy:
     * - Android 13 and below: Use changeCaptureFormat() to update resolution
     * - Android 14+: Only update VideoSource adapter, don't touch capture
     *
     * On Android 14+, changeCaptureFormat() would kill MediaProjection.
     * Instead, we rely on VideoSource.adaptOutputFormat() which only affects
     * the output pipeline, not the capture.
     */
    private fun updateCaptureResolution() {
        sessionManagerScope.launch {
            captureMutex.withLock {
                if (!isCaptureRunning) {
                    logBuffer("Cannot update resolution - capture not running")
                    return@withLock
                }

                val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                windowManager.defaultDisplay.getRealMetrics(metrics)
                streamWidth = metrics.widthPixels / 2
                streamHeight = metrics.heightPixels / 2

                logBuffer("Updating resolution to ${streamWidth}x${streamHeight}")

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // Android 14+: Only update VideoSource adapter, don't touch VirtualDisplay
                        // The capture will continue at the original resolution, but output will be adapted
                        logBuffer("Android 14+: Adapting output format only")
                        videoSource.adaptOutputFormat(streamWidth, streamHeight, TARGET_FPS)
                    } else {
                        // Android 13 and below: Can safely change capture format
                        logBuffer("Android 13-: Changing capture format")
                        screenCapturer.changeCaptureFormat(streamWidth, streamHeight, TARGET_FPS)
                    }
                    surfaceTextureHelper?.forceFrame()
                } catch (e: Exception) {
                    Log.e(TAG_BUFFER, "Error updating resolution: ${e.message}")
                }
            }
        }
    }

    suspend fun observeStatsPeriodically() {
        // Example: check stats every 5 seconds
        while (true) {
            delay(5000)

            // Get the raw PeerConnection
            val nativePc = peerConnection?.connection

            nativePc?.getStats { statsReport ->
                statsReport.statsMap.values.forEach { singleStat ->
                    if (singleStat.type == "outbound-rtp") {
                        val qualityReason = singleStat.members["qualityLimitationReason"]?.toString()
                        val framesEncoded = singleStat.members["framesEncoded"]?.toString()
                        val framesDropped = singleStat.members["framesDropped"]?.toString()

                        Log.d(TAG_CONN, "Stats: quality=$qualityReason, encoded=$framesEncoded, dropped=$framesDropped")
                    }
                }
            }
        }
    }

    fun handleHangup() {
        logConn("Hangup received, closing connection")

        // Reset streaming state
        isIceConnected = false
        firstFrameSentAfterConnect = false

        sessionManagerScope.launch {
            peerConnection?.connection?.close()
            peerConnection = null
        }

        // Stop capture completely
        try {
            screenCapturer.stopCapture()
        } catch (e: Exception) {
            Log.e(TAG_BUFFER, "Failed to stop screen capture: ${e.message}")
        }
        isCaptureRunning = false

        logConn("Connection closed, capture stopped")
    }

    /**
     * Set up a media projection by obtaining a MediaProjection object and creating a virtual display
     */
    private fun startProjection(data: Intent?) {
        if (isCaptureRunning) {
            logBuffer("Already capturing, skipping")
            return
        }

        isCaptureRunning = true
        logBuffer("Starting projection...")

        // Reset first frame detection flags
        firstFrameCaptured = false
        pendingForceFrame = false

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val mDisplay = windowManager.defaultDisplay
        mDisplay.getRealMetrics(metrics)
        streamWidth = metrics.widthPixels / 2
        streamHeight = metrics.heightPixels / 2

        screenCapturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                logBuffer("MediaProjection stopped")
                isCaptureRunning = false
            }
        })

        videoSource = peerConnectionFactory.makeVideoSource(screenCapturer.isScreencast).apply {
            adaptOutputFormat(
                streamWidth,
                streamHeight,
                TARGET_FPS
            )

            // Wrapper to detect first frame capture
            val capturerObserverWrapper = object : CapturerObserver {
                override fun onCapturerStarted(success: Boolean) {
                    capturerObserver.onCapturerStarted(success)
                    logBuffer("Capturer started: $success")
                }

                override fun onCapturerStopped() {
                    capturerObserver.onCapturerStopped()
                    logBuffer("Capturer stopped")
                    firstFrameCaptured = false
                }

                override fun onFrameCaptured(frame: VideoFrame) {
                    if (!firstFrameCaptured) {
                        firstFrameCaptured = true
                        logBuffer("*** First frame captured ***")

                        if (pendingForceFrame) {
                            pendingForceFrame = false
                            surfaceTextureHelper?.forceFrame()
                        }
                    }

                    // Log first frame sent after ICE connection
                    if (isIceConnected && !firstFrameSentAfterConnect) {
                        firstFrameSentAfterConnect = true
                        logConn("*** STREAMING - First frame sent to client ***")
                    }

                    capturerObserver.onFrameCaptured(frame)
                }
            }

            screenCapturer.initialize(surfaceTextureHelper, this@WebRTCScreenCaptureService, capturerObserverWrapper)

            try {
                screenCapturer.startCapture(streamWidth, streamHeight, TARGET_FPS)
                logBuffer("Capture started at ${TARGET_FPS}fps, ${streamWidth}x${streamHeight}")
            } catch (e: Exception) {
                Log.e(TAG_BUFFER, "ScreenCapturer failed to start: ${e.message}")
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