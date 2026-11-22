package com.mflgameday.live.service

import com.mflgameday.live.data.LenientJson
import com.mflgameday.live.model.GameDaySettings
import com.mflgameday.live.model.OperatingMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceLifecyclePolicyTest {

  @Test
  fun defaultSettings_stopsWhenSwipedClosed_whileKeepingBackgroundServiceEnabled() {
    val defaults = GameDaySettings()
    assertTrue("Background service should default ON for Home/screen-off use", defaults.runInBackgroundService)
    assertTrue("Swiping closed from Recents should default to stopping voice & polling", defaults.stopWhenSwipedClosed)
    assertTrue(ServiceLifecyclePolicy.shouldStopOnTaskRemoved(defaults))
  }

  @Test
  fun legacySettingsJsonWithoutSwipeFlag_defaultsToStoppingOnTaskRemoved() {
    val legacyJson = """{"operatingMode":"MFL_AND_NFL_LIVE","runInBackgroundService":true}"""
    val decoded = LenientJson.parser.decodeFromString(GameDaySettings.serializer(), legacyJson)
    assertTrue(decoded.runInBackgroundService)
    assertTrue(decoded.stopWhenSwipedClosed)
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
      "Reopening app after swipe-close pause must auto-resume live polling",
      ServiceLifecyclePolicy.shouldAutoResumeOnAppForeground(
        settings = activeSettings,
        isBroadcastPaused = true,
        isPollLoopActive = false
      )
    )
    assertFalse(
      "Unconfigured app must not auto-start polling before setup",
      ServiceLifecyclePolicy.shouldAutoResumeOnAppForeground(
        settings = GameDaySettings(operatingMode = OperatingMode.UNCONFIGURED),
        isBroadcastPaused = true,
        isPollLoopActive = false
      )
    )
    assertFalse(
      "Already active polling loop should not duplicate on configuration/foreground events",
      ServiceLifecyclePolicy.shouldAutoResumeOnAppForeground(
        settings = activeSettings,
        isBroadcastPaused = false,
        isPollLoopActive = true
      )
    )
  }
}
