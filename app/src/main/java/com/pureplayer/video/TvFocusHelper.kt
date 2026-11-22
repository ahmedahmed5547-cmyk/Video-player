package com.pureplayer.video

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

/**
 * Android TV & Large-Screen D-Pad focus and adaptive layout utilities.
 */
object TvFocusHelper {

    fun isTelevisionDevice(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return uiMode == Configuration.UI_MODE_TYPE_TELEVISION
    }

    /**
     * Pure decision function: use left-side Navigation Rail on Android TV or Expanded wide screens (>= 840dp).
     * Phones (< 840dp, non-TV) always keep the bottom navigation bar untouched.
     */
    fun shouldUseSideNavigationRail(isTv: Boolean, screenWidthDp: Int): Boolean {
        return isTv || screenWidthDp >= 840
    }

    /**
     * Returns TV overscan safe margins (horizontalDp, verticalDp) when running on Android TV.
     */
    fun getOverscanMarginsDp(isTv: Boolean): Pair<Int, Int> {
        return if (isTv) Pair(36, 20) else Pair(0, 0)
    }

    /**
     * Attaches a high-contrast D-Pad focus border + subtle scale-up highlight to any interactive view.
     */
    fun attachDpadFocusHighlight(view: View, accentColor: Int, cornerRadiusPx: Float = 24f) {
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        val originalBg = view.background
        view.setOnFocusChangeListener { v, hasFocus ->
            val reduceAnim = BatteryOptimizationManager.shouldReduceAnimations(v.context)
            if (hasFocus) {
                v.elevation = 14f
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = cornerRadiusPx
                    setColor(Color.argb(46, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
                    setStroke(6, accentColor)
                }
                if (!reduceAnim) {
                    v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(130L).start()
                }
            } else {
                v.elevation = 0f
                v.background = originalBg
                if (!reduceAnim) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
                } else {
                    v.scaleX = 1f
                    v.scaleY = 1f
                }
            }
        }
    }
}
