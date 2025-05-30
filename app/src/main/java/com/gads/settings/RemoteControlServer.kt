package com.gads.settings

import android.annotation.SuppressLint
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.RequiresApi
import io.ktor.http.*
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
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

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

class RemoteControlServer(private val port: Int) {
    private val inputInjector = InputInjector()
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    @SuppressLint("NewApi")
    fun start() {
        val screenDimensions = getScreenSize()
        screenWidth = screenDimensions.first
        screenHeight = screenDimensions.second

        embeddedServer(CIO, port = port) {
            routing {
                post("/tap") {
                    try {
                        val body = call.receiveText()
                        val json = JSONObject(body)
                        val x = json.getDouble("x").toFloat()
                        val y = json.getDouble("y").toFloat()

                        inputInjector.tap(x, y)
                        call.respondText("{\"status\": \"success\"}", ContentType.Application.Json)
                    } catch (e: Exception) {
                        println("Tap error: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest, "{\"status\": \"error\", \"message\": \"Invalid request\"}")
                    }
                }

                post("/touchAndHold") {
                    try {
                        val body = call.receiveText()
                        val json = JSONObject(body)
                        val x = json.getDouble("x").toFloat()
                        val y = json.getDouble("y").toFloat()
                        val duration = json.getLong("duration")

                        inputInjector.touchAndHold(x, y, duration)
                        call.respondText("{\"status\": \"success\"}", ContentType.Application.Json)
                    } catch (e: Exception) {
                        println("Tap error: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest, "{\"status\": \"error\", \"message\": \"Invalid request\"}")
                    }
                }

                post("/swipe") {
                    try {
                        val body = call.receiveText()
                        val json = JSONObject(body)
                        val x1 = json.getDouble("x1").toFloat()
                        val y1 = json.getDouble("y1").toFloat()
                        val x2 = json.getDouble("x2").toFloat()
                        val y2 = json.getDouble("y2").toFloat()
                        val duration = json.optInt("duration", 300)

                        inputInjector.swipeAsync(x1, y1, x2, y2, duration)
                        call.respondText("{\"status\": \"success\"}", ContentType.Application.Json)
                    } catch (e: Exception) {
                        println("Swipe error: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest, "{\"status\": \"error\", \"message\": \"Invalid request\"}")
                    }
                }

                get("/screen-size") {
                    try {
                        call.respondText("{\"x\": ${screenWidth}, \"y\": ${screenHeight}}")
                    } catch (e: Exception) {
                        println("Get screen error: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest, "{\"status\": \"error\", \"message\": \"Invalid request\"}")
                    }
                }


                post("/clipboard") {
                    try {
                        // 1. remember current IME
                        val curIme = exec(("settings get secure default_input_method").trim())

                        // 2. switch to RemoteIme (enable if necessary)
                        exec("ime enable com.gads.settings/.GADSKeyboardIME")
                        exec("ime set com.gads.settings/.GADSKeyboardIME")

                        // 3. synchronous ordered broadcast: resultData holds Base64 text
                        val res = exec(
                            "am broadcast -n com.gads.settings/.ClipboardReceiver -a com.gads.settings.CLIPBOARD_GET"
                        )
                        val b64 = Regex("""Broadcast completed: result=.*?data="(.*?)"""")
                            .find(res)?.groupValues?.get(1) ?: ""
                        val text = String(Base64.decode(b64, Base64.NO_WRAP))

                        // 4. restore user IME
                        exec("ime set $curIme")

                        call.respond(HttpStatusCode.OK, "{\"value\": \"${b64}\"}")
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, "{\"status\": \"error\", \"message\": \"Invalid request\"}")
                    }
                }

                post("/home") {
                    try {
                        inputInjector.pressHome()
                        call.respondText("{\"status\": \"success\"}", ContentType.Application.Json)
                    } catch (e: Exception) {
                        println("Home error: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest, "{\"status\": \"error\", \"message\": \"Invalid request\"}")
                    }
                }

                post("/lock") {
                    try {
                        inputInjector.pressPower()
                        call.respondText("{\"status\": \"success\"}", ContentType.Application.Json)
                    } catch (e: Exception) {
                        println("Lock error: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest, "{\"status\": \"error\", \"message\": \"Invalid request\"}")
                    }
                }

                post("/unlock") {
                    try {
                        inputInjector.pressPower()
                        call.respondText("{\"status\": \"success\"}", ContentType.Application.Json)
                    } catch (e: Exception) {
                        println("Unlock error: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest, "{\"status\": \"error\", \"message\": \"Invalid request\"}")
                    }
                }

                post("/appSwitch") {
                    try {
                        inputInjector.openAppSwitch()
                        call.respondText("{\"status\": \"success\"}", ContentType.Application.Json)
                    } catch (e: Exception) {
                        println("Home error: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest, "{\"status\": \"error\", \"message\": \"Invalid request\"}")
                    }
                }

                get("/ping") {
                    call.respondText("{\"status\": \"pong\"}", ContentType.Application.Json)
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
        println("Screen size via DisplayManagerGlobal: ${width}x${height}")
        return width to height
    }
}

@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
class InputInjector {
    // Instance of InputManager **or** InputManagerGlobal (depends on API level)
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
            println("InputInjector: using InputManagerGlobal API")
        } catch (_: ClassNotFoundException) {
            /* Device is ≤ Android 13 – fall through */
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
            println("InputInjector: using legacy InputManager API")
        }

        inputManagerObj = imObj ?: error("Unable to obtain InputManager")
        injectEvent?.isAccessible = true
        injectInputEventMethod = injectEvent!!

        // Key events still go through the same method signature
        injectKeyEventMethod = injectInputEventMethod
        println("InputInjector initialized")
    }

    private fun injectEvent(event: InputEvent): Boolean =
        injectInputEventMethod.invoke(inputManagerObj, event, 0) as Boolean

    private fun injectKey(event: KeyEvent): Boolean =
        injectKeyEventMethod.invoke(inputManagerObj, event, 0) as Boolean

    private fun injectKeyCode(keyCode: Int) {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = downTime + 10

        val downEvent = KeyEvent(downTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val upEvent = KeyEvent(downTime, eventTime + 10, KeyEvent.ACTION_UP, keyCode, 0)

        injectKey(downEvent)
        injectKey(upEvent)
    }

    fun pressHome() {
        injectKeyCode(KeyEvent.KEYCODE_HOME)
        println("Pressing Home")
    }

    fun pressPower() {
        injectKeyCode(KeyEvent.KEYCODE_POWER)
        println("Pressing power")
    }

    fun openAppSwitch() {
        injectKeyCode(KeyEvent.KEYCODE_APP_SWITCH)
        println("Opening app switch")
    }

    fun pressBack() {
        injectKeyCode(KeyEvent.KEYCODE_BACK)
        println("PRessing back")
    }

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

        println("Tap at ($x, $y)")
    }

    fun touchAndHoldAsync(
        x: Float, y: Float, duration: Long
    ) {
        scope.launch { touchAndHold(x, y, duration) }
    }

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

        println("Touch and hold at ($x, $y) for duration $duration")
    }

    fun swipeAsync(
        x1: Float, y1: Float, x2: Float, y2: Float,
        duration: Int = 300
    ) {
        scope.launch { swipe(x1, y1, x2, y2, duration) }
    }

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

        println("Swipe from ($x1, $y1) to ($x2, $y2) over ${duration}ms")
    }
}

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object ClipboardHelper {

    private val context by lazy {
        // Works from a plain app_process entry-point
        val at = Class.forName("android.app.ActivityThread")
        val curApp = at.getDeclaredMethod("currentApplication")
        curApp.invoke(null) as android.content.Context?
            ?: error("No Context available")
    }

    /**
     * @return current primary-clip text or null if empty/non-text
     */
    fun getText(): String? = runCatching {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        cm.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
    }.getOrNull()
}

fun main(args: Array<String>) {
    println("Starting Remote Control Server...")

    val port = args.getOrNull(0)?.toIntOrNull() ?: 1994

    try {
        val server = RemoteControlServer(port)
        println("Server starting on port $port")
        println("Endpoints:")
        println("  POST /tap - {\"x\": 100, \"y\": 200}")
        println("  POST /swipe - {\"x1\": 100, \"y1\": 200, \"x2\": 300, \"y2\": 400, \"duration\": 500}")

        server.start()
    } catch (e: Exception) {
        println("Failed to start server: ${e.message}")
        e.printStackTrace()
    }
}