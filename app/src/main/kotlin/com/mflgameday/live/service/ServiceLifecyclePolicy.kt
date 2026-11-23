package com.mflgameday.live.service

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
}
