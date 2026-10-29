package com.example.dex_touchpad.services

import android.os.Process
import android.util.Log
import com.example.dex_touchpad.IMouseControl
import java.io.File

private const val TAG = "ShizukuUserService"

/**
 * Shizuku UserService — this class is instantiated by Shizuku in a
 * separate process running as shell UID (2000).
 *
 * Its job is to:
 *   1. Grant WRITE_SECURE_SETTINGS to the main app
 *   2. Find and copy libdextouchpad.so to /data/local/tmp
 *   3. Execute libdextouchpad.so as a native process
 *
 * The native process (libdextouchpad.so) then sends its IMouseControl
 * binder back to the main app via a system broadcast.
 *
 * This class must NOT extend android.app.Service because Shizuku
 * manages its lifecycle via IPC — it just needs to implement IMouseControl.Stub.
 */
class ShizukuUserService : IMouseControl.Stub() {

    init {
        Log.d(TAG, "ShizukuUserService created, UID=${Process.myUid()}")
        startNativeService()
    }

    override fun moveCursor(deltaX: Float, deltaY: Float) {}
    override fun sendClick(buttonCode: Int) {}
    override fun sendScroll(verticalDelta: Float, horizontalDelta: Float) {}
    override fun destroy() {
        Log.d(TAG, "destroy() called")
        stopNativeService()
    }

    private fun startNativeService() {
        try {
            Log.d(TAG, "UID=${Process.myUid()} — granting permissions and starting native service")

            exec("pm grant com.example.dex_touchpad android.permission.WRITE_SECURE_SETTINGS")

            val libPath = findNativeLib()
            if (libPath == null) {
                Log.e(TAG, "libdextouchpad.so not found")
                return
            }

            exec("pkill -f libdextouchpad 2>/dev/null; true")
            Thread.sleep(300)

            val targetPath = "/data/local/tmp/libdextouchpad.so"
            exec("cp -f '$libPath' '$targetPath' && chmod +x '$targetPath'")

            val pb = ProcessBuilder("sh", "-c", "nohup '$targetPath' >/dev/null 2>&1 &")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.inputStream.close()
            Log.d(TAG, "Native UHid service started from $libPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start native service", e)
        }
    }

    private fun stopNativeService() {
        try {
            exec("pkill -f libdextouchpad 2>/dev/null; true")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping native service", e)
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

    private fun findNativeLib(): String? {
        try {
            val process = Runtime.getRuntime().exec(arrayOf(
                "sh", "-c",
                "find /data/app -name 'libdextouchpad.so' 2>/dev/null | head -1"
            ))
            val result = process.inputStream.bufferedReader().readLine()?.trim()
            process.waitFor()
            if (!result.isNullOrEmpty() && File(result).exists()) {
                Log.d(TAG, "Found native lib at: $result")
                return result
            }
        } catch (e: Exception) {
            Log.w(TAG, "find command failed", e)
        }
        val tmpPath = "/data/local/tmp/libdextouchpad.so"
        if (File(tmpPath).exists()) return tmpPath
        return null
    }
}
