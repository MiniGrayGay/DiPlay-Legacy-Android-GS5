package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeTest {
    private val schedule = CarPlayNightSchedule(18 * 60, 6 * 60)

    private fun dark(theme: AppTheme, carPlay: CarPlayNightMode, system: Boolean?, minute: Int = 12 * 60) =
        AppTheme.resolveDark(theme, carPlay, schedule, system, minute)

    @Test fun explicitChoicesIgnoreDayAndNight() {
        assertFalse(dark(AppTheme.LIGHT, CarPlayNightMode.NIGHT, true))
        assertTrue(dark(AppTheme.DARK, CarPlayNightMode.DAY, false))
    }

    @Test fun automaticFollowsTheCarPlaySetting() {
        assertFalse(dark(AppTheme.AUTO, CarPlayNightMode.DAY, true))
        assertTrue(dark(AppTheme.AUTO, CarPlayNightMode.NIGHT, false))
        assertTrue(dark(AppTheme.AUTO, CarPlayNightMode.SCHEDULE, false, 22 * 60))
        assertFalse(dark(AppTheme.AUTO, CarPlayNightMode.SCHEDULE, true, 12 * 60))
        assertFalse(dark(AppTheme.AUTO, CarPlayNightMode.SYSTEM, false))
        assertTrue(dark(AppTheme.AUTO, CarPlayNightMode.AMBIENT, true))
    }

    @Test fun automaticStaysDarkWhenTheHeadUnitReportsNoNightMode() {
        assertTrue(dark(AppTheme.AUTO, CarPlayNightMode.SYSTEM, null))
    }

    @Test fun unknownKeysFallBackToAutomatic() {
        assertEquals(AppTheme.AUTO, AppTheme.fromKey(null))
        assertEquals(AppTheme.AUTO, AppTheme.fromKey("sepia"))
        assertEquals(AppTheme.LIGHT, AppTheme.fromKey("light"))
    }
}
