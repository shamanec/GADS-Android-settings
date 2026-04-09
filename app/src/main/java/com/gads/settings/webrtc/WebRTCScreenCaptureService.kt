package com.gads.settings.webrtc

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.SensorManager
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import android.media.MediaCodecList
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.util.Pair
import com.gads.settings.audio.InternalAudioCapturer
import com.gads.settings.audio.MediaProjectionHolder
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
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.CapturerObserver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.VideoCapturer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoFrame
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
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

    // Orientation listener uses accelerometer - never misses rotation events
    private var orientationListener: OrientationEventListener? = null
    private var lastKnownRotation: Int = -1

    // Screen metrics
    private var metrics: DisplayMetrics = DisplayMetrics()
    private var streamWidth: Int = 0
    private var streamHeight: Int = 0
    private var baseWidth: Int = 0   // Portrait width (smaller dimension)
    private var baseHeight: Int = 0  // Portrait height (larger dimension)

    // Session and connection
    private lateinit var offer: SessionDescription
    private var peerConnection: StreamPeerConnection? = null
    private var peerConnectionFactory: StreamPeerConnectionFactory = StreamPeerConnectionFactory(this)
    private val sessionManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // TURN server configuration (received via WebSocket before offer)
    private var turnIceServers: List<PeerConnection.IceServer> = emptyList()

    // Screen and video
    private lateinit var screenCapturer: VideoCapturer
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

    // Android 14+: Flag to indicate projection was invalidated and needs re-authorization
    @Volatile
    private var projectionNeedsReauthorization = false

    // Audio configuration (set from intent extras in onStartCommand)
    private var audioInputType: String = "microphone"
    private var audioStreamEnabled: Boolean = false
    private var audioInitialized: Boolean = false

    // Microphone audio: WebRTC audio source and track (used when audioInputType == "microphone")
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    // Internal audio: data channel + capturer (used when audioInputType == "internal", API 29+)
    private var audioDataChannel: DataChannel? = null
    private var internalAudioCapturer: InternalAudioCapturer? = null

    // FPS constant
    private val TARGET_FPS = 30

    // Hysteresis to avoid flutter without misaligning with Android system thresholds
    private val HYSTERESIS_DEGREES = 15

    private val SENSOR_DEBOUNCE_MS = 100L
    private val MAX_DEBOUNCE_WAIT_MS = 500L
    private val SYSTEM_ROTATION_TIMEOUT_MS = 450L

    private var pendingRotation: Int = -1
    private var lastStableOrientation: Int = -1
    private var orientationStableSince: Long = 0
    private var debounceStartTime: Long = 0
    private var rotationTimeoutRunnable: Runnable? = null

    // Encoder reconfiguration callback (cancelable for rapid rotations)
    private var encoderReconfigRunnable: Runnable? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || !isCaptureRunning) return

            sessionManagerScope.launch {
                captureMutex.withLock {
                    val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
                    val display = displayManager.getDisplay(displayId) ?: return@withLock
                    val systemRotation = display.rotation

                    cancelRotationTimeout()

                    lastStableOrientation = systemRotation
                    orientationStableSince = 0
                    debounceStartTime = 0

                    when {
                        pendingRotation != -1 && systemRotation == pendingRotation -> {
                            logBuffer("DisplayListener: System confirmed ${rotationToString(systemRotation)}")
                            applyRotationLocked(systemRotation)
                        }
                        systemRotation != lastKnownRotation -> {
                            logBuffer("DisplayListener: External rotation to ${rotationToString(systemRotation)}")
                            applyRotationLocked(systemRotation)
                        }
                        else -> {
                            logBuffer("DisplayListener: No change (${rotationToString(systemRotation)})")
                        }
                    }
                }
            }
        }
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

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
        fun getStartIntent(
            context: Context?,
            resultCode: Int,
            data: Intent?,
            audioInputType: String = "microphone",
            audioStreamEnabled: Boolean = false
        ): Intent {
            val intent = Intent(context, WebRTCScreenCaptureService::class.java)
            intent.putExtra("ACTION", "START")
            intent.putExtra("RESULT_CODE", resultCode)
            intent.putExtra("DATA", data)
            intent.putExtra("AUDIO_INPUT_TYPE", audioInputType)
            intent.putExtra("AUDIO_STREAM_ENABLED", audioStreamEnabled)
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

        /**
         * Detect if device uses Qualcomm hardware encoder.
         * Qualcomm encoders have prefix "OMX.qcom." or "c2.qti."
         * These encoders need longer delay for SPS/PPS generation due to low-latency optimization.
         */
        private fun isQualcommFastEncoder(): Boolean {
            return try {
                val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                codecList.codecInfos.any { codecInfo ->
                    codecInfo.isEncoder &&
                    (codecInfo.name.startsWith("OMX.qcom.") || codecInfo.name.startsWith("c2.qti."))
                }
            } catch (e: Exception) {
                // Fallback: assume not Qualcomm
                false
            }
        }

        /**
         * Get encoder ready delay based on chipset.
         * Qualcomm encoders are optimized for low-latency gaming (240fps),
         * which means they start processing immediately - before SPS/PPS
         * generation is complete. Exynos/Mali encoders have deeper pipelines
         * and naturally wait longer.
         */
        private fun getEncoderReadyDelay(): Long {
            return if (isQualcommFastEncoder()) {
                300L  // Qualcomm encoders need longer delay for SPS/PPS generation (300ms tested insufficient)
            } else {
                100L  // Other devices (Exynos, older Snapdragon)
            }
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

        // Register orientation listener for reliable rotation detection
        orientationListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN || !isCaptureRunning) return

                val now = System.currentTimeMillis()

                // Calculate expected rotation using AOSP formula
                val nearestRotation = when (((orientation + 45) / 90) % 4) {
                    0 -> Surface.ROTATION_0     // 315-45 -> PORTRAIT
                    1 -> Surface.ROTATION_270   // 45-135 -> LANDSCAPE_INVERTED
                    2 -> Surface.ROTATION_180   // 135-225 -> PORTRAIT_INVERTED
                    3 -> Surface.ROTATION_90    // 225-315 -> LANDSCAPE
                    else -> lastKnownRotation
                }

                // Skip if same as current rotation
                if (nearestRotation == lastKnownRotation && pendingRotation == -1) return

                // Skip if this orientation was already rejected (app doesn't support it)
                if (nearestRotation == lastStableOrientation && nearestRotation != lastKnownRotation) return

                // Debounce: ensure orientation is stable before acting
                if (nearestRotation == lastStableOrientation) {
                    // Same as last stable reading - check if stable long enough
                    if (now - orientationStableSince >= SENSOR_DEBOUNCE_MS) {
                        // Stable long enough - proceed with rotation check
                    } else {
                        // Not stable long enough yet
                        return
                    }
                } else {
                    // Different orientation - start new stability timer
                    lastStableOrientation = nearestRotation
                    orientationStableSince = now

                    // If no pending rotation yet, start debounce wait
                    if (debounceStartTime == 0L) {
                        debounceStartTime = now
                    }
                    return
                }

                // Max debounce wait exceeded - force processing
                if (debounceStartTime > 0 && now - debounceStartTime >= MAX_DEBOUNCE_WAIT_MS) {
                    logBuffer("Max debounce wait exceeded, forcing rotation check")
                }

                // Handle fast rotation: update pending target if user rotated again
                if (pendingRotation != -1) {
                    if (pendingRotation != nearestRotation) {
                        logBuffer("Fast rotation: updating pending ${rotationToString(pendingRotation)} -> ${rotationToString(nearestRotation)}")
                        pendingRotation = nearestRotation
                        scheduleRotationTimeout()
                    }
                    return
                }

                // Apply hysteresis to avoid flutter
                val currentCenter = when (lastKnownRotation) {
                    Surface.ROTATION_0 -> 0
                    Surface.ROTATION_270 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_90 -> 270
                    else -> 0
                }

                val distance = minOf(
                    kotlin.math.abs(orientation - currentCenter),
                    360 - kotlin.math.abs(orientation - currentCenter)
                )

                if (distance > 45 + HYSTERESIS_DEGREES) {
                    // Set pending rotation and schedule timeout
                    // DisplayListener will confirm or timeout will apply
                    logBuffer("Sensor detected: ${orientation}° -> ${rotationToString(nearestRotation)}, waiting for system...")
                    pendingRotation = nearestRotation
                    debounceStartTime = now
                    scheduleRotationTimeout()
                }
            }
        }
        orientationListener?.enable()

    }

    // The onStartCommand() method is called by the Android system when the service is started
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        // Reset timing for debug
        serviceStartTime = System.currentTimeMillis()
        logBuffer("Service starting")

        // Keep the intent for multiple projection start ups
        val data = intent.getParcelableExtra<Intent>("DATA")
        projectionIntent = data

        // Reset re-authorization flag when we receive a new Intent
        projectionNeedsReauthorization = false

        // Start the foreground service
        if (isStartCommand(intent)) {
            audioInputType = intent.getStringExtra("AUDIO_INPUT_TYPE") ?: "microphone"
            audioStreamEnabled = intent.getBooleanExtra("AUDIO_STREAM_ENABLED", false)

            // Create notification
            val notification: Pair<Int, Notification> = WebRTCNotificationUtils.getNotification(
                this
            )
            startForeground(notification.first, notification.second)
            logBuffer("Foreground service started")

            // Setup microphone audio track; internal audio uses a DataChannel set up in sendAnswer()
            if (audioStreamEnabled && audioInputType == "microphone" && !audioInitialized) {
                val audioConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                }
                localAudioSource = peerConnectionFactory.makeAudioSource(audioConstraints)
                localAudioTrack = peerConnectionFactory.makeAudioTrack(localAudioSource!!, "audio0")
                localAudioTrack?.setEnabled(true)
                audioInitialized = true
            }

            // Android 14+: Start capture immediately (Intent is one-time-use)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (!isCaptureRunning && projectionIntent != null) {
                    logBuffer("Android 14+: Starting capture immediately")
                    startProjection(projectionIntent)
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Disable orientation listener to prevent leaks
        orientationListener?.disable()
        orientationListener = null

        // Unregister display listener
        try {
            val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
            displayManager.unregisterDisplayListener(displayListener)
        } catch (e: Exception) {
            Log.e(TAG_BUFFER, "Failed to unregister DisplayListener: ${e.message}")
        }

        // Cancel any pending rotation timeout
        cancelRotationTimeout()

        // Release WebRTC audio resources
        localAudioTrack?.dispose()
        localAudioTrack = null
        localAudioSource?.dispose()
        localAudioSource = null

        // Release internal audio resources
        internalAudioCapturer?.stopCapture()
        internalAudioCapturer = null
        audioDataChannel?.close()
        audioDataChannel?.dispose()
        audioDataChannel = null

        // Cleanup capture when service is destroyed
        if (isCaptureRunning) {
            try {
                screenCapturer.stopCapture()
            } catch (e: Exception) {
                Log.e(TAG_BUFFER, "Failed to stop screen capture on destroy: ${e.message}")
            }
            isCaptureRunning = false
        }
    }

    fun setReceivedOffer(offerMsg: String) {
        logConn("Offer received")

        // Parse the offer SDP from the received message from the React UI client
        val offerJson = JSONObject(offerMsg)
        val sdp = offerJson.getString("sdp")
        offer = SessionDescription(SessionDescription.Type.OFFER, sdp)

        // Ensure capture is running before handling the offer
        // Android 13-: Check isCaptureRunning directly (handles race condition with hangup coroutine)
        // Android 14+: Capture should already be running (started in onStartCommand)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (!isCaptureRunning && projectionIntent != null) {
                logBuffer("Android 13-: Restarting capture (was stopped)")
                startProjection(projectionIntent)
            }
        } else {
            // Android 14+: Capture must be running, cannot restart (one-time-use token)
            if (!isCaptureRunning) {
                if (projectionNeedsReauthorization) {
                    logBuffer("Projection invalidated, triggering re-authorization")
                    triggerReauthorization()
                    return
                }
                Log.e(TAG_BUFFER, "Cannot handle offer - capture not running on Android 14+")
                return
            }
        }

        runBlocking {
            peerConnection?.connection?.close()
            peerConnection = null

            logConn("Creating PeerConnection...")

            // Build RTCConfiguration with TURN servers if available
            val iceServers = mutableListOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
            iceServers.addAll(turnIceServers)

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                enableCpuOveruseDetection = false
                enableDscp = true
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                iceCandidatePoolSize = 5
            }

            logConn("PeerConnection config: ${iceServers.size} ICE servers (STUN + ${turnIceServers.size} TURN)")

            // Create a StreamPeerConnection
            peerConnection = peerConnectionFactory.makePeerConnection(
                coroutineScope = sessionManagerScope,
                configuration = rtcConfig,
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
     * Update TURN server configuration (must be called BEFORE offer is received)
     * This stores TURN servers to be used when PeerConnection is created
     */
    fun updateTURNConfig(server: String, port: Int, username: String, password: String) {
        Log.i(TAG_CONN, "Storing TURN config: $server:$port (will apply on next PeerConnection)")

        val turnUri = "turn:$server:$port"
        turnIceServers = listOf(
            PeerConnection.IceServer.builder(turnUri)
                .setUsername(username)
                .setPassword(password)
                .createIceServer()
        )

        Log.i(TAG_CONN, "TURN config stored successfully")
    }

    /**
     * Check if TURN configuration has been set
     */
    fun hasTURNConfigured(): Boolean = turnIceServers.isNotEmpty()

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
                    encoding.minBitrateBps = 1_000_000
                    encoding.maxBitrateBps = 5_000_000
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

        if (!::localVideoTrack.isInitialized) {
            Log.e(TAG_CONN, "sendAnswer: localVideoTrack is not initialized!")
            return
        }

        peerConnection?.connection?.setRemoteDescription(object : org.webrtc.SdpObserver {
            override fun onSetSuccess() {
                val mediaStreamLabels = listOf("ARDAMS")
                peerConnection?.connection?.addTrack(localVideoTrack, mediaStreamLabels)
                localAudioTrack?.let { peerConnection?.connection?.addTrack(it, mediaStreamLabels) }

                // For internal audio: create a negotiated data channel to send raw PCM to the browser.
                // Browser creates the same channel (id=42, negotiated) and plays via AudioContext.
                if (audioStreamEnabled && audioInputType == "internal" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val init = DataChannel.Init().apply {
                        negotiated = true
                        id = 42
                        ordered = true
                    }
                    audioDataChannel = peerConnection?.connection?.createDataChannel("audio", init)
                    MediaProjectionHolder.getOrListen { mp ->
                        val capturer = InternalAudioCapturer(mp)
                        internalAudioCapturer = capturer
                        capturer.startCapture { pcmData, _ ->
                            val channel = audioDataChannel ?: return@startCapture
                            if (channel.state() == DataChannel.State.OPEN) {
                                channel.send(DataChannel.Buffer(ByteBuffer.wrap(pcmData), true))
                            }
                        }
                    }
                }

                peerConnection?.connection?.transceivers?.forEach { transceiver ->
                    transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                    if (transceiver.sender.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                        configureEncodingParameters(transceiver)
                    }
                }

                peerConnection?.connection?.createAnswer(object : org.webrtc.SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        peerConnection?.connection?.setLocalDescription(object : org.webrtc.SdpObserver {
                            override fun onSetSuccess() {
                                peerConnection?.connection?.setBitrate(1_000_000, 300_000, 5_000_000)
                                updatePeerConnectionConstraints()

                                val jsonAnswer = JSONObject().apply {
                                    put("type", answer?.type?.canonicalForm())
                                    put("sdp", answer?.description)
                                }.toString()
                                server.sendMessage(jsonAnswer)

                                forceInitialFrame()
                            }

                            override fun onSetFailure(error: String?) {
                                Log.e(TAG_CONN, "setLocalDescription failed: $error")
                            }

                            override fun onCreateSuccess(sdp: SessionDescription?) {}
                            override fun onCreateFailure(error: String?) {}
                        }, answer)
                    }

                    override fun onCreateFailure(error: String?) {
                        Log.e(TAG_CONN, "createAnswer failed: $error")
                    }

                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String?) {}
                }, mediaConstraints)
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG_CONN, "setRemoteDescription failed: $error")
            }

            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, offer)
    }

    private fun configureEncodingParameters(transceiver: RtpTransceiver) {
        val sender = transceiver.sender
        val parameters = sender.parameters

        val encoding = if (parameters.encodings.isNotEmpty()) {
            parameters.encodings[0]
        } else {
            RtpParameters.Encoding(null, true, 1.0).also {
                parameters.encodings.add(it)
            }
        }

        encoding.apply {
            minBitrateBps = 1_000_000
            maxBitrateBps = 5_000_000
            maxFramerate = 30
            scaleResolutionDownBy = 1.0
            active = true

            try {
                scalabilityMode = "L1T1"
            } catch (e: Exception) {
                Log.w(TAG_CONN, "Scalability mode L1T1 not supported: ${e.message}")
            }
        }

        sender.parameters = parameters
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
     * Force encoder reconfiguration after resolution change.
     *
     * Creates a NEW VideoTrack with the same source to force complete encoder reset,
     * which guarantees new SPS/PPS headers with correct resolution.
     *
     * Note: setTrack(null) disposes the track, so we must create a new one.
     */
    private fun forceEncoderReconfiguration() {
        val pc = peerConnection?.connection ?: return

        val videoSender = pc.senders.find {
            it.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND
        } ?: run {
            logBuffer("No video sender found for encoder reconfiguration")
            return
        }

        try {
            if (!::videoSource.isInitialized) {
                logBuffer("VideoSource not initialized, skipping encoder reconfiguration")
                return
            }

            logBuffer("Encoder reset: creating new track with dimensions ${streamWidth}x${streamHeight}")

            // Create NEW track with same source (this forces encoder reset)
            val newTrack = peerConnectionFactory.makeVideoTrack(
                source = videoSource,
                trackId = "ScreenTrack${UUID.randomUUID()}"
            )
            newTrack.setEnabled(true)

            // Replace track in sender (this triggers encoder reconfiguration)
            videoSender.setTrack(newTrack, false)
            logBuffer("Encoder reset: new track attached")

            // Dispose old track and update reference
            val oldTrack = localVideoTrack
            localVideoTrack = newTrack

            // Emit new track to flow
            sessionManagerScope.launch {
                _localVideoTrackFlow.emit(localVideoTrack)
            }

            // Dispose old track after delay to ensure encoder has generated new SPS/PPS
            // Qualcomm encoders (Snapdragon 8 Gen 2/3) need longer delay due to low-latency optimization
            val encoderDelay = getEncoderReadyDelay()
            logBuffer("Encoder ready delay: ${encoderDelay}ms (Qualcomm fast encoder: ${isQualcommFastEncoder()})")

            mHandler?.postDelayed({
                try {
                    oldTrack.dispose()
                    logBuffer("Encoder reset: old track disposed")
                } catch (e: Exception) {
                    Log.w(TAG_BUFFER, "Old track disposal warning: ${e.message}")
                }

                // RESUME frame delivery AFTER encoder is ready with new SPS/PPS
                (screenCapturer as? ResizableScreenCapturer)?.resumeFrameDelivery()

                // Force frame delivery
                surfaceTextureHelper?.forceFrame()
                logBuffer("Encoder reset complete, frame forced")
            }, encoderDelay)

        } catch (e: Exception) {
            Log.e(TAG_BUFFER, "Encoder reconfiguration failed: ${e.message}")
            // Fallback: resume frames and force frame
            (screenCapturer as? ResizableScreenCapturer)?.resumeFrameDelivery()
            surfaceTextureHelper?.forceFrame()
        }
    }

    /**
     * Schedule encoder reconfiguration with callback cancellation.
     * Ensures only the latest rotation triggers reconfiguration.
     *
     * Since frames are paused, we can use a short delay (just enough for
     * adaptOutputFormat to propagate). The encoder reset will resume frames.
     */
    private fun scheduleEncoderReconfiguration() {
        // Cancel any pending reconfiguration
        encoderReconfigRunnable?.let { mHandler?.removeCallbacks(it) }

        encoderReconfigRunnable = Runnable {
            if (isCaptureRunning && isIceConnected) {
                forceEncoderReconfiguration()
            } else {
                // If not connected, just resume frames (no encoder to reconfigure)
                (screenCapturer as? ResizableScreenCapturer)?.resumeFrameDelivery()
                logBuffer("Encoder reconfig skipped (not connected), frames resumed")
            }
        }

        // Short delay since frames are paused - just need adaptOutputFormat to propagate
        val delay = 50L

        logBuffer("Scheduling encoder reconfig in ${delay}ms (${streamWidth}x${streamHeight})")
        mHandler?.postDelayed(encoderReconfigRunnable!!, delay)
    }

    private fun rotationToString(rotation: Int): String {
        return when (rotation) {
            Surface.ROTATION_0 -> "PORTRAIT (0°)"
            Surface.ROTATION_90 -> "LANDSCAPE (90°)"
            Surface.ROTATION_180 -> "PORTRAIT_INVERTED (180°)"
            Surface.ROTATION_270 -> "LANDSCAPE_INVERTED (270°)"
            else -> "UNKNOWN ($rotation)"
        }
    }

    /**
     * Cancel any pending rotation timeout.
     */
    private fun cancelRotationTimeout() {
        rotationTimeoutRunnable?.let { mHandler?.removeCallbacks(it) }
        rotationTimeoutRunnable = null
    }

    /**
     * Schedule a timeout to apply pending rotation if DisplayListener doesn't confirm in time.
     */
    private fun scheduleRotationTimeout() {
        cancelRotationTimeout()

        rotationTimeoutRunnable = Runnable {
            if (pendingRotation != -1 && isCaptureRunning) {
                handleRotationTimeout()
            }
        }
        mHandler?.postDelayed(rotationTimeoutRunnable!!, SYSTEM_ROTATION_TIMEOUT_MS)
    }

    /**
     * Handle timeout: Check if system rotation matches pending.
     *
     * Design principle: "Display is Truth"
     * - The capture should always follow Display.rotation (actual screen content)
     * - If sensor detected rotation but Display didn't change, the foreground app
     *   doesn't support that orientation - we should NOT override it
     * - Only apply rotation if system actually rotated (confirmed by Display.rotation)
     */
    private fun handleRotationTimeout() {
        sessionManagerScope.launch {
            captureMutex.withLock {
                if (pendingRotation == -1 || !isCaptureRunning) return@withLock

                // Verify current system rotation - this is the source of truth
                val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
                val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                val currentSystemRotation = display?.rotation ?: lastKnownRotation

                when {
                    currentSystemRotation == pendingRotation -> {
                        // System rotated but DisplayListener missed it (rare edge case)
                        logBuffer("Rotation timeout: Display confirms ${rotationToString(pendingRotation)}, applying")
                        applyRotationLocked(pendingRotation)
                    }
                    else -> {
                        // System didn't rotate = foreground app doesn't support this orientation
                        // Cancel pending rotation and stay with current display orientation
                        logBuffer("Rotation timeout: Display=${rotationToString(currentSystemRotation)} != Sensor=${rotationToString(pendingRotation)}. App doesn't support rotation, cancelling.")

                        // Remember rejected sensor orientation to avoid repeated attempts
                        val rejectedOrientation = pendingRotation
                        pendingRotation = -1
                        lastStableOrientation = rejectedOrientation  // Store rejected sensor orientation
                        debounceStartTime = 0
                        orientationStableSince = 0
                    }
                }
            }
        }
    }

    /**
     * Apply rotation change. Must be called within captureMutex.
     * Resets pending state and updates capture dimensions.
     *
     * @param targetRotation The rotation to apply (Surface.ROTATION_*)
     */
    private fun applyRotationLocked(targetRotation: Int) {
        // Reset pending state
        pendingRotation = -1
        lastStableOrientation = -1
        debounceStartTime = 0

        if (!isCaptureRunning) {
            logBuffer("Cannot apply rotation - capture not running")
            return
        }

        // Android 14+: Do NOT resize manually - onCapturedContentResize callback is the source of truth
        // The callback will receive correct dimensions from the system and trigger resize with proper values
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            lastKnownRotation = targetRotation
            logBuffer("Android 14+: Rotation tracked (resize delegated to system callback)")
            return
        }

        // Calculate dimensions based on target rotation
        val isLandscape = targetRotation == Surface.ROTATION_90 ||
                          targetRotation == Surface.ROTATION_270

        val newWidth = if (isLandscape) baseHeight else baseWidth
        val newHeight = if (isLandscape) baseWidth else baseHeight

        if (newWidth == streamWidth && newHeight == streamHeight) {
            lastKnownRotation = targetRotation
            return
        }

        lastKnownRotation = targetRotation
        streamWidth = newWidth
        streamHeight = newHeight

        logBuffer("Rotation applied: ${streamWidth}x${streamHeight} (${rotationToString(targetRotation)})")

        try {
            // Android 13-: Reconfigure capture and encoder
            // PAUSE frames to prevent SPS/PPS mismatch during transition
            (screenCapturer as? ResizableScreenCapturer)?.pauseFrameDelivery()

            screenCapturer.changeCaptureFormat(streamWidth, streamHeight, TARGET_FPS)
            videoSource.adaptOutputFormat(streamWidth, streamHeight, TARGET_FPS)

            // Schedule encoder reconfiguration (will resume frames when done)
            if (isIceConnected) {
                scheduleEncoderReconfiguration()
            } else {
                // Not connected - just resume frames
                (screenCapturer as? ResizableScreenCapturer)?.resumeFrameDelivery()
                surfaceTextureHelper?.forceFrame()
            }
        } catch (e: Exception) {
            Log.e(TAG_BUFFER, "Error applying rotation: ${e.message}")
            (screenCapturer as? ResizableScreenCapturer)?.resumeFrameDelivery()
            surfaceTextureHelper?.forceFrame()
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

        // Cancel any pending rotation timeout
        cancelRotationTimeout()

        sessionManagerScope.launch {
            peerConnection?.connection?.close()
            peerConnection = null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: Keep capture running (Intent is one-time-use)
            logConn("Connection closed, keeping capture running for reconnection (Android 14+)")
        } else {
            // Android 13-: Original behavior - stop capture completely
            try {
                val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
                displayManager.unregisterDisplayListener(displayListener)
            } catch (e: Exception) {
                Log.e(TAG_BUFFER, "Failed to unregister DisplayListener: ${e.message}")
            }

            try {
                screenCapturer.stopCapture()
            } catch (e: Exception) {
                Log.e(TAG_BUFFER, "Failed to stop screen capture: ${e.message}")
            }
            isCaptureRunning = false
            logConn("Connection closed, capture stopped")
        }
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

        // Capture base dimensions (portrait orientation: width < height)
        // Use FULL dimensions to match onCapturedContentResize callback (Android 14+)
        baseWidth = minOf(metrics.widthPixels, metrics.heightPixels)
        baseHeight = maxOf(metrics.widthPixels, metrics.heightPixels)

        // Set initial stream dimensions based on current rotation
        lastKnownRotation = mDisplay.rotation
        val isLandscape = lastKnownRotation == Surface.ROTATION_90 || lastKnownRotation == Surface.ROTATION_270
        streamWidth = if (isLandscape) baseHeight else baseWidth
        streamHeight = if (isLandscape) baseWidth else baseHeight

        // Register display listener for system rotation events
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, mHandler)
        logBuffer("DisplayListener registered")

        val mediaProjectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                logBuffer("MediaProjection stopped")
                isCaptureRunning = false

                // Android 14+: Mark that we need re-authorization
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    projectionNeedsReauthorization = true
                    logBuffer("Projection invalidated, will request re-authorization on next client connection")
                }
            }
        }

        // Android 14+: Use ResizableScreenCapturer (supports VirtualDisplay.resize)
        // Android 13-: Use standard ScreenCapturerAndroid
        screenCapturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val isQualcomm = isQualcommFastEncoder()
            logBuffer("Creating ResizableScreenCapturer (Qualcomm: $isQualcomm)")
            ResizableScreenCapturer(data!!, mediaProjectionCallback, isQualcomm)
        } else {
            logBuffer("Android 13-: Using ScreenCapturerAndroid")
            ScreenCapturerAndroid(data!!, mediaProjectionCallback)
        }

        videoSource = peerConnectionFactory.makeVideoSource(screenCapturer.isScreencast).apply {
            // Android 14+: Set up resize listener to receive dimensions from system callback
            // This ensures VideoSource uses correct dimensions from onCapturedContentResize
            if (screenCapturer is ResizableScreenCapturer) {
                val source = this
                val resizableCapturer = screenCapturer as ResizableScreenCapturer
                resizableCapturer.setResizeListener(object : ResizableScreenCapturer.ResizeListener {
                    override fun onCaptureResized(width: Int, height: Int) {
                        logBuffer("System callback resize: ${width}x${height}")

                        // 1. PAUSE frames immediately to prevent SPS/PPS mismatch
                        resizableCapturer.pauseFrameDelivery()

                        streamWidth = width
                        streamHeight = height

                        // 2. Update VideoSource adapter
                        source.adaptOutputFormat(width, height, TARGET_FPS)

                        // 3. Schedule encoder reconfig (will resume frames when done)
                        scheduleEncoderReconfiguration()
                    }
                })
            }
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

                    // Android 14+: VirtualDisplay captures correctly oriented content
                    // No pixel rotation needed - buffer dimensions are fixed, letterboxing may occur
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        capturerObserver.onFrameCaptured(frame)
                        return
                    }

                    // Android 13-: frames already come correctly sized via changeCaptureFormat()
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

    /**
     * Trigger re-authorization by launching the permission activity.
     * Called when projection was invalidated (Android 14+) and client tries to connect.
     * The appops PROJECT_MEDIA permission set by the backend will skip the user dialog.
     */
    private fun triggerReauthorization() {
        logBuffer("Launching WebRTCScreenCaptureActivity for re-authorization")

        val intent = Intent(this, WebRTCScreenCaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

}