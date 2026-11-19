package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.compat.realSdkInt
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Minimal root shell for the experimental hostapd hotspot backend: feeds a
 * script to `su` on stdin and captures the merged output. Every failure mode
 * (no su binary, denial, timeout, linkage problems on old ROMs) degrades to
 * a failed [Result] instead of throwing, mirroring the defensive posture of
 * the direct-usbfs root grants (issue #266).
 */
object RootShell {
    data class Result(val exitCode: Int?, val output: String, val timedOut: Boolean) {
        val success: Boolean get() = !timedOut && exitCode == 0
    }

    fun run(script: String, timeoutMillis: Long): Result = try {
        val process = ProcessBuilder(listOf("su"))
            .redirectErrorStream(true)
            .start()
        try {
            process.outputStream.bufferedWriter().use { writer ->
                // mksh creates heredoc temp files under $TMPDIR, which on some
                // firmware points at /sqlite_stmt_journals — a directory that
                // does not exist there, killing every heredoc (issue #301).
                // /data/local/tmp exists on stock Android and is root-writable.
                writer.write("export TMPDIR=/data/local/tmp\n")
                writer.write(script)
                writer.write("\nexit\n")
            }
        } catch (_: Exception) {
            // The shell may already be gone (no su); the read side reports it.
        }
        val output = StringBuilder()
        val reader = Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                    lines.forEach { output.append(it).append('\n') }
                }
            } catch (_: Exception) {
            }
        }, "root-shell-reader").apply { isDaemon = true }
        reader.start()
        val exited = waitForProcess(process, timeoutMillis)
        if (!exited) {
            process.destroy()
            Result(exitCode = null, output = output.toString(), timedOut = true)
        } else {
            reader.join(500)
            Result(exitCode = process.exitValue(), output = output.toString(), timedOut = false)
        }
    } catch (_: Exception) {
        Result(exitCode = null, output = "", timedOut = false)
    } catch (_: Error) {
        Result(exitCode = null, output = "", timedOut = false)
    }

    /** True when `su` answers and reports uid 0. */
    fun isRootAvailable(timeoutMillis: Long = 5_000): Boolean {
        val result = run("id", timeoutMillis)
        return result.success && result.output.contains("uid=0")
    }

    /** Process.waitFor(long, TimeUnit) is API 26+; poll exitValue on older releases. */
    private fun waitForProcess(process: Process, timeoutMillis: Long): Boolean {
        if (realSdkInt() >= 26) {
            return process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            try {
                process.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                if (System.nanoTime() >= deadline) return false
                Thread.sleep(50)
            }
        }
    }
}
