package com.pureplayer.video

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings

/**
 * Centralized Battery Saver & Power Efficiency manager.
 * Supports manual Battery Saver, automatic activation on OS Power Save Mode or low battery threshold,
 * system reduced-motion detection, and one-shot 15%/5% playback battery alerts.
 */
object BatteryOptimizationManager {

    private const val PREF_NAME = "vexo_battery_prefs"
    private const val KEY_MANUAL_SAVER = "manual_battery_saver"
    private const val KEY_AUTO_SAVER = "auto_battery_saver"
    private const val KEY_AUTO_THRESHOLD = "auto_battery_threshold"
    private const val KEY_SHOW_PLAYER_INDICATOR = "show_player_battery_indicator"
    private const val KEY_LOW_BATTERY_ALERTS = "low_battery_alerts_enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isManualBatterySaverEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MANUAL_SAVER, false)

    fun setManualBatterySaverEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MANUAL_SAVER, enabled).apply()
    }

    fun isAutoBatterySaverEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SAVER, true)

    fun setAutoBatterySaverEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SAVER, enabled).apply()
    }

    fun getAutoThresholdPercent(context: Context): Int =
        prefs(context).getInt(KEY_AUTO_THRESHOLD, 15).coerceIn(5, 50)

    fun setAutoThresholdPercent(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_AUTO_THRESHOLD, percent.coerceIn(5, 50)).apply()
    }

    fun isPlayerBatteryIndicatorEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_PLAYER_INDICATOR, true)

    fun setPlayerBatteryIndicatorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_PLAYER_INDICATOR, enabled).apply()
    }

    fun isLowBatteryAlertsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOW_BATTERY_ALERTS, true)

    fun setLowBatteryAlertsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOW_BATTERY_ALERTS, enabled).apply()
    }

    fun isSystemPowerSaveMode(context: Context): Boolean {
        return try {
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isPowerSaveMode == true
        } catch (_: Exception) {
            false
        }
    }

    fun getCurrentBatteryPercent(context: Context): Int {
        return try {
            val bm = context.applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 100
        } catch (_: Exception) {
            100
        }
    }

    /**
     * Pure testable decision function for whether Battery Saver mode should be active.
     */
    fun shouldBatterySaverBeActive(
        manualEnabled: Boolean,
        autoEnabled: Boolean,
        systemPowerSave: Boolean,
        batteryPercent: Int,
        thresholdPercent: Int
    ): Boolean {
        if (manualEnabled) return true
        if (!autoEnabled) return false
        return systemPowerSave || (batteryPercent in 1..thresholdPercent)
    }

    fun isBatterySaverActive(context: Context): Boolean {
        return shouldBatterySaverBeActive(
            manualEnabled = isManualBatterySaverEnabled(context),
            autoEnabled = isAutoBatterySaverEnabled(context),
            systemPowerSave = isSystemPowerSaveMode(context),
            batteryPercent = getCurrentBatteryPercent(context),
            thresholdPercent = getAutoThresholdPercent(context)
        )
    }

    /**
     * Respects both Battery Saver and Android's system "Remove animations" accessibility setting.
     */
    fun shouldReduceAnimations(context: Context): Boolean {
        if (isBatterySaverActive(context)) return true
        return try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
            scale == 0f
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns recommended UI ticker interval in ms (slower in Battery Saver to save CPU wakeups).
     */
    fun getRecommendedTickerIntervalMs(context: Context): Long {
        return if (isBatterySaverActive(context)) 1000L else 400L
    }

    /**
     * Evaluates one-shot low-battery alert level (returns 15, 5, or null).
     */
    fun evaluateLowBatteryAlert(
        percent: Int,
        isCharging: Boolean,
        alreadyNotified15: Boolean,
        alreadyNotified5: Boolean
    ): Int? {
        if (isCharging || percent <= 0) return null
        if (percent <= 5 && !alreadyNotified5) return 5
        if (percent <= 15 && !alreadyNotified15) return 15
        return null
    }
}
