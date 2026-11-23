package com.shilapi.xcertplay.media

import android.content.Context

/**
 * The Guangqi switch for music during navigation prompts. Off by default: the car lowers music by
 * about 15 dB at media level 9. On: [GuangqiNavigationSource] sets the music stream back to the
 * car's own low-volume rule once the car has switched, so music is lowered only slightly.
 * Read at every prompt, so a change applies from the next one.
 */
object GuangqiGuidanceMusic {
    /** True on head units with the car's audio source manager, where the switch means something. */
    fun supported(): Boolean = runCatching { Class.forName("android.media.AudioSourceManager") }.isSuccess

    fun keepMusicLevel(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_MUSIC_LEVEL, false)

    fun setKeepMusicLevel(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_MUSIC_LEVEL, enabled).apply()
    }

    internal const val PREFS = "guangqi_navigation_source"
    internal const val KEY_KEEP_MUSIC_LEVEL = "keep_music_level"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
