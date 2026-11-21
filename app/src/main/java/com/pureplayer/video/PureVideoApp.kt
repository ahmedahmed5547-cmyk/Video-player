package com.pureplayer.video

import android.app.Application
import com.google.android.material.color.DynamicColors
import android.util.Log

class PureVideoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (PlaybackStore.getAppearanceMode(this) == PlaybackStore.APPEARANCE_SYSTEM) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
        try {
            PlaybackStore.applyAppearanceMode(this)
            PlaybackStore.applySavedAppLanguage(this)
            PlaybackStore.pruneOldVideoKeys(this)
            XtreamCodesClient.allowUntrustedSslFallback = PlaybackStore.isAllowUntrustedSsl(this)
        } catch (e: Exception) {
            Log.w("PureVideoApp", "Startup initialization warning: ${e.javaClass.simpleName}")
        }
    }
}
