package com.mflgameday.live.service

import com.mflgameday.live.data.LenientJson
import com.mflgameday.live.diagnostics.TelemetrySnapshot
import com.mflgameday.live.model.GameDaySettings
import com.mflgameday.live.model.OperatingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceLifecyclePolicyTest {

  @Test
  fun defaultSettings_stopsWhenSwipedClosed_andTelemetryDefaultsOptOut() {
    val defaults = GameDaySettings()
    assertTrue("Background service should default ON for Home/screen-off use", defaults.runInBackgroundService)
    assertTrue("Swiping closed from Recents should default to stopping voice & polling", defaults.stopWhenSwipedClosed)
    assertFalse("Cloud telemetry must default to opt-in (false) for privacy", defaults.optInAnonymousTelemetry)
    assertTrue(ServiceLifecyclePolicy.shouldStopOnTaskRemoved(defaults))
  }

  @Test
  fun legacySettingsJsonWithoutSwipeFlag_defaultsToStoppingOnTaskRemoved() {
    val legacyJson = """{"operatingMode":"MFL_AND_NFL_LIVE","runInBackgroundService":true}"""
    val decoded = LenientJson.parser.decodeFromString(GameDaySettings.serializer(), legacyJson)
    assertTrue(decoded.runInBackgroundService)
    assertTrue(decoded.stopWhenSwipedClosed)
    assertFalse(decoded.optInAnonymousTelemetry)
    assertTrue(ServiceLifecyclePolicy.shouldStopOnTaskRemoved(decoded))
  }

  @Test
  fun userCanDisableStopWhenSwipedClosedForAlwaysOnKioskTablets() {
    val kioskSettings = GameDaySettings(
      operatingMode = OperatingMode.MFL_AND_NFL_LIVE,
      stopWhenSwipedClosed = false
    )
    assertFalse(ServiceLifecyclePolicy.shouldStopOnTaskRemoved(kioskSettings))
  }

  @Test
  fun reopeningAppAfterSwipeClose_autoResumesWithoutCripplingSettings() {
    val activeSettings = GameDaySettings(operatingMode = OperatingMode.MFL_AND_NFL_LIVE)
    assertTrue(
      ServiceLifecyclePolicy.shouldAutoResumeOnAppForeground(
        settings = activeSettings,
        isBroadcastPaused = true,
        isPollLoopActive = false
      )
    )
    assertFalse(
      ServiceLifecyclePolicy.shouldAutoResumeOnAppForeground(
        settings = GameDaySettings(operatingMode = OperatingMode.UNCONFIGURED),
        isBroadcastPaused = true,
        isPollLoopActive = false
      )
    )
    assertFalse(
      ServiceLifecyclePolicy.shouldAutoResumeOnAppForeground(
        settings = activeSettings,
        isBroadcastPaused = false,
        isPollLoopActive = true
      )
    )
  }

  @Test
  fun serviceAlreadyInForeground_neverReTriggersStartForegroundService() {
    val activeSettings = GameDaySettings(
      operatingMode = OperatingMode.MFL_AND_NFL_LIVE,
      runInBackgroundService = true
    )
    assertTrue(
      ServiceLifecyclePolicy.shouldDispatchStartForegroundService(
        settings = activeSettings,
        isBroadcastPaused = false,
        isServiceAlreadyForeground = false
      )
    )
    assertFalse(
      "Must not re-dispatch startForegroundService when already active in foreground",
      ServiceLifecyclePolicy.shouldDispatchStartForegroundService(
        settings = activeSettings,
        isBroadcastPaused = false,
        isServiceAlreadyForeground = true
      )
    )
    assertFalse(
      "Must not dispatch startForegroundService while broadcast is paused",
      ServiceLifecyclePolicy.shouldDispatchStartForegroundService(
        settings = activeSettings,
        isBroadcastPaused = true,
        isServiceAlreadyForeground = false
      )
    )
  }

  @Test
  fun cancelledOrPausedPollCycle_cannotEmitLateAnnouncementsAfterSwipeClose() {
    assertTrue(
      ServiceLifecyclePolicy.canEmitPollResults(
        isBroadcastPaused = false,
        cycleGeneration = 4L,
        activeGeneration = 4L,
        isCoroutineActive = true
      )
    )
    assertFalse(
      "Paused session must block late poll emission",
      ServiceLifecyclePolicy.canEmitPollResults(
        isBroadcastPaused = true,
        cycleGeneration = 4L,
        activeGeneration = 4L,
        isCoroutineActive = true
      )
    )
    assertFalse(
      "Stale generation after swipe-close/restart must block late poll emission",
      ServiceLifecyclePolicy.canEmitPollResults(
        isBroadcastPaused = false,
        cycleGeneration = 4L,
        activeGeneration = 5L,
        isCoroutineActive = true
      )
    )
    assertFalse(
      "Cancelled coroutine must block late poll emission",
      ServiceLifecyclePolicy.canEmitPollResults(
        isBroadcastPaused = false,
        cycleGeneration = 4L,
        activeGeneration = 4L,
        isCoroutineActive = false
      )
    )
  }

  @Test
  fun telemetrySnapshot_calculatesPollSuccessRateSafely() {
    val snap = TelemetrySnapshot(espnPollsOk = 18, espnPollsFailed = 2, mflPollsOk = 10, mflPollsFailed = 0)
    assertEquals(30L, snap.totalPolls)
    assertEquals(93, snap.pollSuccessRatePct)
  }
}
