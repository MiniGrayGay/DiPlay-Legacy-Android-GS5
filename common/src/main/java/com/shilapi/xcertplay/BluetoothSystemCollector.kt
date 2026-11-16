package com.shilapi.xcertplay

import android.content.Context
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal class BluetoothSystemCollector(private val context: Context, private val emit: (String) -> Unit) {
    fun snapshot(label: String, since: String) {
        emit("--- System evidence $label ---")
        emit("Evidence scope=Bluetooth logcat and dumpsys; HCI packet capture is not enabled or collected by this App")
        // What logcat returns depends on the app's uid: everything as the system uid, its own lines otherwise.
        emit("Logcat scope=whatever this app's uid may read")
        bounded("App logcat", 10) {
            val process = ProcessBuilder("/system/bin/logcat", "-d", "-b", "main", "-v", "threadtime", "-t", "300").redirectErrorStream(true).start()
            try {
                val text = process.inputStream.bufferedReader().use { it.readText().take(200000) }
                show(text, true)
            } finally { process.destroy() }
        }
    }

    private fun show(text: String?, log: Boolean) {
        if (text == null) { emit("Evidence command failed or timed out; no usable output"); return }
        val relevant = Regex("(?i)bluetooth|bt_stack|btif|bta_|rfcomm|sdp|l2cap|hci|avrcp|a2dp|permission denial|permission denied|not found|timed out")
        val privateData = Regex("(?i)link.?key|\\bltk\\b|\\birk\\b|pin.?code|packet|payload|eir.?data")
        val values = text.lineSequence().filter { relevant.containsMatchIn(it) && !privateData.containsMatchIn(it) }.take(180).toList()
        emit("Filtered ${if (log) "logcat" else "dumpsys"} lines=${values.size}; absence of lines is inconclusive")
        values.forEach { emit(it) }
    }

    private fun bounded(label: String, seconds: Long, action: () -> Unit) {
        val task = FutureTask { action() }
        Thread(task, "bluetooth-evidence").apply { isDaemon = true; start() }
        try { task.get(seconds, TimeUnit.SECONDS) }
        catch (error: Exception) {
            task.cancel(true)
            emit("$label unavailable: ${error.javaClass.simpleName}; collection deadline=${seconds}s")
        }
    }

}
