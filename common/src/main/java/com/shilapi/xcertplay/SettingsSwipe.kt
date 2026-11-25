package com.shilapi.xcertplay

import android.view.MotionEvent
import kotlin.math.abs

/** The settings swipe (some fingers, downward), read from a window's touch events. */
internal class SettingsSwipe {
    enum class Result {
        /** Not the swipe. */
        PASS,
        /** It may be starting: what is below has to forget this touch. */
        TAKEN,
        /** Part of it, or its rest. */
        CONSUMED,
        OPEN,
    }

    /** From the last needed finger down until every finger is up. */
    var active = false
        private set
    private var tracking = false
    private var startX = 0f
    private var startY = 0f

    fun onTouch(event: MotionEvent, fingers: Int, density: Float): Result {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = false
                tracking = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (!active && event.pointerCount == fingers) {
                active = true
                tracking = true
                startX = centroid(event, MotionEvent::getX)
                startY = centroid(event, MotionEvent::getY)
                return Result.TAKEN
            }
        }
        if (!active) return Result.PASS
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = false
                tracking = false
            }
            MotionEvent.ACTION_MOVE -> if (tracking && event.pointerCount == fingers) {
                val down = centroid(event, MotionEvent::getY) - startY
                val aside = abs(centroid(event, MotionEvent::getX) - startX)
                if (down >= DISTANCE_DP * density && down >= aside * DIRECTION_RATIO) {
                    tracking = false
                    return Result.OPEN
                }
            }
            // A finger more or less ends it; the rest of the touch is still not for what is below.
            else -> tracking = false
        }
        return Result.CONSUMED
    }

    private inline fun centroid(event: MotionEvent, axis: (MotionEvent, Int) -> Float): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) total += axis(event, index)
        return total / event.pointerCount
    }

    companion object {
        const val DISTANCE_DP = 72
        const val DIRECTION_RATIO = 1.15f

        /** Gives [dispatch] a cancel in place of [event]. */
        inline fun cancelled(event: MotionEvent, dispatch: (MotionEvent) -> Unit) {
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            dispatch(cancel)
            cancel.recycle()
        }
    }
}
