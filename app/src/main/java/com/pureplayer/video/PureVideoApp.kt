package com.pureplayer.video

import android.app.Application
import android.util.Log
import com.google.android.material.color.DynamicColors

class PureVideoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            PlaybackStore.migrateLegacyAppearanceIfNeeded(this)
            if (PlaybackStore.isMaterialYouSelected(this)) {
                DynamicColors.applyToActivitiesIfAvailable(this)
            }
            PlaybackStore.applyAppearanceMode(this)
            PlaybackStore.applySavedAppLanguage(this)
            PlaybackStore.pruneOldVideoKeys(this)
            XtreamCodesClient.allowUntrustedSslFallback = PlaybackStore.isAllowUntrustedSsl(this)
        } catch (e: Exception) {
            Log.w("PureVideoApp", "Startup initialization warning: ${e.javaClass.simpleName}")
        }
    }
}
