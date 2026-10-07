package com.megamaced.nccollectives.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Theme T1: signing out forgets the account, not how the device's owner
 * reads the app. A sign-out that reset the theme read as "the setting
 * won't stick".
 */
@RunWith(AndroidJUnit4::class)
class AppearanceSurvivesSignOutTest {
    private val prefs = UserPreferences(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun signOut_keepsTheAppearanceAndDropsTheRest() =
        runTest {
            prefs.setThemeMode(ThemeMode.Dark)
            prefs.setTextScale(TextScale.Large)
            prefs.setDynamicColor(false)
            prefs.setDefaultCollectiveId(7)

            prefs.clearAll()

            val after = prefs.flow.first()
            assertEquals(ThemeMode.Dark, after.themeMode)
            assertEquals(TextScale.Large, after.textScale)
            assertFalse(after.dynamicColor)
            assertEquals("account data still goes", null, after.defaultCollectiveId)
        }

    @Test
    fun materialYou_isOnUntilSwitchedOff() =
        runTest {
            prefs.clearAll()
            prefs.setDynamicColor(true)

            assertTrue(prefs.flow.first().dynamicColor)
        }
}
