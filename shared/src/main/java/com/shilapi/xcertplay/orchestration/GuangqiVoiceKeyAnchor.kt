package com.shilapi.xcertplay.orchestration

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * Does nothing; it exists for its process name, `<package>:com.thundersoft.connectivity`.
 *
 * On a short press of the Guangqi voice key, `PhoneWindowManager.isCarlifeVrRunning()` passes the
 * key to the CarLife key listener only when `sys.carlife.vr_state` is `1` and
 * `ps | grep com.thundersoft.connectivity` prints a line. DiPlay force-stops the stock CarLife to
 * take that listener slot, so without this process the check fails, the system clears its CarLife
 * connected state, and the stock voice assistant opens instead. [GuangqiCarlifeKeys] binds this
 * service while it holds the keys.
 */
class GuangqiVoiceKeyAnchor : Service() {
    private val binder = Binder()

    override fun onBind(intent: Intent?): IBinder = binder
}
