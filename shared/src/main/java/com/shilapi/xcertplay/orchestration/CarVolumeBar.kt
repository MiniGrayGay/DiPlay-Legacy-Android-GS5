// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.orchestration

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.shared.R

/**
 * DiPlay's own volume bar for the keys [GuangqiVolumeHook] takes: the car's bar is not shown for
 * them. An untouchable, unfocusable system window at the top centre, so it also shows while DiPlay
 * is in the background. Navigation is blue, as the car's own navigation bar is.
 */
internal object CarVolumeBar {
    private const val TAG = "DiPlay-CarVolumeBar"
    private const val SHOW_MILLIS = 1500L
    private const val NAVI_COLOR = 0xFF2F8CFF.toInt()
    private const val MEDIA_COLOR = Color.WHITE

    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var label: TextView? = null
    private var bar: LevelView? = null
    private val hide = Runnable { remove() }

    fun show(context: Context, navigation: Boolean, value: Int, max: Int) {
        val app = context.applicationContext
        main.post {
            runCatching { update(app, navigation, value, max) }.onFailure { Log.w(TAG, "show failed", it) }
        }
    }

    private fun update(app: Context, navigation: Boolean, value: Int, max: Int) {
        if (root == null) attach(app)
        val name = app.getString(if (navigation) R.string.car_volume_navigation else R.string.car_volume_media)
        label?.text = "$name  $value"
        bar?.set(value, max, if (navigation) NAVI_COLOR else MEDIA_COLOR)
        main.removeCallbacks(hide)
        main.postDelayed(hide, SHOW_MILLIS)
    }

    private fun attach(app: Context) {
        val density = app.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val text = TextView(app).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
        }
        val level = LevelView(app)
        val layout = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(14))
            background = GradientDrawable().apply {
                setColor(0xE0202020.toInt())
                cornerRadius = dp(16).toFloat()
            }
            addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(level, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply {
                topMargin = dp(10)
            })
        }
        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            (app.resources.displayMetrics.widthPixels * 0.4f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(24)
            title = "DiPlayVolumeBar"
        }
        (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(layout, params)
        root = layout
        label = text
        bar = level
    }

    private fun remove() {
        val view = root ?: return
        root = null
        label = null
        bar = null
        runCatching {
            (view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        }.onFailure { Log.w(TAG, "hide failed", it) }
    }

    private class LevelView(context: Context) : View(context) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var fraction = 0f

        fun set(value: Int, max: Int, color: Int) {
            fraction = if (max > 0) (value.toFloat() / max).coerceIn(0f, 1f) else 0f
            fill.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val radius = height / 2f
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, track)
            if (fraction > 0f) {
                rect.set(0f, 0f, width * fraction, height.toFloat())
                canvas.drawRoundRect(rect, radius, radius, fill)
            }
        }
    }
}
