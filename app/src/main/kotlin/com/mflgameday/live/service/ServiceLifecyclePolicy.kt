package com.mflgameday.live.service

import com.mflgameday.live.model.GameDaySettings
import com.mflgameday.live.model.OperatingMode

/**
 * Pure lifecycle rules governing background persistence vs. swipe-close shutdown:
 * - Pressing Home, switching apps, or locking the screen keeps the Foreground Service polling & speaking.
 * - Swiping GameDay Live out of Android Recent Apps (`Service.onTaskRemoved`) stops voice & polling
 *   cleanly (when `stopWhenSwipedClosed` is enabled, default `true`) WITHOUT disabling `runInBackgroundService`,
 *   so reopening the app automatically resumes live broadcast with zero crippled settings.
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
}
