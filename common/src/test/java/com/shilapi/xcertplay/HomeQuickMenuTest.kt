package com.shilapi.xcertplay

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** The quick menu without CarPlay: from DiPlay's own pages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w1280dp-h635dp-land-mdpi")
class HomeQuickMenuTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var screen: DiPlayActivity

    @After fun tearDown() {
        screen.finish()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    private fun open(page: String = "home") {
        screen = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", page)).setup().get()
    }

    private fun touch(action: Int, fingers: Int, y: Float) {
        val properties = Array(fingers) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(fingers) { MotionEvent.PointerCoords().apply { x = 400f + it * 80; this.y = y; pressure = 1f; size = 1f } }
        val full = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            action or ((fingers - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else action
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, full, fingers, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        screen.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun swipe(fingers: Int, from: Float = 100f, to: Float = 400f) {
        touch(MotionEvent.ACTION_DOWN, 1, from)
        for (n in 2..fingers) touch(MotionEvent.ACTION_POINTER_DOWN, n, from)
        touch(MotionEvent.ACTION_MOVE, fingers, to)
        for (n in fingers downTo 2) touch(MotionEvent.ACTION_POINTER_UP, n, to)
        touch(MotionEvent.ACTION_UP, 1, to)
    }

    private fun menu(): QuickMenu? = ReflectionHelpers.getField(screen, "quickMenu")

    private fun texts(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun press(label: Int) = texts(menu()!!.view).last { it.text == screen.getString(label) }.performClick()

    @Test fun threeFingersDownOpenItOnTheHomePageAndOnlyThat() {
        open()
        swipe(2)
        swipe(4)
        swipe(3, from = 400f, to = 100f)
        assertNull(menu())
        swipe(3)
        val view = menu()!!.view
        assertNotNull(view.parent)
        assertTrue(texts(view).any { it.text == screen.getString(R.string.settings_quick_settings) })
        assertNotNull("the page is still below", texts(screen.window.decorView).firstOrNull { it.text == screen.getString(R.string.connect_phone) })
    }

    @Test fun theConfiguredFingerCountApplies() {
        open()
        AirPlayPersistence.saveSettingsGestureFingers(screen, 4)
        swipe(3)
        assertNull(menu())
        swipe(4)
        assertNotNull(menu())
    }

    @Test fun itOpensOnASettingsPageAndSavesAtOnceWithoutASession() {
        open("settings")
        swipe(3)
        val before = AirPlayPersistence.loadClusterMapEnabled(screen)
        var row: View = texts(menu()!!.view).first { it.text == screen.getString(R.string.carplay_map_on_instrument_cluster_experimental) }
        while (!row.isClickable) row = row.parent as View
        row.performClick()
        assertEquals(!before, AirPlayPersistence.loadClusterMapEnabled(screen))
        assertEquals("nothing to reconnect", screen.getString(R.string.done), menu()!!.doneLabel.toString())
        press(R.string.done)
        assertNull(menu())
    }

    @Test fun backClosesTheMenuBeforeThePage() {
        open("settings")
        swipe(3)
        screen.onBackPressedDispatcher.onBackPressed()
        assertNull(menu())
        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
        assertFalse(screen.isFinishing)
    }

    @Test fun theMenuStaysAboveAPageThatRendersAgain() {
        open()
        swipe(3)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        val content = screen.findViewById<ViewGroup>(android.R.id.content)
        assertEquals(menu()!!.view, content.getChildAt(content.childCount - 1))
    }

    @Test fun itsFooterGoesToThePages() {
        open()
        swipe(3)
        texts(menu()!!.view).first { it.text == screen.getString(R.string.app_name) + " " + screen.getString(R.string.settings) }.performClick()
        assertNull(menu())
        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
        swipe(3)
        press(R.string.quick_back_to_home)
        assertEquals("home", ReflectionHelpers.getField<String>(screen, "page"))
    }

    @Test fun oneFingerStillPressesWhatIsBelow() {
        open()
        var pressed = 0
        val content = screen.findViewById<ViewGroup>(android.R.id.content)
        val probe = View(screen).apply { isClickable = true; setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_UP) pressed++; true } }
        content.addView(probe, ViewGroup.LayoutParams(-1, -1))
        content.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(635, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, 1280, 635)
        screen.window.decorView.layout(0, 0, 1280, 635)
        touch(MotionEvent.ACTION_DOWN, 1, 300f)
        touch(MotionEvent.ACTION_UP, 1, 300f)
        assertEquals(1, pressed)
        swipe(3)
        assertEquals("a swipe is not a press", 1, pressed)
    }
}
