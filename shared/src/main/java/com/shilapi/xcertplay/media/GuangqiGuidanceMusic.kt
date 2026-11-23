package com.shilapi.xcertplay.media

import android.content.Context

/**
 * The Guangqi music volume during navigation prompts: the music stream index (of 30) DiPlay sets
 * once the car has switched to its navigation mode. The car itself uses 9, which together with the
 * iPhone's own duck leaves music almost inaudible; DiPlay defaults to 11. Read at every prompt, so a
 * change applies from the next one.
 */
object GuangqiGuidanceMusic {
    const val MIN_INDEX = 8
    const val MAX_INDEX = 14
    const val DEFAULT_INDEX = 11

    /** What `DspService` itself sets the music stream to during navigation above media level 6. */
    const val CAR_INDEX = 9

    val choices: List<Int> = (MIN_INDEX..MAX_INDEX).toList()

    /** True on head units with the car's audio source manager, where the setting means something. */
    fun supported(): Boolean = runCatching { Class.forName("android.media.AudioSourceManager") }.isSuccess

    fun musicIndex(context: Context): Int = musicIndex(prefs(context))

    fun setMusicIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_MUSIC_INDEX, index.coerceIn(MIN_INDEX, MAX_INDEX)).apply()
    }

    internal fun musicIndex(prefs: android.content.SharedPreferences): Int =
        prefs.getInt(KEY_MUSIC_INDEX, DEFAULT_INDEX).coerceIn(MIN_INDEX, MAX_INDEX)

    internal const val PREFS = "guangqi_navigation_source"
    private const val KEY_MUSIC_INDEX = "music_index_during_guidance"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
