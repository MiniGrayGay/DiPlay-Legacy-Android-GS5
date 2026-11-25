package com.shilapi.xcertplay

/**
 * Tells music the iPhone resumes after Siri from music Siri was asked for. Both start a media stream,
 * and a start makes CarPlay the car's source again; over the radio a resume must not, as the driver
 * had left the music for the radio. Times are milliseconds on one clock.
 */
internal class SiriResumeGuard {
    private var musicOn = false
    private var musicEnded = NEVER
    private var siriOn = false
    private var siriEnded = NEVER
    private var playedBefore = false
    private var title: String? = null

    fun music(active: Boolean, now: Long) {
        if (musicOn && !active) musicEnded = now
        musicOn = active
    }

    fun siri(active: Boolean, now: Long, title: String?) {
        if (active && !siriOn && now - siriEnded > AFTER_SIRI_MILLIS) {
            // The iPhone closes the music stream just before it opens Siri's.
            playedBefore = musicOn || now - musicEnded <= BEFORE_SIRI_MILLIS
            this.title = title
        }
        if (!active && siriOn) siriEnded = now
        siriOn = active
    }

    /** Whether music starting [now] is what Siri interrupted. */
    fun isResume(now: Long): Boolean = playedBefore && (siriOn || now - siriEnded <= AFTER_SIRI_MILLIS)

    /** Whether [title] is new music soon after Siri: the driver asked for it. */
    fun isRequested(now: Long, title: String?): Boolean =
        playedBefore && title != null && title != this.title && now - siriEnded <= NEW_TITLE_MILLIS

    private companion object {
        const val NEVER = -1_000_000L
        const val BEFORE_SIRI_MILLIS = 2_000L
        const val AFTER_SIRI_MILLIS = 4_000L
        const val NEW_TITLE_MILLIS = 10_000L
    }
}
