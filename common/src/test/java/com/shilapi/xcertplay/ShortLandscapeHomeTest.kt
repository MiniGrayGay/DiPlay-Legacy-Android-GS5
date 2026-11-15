package com.shilapi.xcertplay

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * The Guangqi unit: 1280x720 at 160 dpi, less its 85 dp status bar. Every wireless control must be
 * on screen without scrolling, with the car-hotspot warning and the disconnect button both shown.
 */
@RunWith(RobolectricTestRunner::class)
class ShortLandscapeHomeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @After fun tearDown() {
        activity?.finish()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Config(sdk = [29], qualifiers = "zh-rCN-w1280dp-h635dp-land-mdpi")
    @Test fun chineseWirelessControlsFitAboveTheFold() = assertFits()

    @Config(sdk = [29], qualifiers = "en-w1280dp-h635dp-land-mdpi")
    @Test fun englishWirelessControlsFitAboveTheFold() = assertFits()

    @Config(sdk = [29], qualifiers = "en-w1280dp-h800dp-land-mdpi")
    @Test fun tallScreensKeepTheHeroText() {
        val screen = open()
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.a_familiar_drive) })
    }

    private fun assertFits() {
        val screen = open()
        val height = 635
        val root = screen.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1280, height)
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.a_familiar_drive) })
        for (title in listOf(R.string.connect_phone, R.string.choose_iphone, R.string.disconnect,
            R.string.connect_with_usb, R.string.settings)) {
            val view = texts(screen).first { it.text == screen.getString(title) && it.isShown }
            val at = IntArray(2).also(view::getLocationInWindow)
            assertTrue("${screen.getString(title)} ends at ${at[1] + view.height}", at[1] + view.height <= height)
        }
    }

    private fun open(): DiPlayActivity {
        AirPlayPersistence.saveWirelessEnabled(context, true)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        DiPlayPreferences.savePhone(context, "AA:BB:CC:DD:EE:FF", "My iPhone")
        val screen = Robolectric.buildActivity(DiPlayActivity::class.java, Intent(context, DiPlayActivity::class.java))
            .setup().get().also { activity = it }
        ReflectionHelpers.setField(screen, "setupError", null)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        ReflectionHelpers.getField<View>(screen, "disconnectButton").visibility = View.VISIBLE
        return screen
    }

    private fun texts(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
