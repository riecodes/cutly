package com.eirmon.cutly.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Values sampled directly from the reference screenshots rather than guessed from the brand kit. */
val Accent = Color(0xFFEA445A)
val AccentPressed = Color(0xFFC93B4D)

/** The translucent disc behind the record control once a take is under way. */
val RecordBacking = Color(0x33FFFFFF)

/** Chrome pills: "Add sound", the mode selector track, the undo tag. */
val ChromePill = Color(0xCC262626)
val ChromePillSolid = Color(0xFF262626)

/** Panels and sheets that sit above the viewfinder: speed picker, countdown, video size. */
val SheetSurface = Color(0xFF2A2A2C)
val SheetTrack = Color(0xFF4A4A4C)
val SheetMuted = Color(0xFF9A9A9E)
val Scrim = Color(0x99000000)

/** The discard confirmation sheet, which is light even though the app is dark. */
val DialogSurface = Color(0xFFFFFFFF)
val DialogTitle = Color(0xFF161823)
val DialogNeutral = Color(0xFF57585F)
val DialogDivider = Color(0xFFE3E3E4)

/**
 * The home hub is a warm light surface, not a dark one — it is a launcher, not a viewfinder.
 * Canvas is off-white and white is reserved for the cards floating on it; a pure-white page reads
 * as unfinished at this size.
 */
val Canvas = Color(0xFFF5F5F5)
val Panel = Color(0xFFFFFFFF)
val Hairline = Color(0x12000000)
val HairlineStrong = Color(0x1F000000)
val Ink = Color(0xFF1D1D1F)
val Muted = Color(0xFF86868B)
val Faint = Color(0xFFAEAEB2)

/** The accent at ~12% alpha. A tint, never a lighter hue. */
val AccentTint = Color(0x1FEA445A)

/**
 * Expo-out. Every hand-written transition in the app uses this one curve, which is what makes
 * unrelated motions read as the same system.
 */
val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

private val CutlyColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF121215),
    onSurface = Color.White
)

@Composable
fun CutlyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CutlyColors,
        typography = CutlyTypography,
        content = content
    )
}
