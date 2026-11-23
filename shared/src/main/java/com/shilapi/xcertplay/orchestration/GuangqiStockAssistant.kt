package com.shilapi.xcertplay.orchestration

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Pauses the Guangqi head unit's own voice assistant (WeCarSpeech behind
 * `com.gaei.speechadapter`) while DiPlay needs what it holds.
 *
 * Its wake-word engine keeps the microphone open, and this ROM's audio policy lets only one
 * recorder run (`startInput() ... failed: other input already started`). A CarPlay microphone
 * uplink then starts without error but never records, and the iPhone hears silence on calls,
 * Siri and apps. The stock CarLife solves the same thing by turning the assistant's ASR and wake
 * word off while the phone uses the microphone (`notifyVREnable(false)`); `vr_control` 102 sent to
 * `SpeechAdapterService` does the same, and 101 turns them back on. PhoneWindowManager sends these
 * too, around reversing and phone calls.
 *
 * Each user names a reason; the assistant is paused while any reason holds and resumed when the
 * last one lets go. The pause is remembered, so a process that dies holding it resumes the
 * assistant at the next [recover]. A head unit without the adapter is left alone.
 */
object GuangqiStockAssistant {
    private val holders = mutableSetOf<String>()

    /** Pauses the assistant for [reason]; true when it was paused (now or already). */
    fun acquire(context: Context, reason: String): Boolean {
        val app = context.applicationContext
        if (!present(app)) return false
        val first = synchronized(holders) { holders.isEmpty().also { holders.add(reason) } }
        if (first) {
            prefs(app).edit().putBoolean(KEY_PAUSED, true).commit()
            send(app, VR_DISABLE)
            Log.i(TAG, "stock voice assistant paused ($reason)")
        }
        return true
    }

    /** Lets go of [reason]; resumes the assistant when no reason is left. */
    fun release(context: Context, reason: String) {
        val app = context.applicationContext
        val last = synchronized(holders) { holders.remove(reason) && holders.isEmpty() }
        if (!last) return
        send(app, VR_ENABLE)
        prefs(app).edit().putBoolean(KEY_PAUSED, false).commit()
        Log.i(TAG, "stock voice assistant resumed ($reason)")
    }

    /** Pauses it again while held: the system turns it back on after reversing or a phone call. */
    fun reassert(context: Context) {
        val app = context.applicationContext
        if (synchronized(holders) { holders.isNotEmpty() }) send(app, VR_DISABLE)
    }

    /** Resumes an assistant that a previous process paused and never resumed. */
    fun recover(context: Context) {
        val app = context.applicationContext
        if (synchronized(holders) { holders.isNotEmpty() }) return
        if (!prefs(app).getBoolean(KEY_PAUSED, false)) return
        send(app, VR_ENABLE)
        prefs(app).edit().putBoolean(KEY_PAUSED, false).commit()
        Log.i(TAG, "stock voice assistant resumed after an abandoned pause")
    }

    /** Looked up each time: it runs once per microphone stream or key hold, and is cheap. */
    private fun present(app: Context): Boolean = runCatching {
        app.packageManager.getPackageInfo(ADAPTER_PACKAGE, 0)
        true
    }.getOrDefault(false)

    private fun send(app: Context, command: Int) {
        runCatching {
            app.startService(Intent(ACTION).setClassName(ADAPTER_PACKAGE, ADAPTER_SERVICE)
                .putExtra(CONTROL, command))
        }.onFailure { Log.w(TAG, "stock voice assistant command $command failed", it) }
    }

    private fun prefs(app: Context) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val TAG = "DiPlay-GQ-Assistant"
    private const val ACTION = "android.intent.action.gaei.speechadapter.service"
    private const val ADAPTER_PACKAGE = "com.gaei.speechadapter"
    private const val ADAPTER_SERVICE = "com.gaei.speechadapter.SpeechAdapterService"
    private const val CONTROL = "vr_control"
    private const val VR_ENABLE = 101
    private const val VR_DISABLE = 102
    private const val PREFS = "guangqi_stock_assistant"
    private const val KEY_PAUSED = "paused"
}
