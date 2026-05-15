package com.shamanec.settings

import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@Suppress("DEPRECATION")
class RemoteKeyboardIME : InputMethodService(), KeyboardView.OnKeyboardActionListener {
    private var server: ApplicationEngine? = null
    private var keyboardView: HighlightKeyboardView? = null
    private var keyboard: Keyboard? = null
    private var symbolsKeyboard: Keyboard? = null
    private var isSymbolsKeyboard = false
    private val TAG = "${BuildConfig.LOG_PREFIX} KeyboardIME"

    override fun onCreate() {
        super.onCreate()
        startRemoteServer()
    }

    private fun startRemoteServer() {
        server = embeddedServer(CIO, host = "0.0.0.0", port = 1993) {
            routing {
                post("/type") {
                    val raw = call.receiveText()
                    try {
                        val json = JSONObject(raw)

                        if (!json.has("text")) {
                            call.respondText(
                                "Missing 'text' field",
                                status = HttpStatusCode.BadRequest
                            )
                            return@post
                        }

                        val text = json.getString("text")

                        launch(Dispatchers.Main) {
                            simulateTyping(text)
                        }
                        call.respondText("OK")
                    } catch (e: Exception) {
                        call.respondText(
                            "Invalid JSON: ${e.message}",
                            status = HttpStatusCode.BadRequest
                        )
                    }
                }
            }
        }.start(wait = false)
        Log.i(TAG, "Server started on 0.0.0.0:1993")

        // Warm-up POST on IO thread
        warmupRemoteServer()
    }

    private fun warmupRemoteServer() {
        Thread {
            try {
                val url = URL("http://127.0.0.1:1993/type")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    outputStream.use { it.write("""{"text":""}""".toByteArray()) }
                }
                // read+close to complete the handshake
                conn.inputStream.close()
                conn.disconnect()
                Log.i(TAG, "Warm-up done")
            } catch (e: Exception) {
                Log.w(TAG, "Warm-up failed: ${e.message}")
            }
        }.start()
    }

    override fun onDestroy() {
        server?.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        // Load the keyboard layouts
        keyboard = Keyboard(this, R.xml.keyboard_layout)
        symbolsKeyboard = Keyboard(this, R.xml.symbols_layout)

        // Create and configure the keyboard view
        val inflatedView = layoutInflater.inflate(R.layout.keyboard_view, null)
        keyboardView = inflatedView.findViewById(R.id.keyboard_view)

        keyboardView?.apply {
            keyboard = if (isSymbolsKeyboard) this@RemoteKeyboardIME.symbolsKeyboard else this@RemoteKeyboardIME.keyboard
            setOnKeyboardActionListener(this@RemoteKeyboardIME)
            isPreviewEnabled = false // Disable key previews
        }

        ViewCompat.setOnApplyWindowInsetsListener(inflatedView) { view, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply bottom padding to avoid overlap with system bars (like the gesture bar)
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                systemBarsInsets.bottom
            )

            insets
        }

        return inflatedView
    }

    private fun simulateTyping(text: String) {
        GlobalScope.launch(Dispatchers.Main) {
            for (ch in text) {
                if (text.length == 1) {
                    highlightIfPresent(ch)
                }
                when (ch) {
                    '\n' -> {
                        sendKeyCode(KeyEvent.KEYCODE_ENTER)
                    }
                    '\b' -> {
                        sendKeyCode(KeyEvent.KEYCODE_DEL)
                    }
                    else -> {
                        sendCharacter(ch)
                    }
                }
                delay(5)
            }
        }
    }

    private fun highlightIfPresent(ch: Char) {
        val hv = keyboardView ?: return
        val code = when (ch) {
            '\n' -> KeyEvent.KEYCODE_ENTER          // your layout probably has it
            '\b' -> KeyEvent.KEYCODE_DEL
            else   -> ch.lowercaseChar().code
        }

        // If the current keyboard *contains* that code, flash it
        val current = if (isSymbolsKeyboard) symbolsKeyboard else keyboard
        if (current?.keys?.any { code in it.codes } == true) {
            hv.highlight(code)
        }
    }

    // Send individual character
    private fun sendCharacter(ch: Char) {
        currentInputConnection?.commitText(ch.toString(), 1)
    }

    // Send key code
    private fun sendKeyCode(keyCode: Int) {
        val ic = currentInputConnection
        ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    // KeyboardView.OnKeyboardActionListener implementations

    // Called when the user presses a key
    override fun onPress(primaryCode: Int) {
        // Intentionally empty - no specific action needed on press
    }

    // Called when the user releases a key
    override fun onRelease(primaryCode: Int) {
        // Intentionally empty - no specific action needed on release
    }

    // Called when the user taps a key
    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        val inputConnection = currentInputConnection ?: return

        when (primaryCode) {
            Keyboard.KEYCODE_DELETE -> {
                // Handle backspace
                inputConnection.deleteSurroundingText(1, 0)
            }
            Keyboard.KEYCODE_SHIFT -> {
                // Handle shift key
                keyboard?.isShifted = !(keyboard?.isShifted ?: false)
                keyboardView?.invalidateAllKeys()
            }
            Keyboard.KEYCODE_MODE_CHANGE -> {
                // Handle symbols/alphabet toggle
                isSymbolsKeyboard = !isSymbolsKeyboard
                keyboardView?.keyboard = if (isSymbolsKeyboard) symbolsKeyboard else keyboard
            }
            Keyboard.KEYCODE_DONE -> {
                // Handle enter key
                inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            else -> {
                // Handle regular character
                var code = primaryCode
                if ((keyboard?.isShifted == true) && code >= 'a'.code && code <= 'z'.code) {
                    code = code - 32 // Convert to uppercase
                }
                val char = code.toChar().toString()
                inputConnection.commitText(char, 1)
            }
        }
    }

    // Called when the user does a gesture on the space bar
    override fun swipeLeft() {
        // Optional, intentionally empty
    }

    // Called when the user does a gesture on the space bar
    override fun swipeRight() {
        // Optional, intentionally empty
    }

    // Called when the user does a gesture on the space bar
    override fun swipeDown() {
        // Optional, intentionally empty
    }

    // Called when the user does a gesture on the space bar
    override fun swipeUp() {
        // Optional, intentionally empty
    }

    // Called when the user completed a multi-tap sequence
    override fun onText(text: CharSequence?) {
        text?.let { currentInputConnection?.commitText(it, 1) }
    }
}