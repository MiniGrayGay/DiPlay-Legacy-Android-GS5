package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import com.shilapi.xcertplay.host.R
import java.util.Calendar

/**
 * Light or dark for DiPlay's own screens; CarPlay's picture keeps its own day and night setting.
 *
 * Automatic follows that CarPlay setting, so DiPlay and CarPlay turn dark together: day and night
 * as chosen, the schedule by the clock, and the system or the light sensor by the head unit's
 * night mode. A head unit that reports no night mode stays dark, DiPlay's look before this choice.
 */
enum class AppTheme(val key: String) {
    AUTO("auto"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        private const val PREFS = "diplay"
        private const val KEY = "app_theme"

        fun fromKey(key: String?): AppTheme = entries.firstOrNull { it.key == key } ?: AUTO

        fun preference(context: Context): AppTheme =
            fromKey(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

        fun save(context: Context, theme: AppTheme) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, theme.key).apply()
        }

        fun isDark(context: Context): Boolean = resolveDark(
            preference(context),
            AirPlayPersistence.loadCarPlayNightMode(context),
            AirPlayPersistence.loadCarPlayNightSchedule(context),
            nightModeOrNull(context.resources.configuration.uiMode),
            Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) },
        )

        internal fun resolveDark(
            theme: AppTheme,
            carPlay: CarPlayNightMode,
            schedule: CarPlayNightSchedule,
            systemNight: Boolean?,
            minuteOfDay: Int,
        ): Boolean = when (theme) {
            LIGHT -> false
            DARK -> true
            AUTO -> when (carPlay) {
                CarPlayNightMode.DAY -> false
                CarPlayNightMode.NIGHT -> true
                CarPlayNightMode.SCHEDULE -> schedule.isNight(minuteOfDay)
                CarPlayNightMode.SYSTEM, CarPlayNightMode.AMBIENT -> systemNight ?: true
            }
        }

        fun displayName(context: Context, theme: AppTheme): String = context.getString(when (theme) {
            AUTO -> R.string.settings_app_theme_auto
            LIGHT -> R.string.settings_app_theme_light
            DARK -> R.string.settings_app_theme_dark
        })

        fun showPicker(activity: Activity, onChanged: () -> Unit) {
            val choices = entries
            var selected = choices.indexOf(preference(activity))
            AlertDialog.Builder(activity)
                .setTitle(R.string.settings_app_theme)
                .setSingleChoiceItems(choices.map { displayName(activity, it) }.toTypedArray(), selected) { _, index ->
                    selected = index
                }
                .setPositiveButton(R.string.language_apply) { _, _ ->
                    if (choices[selected] != preference(activity)) {
                        save(activity, choices[selected])
                        onChanged()
                    }
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }
}
