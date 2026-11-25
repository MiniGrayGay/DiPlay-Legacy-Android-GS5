// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.orchestration

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** The test tone: made here, and looked for in what the microphone heard. */
internal object SelfTestTone {
    const val HZ = 1_000
    /** How much louder [HZ] must get than before the tone to count as heard. */
    const val HEARD_DB = 10.0

    fun pcm(sampleRate: Int, millis: Int, amplitude: Double = 0.35): ByteArray {
        val samples = sampleRate * millis / 1_000
        val fade = sampleRate / 50
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val edge = minOf(1.0, minOf(i, samples - 1 - i).toDouble() / fade)
            val value = (sin(2 * PI * HZ * i / sampleRate) * amplitude * edge * Short.MAX_VALUE).toInt()
            out[2 * i] = value.toByte()
            out[2 * i + 1] = (value shr 8).toByte()
        }
        return out
    }

    /** How much louder [HZ] is in the loudest fifth of [during] than in the middle of [before], in dB. */
    fun riseDb(before: ByteArray, during: ByteArray, sampleRate: Int): Double {
        val quiet = powers(before, sampleRate).sorted()
        val loud = powers(during, sampleRate).sorted()
        if (quiet.isEmpty() || loud.isEmpty()) return Double.NaN
        return loud[(loud.size - 1) * 4 / 5] - quiet[quiet.size / 2]
    }

    /** RMS of [pcm] in dBFS. */
    fun levelDb(pcm: ByteArray): Double {
        if (pcm.size < 2) return SILENCE_DB
        var sum = 0.0
        for (i in 0 until pcm.size / 2) sample(pcm, i).let { sum += it * it }
        return decibels(sqrt(sum / (pcm.size / 2)))
    }

    /** Goertzel at [HZ], one value for each 100 ms. */
    private fun powers(pcm: ByteArray, sampleRate: Int): List<Double> {
        val window = sampleRate / 10
        val factor = 2 * cos(2 * PI * HZ / sampleRate)
        return (0 until pcm.size / 2 / window).map { index ->
            var previous = 0.0
            var beforeThat = 0.0
            for (i in 0 until window) {
                val next = sample(pcm, index * window + i) + factor * previous - beforeThat
                beforeThat = previous
                previous = next
            }
            val power = previous * previous + beforeThat * beforeThat - factor * previous * beforeThat
            decibels(sqrt(maxOf(power, 0.0)) / window)
        }
    }

    private fun sample(pcm: ByteArray, index: Int): Double =
        ((pcm[2 * index].toInt() and 0xff) or (pcm[2 * index + 1].toInt() shl 8)) / 32_768.0

    private fun decibels(value: Double) = if (value <= 1e-6) SILENCE_DB else 20 * log10(value)

    private const val SILENCE_DB = -120.0
}

/** What DiPlay's own log lines say about one Siri stream. */
internal class SelfTestSiri(lines: List<String>) {
    private val microphone = lines.filter { it.startsWith("Microphone: stats type=speechrecognition ") }

    val ended = microphone.any { it.endsWith("ended=true") }
    val sent = microphone.sumOf { number(it, "udpSent") ?: 0L }
    val sendErrors = microphone.sumOf { number(it, "sendErrors") ?: 0L }
    val readErrors = microphone.sumOf { number(it, "readErrors") ?: 0L }
    /** When sound was first captured; -1 for a microphone that only gave silence. */
    val firstAudioMs = microphone.mapNotNull { number(it, "firstAudioMs") }.lastOrNull { it >= 0 } ?: -1L
    /** Whether the iPhone's side reached the car. */
    val downlink = lines.any { it.startsWith("Audio: first PCM write audioType=speechrecognition") }
    val sourceHeld = lines.any { "Siri source" in it && "requested" in it && "held=true" in it }
    val sourceRefused = lines.any { "Siri source" in it && "requested" in it && "held=true" !in it }

    val microphoneWorks get() = sent > 0 && sendErrors == 0L && firstAudioMs >= 0

    override fun toString() = "发出 $sent 包，发送错误 $sendErrors，读取错误 $readErrors，" +
        (if (firstAudioMs >= 0) "${firstAudioMs} ms 后有声音" else "一直是静音") +
        if (downlink) "，收到 iPhone 的声音" else "，没收到 iPhone 的声音"

    private fun number(line: String, key: String): Long? =
        Regex("(?:^| )$key=(-?\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
}
