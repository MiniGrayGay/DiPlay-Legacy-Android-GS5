package com.shilapi.xcertplay.settings

import android.content.Context
import com.shilapi.xcertplay.compat.realSdkInt
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R

object SettingsWidgets {

    private fun TextView.applyTextStyle(context: Context, theme: SettingsTheme, bold: Boolean = false) {
        if (theme.isOverlay) {
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            includeFontPadding = false
        } else {
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            setLineSpacing(theme.dp(context, 3).toFloat(), 1f)
        }
    }

    data class SwitchRowResult(
        val rowView: View,
        val switch: Switch,
    )

    /** Self-drawn switch thumb and track in theme colors; Switch tint lists need API 23+. */
    fun styleSwitch(switch: Switch, context: Context, theme: SettingsTheme) {
        fun thumb(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setSize(theme.dp(context, 24), theme.dp(context, 24))
        }
        switch.thumbDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), thumb(Color.WHITE))
            addState(intArrayOf(), thumb(theme.textSecondary))
        }
        fun track(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = theme.dp(context, 14).toFloat()
            setColor(color)
            setSize(theme.dp(context, 46), theme.dp(context, 28))
        }
        switch.trackDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), track(theme.accentTrack))
            addState(intArrayOf(), track(theme.trackOff))
        }
    }

    fun createSwitchRow(
        context: Context,
        label: String,
        description: String,
        checked: Boolean,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        contentDescription: String = label,
        enabled: Boolean = true,
        labelSizeSp: Float = 18f,
        onChanged: (Boolean) -> Unit,
    ): SwitchRowResult {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val switch = Switch(context).apply {
            isChecked = checked
            this.contentDescription = contentDescription
            isEnabled = enabled
            if (realSdkInt() >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                showText = false
                textOn = ""
                textOff = ""
            }
            if (theme.isOverlay) {
                styleSwitch(this, context, theme)
            } else {
                minHeight = theme.dp(context, 56)
                if (realSdkInt() >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    buttonTintList = ColorStateList.valueOf(theme.accent)
                }
            }
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }

        if (theme.isOverlay) {
            val labelView = TextView(context).apply {
                text = label
                textSize = labelSizeSp
                setTextColor(theme.textSecondary)
                applyTextStyle(context, theme)
            }
            row.addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(switch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            row.setPadding(0, theme.dp(context, 12), 0, theme.dp(context, 12))
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val labelView = TextView(context).apply {
                text = label
                textSize = labelSizeSp
                setTextColor(theme.textPrimary)
                applyTextStyle(context, theme, bold = true)
            }
            val descView = TextView(context).apply {
                text = description
                textSize = 14f
                setTextColor(theme.textSecondary)
                setPadding(0, theme.dp(context, 6), theme.dp(context, 16), 0)
                applyTextStyle(context, theme)
            }
            textCol.addView(labelView)
            textCol.addView(descView)
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(switch)
            // The whole row is the target; the switch stays out of the focus order so D-pad stops once.
            switch.isFocusable = false
            row.isFocusable = true
            row.background = com.shilapi.xcertplay.compatibleRipple(
                ColorStateList.valueOf(ROW_RIPPLE), null, android.graphics.drawable.ColorDrawable(Color.WHITE))
            if (realSdkInt() >= 23) row.foreground = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = theme.dp(context, 12).toFloat()
                    setStroke(theme.dp(context, 3), theme.accent)
                })
            }
            row.setOnClickListener { if (switch.isEnabled) switch.toggle() }
            switch.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            row.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Switch::class.java.name
                    info.isCheckable = true
                    info.isChecked = switch.isChecked
                    info.isEnabled = switch.isEnabled
                }
            }
            clampDescription(descView)
        }

        return SwitchRowResult(row, switch)
    }

    /** Long help stays two lines until tapped; screen readers still get the full text. */
    private fun clampDescription(view: TextView) {
        view.maxLines = COLLAPSED_DESCRIPTION_LINES
        view.ellipsize = android.text.TextUtils.TruncateAt.END
        view.post {
            if ((view.layout?.getEllipsisCount(view.lineCount - 1) ?: 0) == 0) return@post
            view.setOnClickListener {
                view.maxLines = Int.MAX_VALUE
                view.ellipsize = null
                view.setOnClickListener(null)
                view.isClickable = false
            }
        }
    }

    private const val COLLAPSED_DESCRIPTION_LINES = 2
    private const val ROW_RIPPLE = 0x336F9FD9
}
