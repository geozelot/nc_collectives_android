package com.megamaced.nccollectives.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Theme T3: every text colour in the brand palette meets WCAG AA (4.5:1) on
 * the surfaces it is drawn on, and no role is left to the library's
 * purple-tinted baseline.
 */
class ColorSchemeContrastTest {
    private val schemes = mapOf(
        "light" to NcCollectivesLightColorScheme,
        "dark" to NcCollectivesDarkColorScheme,
    )

    @Test
    fun everyTextPair_meetsAA() {
        schemes.forEach { (name, scheme) ->
            textPairs(scheme).forEach { (pair, colors) ->
                val ratio = contrast(colors.first, colors.second)
                assertTrue("$name $pair is ${"%.2f".format(ratio)}:1", ratio >= AA)
            }
        }
    }

    @Test
    fun noRoleIsTheLibrarysBaseline() {
        val baselines = mapOf("light" to lightColorScheme(), "dark" to darkColorScheme())
        schemes.forEach { (name, scheme) ->
            val baseline = baselines.getValue(name)
            roles(scheme).zip(roles(baseline)).forEach { (ours, theirs) ->
                assertNotEquals("$name ${ours.first} is still the baseline", theirs.second, ours.second)
            }
        }
    }

    private fun textPairs(s: ColorScheme): Map<String, Pair<Color, Color>> =
        mapOf(
            "onPrimary/primary" to (s.onPrimary to s.primary),
            "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
            "onSecondary/secondary" to (s.onSecondary to s.secondary),
            "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
            "onTertiary/tertiary" to (s.onTertiary to s.tertiary),
            "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
            "onError/error" to (s.onError to s.error),
            "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
            "onBackground/background" to (s.onBackground to s.background),
            "onSurface/surface" to (s.onSurface to s.surface),
            "onSurfaceVariant/surface" to (s.onSurfaceVariant to s.surface),
            "onSurfaceVariant/surfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
            "primary/surface" to (s.primary to s.surface),
            "onSurface/surfaceContainerHighest" to (s.onSurface to s.surfaceContainerHighest),
            "onSurfaceVariant/surfaceContainerHighest" to (s.onSurfaceVariant to s.surfaceContainerHighest),
            "inverseOnSurface/inverseSurface" to (s.inverseOnSurface to s.inverseSurface),
            "inversePrimary/inverseSurface" to (s.inversePrimary to s.inverseSurface),
        )

    private fun roles(s: ColorScheme): List<Pair<String, Color>> =
        listOf(
            "surfaceTint" to s.surfaceTint,
            "inverseSurface" to s.inverseSurface,
            "inverseOnSurface" to s.inverseOnSurface,
            "inversePrimary" to s.inversePrimary,
            "surfaceDim" to s.surfaceDim,
            "surfaceBright" to s.surfaceBright,
            "surfaceContainerLow" to s.surfaceContainerLow,
            "surfaceContainer" to s.surfaceContainer,
            "surfaceContainerHigh" to s.surfaceContainerHigh,
            "surfaceContainerHighest" to s.surfaceContainerHighest,
        )

    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private companion object {
        const val AA = 4.5
    }
}
