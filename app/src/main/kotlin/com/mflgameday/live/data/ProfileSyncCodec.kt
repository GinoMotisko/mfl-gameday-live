package com.mflgameday.live.data

import com.mflgameday.live.model.AnnouncementScope
import com.mflgameday.live.model.AppThemePreset
import com.mflgameday.live.model.GameDaySettings
import com.mflgameday.live.model.LeagueConfig
import com.mflgameday.live.model.OperatingMode
import com.mflgameday.live.model.SpokenCategory
import com.mflgameday.live.model.VoicePersona
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ExportedLeagueEntry(
  val leagueId: String,
  val leagueName: String,
  val year: Int = 2026,
  val franchiseId: String = "0001",
  val franchiseName: String = "Team 0001",
  val baseUrl: String = ProfileSyncCodec.DEFAULT_MFL_BASE_URL,
  val isBestBall: Boolean = false,
  val precision: Int = 2,
  val starterCount: Int = 9,
  val positionLimits: Map<String, String> = emptyMap(),
  val includeInAnnouncements: Boolean = true,
  val bestBallTrackFullRoster: Boolean = true
)

@Serializable
data class ExportedSetupPreferences(
  val operatingMode: OperatingMode? = null,
  val activeLeagueId: String? = null,
  val themePreset: AppThemePreset? = null,
  val scope: AnnouncementScope? = null,
  val enabledCategories: Set<SpokenCategory>? = null,
  val voicePersona: VoicePersona? = null,
  val customSpeechRate: Float? = null,
  val customPitch: Float? = null,
  val playStadiumSoundEffects: Boolean? = null,
  val announceFantasyContextOnPlays: Boolean? = null,
  val onlyPlayersWithStats: Boolean? = null,
  val showBenchAndContenders: Boolean? = null,
  val showGameMinutesProgress: Boolean? = null,
  val showScoringBreakdowns: Boolean? = null,
  val showWinProbabilityAndPace: Boolean? = null,
  val showCrossLeagueRootingBadges: Boolean? = null,
  val pollIntervalSeconds: Int? = null,
  val keepScreenAwake: Boolean? = null,
  val runInBackgroundService: Boolean? = null,
  val stopWhenSwipedClosed: Boolean? = null,
  val celebrationBannerAnimations: Boolean? = null,
  val commandCenterSplitOnWideScreens: Boolean? = null
)

@Serializable
data class ExportedGameDayProfile(
  val schemaVersion: Int = ProfileSyncCodec.CURRENT_SCHEMA_VERSION,
  val leagues: List<ExportedLeagueEntry> = emptyList(),
  val preferences: ExportedSetupPreferences = ExportedSetupPreferences()
)

data class AppliedProfileResult(
  val settings: GameDaySettings,
  val leagues: List<LeagueConfig>
)

/**
 * Secret-free cross-device & cross-flavor setup portability codec (`direct` <-> `playStore`, Phone <-> Tablet).
 * - Explicitly omits `mflUsername`, session cookies (`MFL_USER_ID`), and private `APIKEY` flags (`hasCustomApiKey`).
 * - Enforces HTTPS + `*.myfantasyleague.com` host allowlisting on imported `baseUrl` values.
 * - Clamps speech rate, pitch, year, precision, and poll interval bounds against malformed/tampered JSON.
 */
object ProfileSyncCodec {
  const val CURRENT_SCHEMA_VERSION = 1
  const val MAX_PROFILE_CHARS = 65_536
  const val MAX_IMPORTED_LEAGUES = 25
  const val DEFAULT_MFL_BASE_URL = "https://api.myfantasyleague.com"

  private val exportJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  fun encodeProfile(
    settings: GameDaySettings,
    leagues: List<LeagueConfig>
  ): String {
    val safeMode = when (settings.operatingMode) {
      OperatingMode.DEMO_SIMULATOR -> if (leagues.isNotEmpty()) OperatingMode.MFL_AND_NFL_LIVE else OperatingMode.NFL_LIVE_ONLY
      else -> settings.operatingMode
    }
    val payload = ExportedGameDayProfile(
      schemaVersion = CURRENT_SCHEMA_VERSION,
      leagues = leagues.map { cfg ->
        ExportedLeagueEntry(
          leagueId = cfg.leagueId.trim(),
          leagueName = cfg.leagueName.trim(),
          year = cfg.year.coerceIn(2000, 2100),
          franchiseId = cfg.franchiseId.trim(),
          franchiseName = cfg.franchiseName.trim(),
          baseUrl = sanitizeMflBaseUrl(cfg.baseUrl),
          isBestBall = cfg.isBestBall,
          precision = cfg.precision.coerceIn(0, 4),
          starterCount = cfg.starterCount.coerceIn(1, 40),
          positionLimits = cfg.positionLimits,
          includeInAnnouncements = cfg.includeInAnnouncements,
          bestBallTrackFullRoster = cfg.bestBallTrackFullRoster
        )
      },
      preferences = ExportedSetupPreferences(
        operatingMode = safeMode,
        activeLeagueId = settings.activeLeagueId,
        themePreset = settings.themePreset,
        scope = settings.scope,
        enabledCategories = settings.enabledCategories,
        voicePersona = settings.voicePersona,
        customSpeechRate = settings.customSpeechRate.coerceIn(0.60f, 1.50f),
        customPitch = settings.customPitch.coerceIn(0.70f, 1.40f),
        playStadiumSoundEffects = settings.playStadiumSoundEffects,
        announceFantasyContextOnPlays = settings.announceFantasyContextOnPlays,
        onlyPlayersWithStats = settings.onlyPlayersWithStats,
        showBenchAndContenders = settings.showBenchAndContenders,
        showGameMinutesProgress = settings.showGameMinutesProgress,
        showScoringBreakdowns = settings.showScoringBreakdowns,
        showWinProbabilityAndPace = settings.showWinProbabilityAndPace,
        showCrossLeagueRootingBadges = settings.showCrossLeagueRootingBadges,
        pollIntervalSeconds = settings.pollIntervalSeconds.coerceIn(20, 120),
        keepScreenAwake = settings.keepScreenAwake,
        runInBackgroundService = settings.runInBackgroundService,
        stopWhenSwipedClosed = settings.stopWhenSwipedClosed,
        celebrationBannerAnimations = settings.celebrationBannerAnimations,
        commandCenterSplitOnWideScreens = settings.commandCenterSplitOnWideScreens
      )
    )
    return exportJson.encodeToString(payload)
  }

  fun decodeOrNull(rawJson: String): ExportedGameDayProfile? {
    if (rawJson.length > MAX_PROFILE_CHARS) return null
    val unwrapped = unwrapOptionalCodeFence(rawJson)
    if (!unwrapped.startsWith("{") || !unwrapped.endsWith("}")) return null
    return runCatching {
      LenientJson.parser.decodeFromString<ExportedGameDayProfile>(unwrapped)
    }.getOrNull()?.takeIf { it.schemaVersion in 1..10 }
  }

  fun decodeAndApply(
    rawJson: String,
    existingSettings: GameDaySettings,
    existingLeagues: List<LeagueConfig>
  ): AppliedProfileResult {
    val decoded = decodeOrNull(rawJson)
      ?: throw IllegalArgumentException("Invalid or oversized GameDay setup profile JSON.")
    return applyDecodedProfile(decoded, existingSettings, existingLeagues)
  }

  fun applyDecodedProfile(
    decoded: ExportedGameDayProfile,
    existingSettings: GameDaySettings,
    existingLeagues: List<LeagueConfig>
  ): AppliedProfileResult {
    val existingById = existingLeagues.associateBy { it.leagueId }.toMutableMap()
    val sanitizedImported = decoded.leagues.mapNotNull { entry ->
      val cleanId = entry.leagueId.trim().filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(16)
      if (cleanId.isEmpty()) return@mapNotNull null
      val prior = existingById[cleanId]
      LeagueConfig(
        leagueId = cleanId,
        leagueName = entry.leagueName.trim().ifEmpty { "League $cleanId" }.take(80),
        year = entry.year.coerceIn(2000, 2100),
        franchiseId = entry.franchiseId.trim().ifEmpty { "0001" }.take(12),
        franchiseName = entry.franchiseName.trim().ifEmpty { "Team ${entry.franchiseId}" }.take(80),
        baseUrl = sanitizeMflBaseUrl(entry.baseUrl),
        hasCustomApiKey = prior?.hasCustomApiKey ?: false,
        isBestBall = entry.isBestBall,
        precision = entry.precision.coerceIn(0, 4),
        starterCount = entry.starterCount.coerceIn(1, 40),
        positionLimits = entry.positionLimits
          .entries
          .take(30)
          .associate { (k, v) -> k.trim().take(12) to v.trim().take(16) },
        includeInAnnouncements = entry.includeInAnnouncements,
        bestBallTrackFullRoster = entry.bestBallTrackFullRoster
      )
    }.distinctBy { it.leagueId }.take(MAX_IMPORTED_LEAGUES)

    // Imported leagues take priority in order (capped to MAX_IMPORTED_LEAGUES), while ALWAYS preserving all non-overlapping existing local leagues
    val importedIds = sanitizedImported.map { it.leagueId }.toSet()
    val mergedLeagues = (sanitizedImported + existingLeagues.filterNot { it.leagueId in importedIds })
      .distinctBy { it.leagueId }

    val p = decoded.preferences
    val requestedNonDemoMode = p.operatingMode?.takeIf { it != OperatingMode.DEMO_SIMULATOR }
    val fallbackExistingMode = existingSettings.operatingMode.takeIf { it != OperatingMode.DEMO_SIMULATOR }
    val targetMode = when {
      mergedLeagues.isNotEmpty() && (requestedNonDemoMode == null || requestedNonDemoMode == OperatingMode.UNCONFIGURED) ->
        OperatingMode.MFL_AND_NFL_LIVE
      requestedNonDemoMode == OperatingMode.MFL_AND_NFL_LIVE && mergedLeagues.isEmpty() ->
        OperatingMode.NFL_LIVE_ONLY
      requestedNonDemoMode != null -> requestedNonDemoMode
      fallbackExistingMode != null && fallbackExistingMode != OperatingMode.UNCONFIGURED -> fallbackExistingMode
      mergedLeagues.isNotEmpty() -> OperatingMode.MFL_AND_NFL_LIVE
      else -> OperatingMode.NFL_LIVE_ONLY
    }
    val preferredActiveId = p.activeLeagueId?.trim()?.takeIf { candidate ->
      mergedLeagues.any { it.leagueId == candidate }
    } ?: existingSettings.activeLeagueId?.takeIf { candidate ->
      mergedLeagues.any { it.leagueId == candidate }
    } ?: mergedLeagues.firstOrNull()?.leagueId

    val mergedSettings = existingSettings.copy(
      operatingMode = targetMode,
      activeLeagueId = preferredActiveId,
      // Never overwrite or import mflUsername — keep existing device's own session username only
      mflUsername = existingSettings.mflUsername,
      themePreset = p.themePreset ?: existingSettings.themePreset,
      scope = p.scope ?: existingSettings.scope,
      enabledCategories = p.enabledCategories ?: existingSettings.enabledCategories,
      voicePersona = p.voicePersona ?: existingSettings.voicePersona,
      customSpeechRate = (p.customSpeechRate?.takeIf { it.isFinite() } ?: existingSettings.customSpeechRate)
        .coerceIn(0.60f, 1.50f),
      customPitch = (p.customPitch?.takeIf { it.isFinite() } ?: existingSettings.customPitch)
        .coerceIn(0.70f, 1.40f),
      playStadiumSoundEffects = p.playStadiumSoundEffects ?: existingSettings.playStadiumSoundEffects,
      announceFantasyContextOnPlays = p.announceFantasyContextOnPlays ?: existingSettings.announceFantasyContextOnPlays,
      onlyPlayersWithStats = p.onlyPlayersWithStats ?: existingSettings.onlyPlayersWithStats,
      showBenchAndContenders = p.showBenchAndContenders ?: existingSettings.showBenchAndContenders,
      showGameMinutesProgress = p.showGameMinutesProgress ?: existingSettings.showGameMinutesProgress,
      showScoringBreakdowns = p.showScoringBreakdowns ?: existingSettings.showScoringBreakdowns,
      showWinProbabilityAndPace = p.showWinProbabilityAndPace ?: existingSettings.showWinProbabilityAndPace,
      showCrossLeagueRootingBadges = p.showCrossLeagueRootingBadges ?: existingSettings.showCrossLeagueRootingBadges,
      pollIntervalSeconds = (p.pollIntervalSeconds ?: existingSettings.pollIntervalSeconds).coerceIn(20, 120),
      keepScreenAwake = p.keepScreenAwake ?: existingSettings.keepScreenAwake,
      runInBackgroundService = p.runInBackgroundService ?: existingSettings.runInBackgroundService,
      stopWhenSwipedClosed = p.stopWhenSwipedClosed ?: existingSettings.stopWhenSwipedClosed,
      celebrationBannerAnimations = p.celebrationBannerAnimations ?: existingSettings.celebrationBannerAnimations,
      commandCenterSplitOnWideScreens = p.commandCenterSplitOnWideScreens ?: existingSettings.commandCenterSplitOnWideScreens
    )

    return AppliedProfileResult(
      settings = mergedSettings,
      leagues = mergedLeagues
    )
  }

  internal fun sanitizeMflBaseUrl(rawUrl: String?): String {
    val candidate = rawUrl?.trim().orEmpty().trimEnd('/')
    if (candidate.isEmpty()) return DEFAULT_MFL_BASE_URL
    return runCatching {
      val uri = URI(candidate)
      val scheme = uri.scheme?.lowercase()
      val host = uri.host?.lowercase() ?: return@runCatching DEFAULT_MFL_BASE_URL
      val allowedHost = host == "myfantasyleague.com" || host.endsWith(".myfantasyleague.com")
      if (scheme == "https" && allowedHost && uri.userInfo == null) {
        val portSuffix = if (uri.port != -1 && uri.port != 443) ":${uri.port}" else ""
        "https://$host$portSuffix"
      } else {
        DEFAULT_MFL_BASE_URL
      }
    }.getOrDefault(DEFAULT_MFL_BASE_URL)
  }

  private fun unwrapOptionalCodeFence(input: String): String {
    val trimmed = input.trim()
    if (!trimmed.startsWith("```")) return trimmed
    return trimmed
      .removePrefix("```json")
      .removePrefix("```JSON")
      .removePrefix("```")
      .removeSuffix("```")
      .trim()
  }
}
