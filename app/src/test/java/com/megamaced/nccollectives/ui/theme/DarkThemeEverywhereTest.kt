package com.megamaced.nccollectives.ui.theme

import android.app.UiModeManager
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.prefs.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Theme T2: the window behind the UI follows the app's theme, not the system's
 * and not a light window theme that never changed.
 */
@RunWith(AndroidJUnit4::class)
class DarkThemeEverywhereTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aDarkAppTheme_paintsTheWindowDark() {
        var expected = 0
        compose.setContent {
            NcCollectivesTheme(themeMode = ThemeMode.Dark, dynamicColor = false) {
                expected = MaterialTheme.colorScheme.background.toArgb()
            }
        }
        compose.waitForIdle()

        val window = compose.activity.window.decorView.background as ColorDrawable
        assertEquals(expected, window.color)
    }

    @Test
    fun themeModes_mapToThePlatformsNightModes() {
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, ThemeMode.System.applicationNightMode())
        assertEquals(UiModeManager.MODE_NIGHT_NO, ThemeMode.Light.applicationNightMode())
        assertEquals(UiModeManager.MODE_NIGHT_YES, ThemeMode.Dark.applicationNightMode())
    }
}
