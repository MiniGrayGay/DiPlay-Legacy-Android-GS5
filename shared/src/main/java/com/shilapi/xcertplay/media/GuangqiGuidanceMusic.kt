package com.shilapi.xcertplay.media

import android.content.Context

/**
 * The Guangqi music level during navigation prompts: how many dB below its level before the prompt
 * music plays, whatever the media volume. [GuangqiNavigationSource] turns it into a music stream
 * index once the car has switched to its navigation mode. The car's own fixed 9/30 is one loudness
 * for every media level, nearly silent at media 10 with the iPhone's own duck; 6 dB is what the
 * driver chose by ear at media level 10 (12/30). Read at every prompt, so a change applies from the
 * next one.
 */
object GuangqiGuidanceMusic {
    const val MIN_DUCK_DB = 0
    const val MAX_DUCK_DB = 15
    const val DEFAULT_DUCK_DB = 6

    /** What `DspService` itself sets the music stream to during navigation, for the log. */
    const val CAR_INDEX = 9

    val choices: List<Int> = (MIN_DUCK_DB..MAX_DUCK_DB).toList()

    /** True on head units with the car's audio source manager, where the setting means something. */
    fun supported(): Boolean = runCatching { Class.forName("android.media.AudioSourceManager") }.isSuccess

    fun duckDb(context: Context): Int = duckDb(prefs(context))

    fun setDuckDb(context: Context, db: Int) {
        prefs(context).edit().putInt(KEY_DUCK_DB, db.coerceIn(MIN_DUCK_DB, MAX_DUCK_DB)).apply()
    }

    internal fun duckDb(prefs: android.content.SharedPreferences): Int =
        prefs.getInt(KEY_DUCK_DB, DEFAULT_DUCK_DB).coerceIn(MIN_DUCK_DB, MAX_DUCK_DB)

    internal const val PREFS = "guangqi_navigation_source"
    private const val KEY_DUCK_DB = "music_duck_db"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
