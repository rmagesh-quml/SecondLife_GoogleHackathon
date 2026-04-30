package com.secondlife.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Brand palette ─────────────────────────────────────────────────────────
private val NavyBackground = Color(0xFF06080F)
private val NavySurface    = Color(0xFF0F1320)
private val NavySurface2   = Color(0xFF161B2C)
private val NavyOutline    = Color(0xFF1F2638)

private val AccentGreen    = Color(0xFF22D49C)   // Mic, waveform, "listening"
private val AccentBlue     = Color(0xFF7B8CFF)   // Layperson role, key-term highlights
private val AccentAmber    = Color(0xFFE9B23A)   // Paramedic role
private val AccentRed      = Color(0xFFE55A6A)   // Cancel button, errors
private val AccentMilitary = Color(0xFF4FA66B)   // Military medic role

private val TextPrimary    = Color(0xFFEAEEF7)
private val TextSecondary  = Color(0xFF9AA3B7)
private val TextMuted      = Color(0xFF5C6479)

private val DarkColors = darkColorScheme(
    primary            = AccentGreen,
    onPrimary          = Color(0xFF06241A),
    primaryContainer   = Color(0xFF0F2C24),
    onPrimaryContainer = AccentGreen,

    secondary          = AccentBlue,
    onSecondary        = Color(0xFF0E1430),
    secondaryContainer = Color(0xFF1A2046),
    onSecondaryContainer = AccentBlue,

    tertiary           = AccentAmber,
    onTertiary         = Color(0xFF2A1E00),

    error              = AccentRed,
    onError            = Color(0xFF2B0810),
    errorContainer     = Color(0xFF3A1A22),
    onErrorContainer   = AccentRed,

    background         = NavyBackground,
    onBackground       = TextPrimary,
    surface            = NavySurface,
    onSurface          = TextPrimary,
    surfaceVariant     = NavySurface2,
    onSurfaceVariant   = TextSecondary,
    outline            = NavyOutline,
    outlineVariant     = TextMuted,
)

// Light scheme is a placeholder; the app is designed for the dark UI in the spec.
private val LightColors = lightColorScheme()

/**
 * Brand-specific extras that don't fit Material's color slots cleanly,
 * exposed via a CompositionLocal so any composable can read them without props.
 */
data class SecondLifeColors(
    val accentGreen: Color,
    val accentBlue: Color,
    val accentAmber: Color,
    val accentMilitary: Color,
    val accentRed: Color,
    val textMuted: Color,
)

val LocalSecondLifeColors = staticCompositionLocalOf {
    SecondLifeColors(
        accentGreen    = AccentGreen,
        accentBlue     = AccentBlue,
        accentAmber    = AccentAmber,
        accentMilitary = AccentMilitary,
        accentRed      = AccentRed,
        textMuted      = TextMuted,
    )
}

/** Map a role key to its accent color. Centralised so UI never hardcodes per-role colors. */
fun SecondLifeColors.colorForRole(role: String): Color = when (role) {
    "layperson"      -> accentBlue
    "paramedic"      -> accentAmber
    "military_medic" -> accentMilitary
    else             -> accentBlue
}

private val SecondLifeTypography = Typography(
    titleLarge    = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = 0.sp),
    titleMedium   = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 16.sp),
    bodyLarge     = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium    = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge    = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 12.sp, letterSpacing = 1.2.sp),
    labelMedium   = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 11.sp, letterSpacing = 1.0.sp),
    labelSmall    = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 10.sp, letterSpacing = 0.8.sp),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Light,
        fontSize   = 32.sp,
        letterSpacing = 2.sp,
    ),
)

@Composable
fun SecondLifeTheme(
    darkTheme: Boolean = true,                      // App is dark-first by design.
    content:   @Composable () -> Unit,
) {
    val scheme = if (darkTheme || isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(
        colorScheme = scheme,
        typography  = SecondLifeTypography,
        content     = content,
    )
}
