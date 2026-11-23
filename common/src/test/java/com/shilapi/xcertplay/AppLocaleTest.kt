package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The Guangqi head unit runs Android 4.4, which takes the pre-Android-13 path: the choice lives in
 * preferences and is applied as a context override. Robolectric cannot run API 19, so the same
 * branch is exercised on API 28.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en", manifest = Config.NONE)
class AppLocaleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)

    @After fun clear() {
        prefs.edit().remove("app_language").commit()
    }

    @Test fun onlySimplifiedChineseAndEnglishAreOffered() {
        assertEquals(listOf(AppLocale.SIMPLIFIED_CHINESE, AppLocale.ENGLISH), AppLocale.ALL)
        assertEquals("简体中文", AppLocale.displayName(context, AppLocale.SIMPLIFIED_CHINESE))
        assertEquals("English", AppLocale.displayName(context, AppLocale.ENGLISH))
    }

    @Test fun withNoChoiceTheAppIsInSimplifiedChineseOnAnEnglishSystem() {
        assertEquals(AppLocale.SIMPLIFIED_CHINESE, AppLocale.preference(context))
        val wrapped = AppLocale.wrap(context)
        assertEquals(Locale.SIMPLIFIED_CHINESE, wrapped.resources.configuration.locales[0])
        assertEquals("应用语言", wrapped.getString(R.string.language_app_language))
    }

    @Test fun englishIsKeptOnceChosen() {
        AppLocale.save(context, AppLocale.ENGLISH)
        assertEquals(AppLocale.ENGLISH, AppLocale.preference(context))
        val wrapped = AppLocale.wrap(context)
        assertEquals("en", wrapped.resources.configuration.locales[0].language)
        assertEquals("App language", wrapped.getString(R.string.language_app_language))
    }

    @Test fun oldChoicesAndRemovedLanguagesReadAsSimplifiedChinese() {
        for (old in listOf("system", "zh-TW", "ar", "ru", "es", "uk")) {
            prefs.edit().putString("app_language", old).commit()
            assertEquals(old, AppLocale.SIMPLIFIED_CHINESE, AppLocale.preference(context))
        }
    }

    @Test fun enforceWritesTheChosenLanguageBackAfterTheSystemReplacesIt() {
        AppLocale.save(context, AppLocale.SIMPLIFIED_CHINESE)
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(
            android.content.res.Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) },
            context.resources.displayMetrics,
        )
        assertTrue(AppLocale.enforce(context))
        assertEquals(Locale.SIMPLIFIED_CHINESE, context.resources.configuration.locales[0])
        assertFalse(AppLocale.enforce(context))
    }

    @Test fun savingRejectsALanguageThatIsNotShipped() {
        val error = runCatching { AppLocale.save(context, "ar") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
