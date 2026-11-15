package com.shilapi.xcertplay.settings

import android.content.Context
import android.graphics.Color

enum class SettingsTheme(
    val isOverlay: Boolean,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
    val accentTrack: Int,
    val trackOff: Int,
    val buttonText: Int,
) {
    CARD(
        isOverlay = false,
        textPrimary = Color.rgb(241, 245, 252),
        textSecondary = Color.rgb(168, 182, 202),
        accent = Color.rgb(166, 200, 255),
        accentTrack = Color.rgb(0x32, 0x58, 0x8c),
        trackOff = Color.rgb(42, 56, 75),
        buttonText = Color.rgb(12, 17, 27),
    ),
    // DiPlay's own screens in the light appearance; the in-session menu stays dark over CarPlay.
    CARD_LIGHT(
        isOverlay = false,
        textPrimary = Color.rgb(20, 26, 36),
        textSecondary = Color.rgb(84, 96, 114),
        accent = Color.rgb(31, 95, 191),
        accentTrack = Color.rgb(31, 95, 191),
        trackOff = Color.rgb(196, 205, 217),
        buttonText = Color.WHITE,
    ),
    // The in-session (three-finger) menu shares the main settings palette: it used
    // to carry its own green accent, which made the two settings surfaces look like
    // different products. Layout differences stay (isOverlay), colors do not.
    OVERLAY(
        isOverlay = true,
        textPrimary = Color.rgb(241, 245, 252),
        textSecondary = Color.rgb(168, 182, 202),
        accent = Color.rgb(166, 200, 255),
        accentTrack = Color.rgb(0x32, 0x58, 0x8c),
        trackOff = Color.rgb(42, 56, 75),
        buttonText = Color.rgb(12, 17, 27),
    );

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
