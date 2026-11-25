package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.compat.realSdkInt
import android.util.Log
import android.app.Application
import androidx.multidex.MultiDex
import com.shilapi.xcertplay.orchestration.GuangqiVolumeHook
import com.shilapi.xcertplay.orchestration.GuangqiVolumeTakeover
import com.shilapi.xcertplay.util.safeStackTrace
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiPlayApplication : Application() {

    /**
     * Guangqi: [com.shilapi.xcertplay.orchestration.GuangqiVolumeHookReceiver] runs in system_server
     * (`android:process="system"`), and Android creates this class there too. In that process it
     * does nothing: no secondary dex, no crash handler (it would replace system_server's), no
     * overlays. Kept free of Kotlin runtime calls beyond what the main dex already holds.
     */
    private var inSystemServer = false

    override fun attachBaseContext(base: Context) {
        inSystemServer = GuangqiVolumeHook.inSystemServer()
        super.attachBaseContext(base)
        if (!inSystemServer) MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        if (inSystemServer) return
        setupCrashHandler()
        // Session log output switch (issue #186): loaded once per process; toggling the
        // setting in Settings takes effect after restarting the app.
        try {
            LoggingGate.init(this)
        } catch (ignored: Throwable) {
        }
        try {
            FloatingDotOverlay.restoreIfEnabled(this)
        } catch (ignored: Throwable) {
        }
        if (GuangqiVolumeHook.processName() == packageName) {
            try {
                GuangqiVolumeTakeover.start(this)
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrash(this, thread, throwable)
            } catch (ignored: Throwable) {
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    companion object {
        private const val TAG = "DiPlayApplication"

        fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            // safeStackTrace never recurses into the cause chain: a cyclic cause
            // used to StackOverflow the crash reporter itself (#163) and hide
            // the original error.
            val stackTrace = throwable.safeStackTrace()

            val logEntry = buildString {
                append("\n================ FATAL EXCEPTION ================\n")
                append("Time: ").append(timestamp).append("\n")
                append("Thread: ").append(thread.name).append(" (id=").append(thread.id).append(")\n")
                append("Android SDK: ").append(Build.VERSION.SDK_INT).append("\n")
                append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
                append("Exception: ").append(stackTrace).append("\n")
                append("=================================================\n")
            }

            Log.e(TAG, logEntry)

            // 1. Always write to private app directory (preserved)
            runCatching {
                val logDir = File(context.filesDir, "logs")
                if (!logDir.exists()) logDir.mkdirs()
                val logFile = File(logDir, "diplay_crash.log")
                logFile.appendText(logEntry)
            }

            // 2. Also write to app-specific external files dir if available (accessible via MTP/file manager)
            runCatching {
                val externalDir = context.getExternalFilesDir("logs")
                if (externalDir != null) {
                    if (!externalDir.exists()) externalDir.mkdirs()
                    File(externalDir, "diplay_crash.log").appendText(logEntry)
                }
            }

            // 3. On legacy Android (API <= 28), also attempt to write to public SD card root /mnt/sdcard/
            // for head units without root access (e.g. Chery eQ1, Allwinner T3)
            if (realSdkInt() <= 28) {
                runCatching {
                    @Suppress("DEPRECATION")
                    val sdcardDir = android.os.Environment.getExternalStorageDirectory()
                    if (sdcardDir != null && sdcardDir.canWrite()) {
                        File(sdcardDir, "diplay_crash.log").appendText(logEntry)
                    }
                }
            }
        }
    }
}
