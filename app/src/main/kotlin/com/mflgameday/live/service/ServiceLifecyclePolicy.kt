package com.mflgameday.live.service

import com.mflgameday.live.data.MflStatusInfo
import com.mflgameday.live.model.AnnouncementScope
import com.mflgameday.live.model.GameDaySettings
import com.mflgameday.live.model.GamePhase
import com.mflgameday.live.model.NflGameState
import com.mflgameday.live.model.OperatingMode

/**
 * Pure lifecycle rules governing game-window background persistence, automatic off-hours standby,
 * swipe-close / notification-dismiss shutdown, non-reentrant foreground service startup,
 * notification summary deduplication, and post-cancellation poll emission guards.
 */
object ServiceLifecyclePolicy {

  /** Start background standby up to 90 minutes before kickoff (also bridges 4:25 PM -> 8:20 PM SNF). */
  const val PRE_KICKOFF_STANDBY_WINDOW_MS: Long = 90L * 60L * 1000L

  /** Keep weather-delayed games in the active window up to 2 hours past scheduled kickoff while still SCHEDULED. */
  const val OVERDUE_SCHEDULED_GRACE_MS: Long = 120L * 60L * 1000L

  /** Allow a just-finished Final call to remain visible briefly when liveGames drops to 0, never hours/days later. */
  const val POST_FINAL_CALL_FRESHNESS_MS: Long = 20L * 60L * 1000L

  data class NotificationSummary(
    val title: String,
    val body: String,
    val muteActionLabel: String
  ) {
    val signature: String
      get() = "$title\n$body\n$muteActionLabel"
  }

  fun shouldStopOnTaskRemoved(settings: GameDaySettings): Boolean {
    return settings.stopWhenSwipedClosed
  }

  /**
   * Handles swipe-close / Back-exit when `GameDayLiveService` has not yet been promoted
   * (e.g., user swiped closed from Recents before the initial scoreboard fetch completed, so
   * `GameDayLiveService.onTaskRemoved` cannot fire), without stopping an active background
   * `GameDayLiveService` if Android merely reclaims a backgrounded `MainActivity` for RAM.
   */
  fun shouldStopOnMainActivityDestroyed(
    settings: GameDaySettings,
    isBroadcastPaused: Boolean,
    isChangingConfigurations: Boolean,
    isFinishing: Boolean,
    isServiceActiveOrPending: Boolean
  ): Boolean {
    if (isChangingConfigurations || isBroadcastPaused) return false
    if (settings.operatingMode == OperatingMode.UNCONFIGURED) return false
    if (!settings.stopWhenSwipedClosed) return false
    return isFinishing || !isServiceActiveOrPending
  }

  fun shouldAutoResumeOnAppForeground(
    settings: GameDaySettings,
    isBroadcastPaused: Boolean,
    isPollLoopActive: Boolean
  ): Boolean {
    if (settings.operatingMode == OperatingMode.UNCONFIGURED) return false
    if (isBroadcastPaused) return true
    if (settings.operatingMode != OperatingMode.DEMO_SIMULATOR && !isPollLoopActive) return true
    return false
  }

  /**
   * True ONLY when live broadcast persistence is warranted:
   * - Demo Simulator session is active, OR
   * - At least one NFL game is currently `LIVE` or `HALFTIME`, OR
   * - A `SCHEDULED` NFL game kicks off within [PRE_KICKOFF_STANDBY_WINDOW_MS] (or is within weather-delay grace).
   *
   * Returns `false` on cold start before scoreboard loads (`nflGames.isEmpty()`), mid-week, overnight,
   * and once all games in the current slate are `FINAL` with no upcoming game within 90 minutes.
   */
  fun isWithinActiveBroadcastWindow(
    operatingMode: OperatingMode,
    nflGames: List<NflGameState>,
    nowMillis: Long
  ): Boolean {
    if (operatingMode == OperatingMode.UNCONFIGURED) return false
    if (operatingMode == OperatingMode.DEMO_SIMULATOR) return true
    if (nflGames.isEmpty()) return false
    if (nflGames.any { it.phase == GamePhase.LIVE || it.phase == GamePhase.HALFTIME }) return true
    return nflGames.any { game ->
      val ko = game.kickoffEpochMillis
      game.phase == GamePhase.SCHEDULED &&
        ko != null &&
        (ko - nowMillis) in -OVERDUE_SCHEDULED_GRACE_MS..PRE_KICKOFF_STANDBY_WINDOW_MS
    }
  }

  /**
   * Whether `GameDayLiveService` (`mediaPlayback` Foreground Service + WakeLock + persistent notification)
   * should currently be active. Stays alive while `isSpeakingNow` so a Final Score call is never clipped
   * right as the last game flips to `FINAL`, then tears down immediately once speech finishes.
   *
   * When [preserveWhileSlateLoading] is `true` (used for an already-promoted `START_STICKY` OS service
   * recreation whose initial scoreboard fetch is still in flight), keeps the recreated service alive
   * while `nflGames.isEmpty()` until the first poll resolves whether games are active.
   */
  fun shouldRunForegroundService(
    settings: GameDaySettings,
    isBroadcastPaused: Boolean,
    nflGames: List<NflGameState>,
    nowMillis: Long,
    isSpeakingNow: Boolean = false,
    preserveWhileSlateLoading: Boolean = false
  ): Boolean {
    if (settings.operatingMode == OperatingMode.UNCONFIGURED) return false
    if (!settings.runInBackgroundService) return false
    if (isBroadcastPaused) return false
    if (preserveWhileSlateLoading && nflGames.isEmpty()) return true
    return isSpeakingNow || isWithinActiveBroadcastWindow(settings.operatingMode, nflGames, nowMillis)
  }

  /**
   * Prevents redundant `startForegroundService` re-entry loops when the service is already
   * running in the foreground (`onStartCommand -> startLiveEngine -> syncForegroundServiceState`),
   * and prevents starting the Foreground Service outside active game windows.
   */
  fun shouldDispatchStartForegroundService(
    settings: GameDaySettings,
    isBroadcastPaused: Boolean,
    isServiceAlreadyForeground: Boolean,
    nflGames: List<NflGameState>,
    nowMillis: Long,
    isSpeakingNow: Boolean = false
  ): Boolean {
    if (isServiceAlreadyForeground) return false
    return shouldRunForegroundService(
      settings = settings,
      isBroadcastPaused = isBroadcastPaused,
      nflGames = nflGames,
      nowMillis = nowMillis,
      isSpeakingNow = isSpeakingNow
    )
  }

  /**
   * Determines whether the network polling coroutine should stay alive:
   * - Always allowed while the app is visibly open on screen (`isAppInForeground == true`).
   * - When backgrounded (`isAppInForeground == false`) with `runInBackgroundService == true`:
   *   - Retains retry/network-recovery loop if `nflGames.isEmpty()` (initial scoreboard fetch failed or
   *     device started offline, so game-window state is unknown — still with ZERO notification until loaded).
   *   - Once the slate is loaded (`nflGames.isNotEmpty()`), continues ONLY if inside an active broadcast
   *     window or finishing a spoken call; sleeps completely off-hours.
   */
  fun shouldKeepPollingInBackground(
    settings: GameDaySettings,
    isBroadcastPaused: Boolean,
    isAppInForeground: Boolean,
    nflGames: List<NflGameState>,
    nowMillis: Long,
    isSpeakingNow: Boolean = false
  ): Boolean {
    if (settings.operatingMode == OperatingMode.UNCONFIGURED || isBroadcastPaused) return false
    if (settings.operatingMode == OperatingMode.DEMO_SIMULATOR) return false
    if (isAppInForeground) return true
    if (!settings.runInBackgroundService) return false
    if (nflGames.isEmpty()) return true
    return isSpeakingNow || isWithinActiveBroadcastWindow(settings.operatingMode, nflGames, nowMillis)
  }

  /**
   * Adaptive poll cadence:
   * - Initial scoreboard not yet loaded (`nflGames.isEmpty()`): `60s` retry backoff until first slate loads
   * - Live / Halftime games: user's configured interval (`15..120s`, default `30s`)
   * - Kickoff imminent (`<= 60s`): `30s`
   * - Final 15m countdown before kickoff: `30..180s` (wakes right before kickoff)
   * - 15..90m pregame standby window: gentle `300..600s` (5-10 min, avoiding rapid notification/network churn)
   * - Off-hours / all Final (foreground UI only; background stops completely): `900s` (15 min)
   */
  fun computePollWaitSeconds(
    pollIntervalSeconds: Int,
    nflGames: List<NflGameState>,
    nowMillis: Long
  ): Int {
    if (nflGames.isEmpty()) {
      return 60
    }
    val anyLive = nflGames.any { it.phase == GamePhase.LIVE || it.phase == GamePhase.HALFTIME }
    if (anyLive) {
      return pollIntervalSeconds.coerceIn(15, 120)
    }
    val nextKickoff = nflGames
      .filter { it.phase == GamePhase.SCHEDULED }
      .mapNotNull { it.kickoffEpochMillis }
      .filter { it >= nowMillis - OVERDUE_SCHEDULED_GRACE_MS }
      .minOrNull()
      ?: return 900

    val untilKickoffSec = (nextKickoff - nowMillis) / 1000L
    return when {
      untilKickoffSec <= 60L -> 30
      untilKickoffSec <= 900L -> (untilKickoffSec - 15L).coerceIn(30L, 180L).toInt()
      untilKickoffSec <= (PRE_KICKOFF_STANDBY_WINDOW_MS / 1000L) ->
        (untilKickoffSec - 600L).coerceIn(300L, 600L).toInt()
      else -> 900
    }
  }

  fun isSimulatedPlayId(eventId: String): Boolean {
    return eventId.startsWith("sim_") || eventId.startsWith("seed_")
  }

  /**
   * Builds clean, non-stale notification copy and a deterministic deduplication signature.
   * Simulated audition plays (`sim_*` / `seed_*`) and hours-old historical plays are never shown
   * in the notification shade when `liveGamesCount == 0` in real MFL/NFL modes.
   */
  fun buildNotificationSummary(
    appBrand: String,
    operatingMode: OperatingMode,
    currentWeek: Int,
    muted: Boolean,
    scope: AnnouncementScope,
    liveGamesCount: Int,
    matchupHeadline: String?,
    latestPlaySpokenText: String?,
    latestPlayEventId: String?,
    latestPlayTimestampMillis: Long?,
    nowMillis: Long
  ): NotificationSummary {
    val title = matchupHeadline?.takeIf { it.isNotBlank() } ?: "$appBrand · Week $currentWeek"
    val muteActionLabel = if (muted) "Unmute" else "Mute"

    val isDemo = operatingMode == OperatingMode.DEMO_SIMULATOR
    val isRealPlay = !latestPlaySpokenText.isNullOrBlank() &&
      (isDemo || (latestPlayEventId != null && !isSimulatedPlayId(latestPlayEventId)))
    val isPlayFreshForZeroLive = latestPlayTimestampMillis != null &&
      (nowMillis - latestPlayTimestampMillis) in 0L..POST_FINAL_CALL_FRESHNESS_MS
    val canShowPlayText = isRealPlay && (isDemo || liveGamesCount > 0 || isPlayFreshForZeroLive)

    val body = when {
      muted && liveGamesCount > 0 -> "Announcements Muted · $liveGamesCount live games"
      muted -> "Announcements Muted · Standing by for kickoff"
      canShowPlayText -> latestPlaySpokenText.orEmpty()
      liveGamesCount > 0 -> "Live · $liveGamesCount games active · Scope: ${scope.shortLabel}"
      else -> "Standing by for kickoff · Scope: ${scope.shortLabel}"
    }

    return NotificationSummary(
      title = title,
      body = body,
      muteActionLabel = muteActionLabel
    )
  }

  /**
   * Blocks any in-flight poll cycle from updating UI or speaking announcements after
   * swipe-close / pause / loop restart.
   */
  fun canEmitPollResults(
    isBroadcastPaused: Boolean,
    cycleGeneration: Long,
    activeGeneration: Long,
    isCoroutineActive: Boolean
  ): Boolean {
    return !isBroadcastPaused && isCoroutineActive && cycleGeneration == activeGeneration
  }

  /**
   * Determines whether an already-running background poll loop should wake immediately (`0ms`)
   * and re-check week rollover when the user brings the app back into the foreground.
   */
  fun shouldWakeActivePollOnAppForeground(
    settings: GameDaySettings,
    isBroadcastPaused: Boolean,
    isPollLoopActive: Boolean,
    isRefreshing: Boolean,
    lastUpdatedMillis: Long?,
    nowMillis: Long,
    allGamesFinal: Boolean,
    staleThresholdMs: Long = 45_000L
  ): Boolean {
    if (settings.operatingMode != OperatingMode.MFL_AND_NFL_LIVE &&
      settings.operatingMode != OperatingMode.NFL_LIVE_ONLY
    ) {
      return false
    }
    if (isBroadcastPaused || !isPollLoopActive || isRefreshing) return false
    if (allGamesFinal) return true
    if (lastUpdatedMillis == null) return true
    return (nowMillis - lastUpdatedMillis) >= staleThresholdMs
  }

  /**
   * Resolves the active NFL & Fantasy week in lockstep across MFL `mfl_status.json` and ESPN's
   * live scoreboard so users returning after Monday Night Football automatically land on the
   * next week's slate without NFL/MFL week split-brain.
   */
  fun resolveEffectiveWeek(
    operatingMode: OperatingMode,
    currentUiWeek: Int,
    mflStatus: MflStatusInfo?,
    espnSeasonType: Int?,
    espnWeekNumber: Int?,
    previousGamesAllFinal: Boolean,
    sameSeasonAsUi: Boolean = true
  ): Int {
    val normalizedEspnWeek = when {
      espnWeekNumber == null || espnWeekNumber <= 0 -> null
      espnSeasonType == 2 || espnSeasonType == null -> espnWeekNumber.coerceIn(1, 18)
      espnSeasonType == 3 -> if (espnWeekNumber >= 4) 22 else (18 + espnWeekNumber) // 1->19, 2->20, 3->21, 4/5(ProBowl/SB)->22
      else -> null // Preseason (1) & Offseason (4): never override regular/postseason week numbers
    }

    if (operatingMode == OperatingMode.NFL_LIVE_ONLY) {
      return (normalizedEspnWeek ?: mflStatus?.effectiveActiveWeek ?: currentUiWeek).coerceIn(1, MflStatusInfo.MAX_MFL_NFL_WEEK)
    }

    val baseWeek = (mflStatus?.effectiveActiveWeek ?: currentUiWeek).coerceIn(1, MflStatusInfo.MAX_MFL_NFL_WEEK)
    // Preserve an already-advanced week within the same season if ESPN temporarily fails (`normalizedEspnWeek == null`)
    if (normalizedEspnWeek == null && sameSeasonAsUi && currentUiWeek > baseWeek) {
      return currentUiWeek.coerceIn(1, MflStatusInfo.MAX_MFL_NFL_WEEK)
    }
    // Advance when all prior-week games are FINAL, and hold that advanced week on subsequent cycles
    // (`currentUiWeek >= normalizedEspnWeek`) once the new week's SCHEDULED games replace nflGames!
    val canAdvanceOrHoldEspn = previousGamesAllFinal ||
      (normalizedEspnWeek != null && sameSeasonAsUi && currentUiWeek >= normalizedEspnWeek)
    if (canAdvanceOrHoldEspn && normalizedEspnWeek != null && normalizedEspnWeek > baseWeek) {
      return normalizedEspnWeek
    }
    return baseWeek
  }

  /**
   * Maps a unified NFL/MFL week (`1..22`) to ESPN's `(seasonType, week)` query parameters.
   */
  fun toEspnTargetSeasonTypeAndWeek(mflWeek: Int): Pair<Int, Int> {
    val clamped = mflWeek.coerceIn(1, MflStatusInfo.MAX_MFL_NFL_WEEK)
    return when {
      clamped <= 18 -> 2 to clamped
      clamped <= 21 -> 3 to (clamped - 18)
      else -> 3 to 5 // MFL Week 22 = Super Bowl = ESPN seasontype=3&week=5
    }
  }
}
