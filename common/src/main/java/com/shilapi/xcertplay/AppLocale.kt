// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.app.LocaleManager
import android.os.Build
import com.shilapi.xcertplay.compat.realSdkInt
import android.os.LocaleList
import android.content.Context
import android.content.res.Configuration
import com.shilapi.xcertplay.host.R
import java.util.Locale

/**
 * The app language: Simplified Chinese or English, Simplified Chinese until the driver picks one.
 * Android 13+ keeps the choice as the platform app locale; older Android keeps it in preferences
 * and applies it as a context override. A saved "system" or a language DiPlay no longer ships
 * (Traditional Chinese, Arabic, Russian, Spanish, Ukrainian) reads as Simplified Chinese.
 */
object AppLocale {
    const val SIMPLIFIED_CHINESE = "zh"
    const val ENGLISH = "en"
    const val DEFAULT = SIMPLIFIED_CHINESE

    val ALL = listOf(SIMPLIFIED_CHINESE, ENGLISH)

    private const val PREFS = "diplay"
    private const val KEY_LANGUAGE = "app_language"

    private const val KEY_MIGRATED = "app_language_platform_migrated"

    fun preference(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) return DEFAULT
            return if (locales[0].language == "en") ENGLISH else DEFAULT
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null)?.takeIf { it in ALL } ?: DEFAULT
    }

    fun save(context: Context, language: String) {
        require(language in ALL)
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList(locale(language))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_MIGRATED, true).remove(KEY_LANGUAGE).apply()
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LANGUAGE, language).apply()
        }
    }

    /**
     * On Android 13+ the OS applies a chosen language itself; with none chosen, the default is
     * applied here. Older Android always gets the override.
     */
    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val manager = context.getSystemService(LocaleManager::class.java)
            if (!prefs.getBoolean(KEY_MIGRATED, false)) {
                val previous = prefs.getString(KEY_LANGUAGE, null)?.takeIf { it in ALL }
                // Never overwrite a language already chosen through Android Settings.
                if (manager.applicationLocales.isEmpty && previous != null) {
                    manager.applicationLocales = LocaleList(locale(previous))
                }
                prefs.edit().putBoolean(KEY_MIGRATED, true).remove(KEY_LANGUAGE).apply()
            }
            if (!manager.applicationLocales.isEmpty) return context
        }
        val locale = locale(preference(context))
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }

    /**
     * Before Android 13, some head units run apps in a compatibility mode that puts the system language
     * back into the activity's resources after [wrap]. Writes the chosen language again; returns true
     * if it had to.
     */
    @Suppress("DEPRECATION")
    fun enforce(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) return false
        val locale = locale(preference(context))
        val resources = context.resources
        val currentLocale = if (realSdkInt() >= 24) {
            resources.configuration.locales[0]
        } else {
            resources.configuration.locale
        }
        if (currentLocale == locale) return false
        val configuration = Configuration(resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return true
    }

    fun showPicker(activity: Activity) {
        var selected = ALL.indexOf(preference(activity)).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle(R.string.language_app_language)
            .setSingleChoiceItems(ALL.map { displayName(activity, it) }.toTypedArray(), selected) { _, index ->
                selected = index
            }
            .setPositiveButton(R.string.language_apply) { _, _ ->
                val next = ALL[selected]
                if (next != preference(activity)) {
                    save(activity, next)
                    // LocaleManager recreates activities itself on Android 13+.
                    if (Build.VERSION.SDK_INT < 33) activity.recreate()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** Names stay in their native form. */
    @Suppress("UNUSED_PARAMETER")
    fun displayName(context: Context, language: String): String = when (language) {
        ENGLISH -> "English"
        else -> "简体中文"
    }

    private fun locale(language: String): Locale = when (language) {
        ENGLISH -> Locale.ENGLISH
        else -> Locale.SIMPLIFIED_CHINESE
    }
}
