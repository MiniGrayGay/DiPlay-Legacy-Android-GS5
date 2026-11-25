// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.orchestration

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.io.File

/**
 * Who is recording on the Guangqi head unit, and the last resort when pausing the stock assistant
 * ([GuangqiStockAssistant]) did not free the microphone: every process that is recording is ended,
 * whoever it is. The car's own Bluetooth calls do not record through Android (the Parrot module
 * drives the microphone), so they are never a holder. Android starts an ended service again when it
 * is next bound; DiPlay holds the microphone by then. Only DiPlay itself and system_server, whose
 * end would restart the whole system, are left alone. DiPlay's system uid may read the dump and end
 * a process.
 */
internal object GuangqiMicrophoneHolder {
    private const val TAG = "DiPlay-GQ-MicHolder"
    private const val SYSTEM_SERVER = "system_server"

    /** The pids of the clients with an active input track, from `dumpsys media.audio_flinger`. */
    fun recordingPids(dump: String): List<Int> {
        val pids = mutableListOf<Int>()
        var active = false
        for (line in dump.lineSequence()) {
            if (line.startsWith("Input thread ")) {
                active = line.trimEnd().endsWith("active tracks")
                continue
            }
            if (!active) continue
            if (line.isBlank()) {
                active = false
                continue
            }
            TRACK.find(line)?.let { pids += it.groupValues[1].toInt() }
        }
        return pids.distinct()
    }

    /** False for DiPlay's own processes and for system_server; every other holder may be ended. */
    fun mayEnd(process: String, ownPackage: String): Boolean =
        process != SYSTEM_SERVER && process != ownPackage && !process.startsWith("$ownPackage:")

    /** Reports the holders and ends them. Blocks; not for the main thread. */
    fun evict(context: Context, log: (String) -> Unit) {
        val holders = runCatching { recordingPids(dump()) }
            .onFailure { Log.w(TAG, "could not read the recording clients", it) }
            .getOrDefault(emptyList())
            .filter { it != android.os.Process.myPid() }
            .map { it to processName(it) }
        if (holders.isEmpty()) {
            log("Microphone holder: none found")
            return
        }
        for ((pid, process) in holders) {
            if (!mayEnd(process, context.packageName)) {
                log("Microphone holder: pid $pid $process; left alone")
                continue
            }
            val done = runCatching { end(context, pid) }
            log("Microphone holder: pid $pid $process; " +
                done.getOrElse { "could not be ended: ${it.cause ?: it}" })
        }
    }

    private fun dump(): String {
        val process = ProcessBuilder("dumpsys", "media.audio_flinger").redirectErrorStream(true).start()
        return try {
            process.inputStream.bufferedReader().readText()
        } finally {
            process.destroy()
        }
    }

    private fun processName(pid: Int): String =
        runCatching { File("/proc/$pid/cmdline").readText().substringBefore('\u0000') }.getOrDefault("")

    /** Returns what was done, for the log. */
    private fun end(context: Context, pid: Int): String {
        val activities = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val app = activities.runningAppProcesses?.firstOrNull { it.pid == pid }
        if (app == null) {
            // Not an app: without root a signal only reaches a process of the same uid.
            android.os.Process.killProcess(pid)
            return "not an app; signalled"
        }
        val manager = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null)!!
        manager.javaClass.getMethod("killApplicationProcess", String::class.java, Int::class.javaPrimitiveType)
            .invoke(manager, app.processName, app.uid)
        return "ended"
    }

    /** "  2041   1 00000010      10 6 00412300   2304": client, format, channel mask, session. */
    private val TRACK = Regex("""^\s*(\d+)\s+\d+\s+[0-9A-Fa-f]{8}\s+\d+\s""")
}
