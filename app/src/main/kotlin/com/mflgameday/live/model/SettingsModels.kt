package com.mflgameday.live.model

import kotlinx.serialization.Serializable

@Serializable
enum class OperatingMode {
  UNCONFIGURED,
  MFL_AND_NFL_LIVE,
  NFL_LIVE_ONLY,
  DEMO_SIMULATOR
}

@Serializable
enum class AppThemePreset(
  val displayName: String,
  val tagline: String,
  val isDark: Boolean
) {
  SYSTEM_AUTO(
    displayName = "Auto Dark / Light",
    tagline = "Follows Samsung One UI / Android system day & night schedule automatically",
    isDark = true
  ),
  STADIUM_PRIMETIME(
    displayName = "Stadium PrimeTime",
    tagline = "Deep obsidian broadcast navy, emerald turf & RedZone crimson",
    isDark = true
  ),
  SUNDAY_DAYLIGHT(
    displayName = "Sunday Daylight",
    tagline = "Crisp network TV alabaster, cobalt scorebugs & bright daylight contrast",
    isDark = false
  ),
  CYBER_GRIDIRON_OLED(
    displayName = "Cyber Gridiron OLED",
    tagline = "True black AMOLED saver for Pixel Fold & Tab S7 with electric neon",
    isDark = true
  ),
  RETRO_GAMEDAY_GOLD(
    displayName = "Classic GameDay Gold",
    tagline = "15-year MFL Windows GameDay tribute with warm charcoal & amber LEDs",
    isDark = true
  ),
  REDZONE_BLITZ(
    displayName = "RedZone Octane",
    tagline = "High-alert Sunday afternoon garnet, blazing scarlet & spotlight gold",
    isDark = true
  )
}

@Serializable
enum class AnnouncementScope(
  val shortLabel: String,
  val description: String
) {
  ALL_NFL(
    shortLabel = "All NFL",
    description = "Every live NFL game across the league"
  ),
  MY_GAMES(
    shortLabel = "My Games",
    description = "NFL games involving my or my opponents' players"
  ),
  MY_PLAYERS(
    shortLabel = "My Players",
    description = "Only plays directly involving my or my opponents' players"
  )
}

@Serializable
enum class VoicePersona(
  val title: String,
  val subtitle: String,
  val speechRate: Float,
  val pitch: Float
) {
  BOOTH_PLAY_BY_PLAY(
    title = "Booth Play-by-Play (David)",
    subtitle = "Crisp, authoritative network broadcast cadence",
    speechRate = 1.03f,
    pitch = 0.96f
  ),
  REDZONE_SHOUTY(
    title = "Excited RedZone (Shouty)",
    subtitle = "Fast-paced, energetic Sunday afternoon excitement",
    speechRate = 1.14f,
    pitch = 1.08f
  ),
  STUDIO_HOST(
    title = "Smooth Studio Host (Callie)",
    subtitle = "Warm, clear studio desk delivery",
    speechRate = 0.98f,
    pitch = 1.05f
  ),
  CUSTOM_TUNED(
    title = "Custom Rate & Pitch",
    subtitle = "Fine-tune your selected Android voice engine",
    speechRate = 1.0f,
    pitch = 1.0f
  )
}

@Serializable
data class VoiceOption(
  val id: String,
  val displayName: String,
  val localeTag: String,
  val isNetworkEnhanced: Boolean,
  val qualityRank: Int
)

/**
 * Comprehensive user settings combining every classic Windows `Gameday.ini` tick
 * with modern Android capabilities.
 */
@Serializable
data class GameDaySettings(
  val operatingMode: OperatingMode = OperatingMode.UNCONFIGURED,
  val activeLeagueId: String? = null,
  val mflUsername: String = "",
  val themePreset: AppThemePreset = AppThemePreset.STADIUM_PRIMETIME,
  val scope: AnnouncementScope = AnnouncementScope.ALL_NFL,
  val muted: Boolean = false,
  val enabledCategories: Set<SpokenCategory> = setOf(
    SpokenCategory.TOUCHDOWNS,
    SpokenCategory.FIELD_GOALS,
    SpokenCategory.TURNOVERS,
    SpokenCategory.SACKS,
    SpokenCategory.RED_ZONE,
    SpokenCategory.GAME_STATUS,
    SpokenCategory.FANTASY_SWINGS
  ),
  val voicePersona: VoicePersona = VoicePersona.BOOTH_PLAY_BY_PLAY,
  val selectedSystemVoiceId: String? = null,
  val customSpeechRate: Float = 1.03f,
  val customPitch: Float = 1.0f,
  val playStadiumSoundEffects: Boolean = true,
  val announceFantasyContextOnPlays: Boolean = true,
  // Classic Windows Gameday.ini display & scoring toggles:
  val onlyPlayersWithStats: Boolean = false,
  val showBenchAndContenders: Boolean = true,
  val showGameMinutesProgress: Boolean = true,
  val showScoringBreakdowns: Boolean = true,
  val showWinProbabilityAndPace: Boolean = true,
  val showCrossLeagueRootingBadges: Boolean = true,
  // Polling & Android OS behavior:
  val pollIntervalSeconds: Int = 30,
  val keepScreenAwake: Boolean = true,
  val runInBackgroundService: Boolean = true,
  val stopWhenSwipedClosed: Boolean = true,
  val celebrationBannerAnimations: Boolean = true,
  val commandCenterSplitOnWideScreens: Boolean = true,
  val hasCompletedQuickTour: Boolean = false
)
