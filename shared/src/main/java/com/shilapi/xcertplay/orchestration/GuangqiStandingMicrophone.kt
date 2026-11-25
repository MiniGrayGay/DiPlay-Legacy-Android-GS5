// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.orchestration

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.util.Log
import com.shilapi.xcertplay.media.MicrophoneUplink
import com.shilapi.xcertplay.media.StandingMicrophone

/**
 * The setting and the session lifetime of the [StandingMicrophone]: with it on, DiPlay holds the
 * Guangqi head unit's microphone from the start of a CarPlay session to its end, and the stock
 * assistant ([GuangqiStockAssistant]) stays paused for as long. Off by default.
 */
object GuangqiStandingMicrophone {
    private const val TAG = "DiPlay-GQ-StandingMic"
    private const val PREFS = "guangqi_standing_microphone"
    private const val KEY_ENABLED = "enabled"
    private const val CALL_PREFS = "xcertplay_airplay"
    private const val KEY_CALL_MIC_COMPATIBILITY = "call_mic_compatibility"

    private val lock = Any()
    private var owner: Any? = null
    private var sessionLog: ((String) -> Unit)? = null
    @Volatile private var microphone: StandingMicrophone? = null
    private var opened = 0

    /** True on head units with the car's audio source manager, whose assistant holds the microphone. */
    fun supported(): Boolean = runCatching { Class.forName("android.media.AudioSourceManager") }.isSuccess

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /** Applies to a running session at once; off waits for a call or Siri that is using it. */
    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        val app = context.applicationContext
        synchronized(lock) {
            if (!on) shut("setting off") { it.closeWhenIdle() } else if (owner != null && supported()) open(app)
        }
    }

    fun sessionStarted(context: Context, owner: Any, log: (String) -> Unit) {
        val app = context.applicationContext
        synchronized(lock) {
            this.owner = owner
            sessionLog = log
            if (supported() && enabled(app)) open(app)
        }
    }

    fun sessionEnded(owner: Any) {
        synchronized(lock) {
            if (this.owner !== owner) return
            this.owner = null
            shut("session ended") { it.close() }
            sessionLog = null
        }
    }

    /** The recorder a microphone stream shares; null when it opens its own. */
    internal fun current(): StandingMicrophone? = microphone

    private fun open(app: Context) {
        if (microphone != null) return
        if (app.checkPermission(Manifest.permission.RECORD_AUDIO, android.os.Process.myPid(), android.os.Process.myUid()) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            report("Standing microphone: no microphone permission")
            return
        }
        // Its own reason: an earlier one may still be letting go of the microphone.
        val reason = "standing microphone ${++opened}"
        GuangqiStockAssistant.acquire(app, reason)
        val log = sessionLog
        microphone = StandingMicrophone(
            source = { if (callsUseMic(app)) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_COMMUNICATION },
            makeRoom = { GuangqiStockAssistant.reassert(app) },
            log = { message ->
                Log.i(TAG, message)
                runCatching { log?.invoke(message) }
            },
            onReleased = { GuangqiStockAssistant.release(app, reason) },
        ).start()
    }

    private fun shut(reason: String, end: (StandingMicrophone) -> Unit) {
        val held = microphone ?: return
        microphone = null
        report("Standing microphone: closing ($reason)")
        end(held)
    }

    /** As a per-stream call microphone: MIC when VOICE_COMMUNICATION is silent or the driver chose it. */
    private fun callsUseMic(app: Context): Boolean = MicrophoneUplink.voiceCommunicationSilentBroken ||
        app.getSharedPreferences(CALL_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CALL_MIC_COMPATIBILITY, false)

    private fun report(message: String) {
        Log.i(TAG, message)
        runCatching { sessionLog?.invoke(message) }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
