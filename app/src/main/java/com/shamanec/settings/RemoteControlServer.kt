package com.shamanec.settings

import android.annotation.SuppressLint
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import android.graphics.Bitmap
import android.util.Base64

/**
 * Function for executing shell commands on the device
 */
fun exec(
    cmd: String,
    asRoot: Boolean = false,
    timeoutSec: Long = 10
): String {
    val args = if (asRoot) arrayOf("su", "-c", cmd)
    else arrayOf("sh", "-c", cmd)

    val proc = ProcessBuilder(*args)
        .redirectErrorStream(true)
        .start()

    val output = BufferedReader(InputStreamReader(proc.inputStream)).use {
        it.readText()
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw RuntimeException("Command timed out: $cmd")
        }
    } else {
        proc.waitFor()
    }

    return output
}

/**
 * Returns "portrait" or "landscape" based on the display surface orientation
 * (0/1/2/3). Reading the focused window config is unreliable: a letterboxed app
 * (e.g. YouTube) keeps a portrait window even when the display is forced to landscape.
 */
fun getCurrentRotation(): String {
    for (line in exec("dumpsys input").lineSequence()) {
        val idx = line.indexOf("SurfaceOrientation:")
        if (idx < 0) continue
        val value = line.substring(idx + "SurfaceOrientation:".length).trim().takeWhile { it.isDigit() }.toIntOrNull()
        return if (value == 1 || value == 3) "landscape" else "portrait"
    }
    return "portrait"
}

// Packages that must keep their natural orientation. The display-wide force override is
// never applied while one of these is focused, so the launcher and GADS stay in portrait.
private val gadsPackagePrefixes = listOf("com.gads.", "com.shamanec.")
private var cachedLauncherPackage: String? = null

// Last rotation requested by the user; the watcher keeps the force override in sync with it.
@Volatile
private var desiredRotation = "portrait"

private fun launcherPackage(): String {
    cachedLauncherPackage?.let { return it }
    val pkg = exec("cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN")
        .lineSequence().map { it.trim() }.lastOrNull { it.contains("/") }
        ?.substringBefore("/").orEmpty()
    cachedLauncherPackage = pkg
    return pkg
}

private fun foregroundPackage(): String {
    // mResumedActivity reflects the real foreground; mFocusedApp in `dumpsys window`
    // goes stale (keeps pointing at a force-stopped app even after returning home).
    for (line in exec("dumpsys activity activities").lineSequence()) {
        if (!line.contains("mResumedActivity")) continue
        val match = Regex("([a-zA-Z0-9._]+)/").find(line) ?: continue
        return match.groupValues[1]
    }
    return ""
}

// A "real app" is anything that is not the launcher or a GADS package - only those get the
// forced orientation override. An empty package (transient focus) counts as non-app.
private fun isRealApp(pkg: String): Boolean =
    pkg.isNotEmpty() && pkg != launcherPackage() && gadsPackagePrefixes.none { pkg.startsWith(it) }

// Enables/disables the display-wide "ignore per-app orientation" override (command renamed across releases).
private fun setForceRotation(enable: Boolean) {
    when {
        Build.VERSION.SDK_INT >= 31 -> exec("cmd window set-ignore-orientation-request ${if (enable) "true" else "false"}")
        Build.VERSION.SDK_INT >= 29 -> exec("cmd window set-fix-to-user-rotation ${if (enable) "enabled" else "disabled"}")
    }
}

@Serializable
data class TapRequest(val x: Float, val y: Float)

@Serializable
data class DoubleTapRequest(val x: Float, val y: Float)

@Serializable
data class TouchAndHoldRequest(val x: Float, val y: Float, val duration: Long)

@Serializable
data class SwipeRequest(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val duration: Int = 300)

@Serializable
data class PinchRequest(
    val centerX: Float,
    val centerY: Float,
    val scale: Float,
    val duration: Int = 300,
    val direction: String = "diagonal"
)

@Serializable
data class StatusResponse(val status: String, val message: String? = null)

@Serializable
data class ScreenSizeResponse(val x: Int, val y: Int)

@Serializable
data class ClipboardResponse(val value: String)

@Serializable
data class RotationRequest(val rotation: String)

@Serializable
data class RotationResponse(val rotation: String)

@Serializable
data class SetRotationResponse(val rotation: String, val applied: Boolean)

@Serializable
data class AppInfo(
    @kotlinx.serialization.SerialName("bundle_identifier") val packageName: String,
    @kotlinx.serialization.SerialName("app_name") val appName: String,
    @kotlinx.serialization.SerialName("can_uninstall") val canUninstall: Boolean
)

@Serializable
data class AndroidFileNode(
    val name: String,
    val isFile: Boolean = false,
    val fullPath: String,
    val fileDate: Long? = null,
    val children: Map<String, AndroidFileNode>? = null
)

@Serializable
data class ScreenshotResponse(val screenshot: String)

/**
 * The ktor server accepting the requests for the remote control and data acquisition
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class RemoteControlServer(private val port: Int) {
    private val inputInjector = InputInjector()
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var logTag = "${BuildConfig.LOG_PREFIX} Remote"
    private val watcherScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Cached reflection objects for SurfaceControl screenshot
    private val screenshotMethod: Method?

    // Context resolved via reflection — tries multiple approaches since we run as app_process
    private val appContext: android.content.Context? by lazy {
        // Try 1: AppGlobals.getInitialApplication()
        try {
            val app = Class.forName("android.app.AppGlobals")
                .getDeclaredMethod("getInitialApplication")
                .invoke(null) as? android.app.Application
            if (app != null) {
                Log.d(logTag, "Got context via AppGlobals")
                return@lazy app
            }
        } catch (e: Exception) {
            Log.w(logTag, "AppGlobals approach failed: ${e.message}")
        }

        // Try 2: ActivityThread.currentApplication()
        try {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? android.app.Application
            if (app != null) {
                Log.d(logTag, "Got context via ActivityThread.currentApplication()")
                return@lazy app
            }
        } catch (e: Exception) {
            Log.w(logTag, "ActivityThread.currentApplication() approach failed: ${e.message}")
        }

        // Try 3: ActivityThread.currentActivityThread().getSystemContext()
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            if (thread != null) {
                val ctx = atClass.getDeclaredMethod("getSystemContext").invoke(thread) as? android.content.Context
                if (ctx != null) {
                    Log.d(logTag, "Got context via ActivityThread.currentActivityThread().getSystemContext()")
                    return@lazy ctx
                }
                val app = atClass.getDeclaredMethod("getApplication").invoke(thread) as? android.app.Application
                if (app != null) {
                    Log.d(logTag, "Got context via ActivityThread.currentActivityThread().getApplication()")
                    return@lazy app
                }
            }
        } catch (e: Exception) {
            Log.w(logTag, "ActivityThread.currentActivityThread() approach failed: ${e.message}")
        }

        // Try 4: ActivityThread.systemMain().getSystemContext()
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = atClass.getDeclaredMethod("systemMain").invoke(null)
            val ctx = atClass.getDeclaredMethod("getSystemContext").invoke(thread) as? android.content.Context
            if (ctx != null) {
                Log.d(logTag, "Got context via ActivityThread.systemMain()")
                return@lazy ctx
            }
        } catch (e: Exception) {
            Log.w(logTag, "ActivityThread.systemMain() approach failed: ${e.message}")
        }

        Log.e(logTag, "All context resolution approaches failed")
        null
    }

    private val packageManager: android.content.pm.PackageManager? by lazy {
        appContext?.packageManager
    }

    init {
        // Initialize SurfaceControl reflection
        var method: Method? = null
        try {
            val surfaceClass = Class.forName("android.view.SurfaceControl")
            method = surfaceClass.getDeclaredMethod(
                "screenshot", 
                android.graphics.Rect::class.java, 
                Int::class.javaPrimitiveType, 
                Int::class.javaPrimitiveType, 
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            Log.i(logTag, "SurfaceControl screenshot method initialized")
        } catch (e: Exception) {
            Log.e(logTag, "Failed to initialize SurfaceControl screenshot: ${e.message}")
        }
        screenshotMethod = method
    }

    @SuppressLint("NewApi")
    fun start() {
        val screenDimensions = getScreenSize()
        screenWidth = screenDimensions.first
        screenHeight = screenDimensions.second

        startOrientationWatcher()

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

                post("/doubleTap") {
                    try {
                        val request = call.receive<DoubleTapRequest>()
                        try {
                            Log.d(logTag, "Attempting to perform double tap action at coordinates `${request.x}:${request.y}`")
                            inputInjector.doubleTap(request.x, request.y)
                            call.respond(HttpStatusCode.OK, StatusResponse("success", "Successfully performed double tap action"))
                        } catch (e: Exception) {
                            Log.e(logTag, "Internal error performing double tap action: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, StatusResponse("error", "Failed performing double tap action - ${e.message}"))
                        }
                    } catch (e: Exception) {
                        Log.e(logTag, "Bad request performing double tap action: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest, StatusResponse("error", "Invalid double tap request - ${e.message}"))
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

                post("/pinch") {
                    try {
                        val request = call.receive<PinchRequest>()
                        Log.d(logTag, "Performing pinch at (${request.centerX}, ${request.centerY}) scale=${request.scale}")

                        try {
                            inputInjector.pinch(request.centerX, request.centerY, request.scale, request.duration, request.direction)
                            call.respond(HttpStatusCode.OK, StatusResponse("success", "Pinch executed successfully"))
                        } catch (e: Exception) {
                            Log.e(logTag, "Failed to execute pinch: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, StatusResponse("error", "Pinch failed - ${e.message}"))
                        }
                    } catch (e: Exception) {
                        Log.e(logTag, "Invalid pinch request: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest, StatusResponse("error", "Invalid request - ${e.message}"))
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

                get("/rotation") {
                    Log.d(logTag, "Getting device rotation")
                    try {
                        call.respond(HttpStatusCode.OK, RotationResponse(getCurrentRotation()))
                    } catch (e: Exception) {
                        Log.e(logTag, "Internal error getting device rotation: ${e.message}")
                        call.respond(HttpStatusCode.InternalServerError, StatusResponse("error", "Failed getting device rotation - ${e.message}"))
                    }
                }

                post("/rotation") {
                    try {
                        val request = call.receive<RotationRequest>()
                        if (request.rotation != "portrait" && request.rotation != "landscape") {
                            call.respond(HttpStatusCode.BadRequest, StatusResponse("error", "Invalid rotation `${request.rotation}`, expected `portrait` or `landscape`"))
                            return@post
                        }
                        try {
                            Log.d(logTag, "Attempting to change device rotation to `${request.rotation}`")
                            val foreground = foregroundPackage()
                            if (!isRealApp(foreground)) {
                                // The launcher and GADS keep their natural orientation - drop the override
                                // so the home screen stays portrait instead of being forced to rotate.
                                Log.d(logTag, "Ignoring rotation request on non-app foreground `$foreground`")
                                setForceRotation(false)
                                call.respond(HttpStatusCode.OK, SetRotationResponse(getCurrentRotation(), false))
                                return@post
                            }
                            desiredRotation = request.rotation
                            // user_rotation only takes effect when auto-rotate is off
                            exec("settings put system accelerometer_rotation 0")
                            exec("settings put system user_rotation ${if (request.rotation == "landscape") "1" else "0"}")
                            // Force the display to honour it even if the focused app pins its orientation
                            setForceRotation(request.rotation == "landscape")
                            // Rotation is not instant; poll until the display reflects it (~2s max)
                            var currentRotation = getCurrentRotation()
                            var attempts = 0
                            while (currentRotation != request.rotation && attempts < 10) {
                                delay(200)
                                currentRotation = getCurrentRotation()
                                attempts++
                            }
                            val applied = currentRotation == request.rotation
                            if (!applied) {
                                // Pre-29 devices cannot force rotation; revert the pending user_rotation
                                // so it does not kick in once the foreground app changes
                                exec("settings put system user_rotation ${if (currentRotation == "landscape") "1" else "0"}")
                            }
                            call.respond(HttpStatusCode.OK, SetRotationResponse(currentRotation, applied))
                        } catch (e: Exception) {
                            Log.e(logTag, "Internal error changing device rotation: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, StatusResponse("error", "Failed changing device rotation - ${e.message}"))
                        }
                    } catch (e: Exception) {
                        Log.e(logTag, "Bad request changing device rotation: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest, StatusResponse("error", "Invalid rotation request - ${e.message}"))
                    }
                }

                post("/clipboard") {
                    try {
                        Log.d(logTag, "Getting current device IME")
                        val curIme = exec(("settings get secure default_input_method").trim())

                        val pkg = BuildConfig.APPLICATION_ID
                        Log.d(logTag, "Enabling GADS IME ($pkg)")
                        exec("ime enable $pkg/com.shamanec.settings.RemoteKeyboardIME")
                        Log.d(logTag, "Setting GADS IME as default")
                        exec("ime set $pkg/com.shamanec.settings.RemoteKeyboardIME")

                        // 3. synchronous ordered broadcast: resultData holds Base64 text
                        Log.d(logTag, "Sending broadcast to GADS IME to get clipboard data")
                        val res = exec(
                            "am broadcast -n $pkg/com.shamanec.settings.ClipboardReceiver -a ${BuildConfig.APPLICATION_ID}.CLIPBOARD_GET"
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

                get("/screenshot-lq") {
                    Log.d(logTag, "Taking screenshot")
                    val screenshot = takeScreenshot(useJpeg = true, scale = 3)
                    if (screenshot != null) {
                        call.respond(HttpStatusCode.OK, ScreenshotResponse(screenshot))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, StatusResponse("error", "Failed to take screenshot - functionality not available or capture failed"))
                    }
                }

                get("/screenshot") {
                    Log.d(logTag, "Taking screenshot")
                    val screenshot = takeScreenshot(useJpeg = true)
                    if (screenshot != null) {
                        call.respond(HttpStatusCode.OK, ScreenshotResponse(screenshot))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, StatusResponse("error", "Failed to take screenshot - functionality not available or capture failed"))
                    }
                }

                get("/screenshot-hq") {
                    Log.d(logTag, "Taking high-quality screenshot")
                    val screenshot = takeScreenshot(useJpeg = false)
                    if (screenshot != null) {
                        call.respond(HttpStatusCode.OK, ScreenshotResponse(screenshot))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, StatusResponse("error", "Failed to take screenshot - functionality not available or capture failed"))
                    }
                }

                get("/ping") {
                    call.respond(HttpStatusCode.OK)
                }

                get("/installed-apps") {
                    try {
                        val installedApps = getInstalledApps()
                        val ownPackages = setOf("com.gads.settings", "com.heliofleet.settings")
                        val filtered = installedApps.filterNot { it.packageName in ownPackages }
                        call.respond(HttpStatusCode.OK, filtered)
                    } catch (e: Exception) {
                        Log.e(logTag, "Internal error getting installed apps: ${e.message}")
                        call.respond(HttpStatusCode.InternalServerError, StatusResponse("error", "Failed getting installed apps - ${e.message}"))
                    }
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

    // Keeps the display-wide force override in sync with the foreground app: enabled only while a
    // real app is focused and landscape is desired, disabled otherwise so the launcher/GADS revert
    // to their natural portrait orientation when they come to the foreground.
    private fun startOrientationWatcher() {
        watcherScope.launch {
            var lastForce: Boolean? = null
            while (true) {
                try {
                    val force = desiredRotation == "landscape" && isRealApp(foregroundPackage())
                    if (force != lastForce) {
                        if (force) {
                            // A real app regained focus while landscape is desired - re-assert it,
                            // since the launcher resets user_rotation back to portrait on the way out.
                            exec("settings put system accelerometer_rotation 0")
                            exec("settings put system user_rotation 1")
                        }
                        setForceRotation(force)
                        lastForce = force
                    }
                } catch (e: Exception) {
                    Log.w(logTag, "Orientation watcher error: ${e.message}")
                }
                delay(700)
            }
        }
    }

    private fun takeScreenshot(useJpeg: Boolean = true, scale: Int = 2): String? {
        if (screenshotMethod == null) {
            Log.e(logTag, "Screenshot functionality not available - SurfaceControl initialization failed")
            return null
        }
        
        return try {
            // Capture at half resolution directly to avoid scaling step
            val scaledWidth = screenWidth / scale
            val scaledHeight = screenHeight / scale
            val rect = android.graphics.Rect(0, 0, screenWidth, screenHeight)
            
            val bitmap = screenshotMethod.invoke(null, rect, scaledWidth, scaledHeight, 0) as Bitmap
            
            val imageBytes = if (useJpeg) {
                bitmapToJpegBytes(bitmap)
            } else {
                bitmapToPngBytes(bitmap)
            }
            
            val base64String = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            
            bitmap.recycle()
            base64String
        } catch (e: Exception) {
            Log.e(logTag, "Failed to take screenshot: ${e.message}")
            null
        }
    }

    private fun bitmapToJpegBytes(bitmap: Bitmap, quality: Int = 70): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return outputStream.toByteArray()
    }

    private fun bitmapToPngBytes(bitmap: Bitmap): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return outputStream.toByteArray()
    }

    private fun getInstalledApps(): List<AppInfo> {
        val pm = packageManager
        if (pm != null) {
            return pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
                .map { AppInfo(packageName = it.packageName, appName = pm.getApplicationLabel(it).toString(), canUninstall = true) }
                .sortedBy { it.appName }
        }

        // Fallback: use IPackageManager binder directly via ServiceManager
        Log.w(logTag, "PackageManager unavailable, trying IPackageManager binder")
        val ipm = getIPackageManager()
        if (ipm != null) {
            return exec("pm list packages -3")
                .lines()
                .mapNotNull { line ->
                    val pkg = line.removePrefix("package:").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val appName = resolveAppLabel(ipm, pkg) ?: pkg
                    AppInfo(packageName = pkg, appName = appName, canUninstall = true)
                }
                .sortedBy { it.appName }
        }

        // Last resort: package names only
        Log.w(logTag, "IPackageManager unavailable, returning package names only")
        return exec("pm list packages -3")
            .lines()
            .mapNotNull { line ->
                val pkg = line.removePrefix("package:").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                AppInfo(packageName = pkg, appName = pkg, canUninstall = true)
            }
            .sortedBy { it.packageName }
    }

    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    private fun getIPackageManager(): Any? {
        return try {
            val binder = Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
                .invoke(null, "package") as? android.os.IBinder ?: return null
            Class.forName("android.content.pm.IPackageManager\$Stub")
                .getDeclaredMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder)
        } catch (e: Exception) {
            Log.w(logTag, "IPackageManager binder unavailable: ${e.message}")
            null
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun resolveAppLabel(ipm: Any, packageName: String): String? {
        return try {
            val appInfo = ipm.javaClass
                .getMethod("getApplicationInfo", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(ipm, packageName, 0, 0) as? android.content.pm.ApplicationInfo
                ?: return null
            // nonLocalizedLabel is set when the manifest uses a hardcoded string (no resource needed)
            appInfo.nonLocalizedLabel?.toString()
                ?: run {
                    // Load label from APK resources without a Context
                    val am = android.content.res.AssetManager::class.java.newInstance()
                    android.content.res.AssetManager::class.java
                        .getDeclaredMethod("addAssetPath", String::class.java)
                        .apply { isAccessible = true }
                        .invoke(am, appInfo.publicSourceDir)
                    @Suppress("DEPRECATION")
                    android.content.res.Resources(am, null, null).getString(appInfo.labelRes)
                }
        } catch (e: Exception) {
            null
        }
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
    private var logTag = "${BuildConfig.LOG_PREFIX} Remote"
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
     * Inject double tap event at coordinates with Android framework-compliant timing
     */
    fun doubleTap(x: Float, y: Float) {
        // First tap
        val time1 = SystemClock.uptimeMillis()
        val down1 = MotionEvent.obtain(time1, time1, MotionEvent.ACTION_DOWN, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val up1 = MotionEvent.obtain(time1, time1 + 10, MotionEvent.ACTION_UP, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        injectEvent(down1)
        injectEvent(up1)
        down1.recycle()
        up1.recycle()

        // Wait within the double tap window (40ms < 50ms < 300ms)
        Thread.sleep(50)

        // Second tap
        val time2 = SystemClock.uptimeMillis()
        val down2 = MotionEvent.obtain(time2, time2, MotionEvent.ACTION_DOWN, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val up2 = MotionEvent.obtain(time2, time2 + 10, MotionEvent.ACTION_UP, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        injectEvent(down2)
        injectEvent(up2)
        down2.recycle()
        up2.recycle()
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

    fun pinch(centerX: Float, centerY: Float, scale: Float, duration: Int, direction: String = "diagonal") {
        val startTime = SystemClock.uptimeMillis()

        val baseDistance = 200f
        val startDistance = if (scale < 1.0f) baseDistance else baseDistance / scale
        val endDistance = if (scale < 1.0f) baseDistance * scale else baseDistance

        val pointerProp1 = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val pointerProp2 = MotionEvent.PointerProperties().apply {
            id = 1
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val props = arrayOf(pointerProp1, pointerProp2)

        val (startX1, startY1, startX2, startY2) = when (direction) {
            "horizontal" -> arrayOf(
                centerX - startDistance, centerY,
                centerX + startDistance, centerY
            )
            "vertical" -> arrayOf(
                centerX, centerY - startDistance,
                centerX, centerY + startDistance
            )
            else -> arrayOf(
                centerX - startDistance, centerY - startDistance,
                centerX + startDistance, centerY + startDistance
            )
        }

        val startCoords1 = MotionEvent.PointerCoords().apply {
            x = startX1
            y = startY1
            pressure = 1f
            size = 1f
        }
        val startCoords2 = MotionEvent.PointerCoords().apply {
            x = startX2
            y = startY2
            pressure = 1f
            size = 1f
        }

        val down1 = MotionEvent.obtain(
            startTime, startTime,
            MotionEvent.ACTION_DOWN,
            1, arrayOf(pointerProp1), arrayOf(startCoords1),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        injectEvent(down1)
        down1.recycle()

        val down2 = MotionEvent.obtain(
            startTime, startTime,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, props, arrayOf(startCoords1, startCoords2),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        injectEvent(down2)
        down2.recycle()

        val (endX1, endY1, endX2, endY2) = when (direction) {
            "horizontal" -> arrayOf(
                centerX - endDistance, centerY,
                centerX + endDistance, centerY
            )
            "vertical" -> arrayOf(
                centerX, centerY - endDistance,
                centerX, centerY + endDistance
            )
            else -> arrayOf(
                centerX - endDistance, centerY - endDistance,
                centerX + endDistance, centerY + endDistance
            )
        }

        val steps = maxOf(duration / 10, 20)

        for (i in 1..steps) {
            val eventTime = startTime + (i * duration / steps)
            val progress = i.toFloat() / steps

            val coords1 = MotionEvent.PointerCoords().apply {
                x = startX1 + (endX1 - startX1) * progress
                y = startY1 + (endY1 - startY1) * progress
                pressure = 1f
                size = 1f
            }
            val coords2 = MotionEvent.PointerCoords().apply {
                x = startX2 + (endX2 - startX2) * progress
                y = startY2 + (endY2 - startY2) * progress
                pressure = 1f
                size = 1f
            }

            val move = MotionEvent.obtain(
                startTime, eventTime,
                MotionEvent.ACTION_MOVE,
                2, props, arrayOf(coords1, coords2),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            injectEvent(move)
            move.recycle()

            Thread.sleep((duration / steps).toLong())
        }

        val endCoords1 = MotionEvent.PointerCoords().apply {
            x = endX1
            y = endY1
            pressure = 1f
            size = 1f
        }
        val endCoords2 = MotionEvent.PointerCoords().apply {
            x = endX2
            y = endY2
            pressure = 1f
            size = 1f
        }

        val endTime = startTime + duration
        val up2 = MotionEvent.obtain(
            startTime, endTime,
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, props, arrayOf(endCoords1, endCoords2),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        injectEvent(up2)
        up2.recycle()

        val up1 = MotionEvent.obtain(
            startTime, endTime,
            MotionEvent.ACTION_UP,
            1, arrayOf(pointerProp1), arrayOf(endCoords1),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        injectEvent(up1)
        up1.recycle()
    }
}

fun main(args: Array<String>) {
    val logTag = "${BuildConfig.LOG_PREFIX} Remote"
    val port = args.getOrNull(0)?.toIntOrNull() ?: 1994
    val jsonFormatter = Json { prettyPrint = false }

    try {
        val server = RemoteControlServer(port)
        Log.i(logTag, "Starting GADS remote control server on port `$port`...")
        Log.i(logTag, "Endpoints:")
        Log.i(logTag, "POST /tap ${jsonFormatter.encodeToString(TapRequest.serializer(), TapRequest(x = 100f, y = 200f))}")
        Log.i(logTag, "POST /doubleTap ${jsonFormatter.encodeToString(DoubleTapRequest.serializer(), DoubleTapRequest(x = 100f, y = 200f))}")
        Log.i(logTag, "POST /swipe ${jsonFormatter.encodeToString(SwipeRequest.serializer(), SwipeRequest(x1 = 100f, y1 = 200f, x2 = 100f, y2 = 200f, duration = 1000))}")
        Log.i(logTag, "POST /touchAndHold ${jsonFormatter.encodeToString(TouchAndHoldRequest.serializer(), TouchAndHoldRequest(x = 100f, y = 200f, duration = 1000))}")
        Log.i(logTag, "POST /pinch ${jsonFormatter.encodeToString(PinchRequest.serializer(), PinchRequest(centerX = 500f, centerY = 800f, scale = 2.0f, duration = 300, direction = "diagonal"))}")
        Log.i(logTag, "GET /screen-size")
        Log.i(logTag, "POST /clipboard")
        Log.i(logTag, "POST /home")
        Log.i(logTag, "POST /back")
        Log.i(logTag, "POST /lock")
        Log.i(logTag, "POST /unlock")
        Log.i(logTag, "POST /appSwitch")
        Log.i(logTag, "GET /screenshot")
        Log.i(logTag, "GET /screenshot-hq")
        Log.i(logTag, "GET /files")
        Log.i(logTag, "GET /ping")

        server.start()
    } catch (e: Exception) {
        Log.e(logTag, "Failed to start GADS remote control server - ${e.message}")
        e.printStackTrace()
    }
}