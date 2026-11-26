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
    tagline = "Deep obsidian broadcast navy, emerald turf & Red Zone crimson",
    isDark = true
  ),
  SUNDAY_DAYLIGHT(
    displayName = "Sunday Daylight",
    tagline = "Crisp network TV alabaster, cobalt scorebugs & bright daylight contrast",
    isDark = false
  ),
  CYBER_GRIDIRON_OLED(
    displayName = "Cyber Gridiron OLED",
    tagline = "True black AMOLED saver for foldables & tablets with electric neon",
    isDark = true
  ),
  RETRO_GAMEDAY_GOLD(
    displayName = "Classic GameDay Gold",
    tagline = "15-year classic desktop GameDay tribute with warm charcoal & amber LEDs",
    isDark = true
  ),
  REDZONE_BLITZ(
    displayName = "Red Zone Octane",
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
    shortLabel = if (com.mflgameday.live.BuildConfig.IS_PLAY_STORE_BUILD) "All Games" else "All NFL",
    description = "Every live pro football game across the slate"
  ),
  MY_GAMES(
    shortLabel = "My Games",
    description = "Games involving my or my opponents' players"
  ),
  MY_PLAYERS(
    shortLabel = "My Players",
    description = "Only plays directly involving my or my opponents' players"
  )
}

/**
 * Curated human commentator roster + custom fine-tune slot.
 * Preserves legacy wire tokens (`BOOTH_PLAY_BY_PLAY`, `REDZONE_SHOUTY`, `STUDIO_HOST`, `CUSTOM_TUNED`)
 * so saved `v1.2.5` settings upgrade with zero data loss.
 */
@Serializable
enum class VoicePersona(
  val shortName: String,
  val roleBadge: String,
  val flagEmoji: String,
  val title: String,
  val subtitle: String,
  val speechRate: Float,
  val pitch: Float,
  val preferredCountry: String,
  val prefersFemale: Boolean,
  val accentHex: Long,
  val sampleLine: String,
  val sampleKind: GameEventKind
) {
  BOOTH_PLAY_BY_PLAY(
    shortName = "David",
    roleBadge = "Network Lead",
    flagEmoji = "🇺🇸",
    title = "David · Network Lead",
    subtitle = "Crisp, authoritative Sunday play-by-play baritone",
    speechRate = 1.02f,
    pitch = 0.92f,
    preferredCountry = "US",
    prefersFemale = false,
    accentHex = 0xFF0284C7,
    sampleLine = "Touchdown! 42 yard strike down the seam, and your fantasy squad takes the lead!",
    sampleKind = GameEventKind.TOUCHDOWN
  ),
  REDZONE_SHOUTY(
    shortName = "Red Zone Rex",
    roleBadge = "Octane Hype",
    flagEmoji = "🇺🇸",
    title = "Red Zone Rex · Octane Hype",
    subtitle = "Fast-paced, breathless witching-hour energy",
    speechRate = 1.16f,
    pitch = 1.08f,
    preferredCountry = "US",
    prefersFemale = false,
    accentHex = 0xFFE11D48,
    sampleLine = "Quad-box chaos! First and goal at the one yard line — everybody eats right now!",
    sampleKind = GameEventKind.RED_ZONE
  ),
  STUDIO_HOST(
    shortName = "Callie",
    roleBadge = "Studio Host",
    flagEmoji = "🇺🇸",
    title = "Callie · Studio Host",
    subtitle = "Warm, polished studio anchor desk delivery",
    speechRate = 0.99f,
    pitch = 1.06f,
    preferredCountry = "US",
    prefersFemale = true,
    accentHex = 0xFFD97706,
    sampleLine = "From the studio desk: huge interception at midfield flips your fantasy matchup.",
    sampleKind = GameEventKind.INTERCEPTION
  ),
  LONDON_CALLING(
    shortName = "Nigel",
    roleBadge = "London Booth",
    flagEmoji = "🇬🇧",
    title = "Nigel · London Booth",
    subtitle = "Dry, clinical British broadcast commentary",
    speechRate = 1.04f,
    pitch = 0.96f,
    preferredCountry = "GB",
    prefersFemale = false,
    accentHex = 0xFF4F46E5,
    sampleLine = "Brilliant form! Picked clean out of the sky — absolutely clinical defense.",
    sampleKind = GameEventKind.INTERCEPTION
  ),
  AUSSIE_SIDELINE(
    shortName = "Macca",
    roleBadge = "Aussie Sideline",
    flagEmoji = "🇦🇺",
    title = "Macca · Aussie Sideline",
    subtitle = "High-voltage Down-Under sideline call",
    speechRate = 1.08f,
    pitch = 1.02f,
    preferredCountry = "AU",
    prefersFemale = false,
    accentHex = 0xFF059669,
    sampleLine = "Cracking hit in the backfield! Quarterback flattened for an eight yard sack!",
    sampleKind = GameEventKind.SACK
  ),
  FILM_ROOM(
    shortName = "Coach Vance",
    roleBadge = "Film Room",
    flagEmoji = "🇺🇸",
    title = "Coach Vance · Film Room",
    subtitle = "Measured, gravelly late-night X's and O's analyst",
    speechRate = 0.91f,
    pitch = 0.84f,
    preferredCountry = "US",
    prefersFemale = false,
    accentHex = 0xFF64748B,
    sampleLine = "Watch the weak-side blitz pick up the edge — textbook strip-sack changes the game.",
    sampleKind = GameEventKind.FUMBLE_LOST
  ),
  CUSTOM_TUNED(
    shortName = "Custom",
    roleBadge = "Custom Tuned",
    flagEmoji = "🎛️",
    title = "Custom Speed & Pitch",
    subtitle = "Hand-tuned rate, pitch & system voice override",
    speechRate = 1.0f,
    pitch = 1.0f,
    preferredCountry = "US",
    prefersFemale = false,
    accentHex = 0xFF0EA5E9,
    sampleLine = "Custom broadcast voice check: Touchdown from 28 yards out!",
    sampleKind = GameEventKind.TOUCHDOWN
  );

  companion object {
    val commentatorRoster: List<VoicePersona> = listOf(
      BOOTH_PLAY_BY_PLAY,
      REDZONE_SHOUTY,
      STUDIO_HOST,
      LONDON_CALLING,
      AUSSIE_SIDELINE,
      FILM_ROOM
    )
  }
}

/**
 * One-tap Sunday broadcast presets that configure both game scope and spoken play categories
 * without forcing new users through 12 separate radio/checkbox decisions.
 */
enum class BroadcastPreset(
  val emoji: String,
  val title: String,
  val subtitle: String,
  val targetScope: AnnouncementScope?,
  val targetCategories: Set<SpokenCategory>?
) {
  SUNDAY_REDZONE_PARTY(
    emoji = "🏈",
    title = "Sunday RedZone Party",
    subtitle = "Every score, turnover & Red Zone trip across all games",
    targetScope = AnnouncementScope.ALL_NFL,
    targetCategories = setOf(
      SpokenCategory.TOUCHDOWNS,
      SpokenCategory.FIELD_GOALS,
      SpokenCategory.TURNOVERS,
      SpokenCategory.SACKS,
      SpokenCategory.RED_ZONE,
      SpokenCategory.GAME_STATUS,
      SpokenCategory.FANTASY_SWINGS
    )
  ),
  JUST_MY_MATCHUP(
    emoji = "🎯",
    title = "Just My Matchup",
    subtitle = "Only plays & lead flips involving my starters or opponent",
    targetScope = AnnouncementScope.MY_PLAYERS,
    targetCategories = setOf(
      SpokenCategory.TOUCHDOWNS,
      SpokenCategory.FIELD_GOALS,
      SpokenCategory.TURNOVERS,
      SpokenCategory.SACKS,
      SpokenCategory.BIG_PLAYS,
      SpokenCategory.FANTASY_SWINGS
    )
  ),
  BIG_SCORES_ONLY(
    emoji = "🛋️",
    title = "Big Scores Only",
    subtitle = "Chill mode: touchdowns, field goals & fantasy lead flips",
    targetScope = AnnouncementScope.MY_GAMES,
    targetCategories = setOf(
      SpokenCategory.TOUCHDOWNS,
      SpokenCategory.FIELD_GOALS,
      SpokenCategory.FANTASY_SWINGS
    )
  ),
  CUSTOM(
    emoji = "🎛️",
    title = "Custom Mix",
    subtitle = "Personalized scope & play-type selection",
    targetScope = null,
    targetCategories = null
  );

  fun applyTo(settings: GameDaySettings): GameDaySettings {
    val sc = targetScope ?: return settings
    val cats = targetCategories ?: return settings
    return settings.copy(scope = sc, enabledCategories = cats)
  }

  companion object {
    val selectablePresets: List<BroadcastPreset> = listOf(
      SUNDAY_REDZONE_PARTY,
      JUST_MY_MATCHUP,
      BIG_SCORES_ONLY
    )

    fun detectActive(settings: GameDaySettings): BroadcastPreset {
      return selectablePresets.firstOrNull { preset ->
        preset.targetScope == settings.scope && preset.targetCategories == settings.enabledCategories
      } ?: CUSTOM
    }
  }
}

@Serializable
data class VoiceOption(
  val id: String,
  val displayName: String,
  val localeTag: String,
  val isNetworkEnhanced: Boolean,
  val qualityRank: Int
) {
  val capabilityBadge: String
    get() = when {
      qualityRank >= 400 && !isNetworkEnhanced -> "Studio HD · Offline"
      qualityRank >= 400 -> "Studio HD"
      !isNetworkEnhanced -> "Works Offline"
      else -> "Standard"
    }
}

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
  val optInAnonymousTelemetry: Boolean = false,
  val celebrationBannerAnimations: Boolean = true,
  val commandCenterSplitOnWideScreens: Boolean = true,
  val hasCompletedQuickTour: Boolean = false
)
