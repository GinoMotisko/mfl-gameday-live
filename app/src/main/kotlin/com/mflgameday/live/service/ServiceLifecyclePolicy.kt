package com.mflgameday.live.service

import com.mflgameday.live.data.MflStatusInfo
import com.mflgameday.live.model.GameDaySettings
import com.mflgameday.live.model.OperatingMode

/**
 * Pure lifecycle rules governing background persistence vs. swipe-close shutdown,
 * non-reentrant foreground service startup, and post-cancellation poll emission guards.
 */
object ServiceLifecyclePolicy {

  fun shouldStopOnTaskRemoved(settings: GameDaySettings): Boolean {
    return settings.stopWhenSwipedClosed
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
   * Prevents redundant `startForegroundService` re-entry loops when the service is already
   * running in the foreground (`onStartCommand -> startLiveEngine -> syncForegroundServiceState`).
   */
  fun shouldDispatchStartForegroundService(
    settings: GameDaySettings,
    isBroadcastPaused: Boolean,
    isServiceAlreadyForeground: Boolean
  ): Boolean {
    if (settings.operatingMode == OperatingMode.UNCONFIGURED) return false
    if (!settings.runInBackgroundService) return false
    if (isBroadcastPaused) return false
    if (isServiceAlreadyForeground) return false
    return true
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
