package com.megamaced.nccollectives.ui.theme

import android.app.UiModeManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.drawable.toDrawable
import com.megamaced.nccollectives.data.prefs.TextScale
import com.megamaced.nccollectives.data.prefs.ThemeMode
import android.graphics.Color as AndroidColor

@Composable
fun NcCollectivesTheme(
    themeMode: ThemeMode = ThemeMode.System,
    // Body-text size preference. Published through `LocalTextScale` for
    // the page renderer and the two editors to read; the M3 typography
    // itself is left alone so chrome keeps its own scale.
    textScale: TextScale = TextScale.Default,
    // Material You on Android 12+; brand palette otherwise. Settings →
    // Appearance can switch it off (`UserPrefs.dynamicColor`), which forces
    // the brand scheme on all API levels.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> {
            NcCollectivesDarkColorScheme
        }

        else -> {
            NcCollectivesLightColorScheme
        }
    }

    val view = LocalView.current
    val context = LocalContext.current
    // Theme T2: the platform's night mode follows the in-app choice on
    // Android 12+. That is what makes the window theme (values-night), the
    // editor WebView's prefers-color-scheme, system dialogs and Custom Tabs
    // agree with the app when the choice differs from the system's. The
    // system persists it and recreates the activity when it changes, and
    // setting the value already in force is a no-op. Android 10–11 have no
    // per-app night mode; the window background and bars below still follow.
    LaunchedEffect(themeMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(themeMode.applicationNightMode())
        }
    }
    val background = colorScheme.background.toArgb()
    if (!view.isInEditMode) {
        LaunchedEffect(darkTheme, background) {
            val activity = view.context as? ComponentActivity ?: return@LaunchedEffect
            // Theme T2: bars styled from the *app's* theme. enableEdgeToEdge()
            // in onCreate styled them from the system night mode, and the
            // statusBarColor / navigationBarColor writes that followed were
            // no-ops at targetSdk 35+, so an app theme other than the system's
            // left light icons on a light bar, or a dark scrim on a light app.
            activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { darkTheme },
                navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { darkTheme },
            )
            // And the window behind everything, for the moments Compose
            // draws nothing: transitions, the keyboard animation, a WebView
            // before its first paint.
            activity.window.setBackgroundDrawable(background.toDrawable())
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NcCollectivesTypography,
        shapes = NcCollectivesShapes,
    ) {
        CompositionLocalProvider(
            LocalTextScale provides textScale.multiplier,
            content = content,
        )
    }
}

/** Theme T2: the [UiModeManager] night mode an app-wide [ThemeMode] maps to. */
internal fun ThemeMode.applicationNightMode(): Int =
    when (this) {
        ThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
        ThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
        ThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
    }

// enableEdgeToEdge's own defaults for the 3-button navigation bar scrim.
private val LIGHT_SCRIM = AndroidColor.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = AndroidColor.argb(0x80, 0x1b, 0x1b, 0x1b)
