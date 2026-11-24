package com.shilapi.xcertplay.settings

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsWidgetsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun constructingAndChangingASwitchDoesNotSaveTheInitialValue() {
        for (theme in SettingsTheme.entries) {
            val saved = mutableListOf<Boolean>()
            val row = SettingsWidgets.createSwitchRow(context, "Title", "Details", true, theme,
                onChanged = saved::add)
            assertTrue(saved.isEmpty())
            row.switch.isChecked = false
            row.switch.isChecked = false
            assertEquals(listOf(false), saved)
        }
    }

    @Test fun overlayUsesCardThemeColorsAndLabelMetrics() {
        val row = SettingsWidgets.createSwitchRow(context, "Title", "Details", true, SettingsTheme.OVERLAY,
            labelSizeSp = 20f, onChanged = {})
        val label = (row.rowView as ViewGroup).getChildAt(0) as TextView
        assertEquals(20f, label.textSize / context.resources.displayMetrics.scaledDensity, 0.01f)
        assertFalse(label.includeFontPadding)
        assertEquals(SettingsTheme.OVERLAY.textSecondary, label.currentTextColor)
        assertNotNull(row.switch.thumbDrawable)
        assertNotNull(row.switch.trackDrawable)
    }

    @Test fun cardDescriptionsKeepMultilineSpacingAndTheirAccessibleTitle() {
        val row = SettingsWidgets.createSwitchRow(context, "Title", "First line\nSecond line", false,
            SettingsTheme.CARD, onChanged = {})
        val texts = views(row.rowView).filterIsInstance<TextView>().filter { it !== row.switch }.toList()
        assertEquals(listOf("Title", "First line\nSecond line"), texts.map { it.text.toString() })
        assertTrue(texts.all { it.lineSpacingExtra == SettingsTheme.CARD.dp(context, 3).toFloat() })
        assertEquals("Title", row.switch.contentDescription)
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
}
