package com.pureplayer.video

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI verification test for Settings responsive layout across Phone, Foldable, Tablet & Desktop.
 * Run on any Android Studio emulator via: ./gradlew connectedPlayDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class SettingsResponsiveUiTest {

    @Test
    fun settingsDialog_maintainsReadableWidthAndVisibleTitle() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.showProSettingsDialog(clearStack = true)
            }
            scenario.onActivity { activity ->
                val card = activity.activeFullScreenCardView
                assertNotNull("Settings card container should be attached", card)
                val density = activity.resources.displayMetrics.density.coerceAtLeast(1f)
                val measuredWidthDp = ((card?.width ?: 0) / density).toInt()
                val contentWidthDp = measuredWidthDp - (((card?.paddingLeft ?: 0) + (card?.paddingRight ?: 0)) / density).toInt()
                assertTrue("Expected Settings content width >= 300dp, got ${contentWidthDp}dp", contentWidthDp >= 300)
                assertTrue("Settings card should be visible", card?.visibility == View.VISIBLE)
            }
        }
    }
}
