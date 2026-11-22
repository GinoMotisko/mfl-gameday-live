package com.mflgameday.live.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.mflgameday.live.model.AppThemePreset

@Immutable
data class GameDayExtendedColors(
  val isDark: Boolean,
  val turfAccent: Color,
  val redZoneAlert: Color,
  val touchdownGold: Color,
  val mineBadge: Color,
  val opponentBadge: Color,
  val conflictBadge: Color,
  val cardSurface: Color,
  val cardElevated: Color,
  val cardBorder: Color,
  val subtleText: Color,
  val headerGradientStart: Color,
  val headerGradientEnd: Color
)

val LocalGameDayColors = staticCompositionLocalOf {
  GameDayExtendedColors(
    isDark = true,
    turfAccent = Color(0xFF00E676),
    redZoneAlert = Color(0xFFFF1744),
    touchdownGold = Color(0xFFFFD54F),
    mineBadge = Color(0xFF38BDF8),
    opponentBadge = Color(0xFFFB923C),
    conflictBadge = Color(0xFFE879F9),
    cardSurface = Color(0xFF11192C),
    cardElevated = Color(0xFF18233C),
    cardBorder = Color(0xFF263554),
    subtleText = Color(0xFF94A3B8),
    headerGradientStart = Color(0xFF0B1325),
    headerGradientEnd = Color(0xFF0F292E)
  )
}

private fun schemeForPreset(
  preset: AppThemePreset,
  systemDark: Boolean
): Pair<ColorScheme, GameDayExtendedColors> {
  val effective = if (preset == AppThemePreset.SYSTEM_AUTO) {
    if (systemDark) AppThemePreset.STADIUM_PRIMETIME else AppThemePreset.SUNDAY_DAYLIGHT
  } else {
    preset
  }

  return when (effective) {
    AppThemePreset.SYSTEM_AUTO,
    AppThemePreset.STADIUM_PRIMETIME -> {
      darkColorScheme(
        primary = Color(0xFF00E676),
        onPrimary = Color(0xFF032110),
        primaryContainer = Color(0xFF0A3D28),
        onPrimaryContainer = Color(0xFF86EFAC),
        secondary = Color(0xFF38BDF8),
        onSecondary = Color(0xFF042233),
        tertiary = Color(0xFFFFD54F),
        background = Color(0xFF070D19),
        onBackground = Color(0xFFF1F5F9),
        surface = Color(0xFF0D1526),
        onSurface = Color(0xFFF1F5F9),
        surfaceVariant = Color(0xFF162138),
        onSurfaceVariant = Color(0xFFCBD5E1),
        error = Color(0xFFFF5252)
      ) to GameDayExtendedColors(
        isDark = true,
        turfAccent = Color(0xFF00E676),
        redZoneAlert = Color(0xFFFF1744),
        touchdownGold = Color(0xFFFFD54F),
        mineBadge = Color(0xFF38BDF8),
        opponentBadge = Color(0xFFFB923C),
        conflictBadge = Color(0xFFE879F9),
        cardSurface = Color(0xFF111B30),
        cardElevated = Color(0xFF182540),
        cardBorder = Color(0xFF253759),
        subtleText = Color(0xFF94A3B8),
        headerGradientStart = Color(0xFF0B1528),
        headerGradientEnd = Color(0xFF092B29)
      )
    }

    AppThemePreset.SUNDAY_DAYLIGHT -> {
      lightColorScheme(
        primary = Color(0xFF0B57D0),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD3E3FD),
        onPrimaryContainer = Color(0xFF041E49),
        secondary = Color(0xFF047857),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFFB45309),
        background = Color(0xFFF1F5F9),
        onBackground = Color(0xFF0F172A),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF0F172A),
        surfaceVariant = Color(0xFFE2E8F0),
        onSurfaceVariant = Color(0xFF334155),
        error = Color(0xFFDC2626)
      ) to GameDayExtendedColors(
        isDark = false,
        turfAccent = Color(0xFF047857),
        redZoneAlert = Color(0xFFDC2626),
        touchdownGold = Color(0xFFB45309),
        mineBadge = Color(0xFF0369A1),
        opponentBadge = Color(0xFFC2410C),
        conflictBadge = Color(0xFF7E22CE),
        cardSurface = Color(0xFFFFFFFF),
        cardElevated = Color(0xFFF8FAFC),
        cardBorder = Color(0xFFCBD5E1),
        subtleText = Color(0xFF475569),
        headerGradientStart = Color(0xFFE0ECFF),
        headerGradientEnd = Color(0xFFDDF7EC)
      )
    }

    AppThemePreset.CYBER_GRIDIRON_OLED -> {
      darkColorScheme(
        primary = Color(0xFF00E5FF),
        onPrimary = Color(0xFF001F24),
        primaryContainer = Color(0xFF003842),
        onPrimaryContainer = Color(0xFF84FFFF),
        secondary = Color(0xFF76FF03),
        onSecondary = Color(0xFF0F2600),
        tertiary = Color(0xFFFF2E93),
        background = Color(0xFF000000),
        onBackground = Color(0xFFF8FAFC),
        surface = Color(0xFF07090E),
        onSurface = Color(0xFFF8FAFC),
        surfaceVariant = Color(0xFF111622),
        onSurfaceVariant = Color(0xFFCBD5E1),
        error = Color(0xFFFF1744)
      ) to GameDayExtendedColors(
        isDark = true,
        turfAccent = Color(0xFF76FF03),
        redZoneAlert = Color(0xFFFF2E93),
        touchdownGold = Color(0xFFFFEA00),
        mineBadge = Color(0xFF00E5FF),
        opponentBadge = Color(0xFFFF9100),
        conflictBadge = Color(0xFFD500F9),
        cardSurface = Color(0xFF0A0E17),
        cardElevated = Color(0xFF121927),
        cardBorder = Color(0xFF1F2B42),
        subtleText = Color(0xFF94A3B8),
        headerGradientStart = Color(0xFF040B14),
        headerGradientEnd = Color(0xFF0A1926)
      )
    }

    AppThemePreset.RETRO_GAMEDAY_GOLD -> {
      darkColorScheme(
        primary = Color(0xFFFFB300),
        onPrimary = Color(0xFF261900),
        primaryContainer = Color(0xFF473200),
        onPrimaryContainer = Color(0xFFFFE082),
        secondary = Color(0xFF4CAF50),
        onSecondary = Color(0xFF072109),
        tertiary = Color(0xFFFF7043),
        background = Color(0xFF121316),
        onBackground = Color(0xFFF5F5F4),
        surface = Color(0xFF1A1C20),
        onSurface = Color(0xFFF5F5F4),
        surfaceVariant = Color(0xFF262930),
        onSurfaceVariant = Color(0xFFD6D3D1),
        error = Color(0xFFEF5350)
      ) to GameDayExtendedColors(
        isDark = true,
        turfAccent = Color(0xFF66BB6A),
        redZoneAlert = Color(0xFFFF3D00),
        touchdownGold = Color(0xFFFFCA28),
        mineBadge = Color(0xFFFFB300),
        opponentBadge = Color(0xFFFF7043),
        conflictBadge = Color(0xFFCE93D8),
        cardSurface = Color(0xFF1E2025),
        cardElevated = Color(0xFF282B33),
        cardBorder = Color(0xFF3B3F4A),
        subtleText = Color(0xFFA8A29E),
        headerGradientStart = Color(0xFF231F16),
        headerGradientEnd = Color(0xFF17261A)
      )
    }

    AppThemePreset.REDZONE_BLITZ -> {
      darkColorScheme(
        primary = Color(0xFFFF3D00),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF4F1105),
        onPrimaryContainer = Color(0xFFFFCCBC),
        secondary = Color(0xFFFFAB00),
        onSecondary = Color(0xFF261900),
        tertiary = Color(0xFF00E676),
        background = Color(0xFF120609),
        onBackground = Color(0xFFFFF1F2),
        surface = Color(0xFF1B0A0F),
        onSurface = Color(0xFFFFF1F2),
        surfaceVariant = Color(0xFF2B121A),
        onSurfaceVariant = Color(0xFFFECDD3),
        error = Color(0xFFFF1744)
      ) to GameDayExtendedColors(
        isDark = true,
        turfAccent = Color(0xFF00E676),
        redZoneAlert = Color(0xFFFF1744),
        touchdownGold = Color(0xFFFFCA28),
        mineBadge = Color(0xFF38BDF8),
        opponentBadge = Color(0xFFFFAB00),
        conflictBadge = Color(0xFFF472B6),
        cardSurface = Color(0xFF210D14),
        cardElevated = Color(0xFF2E141D),
        cardBorder = Color(0xFF4C1D2B),
        subtleText = Color(0xFFFDA4AF),
        headerGradientStart = Color(0xFF2E0B14),
        headerGradientEnd = Color(0xFF1F1209)
      )
    }
  }
}

@Composable
fun MFLGameDayTheme(
  preset: AppThemePreset = AppThemePreset.STADIUM_PRIMETIME,
  content: @Composable () -> Unit
) {
  val sysDark = isSystemInDarkTheme()
  val (rawScheme, rawExt) = schemeForPreset(preset, sysDark)
  val animSpec = tween<Color>(durationMillis = 320)

  val animatedScheme = rawScheme.copy(
    primary = animateColorAsState(rawScheme.primary, animSpec, label = "p").value,
    background = animateColorAsState(rawScheme.background, animSpec, label = "bg").value,
    surface = animateColorAsState(rawScheme.surface, animSpec, label = "sf").value,
    surfaceVariant = animateColorAsState(rawScheme.surfaceVariant, animSpec, label = "sv").value
  )

  CompositionLocalProvider(LocalGameDayColors provides rawExt) {
    MaterialTheme(
      colorScheme = animatedScheme,
      typography = Typography,
      content = content
    )
  }
}
