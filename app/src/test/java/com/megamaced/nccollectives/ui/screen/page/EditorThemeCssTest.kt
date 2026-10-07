package com.megamaced.nccollectives.ui.screen.page

import androidx.compose.ui.graphics.Color
import com.megamaced.nccollectives.ui.theme.NcCollectivesDarkColorScheme
import com.megamaced.nccollectives.ui.theme.NcCollectivesLightColorScheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Theme T4: the collaborative editor is painted in the app's colours, in
 * both modes, from the whole token set rather than a dark-only subset.
 */
class EditorThemeCssTest {
    @Test
    fun aLightApp_paintsTheEditorLightToo() {
        // It used to inject nothing in light mode, so a dark Nextcloud user
        // theme showed through inside a light app.
        val css = editorThemeCss(EditorPalette.from(NcCollectivesLightColorScheme, isDark = false))

        assertTrue(css.contains("color-scheme: light"))
        assertTrue(css.contains("--color-main-background: ${NcCollectivesLightColorScheme.background.hexForTest()}"))
        assertTrue(css.contains("--color-main-text: ${NcCollectivesLightColorScheme.onSurface.hexForTest()}"))
    }

    @Test
    fun aDarkApp_usesItsOwnColoursAndAccent() {
        val css = editorThemeCss(EditorPalette.from(NcCollectivesDarkColorScheme, isDark = true))

        assertTrue(css.contains("color-scheme: dark"))
        assertTrue(css.contains("--color-main-background: ${NcCollectivesDarkColorScheme.background.hexForTest()}"))
        assertTrue(
            "the accent is the app's, not forced grey",
            css.contains("--color-primary: ${NcCollectivesDarkColorScheme.primary.hexForTest()}"),
        )
        assertFalse("no hard-coded Nextcloud grey left", css.contains("#171717"))
    }

    @Test
    fun theInjectedScript_keepsTheCssOnWindowForLaterUpdates() {
        val script = buildInjectionScript("a { color: 'red'; }")

        assertTrue(script.contains("window.__ncCollectivesCss = "))
        assertTrue("quotes are escaped", script.contains("a { color: \\'red\\'; }"))
        assertTrue("an existing style is updated, not kept", script.contains("existing.textContent = css"))
    }

    private fun Color.hexForTest(): String {
        val argb = (alpha * 255).toInt() shl 24 or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()
        return "#%06X".format(argb and 0xFFFFFF)
    }
}
