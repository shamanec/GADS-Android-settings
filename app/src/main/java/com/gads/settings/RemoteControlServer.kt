package com.gads.settings

import android.annotation.SuppressLint
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.RequiresApi
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Function for executing shell commands on the device
 */
@RequiresApi(Build.VERSION_CODES.O)
fun exec(
    cmd: String,
    asRoot: Boolean = false,
    timeoutSec: Long = 10
): String {
    // build the command line
    val args = if (asRoot) arrayOf("su", "-c", cmd)
    else arrayOf("sh", "-c", cmd)

    val proc = ProcessBuilder(*args)
        .redirectErrorStream(true)   // merge stderr into stdout
        .start()

    // read output in one go
    val output = BufferedReader(InputStreamReader(proc.inputStream)).use {
        it.readText()
    }

    // wait (with timeout) so we don’t leak processes
    if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
        proc.destroyForcibly()
        throw RuntimeException("Command timed out: $cmd")
    }

    return output
}

@Serializable
data class TapRequest(val x: Float, val y: Float)

@Serializable
data class TouchAndHoldRequest(val x: Float, val y: Float, val duration: Long)

@Serializable
data class SwipeRequest(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val duration: Int = 300)

@Serializable
data class StatusResponse(val status: String, val message: String? = null)

@Serializable
data class ScreenSizeResponse(val x: Int, val y: Int)

@Serializable
data class ClipboardResponse(val value: String)

@Serializable
data class AndroidFileNode(
    val name: String,
    val isFile: Boolean = false,
    val fullPath: String,
    val fileDate: Long? = null,
    val children: Map<String, AndroidFileNode>? = null
)

/**
 * The ktor server accepting the requests for the remote control and data acquisition
 */
class RemoteControlServer(private val port: Int) {
    private val inputInjector = InputInjector()
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var logTag = "GADS Remote"

    @SuppressLint("NewApi")
    fun start() {
        val screenDimensions = getScreenSize()
        screenWidth = screenDimensions.first
        screenHeight = screenDimensions.second

        val jsonFormatter = Json {
            prettyPrint = false
            ignoreUnknownKeys = true // skip unknown keys from incoming requests,
            encodeDefaults = true
        }

        embeddedServer(CIO, port = port) {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json(jsonFormatter)
            }

            routing {
                post("/tap") {
                    try {
                        val request = call.receive<TapRequest>()
                        val x = request.x
                        val y = request.y

                        try {
                            Log.d(logTag, "Attempting to perform tap action at coordinates `$x:$y`")
                            inputInjector.tap(x, y)
                            call.respond(HttpStatusCode.OK, StatusResponse("success", "Successfully performed tap action"))
                        } catch (e: Exception) {
                            Log.e(logTag, "Internal error performing tap action: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, StatusResponse("error", "Failed performing tap action - ${e.message}"))
                        }
                    } catch (e: Exception) {
                        Log.e(logTag, "Bad request performing tap action: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest, StatusResponse("error", "Invalid tap request - ${e.message}"))
                    }
                }

                post("/touchAndHold") {
                    try {
                        val request = call.receive<TouchAndHoldRequest>()
                        val x = request.x
                        val y = request.y
                        val duration = request.duration

                        Log.d(logTag, "Attempting to perform touch and hold action at coordinates `$x:$y` for duration $duration")
                        try {
                            inputInjector.touchAndHold(x, y, duration)
                            call.respond(HttpStatusCode.OK, StatusResponse("success", "Successfully performed touch and hold action"))
                        } catch(e: Exception) {
                            Log.e(logTag, "Internal error performing touch and hold action: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, StatusResponse("error", "Failed performing touch and hold action - ${e.message}"))
                        }
                    } catch (e: Exception) {
                        Log.e(logTag, "Bad request performing touch and hold action: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest, StatusResponse("error", "Invalid touch and hold request - ${e.message}"))
                    }
                }

                post("/swipe") {
                    try {
                        val request = call.receive<SwipeRequest>()
                        val x1 = request.x1
                        val y1 = request.y1
                        val x2 = request.x2
                        val y2 = request.y2
                        val duration = request.duration

                        Log.d(logTag, "Attempting to perform swipe action from `$x1:$y1` to `$x2:$y2` for duration $duration")
                        try {
                            inputInjector.swipeAsync(x1, y1, x2, y2, duration)
                            call.respondText("{\"status\": \"success\"}", ContentType.Application.Json)
                        } catch (e: Exception) {
                            Log.e(logTag, "Internal error performing swipe action: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, StatusResponse("error", "Failed performing swipe action - ${e.message}"))
                        }
                    } catch (e: Exception) {
                        Log.e(logTag, "Bad request performing swipe action: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest, StatusResponse("error", "Invalid swipe request - ${e.message}"))
                    }
                }

                get("/screen-size") {
                    Log.d(logTag, "Getting device screen dimensions")
                    try {
                        call.respond(HttpStatusCode.OK, ScreenSizeResponse(screenWidth, screenHeight))
                    } catch (e: Exception) {
                        Log.e(logTag, "Internal error getting device screen dimensions: ${e.message}")
                        call.respond(HttpStatusCode.InternalServerError, StatusResponse("error", "Failed getting device screen dimensions - ${e.message}"))
                    }
                }

                post("/clipboard") {
                    try {
                        Log.d(logTag, "Getting current device IME")
                        val curIme = exec(("settings get secure default_input_method").trim())

                        Log.d(logTag, "Enabling GADS IME")
                        exec("ime enable com.gads.settings/.GADSKeyboardIME")
                        Log.d(logTag, "Setting GADS IME as default")
                        exec("ime set com.gads.settings/.GADSKeyboardIME")

                        // 3. synchronous ordered broadcast: resultData holds Base64 text
                        Log.d(logTag, "Sending broadcast to GADS IME to get clipboard data")
                        val res = exec(
                            "am broadcast -n com.gads.settings/.ClipboardReceiver -a com.gads.settings.CLIPBOARD_GET"
                        )
                        val b64 = Regex("""Broadcast completed: result=.*?data="(.*?)"""")
                            .find(res)?.groupValues?.get(1) ?: ""

                        Log.d(logTag, "Restoring previous device IME")
                        exec("ime set $curIme")

                        call.respond(HttpStatusCode.OK, ClipboardResponse(b64))
                    } catch (e: Exception) {
                        Log.e(logTag, "Internal error getting device clipboard: ${e.message}")
                        call.respond(HttpStatusCode.InternalServerError, StatusResponse("error", "Failed getting device clipboard - ${e.message}"))
                    }
                }

                post("/home") {
                    try {
                        inputInjector.pressHome()
                        call.respond(HttpStatusCode.OK, StatusResponse("success", "Successfully navigated to device Home"))
                    } catch (e: Exception) {
                        Log.e(logTag, "Internal error navigating to Home: ${e.message}")
                        call.respond(HttpStatusCode.InternalServerError, StatusResponse("error", "Failed navigating to device Home - ${e.message}"))
                    }
                }

                post("/lock") {
                    try {
                        Log.d(logTag, "Attempting to lock device by pressing the power button")
                        inputInjector.pressPower()
                        call.respond(HttpStatusCode.OK, StatusResponse("success", "Successfully locked device"))
                    } catch (e: Exception) {
                        Log.e(logTag, "Internal error locking device: ${e.message}")
                        call.respond(HttpStatusCode.InternalServerError, StatusResponse("error", "Failed locking device - ${e.message}"))
                    }
                }

                post("/unlock") {
                    try {
                        Log.d(logTag, "Attempting to unlock device")
                        Log.d(logTag, "Pressing power button on device")
                        inputInjector.pressPower()
                        delay(1000)
                        val xCoordinate = screenWidth / 2
                        val yCoordinate1 = screenHeight - (screenHeight * 0.9)
                        val yCoordinate2 = screenHeight - (screenHeight * 0.1)
                        Log.d(logTag, "Swiping from `$xCoordinate:$yCoordinate1` to `$xCoordinate:$yCoordinate2`")
                        inputInjector.swipeAsync(
                            xCoordinate.toFloat(),
                            yCoordinate1.toFloat(),
                            xCoordinate.toFloat(),
                            yCoordinate2.toFloat(),
                            1000
                        )
                        call.respond(HttpStatusCode.OK, StatusResponse("success", "Successfully unlocked device"))
                    } catch (e: Exception) {
                        Log.e(logTag, "Internal error unlocking device: ${e.message}")
                        call.respond(HttpStatusCode.InternalServerError, StatusResponse("error", "Failed unlocking device - ${e.message}"))
                    }
                }

                post("/appSwitch") {
                    try {
                        Log.d(logTag, "Opening app switcher")
                        inputInjector.openAppSwitch()
                        call.respond(HttpStatusCode.OK, StatusResponse("success", "Successfully opened app switcher"))
                    } catch (e: Exception) {
                        Log.e(logTag, "Internal error opening app switcher: ${e.message}")
                        call.respond(HttpStatusCode.InternalServerError, StatusResponse("error", "Failed opening app switcher - ${e.message}"))
                    }
                }

                get("/files") {
                    val filesNode: AndroidFileNode
                    try {
                        Log.d(logTag, "Getting shared storage files")
                        filesNode = getFiles()
                        call.respond(HttpStatusCode.OK, filesNode)
                    } catch (e: Exception) {
                        Log.e(logTag, "Internal error getting shared storage files: ${e.message}")
                        call.respond(HttpStatusCode.InternalServerError, StatusResponse("error", "Failed getting shared storage files - ${e.message}"))
                    }
                }

                get("/ping") {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }.start(wait = true)
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun getScreenSize(): Pair<Int, Int> {
        val dmgCls = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val getInst = dmgCls.getDeclaredMethod("getInstance")
        val dmg = getInst.invoke(null)

        val getDisplay = dmgCls.getDeclaredMethod("getRealDisplay", Int::class.javaPrimitiveType)
        val display = getDisplay.invoke(dmg, 0)  // 0 = DEFAULT_DISPLAY

        val dm = android.util.DisplayMetrics()
        val getMetrics = display.javaClass.getDeclaredMethod("getRealMetrics", android.util.DisplayMetrics::class.java)
        getMetrics.invoke(display, dm)

        val width = dm.widthPixels
        val height = dm.heightPixels
        Log.i(logTag, "Got screen size via DisplayManagerGlobal: $width:$height")
        return width to height
    }

    private fun getFiles(): AndroidFileNode {
        val root = Environment.getExternalStorageDirectory() // /sdcard
        val topLevelFolders = listOf("DCIM", "Pictures", "Download", "Documents", "Movies", "Music")

        val filteredChildren = root.listFiles()
            ?.filter { it.isDirectory && it.name in topLevelFolders }
            ?.associate { it.name to buildTree(it) }
            ?: emptyMap()

        return AndroidFileNode(
            name = root.name,
            fullPath = root.absolutePath,
            children = filteredChildren
        )
    }

    private fun buildTree(current: File): AndroidFileNode {
        val excludedFolders = setOf(".thumbnails", ".nomedia", "cache", "tmp")
        if (current.isFile) {
            return AndroidFileNode(
                name = current.name,
                fullPath = current.absolutePath,
                isFile = true,
                fileDate = current.lastModified()
            )
        }

        val childrenMap = current.listFiles()
            ?.filter { file ->
                val name = file.name
                name !in excludedFolders && !name.startsWith(".") // Exclude by name and hidden folders
            }
            ?.sortedBy { it.name }
            ?.associate { it.name to buildTree(it) }
            ?: emptyMap()

        return AndroidFileNode(
            name = current.name,
            fullPath = current.absolutePath,
            isFile = false,
            children = childrenMap
        )
    }
}

@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
class InputInjector {
    // Instance of InputManager **or** InputManagerGlobal (depends on API level)
    private var logTag = "GADS Remote"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val inputManagerObj: Any
    private val injectInputEventMethod: Method
    private val injectKeyEventMethod: Method

    init {
        // 1️⃣  Try the new API first (Android 14+)
        var ok = false
        var imObj: Any? = null
        var injectEvent: Method? = null
        try {
            val imGlobalCls = Class.forName("android.hardware.input.InputManagerGlobal")
            val getInst = imGlobalCls.getDeclaredMethod("getInstance")
            getInst.isAccessible = true
            imObj = getInst.invoke(null)

            // instance method: injectInputEvent(InputEvent,int)
            injectEvent = imGlobalCls.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            ok = true
            Log.i(logTag, "InputInjector: using InputManagerGlobal API")
        } catch (_: ClassNotFoundException) {
            /* Device is ≤ Android 13 – fall through */
            Log.w(logTag, "InputInjector: failed using InputManagerGlobal API, attempting fallback to legacy InputManager API")
        }

        // 2️⃣  Fallback for Android 13 and older
        if (!ok) {
            val imCls = Class.forName("android.hardware.input.InputManager")
            val getInst = imCls.getDeclaredMethod("getInstance")
            getInst.isAccessible = true
            imObj = getInst.invoke(null)      // returns non-null on pre-14
            injectEvent = imCls.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            Log.i(logTag, "InputInjector: using legacy InputManager API")
        }

        inputManagerObj = imObj ?: error("Unable to obtain InputManager")
        injectEvent?.isAccessible = true
        injectInputEventMethod = injectEvent!!

        // Key events still go through the same method signature
        injectKeyEventMethod = injectInputEventMethod
        Log.i(logTag, "InputInjector initialized")
    }

    /**
     * Inject events like tap, swipe, etc
     * @param event the event we want to perform either InputEvent or MotionEvent
     */
    private fun injectEvent(event: InputEvent): Boolean =
        injectInputEventMethod.invoke(inputManagerObj, event, 0) as Boolean

    /**
     * Inject key events like Home, Power, etc
     * @param event the key event we want to perform
     */
    private fun injectKey(event: KeyEvent): Boolean =
        injectKeyEventMethod.invoke(inputManagerObj, event, 0) as Boolean

    /**
     * Inject key codes as events like Home, Power, etc
     * @param keyCode the representation for the keycode we want to inject as event, e.g. KeyEvent.KEYCODE_HOME
     */
    private fun injectKeyCode(keyCode: Int) {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = downTime + 10

        val downEvent = KeyEvent(downTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val upEvent = KeyEvent(downTime, eventTime + 10, KeyEvent.ACTION_UP, keyCode, 0)

        injectKey(downEvent)
        injectKey(upEvent)
    }

    /**
     * Simulate pressing Home button
     */
    fun pressHome() {
        injectKeyCode(KeyEvent.KEYCODE_HOME)
    }

    /**
     * Simulate pressing Power button
     */
    fun pressPower() {
        injectKeyCode(KeyEvent.KEYCODE_POWER)
    }

    /**
     * Simulate pressing app switcher button
     */
    fun openAppSwitch() {
        injectKeyCode(KeyEvent.KEYCODE_APP_SWITCH)
    }

    /**
     * Simulate pressing Back button
     */
    fun pressBack() {
        injectKeyCode(KeyEvent.KEYCODE_BACK)
    }

    /**
     * Inject tap event at coordinates
     */
    fun tap(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()

        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }

        val up = MotionEvent.obtain(time, time + 10, MotionEvent.ACTION_UP, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }

        injectEvent(down)
        injectEvent(up)

        down.recycle()
        up.recycle()
    }

    /**
     * Inject touch&hold event at coordinates
     */
    fun touchAndHoldAsync(
        x: Float, y: Float, duration: Long
    ) {
        scope.launch { touchAndHold(x, y, duration) }
    }

    /**
     * Inject touch&hold event at coordinates
     */
    suspend fun touchAndHold(x: Float, y: Float, duration: Long) {
        val time = SystemClock.uptimeMillis()

        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        injectEvent(down)

        delay(duration)

        val upTime = SystemClock.uptimeMillis()
        val up = MotionEvent.obtain(upTime, upTime, MotionEvent.ACTION_UP, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        injectEvent(up)

        down.recycle()
        up.recycle()
    }

    /**
     * Inject swipe event at coordinates
     */
    fun swipeAsync(
        x1: Float, y1: Float, x2: Float, y2: Float,
        duration: Int = 300
    ) {
        scope.launch { swipe(x1, y1, x2, y2, duration) }
    }

    /**
     * Inject swipe event at coordinates
     */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Int) {
        val startTime = SystemClock.uptimeMillis()

        // Touch down at start position
        val down = MotionEvent.obtain(startTime, startTime, MotionEvent.ACTION_DOWN, x1, y1, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        injectEvent(down)

        // Calculate movement
        val steps = maxOf(duration / 5, 10) // More steps, smaller intervals
        val deltaX = (x2 - x1) / steps.toFloat()
        val deltaY = (y2 - y1) / steps.toFloat()
        val stepTime = duration / steps

        // Send move events
        for (i in 1 until steps) {
            val eventTime = startTime + (i * stepTime)
            val currentX = x1 + (deltaX * i)
            val currentY = y1 + (deltaY * i)

            val move = MotionEvent.obtain(startTime, eventTime, MotionEvent.ACTION_MOVE, currentX, currentY, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            injectEvent(move)
            move.recycle()

            // Small delay between moves
            Thread.sleep(stepTime.toLong())
        }

        // Touch up at end position
        val endTime = startTime + duration
        val up = MotionEvent.obtain(startTime, endTime, MotionEvent.ACTION_UP, x2, y2, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        injectEvent(up)

        down.recycle()
        up.recycle()
    }
}

fun main(args: Array<String>) {
    val logTag = "GADS Remote"
    val port = args.getOrNull(0)?.toIntOrNull() ?: 1994
    val jsonFormatter = Json { prettyPrint = false }

    try {
        val server = RemoteControlServer(port)
        Log.i(logTag, "Starting GADS remote control server on port `$port`...")
        Log.i(logTag, "Endpoints:")
        Log.i(logTag, "POST /tap ${jsonFormatter.encodeToString(TapRequest.serializer(), TapRequest(x = 100f, y = 200f))}")
        Log.i(logTag, "POST /swipe ${jsonFormatter.encodeToString(SwipeRequest.serializer(), SwipeRequest(x1 = 100f, y1 = 200f, x2 = 100f, y2 = 200f, duration = 1000))}")
        Log.i(logTag, "POST /touchAndHold ${jsonFormatter.encodeToString(TouchAndHoldRequest.serializer(), TouchAndHoldRequest(x = 100f, y = 200f, duration = 1000))}")
        Log.i(logTag, "GET /screen-size")
        Log.i(logTag, "POST /clipboard")
        Log.i(logTag, "POST /home")
        Log.i(logTag, "POST /back")
        Log.i(logTag, "POST /lock")
        Log.i(logTag, "POST /unlock")
        Log.i(logTag, "POST /appSwitch")
        Log.i(logTag, "POST /ping")

        server.start()
    } catch (e: Exception) {
        Log.e(logTag, "Failed to start GADS remote control server - ${e.message}")
        e.printStackTrace()
    }
}