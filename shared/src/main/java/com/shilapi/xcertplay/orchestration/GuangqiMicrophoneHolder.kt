// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.orchestration

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Who is recording on the Guangqi head unit, and the last resort when that is its stock assistant
 * and pausing it ([GuangqiStockAssistant]) did not free the microphone: its process is ended.
 * Android starts it again when the car next binds to it; DiPlay holds the microphone by then. Any
 * other holder is only reported. DiPlay's system uid may read the dump and end a process.
 */
internal object GuangqiMicrophoneHolder {
    private const val TAG = "DiPlay-GQ-MicHolder"
    private const val ASSISTANT_PACKAGE = "com.tencent.wecarspeech"

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

    /** True for the stock assistant's own processes. */
    fun isAssistant(process: String): Boolean =
        process == ASSISTANT_PACKAGE || process.startsWith("$ASSISTANT_PACKAGE:")

    /** Reports the holders and ends those that are the stock assistant. Blocks; not for the main thread. */
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
            if (!isAssistant(process)) {
                log("Microphone holder: pid $pid $process; left alone")
                continue
            }
            val failure = runCatching { end(context, process) }.exceptionOrNull()
            log("Microphone holder: pid $pid $process; " +
                if (failure == null) "ended" else "could not be ended: ${failure.cause ?: failure}")
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

    private fun end(context: Context, process: String) {
        val uid = context.packageManager.getApplicationInfo(ASSISTANT_PACKAGE, 0).uid
        val manager = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null)!!
        manager.javaClass.getMethod("killApplicationProcess", String::class.java, Int::class.javaPrimitiveType)
            .invoke(manager, process, uid)
    }

    /** "  2041   1 00000010      10 6 00412300   2304": client, format, channel mask, session. */
    private val TRACK = Regex("""^\s*(\d+)\s+\d+\s+[0-9A-Fa-f]{8}\s+\d+\s""")
}
