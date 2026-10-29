package com.example.dex_touchpad

import android.app.Application
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic build addition: captures uncaught exceptions from any of the
 * app's processes (main and :user_service) into last_crash.txt so a crash
 * can be read back on the next launch instead of vanishing silently.
 */
class DexApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val entry = "\n==== $stamp pid=${Process.myPid()} thread=${thread.name} ====\n$sw"
                synchronized(CRASH_LOCK) {
                    File(filesDir, CRASH_FILE).appendText(entry)
                    try {
                        getExternalFilesDir(null)?.let { File(it, CRASH_FILE).appendText(entry) }
                    } catch (ignored: Exception) {
                    }
                }
                Log.e("DexCrash", "Uncaught exception captured", throwable)
            } catch (ignored: Exception) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
        private val CRASH_LOCK = Any()

        fun readCrashLog(app: Application): String? {
            val f = File(app.filesDir, CRASH_FILE)
            if (!f.exists()) return null
            val text = runCatching { f.readText() }.getOrNull()
            return text?.takeIf { it.isNotBlank() }
        }

        fun clearCrashLog(app: Application) {
            runCatching { File(app.filesDir, CRASH_FILE).delete() }
            runCatching { app.getExternalFilesDir(null)?.let { File(it, CRASH_FILE).delete() } }
        }
    }
}
