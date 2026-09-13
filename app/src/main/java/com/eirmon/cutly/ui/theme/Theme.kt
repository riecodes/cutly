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

/** Light-dialog text tones, used by the language picker. */
val Hairline = Color(0x12000000)
val Muted = Color(0xFF86868B)
val Faint = Color(0xFFAEAEB2)

/** The editor and the project shell: one dark surface family, panels a step lighter. */
val EditorSurface = Color(0xFF111114)
val EditorPanel = Color(0xFF1B1B1F)
val EditorTrack = Color(0xFF2A2A2F)
val EditorMuted = Color(0xFFA0A0A8)
val EditorFaint = Color(0xFF6F6F77)
val EditorLine = Color(0xFF35353B)

/**
 * Expo-out. Every hand-written transition in the app uses this one curve, which is what makes
 * unrelated motions read as the same system.
 */
val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

private val CutlyColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = EditorSurface,
    onBackground = Color.White,
    surface = EditorPanel,
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
