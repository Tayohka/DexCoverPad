package com.example.dex_touchpad.services

import android.util.Log
import java.io.File

private const val TAG = "MouseNative"

/**
 * JNI bridge to libdextouchpad.so, a UHid virtual mouse.
 *
 * The project shipped the native library as a prebuilt binary but never
 * included this class, so the library could never be called. These
 * declarations match the library's exported JNI symbols exactly
 * (signatures recovered from the compiled code):
 *
 *   nativeCreateUHid()Z       open /dev/uhid and create the virtual mouse
 *   nativeCloseUHid()Z        destroy the virtual mouse and close the fd
 *   nativeUHidEvent(II)V      relative move; each axis clamped to ±127 natively
 *   nativeUHidPressL/R/M(Z)V  write the buttons byte with that button's bit (or 0)
 *   nativeUHidScroll(I)V      wheel delta in the report's wheel byte
 *
 * The HID report is 4 bytes: [buttons, dx, dy, wheel].
 */
object MouseNative {

    @Volatile
    var isLoaded: Boolean = false
        private set

    fun load() {
        if (isLoaded) return
        try {
            System.loadLibrary("dextouchpad")
            isLoaded = true
            Log.d(TAG, "libdextouchpad.so loaded via loadLibrary")
            return
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "loadLibrary failed; trying extracted path", e)
        }
        // In the Shizuku user-service process the classloader may not have the
        // app's native lib dir on its search path — find the extracted .so
        // under /data/app and load it by absolute path.
        val path = findNativeLibPath()
        if (path != null) {
            try {
                System.load(path)
                isLoaded = true
                Log.d(TAG, "libdextouchpad.so loaded from $path")
                return
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "System.load($path) failed", e)
            }
        }
        Log.e(TAG, "Could not load libdextouchpad.so by any route")
    }

    private fun findNativeLibPath(): String? {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "find /data/app -name 'libdextouchpad.so' 2>/dev/null | head -1")
            )
            val result = process.inputStream.bufferedReader().readLine()?.trim()
            process.waitFor()
            if (!result.isNullOrEmpty() && File(result).exists()) result else null
        } catch (e: Exception) {
            Log.w(TAG, "find for libdextouchpad.so failed", e)
            null
        }
    }

    external fun nativeCreateUHid(): Boolean
    external fun nativeCloseUHid(): Boolean
    external fun nativeUHidEvent(dx: Int, dy: Int)
    external fun nativeUHidPressL(down: Boolean)
    external fun nativeUHidPressR(down: Boolean)
    external fun nativeUHidPressM(down: Boolean)
    external fun nativeUHidScroll(delta: Int)
}
