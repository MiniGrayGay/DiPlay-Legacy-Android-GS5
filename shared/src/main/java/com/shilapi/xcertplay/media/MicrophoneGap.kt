package com.shilapi.xcertplay.media

/** Counts the whole frames missing while a standing microphone delivers nothing; capture thread only. */
internal class MicrophoneGap(frameMillis: Int) {
    private val frameNs = frameMillis * 1_000_000L
    /** Up to when the stream is covered; null before its first audio. */
    private var coveredNs: Long? = null

    fun heard(nowNs: Long) {
        coveredNs = nowNs
    }

    /** Frames of silence to send now. A gap too short to be more than jitter is left to the recorder. */
    fun idle(nowNs: Long): Int {
        val covered = coveredNs ?: return 0
        val missing = nowNs - covered
        if (missing < MIN_GAP_NS) return 0
        val frames = (missing / frameNs).toInt()
        coveredNs = covered + frames * frameNs
        return frames
    }

    private companion object {
        const val MIN_GAP_NS = 180_000_000L
    }
}
