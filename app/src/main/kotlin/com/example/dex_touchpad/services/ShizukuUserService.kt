package com.example.dex_touchpad.services

import android.os.Process
import android.util.Log
import com.example.dex_touchpad.IMouseControl
import kotlin.math.roundToInt

private const val TAG = "ShizukuUserService"

// Linux input event codes the UI sends (include/uapi/linux/input-event-codes.h)
private const val BTN_LEFT = 272
private const val BTN_RIGHT = 273
private const val BTN_MIDDLE = 274

/**
 * Shizuku UserService — instantiated by Shizuku in a separate process
 * running as shell UID (2000).
 *
 * This service IS the mouse: it loads libdextouchpad.so in-process and
 * implements IMouseControl directly on top of the native UHid functions.
 * The binder Shizuku hands to MainActivity in onServiceConnected is this
 * service's own binder — no native subprocess and no broadcast handshake.
 *
 * (The original code instead copied the .so to /data/local/tmp and tried
 * to execute it as a standalone program, then waited for it to broadcast
 * a binder back. A JNI library has no entry point and no binder code, so
 * that could never work — the app hung at "Starting native service..."
 * forever.)
 *
 * This class must NOT extend android.app.Service because Shizuku
 * manages its lifecycle via IPC — it just implements IMouseControl.Stub.
 */
class ShizukuUserService : IMouseControl.Stub() {

    private val nativeLock = Any()

    @Volatile
    private var deviceReady = false

    init {
        Log.d(TAG, "ShizukuUserService created, UID=${Process.myUid()}")
        try {
            exec("pm grant com.example.dex_touchpad android.permission.WRITE_SECURE_SETTINGS")
        } catch (e: Exception) {
            Log.w(TAG, "pm grant failed", e)
        }
        ensureDevice()
    }

    private fun ensureDevice(): Boolean {
        if (deviceReady) return true
        synchronized(nativeLock) {
            if (deviceReady) return true
            MouseNative.load()
            if (!MouseNative.isLoaded) return false
            deviceReady = runCatching { MouseNative.nativeCreateUHid() }
                .onFailure { Log.e(TAG, "nativeCreateUHid threw", it) }
                .getOrDefault(false)
            Log.d(TAG, "UHid device ready: $deviceReady")
            return deviceReady
        }
    }

    override fun moveCursor(deltaX: Float, deltaY: Float) {
        if (!ensureDevice()) return
        synchronized(nativeLock) {
            // The native side clamps each axis to ±127 per event; chunk
            // larger deltas so fast flicks aren't truncated.
            var x = deltaX.roundToInt()
            var y = deltaY.roundToInt()
            while (x != 0 || y != 0) {
                val sx = x.coerceIn(-127, 127)
                val sy = y.coerceIn(-127, 127)
                MouseNative.nativeUHidEvent(sx, sy)
                x -= sx
                y -= sy
            }
        }
    }

    override fun sendClick(buttonCode: Int) {
        if (!ensureDevice()) return
        synchronized(nativeLock) {
            when (buttonCode) {
                BTN_LEFT -> click { MouseNative.nativeUHidPressL(it) }
                BTN_RIGHT -> click { MouseNative.nativeUHidPressR(it) }
                BTN_MIDDLE -> click { MouseNative.nativeUHidPressM(it) }
                else -> Log.w(TAG, "Unknown button code $buttonCode")
            }
        }
    }

    private fun click(press: (Boolean) -> Unit) {
        press(true)
        // Give the input stack a beat to register the press before release;
        // a same-instant down/up can be coalesced and lost.
        Thread.sleep(30)
        press(false)
    }

    override fun sendScroll(verticalDelta: Float, horizontalDelta: Float) {
        if (!ensureDevice()) return
        // The virtual mouse has a single wheel axis; horizontal is ignored.
        val v = verticalDelta.roundToInt()
        if (v == 0) return
        synchronized(nativeLock) {
            MouseNative.nativeUHidScroll(v)
        }
    }

    override fun destroy() {
        Log.d(TAG, "destroy() called")
        synchronized(nativeLock) {
            if (deviceReady) {
                runCatching { MouseNative.nativeCloseUHid() }
                deviceReady = false
            }
        }
    }

    private fun exec(command: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            process.inputStream.bufferedReader().readText()
            process.errorStream.bufferedReader().readText()
            process.waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "exec failed: $command", e)
        }
    }
}
