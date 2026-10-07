package com.megamaced.nccollectives.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Ink-and-paper palette: Nextcloud brand blue as the M3 primary so the app
// sits visually alongside the official Nextcloud client suite (Files, News,
// Passwords, Talk), paired with warm parchment neutrals to keep the
// notebook character. The README's "Unofficial" disclaimer is the
// affiliation cover; visually we lean into the suite.
// Theme T3: a step deeper than the brand's #0082C9, which the launcher icon
// keeps. White on #0082C9 is 4.17:1 and #0082C9 on the parchment background
// is 3.94:1, both short of WCAG AA's 4.5:1 for body text. Primary is the
// colour of every filled button's label background, every text button, link
// and selected control, so this was a large share of the "no contrast" report.
// #0076B6 is 4.92:1 and 4.65:1 (ColorSchemeContrastTest).
private val InkPrimary = Color(0xFF0076B6)
private val InkOnPrimary = Color(0xFFFFFFFF)
private val InkPrimaryContainer = Color(0xFFCDE5FF)
private val InkOnPrimaryContainer = Color(0xFF001D36)

private val SlateSecondary = Color(0xFF515E70)
private val SlateOnSecondary = Color(0xFFFFFFFF)
private val SlateSecondaryContainer = Color(0xFFD5E3F7)
private val SlateOnSecondaryContainer = Color(0xFF0D1B2A)

private val MossTertiary = Color(0xFF5A6447)
private val MossOnTertiary = Color(0xFFFFFFFF)
private val MossTertiaryContainer = Color(0xFFDDEAC3)
private val MossOnTertiaryContainer = Color(0xFF181E09)

private val ErrorLight = Color(0xFFBA1A1A)
private val OnErrorLight = Color(0xFFFFFFFF)
private val ErrorContainerLight = Color(0xFFFFDAD6)
private val OnErrorContainerLight = Color(0xFF410002)

private val ParchmentBackgroundLight = Color(0xFFFBF8F3)
private val OnBackgroundLight = Color(0xFF1B1B1F)
private val ParchmentSurfaceLight = Color(0xFFFBF8F3)
private val OnSurfaceLight = Color(0xFF1B1B1F)
private val SurfaceVariantLight = Color(0xFFE0E2EC)
private val OnSurfaceVariantLight = Color(0xFF44474E)
private val OutlineLight = Color(0xFF74777F)
private val OutlineVariantLight = Color(0xFFC4C6CF)

// Theme T3: the M3 roles the scheme used to leave to the library's baseline,
// which is purple-tinted. Every sheet, card, menu, dialog and the markdown
// code and table backgrounds use the surface containers, so they drew
// lilac panels on parchment. Tonal steps of the parchment neutral, from
// lightest (lowest) to darkest (highest).
private val SurfaceDimLight = Color(0xFFDCD9D4)
private val SurfaceBrightLight = Color(0xFFFBF8F3)
private val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
private val SurfaceContainerLowLight = Color(0xFFF5F2ED)
private val SurfaceContainerLight = Color(0xFFEFECE7)
private val SurfaceContainerHighLight = Color(0xFFEAE7E2)
private val SurfaceContainerHighestLight = Color(0xFFE4E1DC)
private val InverseSurfaceLight = Color(0xFF31302C)
private val InverseOnSurfaceLight = Color(0xFFF3F0EB)

private val InkPrimaryDark = Color(0xFF9BCAFF)
private val InkOnPrimaryDark = Color(0xFF003258)
private val InkPrimaryContainerDark = Color(0xFF00497D)
private val InkOnPrimaryContainerDark = Color(0xFFCDE5FF)

private val SlateSecondaryDark = Color(0xFFB8C7DA)
private val SlateOnSecondaryDark = Color(0xFF233140)
private val SlateSecondaryContainerDark = Color(0xFF3A4757)
private val SlateOnSecondaryContainerDark = Color(0xFFD5E3F7)

private val MossTertiaryDark = Color(0xFFC1CDA9)
private val MossOnTertiaryDark = Color(0xFF2D351C)
private val MossTertiaryContainerDark = Color(0xFF434C31)
private val MossOnTertiaryContainerDark = Color(0xFFDDEAC3)

private val ErrorDark = Color(0xFFFFB4AB)
private val OnErrorDark = Color(0xFF690005)
private val ErrorContainerDark = Color(0xFF93000A)
private val OnErrorContainerDark = Color(0xFFFFDAD6)

private val BackgroundDark = Color(0xFF13161B)
private val OnBackgroundDark = Color(0xFFE3E2E6)
private val SurfaceDark = Color(0xFF13161B)
private val OnSurfaceDark = Color(0xFFE3E2E6)
private val SurfaceVariantDark = Color(0xFF44474E)
private val OnSurfaceVariantDark = Color(0xFFC4C6CF)
private val OutlineDark = Color(0xFF8E9099)
private val OutlineVariantDark = Color(0xFF44474E)

// Theme T3: as for the light scheme, steps of the cool ink neutral.
private val SurfaceDimDark = Color(0xFF13161B)
private val SurfaceBrightDark = Color(0xFF393C42)
private val SurfaceContainerLowestDark = Color(0xFF0E1116)
private val SurfaceContainerLowDark = Color(0xFF1B1E23)
private val SurfaceContainerDark = Color(0xFF1F2227)
private val SurfaceContainerHighDark = Color(0xFF2A2D32)
private val SurfaceContainerHighestDark = Color(0xFF35383D)
private val InverseSurfaceDark = Color(0xFFE3E2E6)
private val InverseOnSurfaceDark = Color(0xFF303035)
private val InversePrimaryDark = Color(0xFF0061A4)

internal val NcCollectivesLightColorScheme = lightColorScheme(
    primary = InkPrimary,
    onPrimary = InkOnPrimary,
    primaryContainer = InkPrimaryContainer,
    onPrimaryContainer = InkOnPrimaryContainer,
    secondary = SlateSecondary,
    onSecondary = SlateOnSecondary,
    secondaryContainer = SlateSecondaryContainer,
    onSecondaryContainer = SlateOnSecondaryContainer,
    tertiary = MossTertiary,
    onTertiary = MossOnTertiary,
    tertiaryContainer = MossTertiaryContainer,
    onTertiaryContainer = MossOnTertiaryContainer,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = ParchmentBackgroundLight,
    onBackground = OnBackgroundLight,
    surface = ParchmentSurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    surfaceTint = InkPrimary,
    inversePrimary = InkPrimaryDark,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = InverseOnSurfaceLight,
    surfaceDim = SurfaceDimLight,
    surfaceBright = SurfaceBrightLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
)

internal val NcCollectivesDarkColorScheme = darkColorScheme(
    primary = InkPrimaryDark,
    onPrimary = InkOnPrimaryDark,
    primaryContainer = InkPrimaryContainerDark,
    onPrimaryContainer = InkOnPrimaryContainerDark,
    secondary = SlateSecondaryDark,
    onSecondary = SlateOnSecondaryDark,
    secondaryContainer = SlateSecondaryContainerDark,
    onSecondaryContainer = SlateOnSecondaryContainerDark,
    tertiary = MossTertiaryDark,
    onTertiary = MossOnTertiaryDark,
    tertiaryContainer = MossTertiaryContainerDark,
    onTertiaryContainer = MossOnTertiaryContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    surfaceTint = InkPrimaryDark,
    inversePrimary = InversePrimaryDark,
    inverseSurface = InverseSurfaceDark,
    inverseOnSurface = InverseOnSurfaceDark,
    surfaceDim = SurfaceDimDark,
    surfaceBright = SurfaceBrightDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
)
