package com.mflgameday.live.service

import com.mflgameday.live.data.LenientJson
import com.mflgameday.live.data.RawMflFranchiseLive
import com.mflgameday.live.data.RawMflMatchup
import com.mflgameday.live.data.RawMflPlayerScore
import com.mflgameday.live.diagnostics.TelemetrySnapshot
import com.mflgameday.live.engine.BestBallAndPaceCalculator
import com.mflgameday.live.model.FantasyPlayer
import com.mflgameday.live.model.LeagueConfig
import com.mflgameday.live.model.NflTeam
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

  @Test
  fun benchInsights_ignoreUnplayedScheduledStarters_andRespectZeroProjectionWhenFeedPresent() {
    val cfg = LeagueConfig(
      leagueId = "10479",
      leagueName = "Wyoming Valley",
      year = 2026,
      franchiseId = "0001",
      franchiseName = "The Meth Lab All-Stars",
      baseUrl = "https://www48.myfantasyleague.com",
      isBestBall = false
    )
    val playersDb = mapOf(
      "qb_daniels" to FantasyPlayer("qb_daniels", "Jayden Daniels", "QB", NflTeam("WAS")),
      "qb_mayfield" to FantasyPlayer("qb_mayfield", "Baker Mayfield", "QB", NflTeam("TBB")),
      "qb_inactive" to FantasyPlayer("qb_inactive", "Inactive Backup", "QB", NflTeam("CLE"))
    )
    // League projections present: Jayden Daniels projected 22.5, Inactive Backup omitted (0.0)
    val projections = mapOf("qb_daniels" to 22.5, "qb_mayfield" to 19.0)

    val rawMatchup = RawMflMatchup(
      franchises = listOf(
        RawMflFranchiseLive(
          franchiseId = "0001",
          score = 0.0,
          isHome = false,
          playersCurrentlyPlaying = 0,
          playersYetToPlay = 1,
          gameSecondsRemaining = 3600,
          players = listOf(
            // Unplayed starter (3600s remaining)
            RawMflPlayerScore("qb_daniels", score = 0.0, isStarter = true, gameSecondsRemaining = 3600),
            // Finished bench QB (0s remaining, scored 21.0)
            RawMflPlayerScore("qb_mayfield", score = 21.0, isStarter = false, gameSecondsRemaining = 0)
          )
        ),
        RawMflFranchiseLive(
          franchiseId = "0002",
          score = 0.0,
          isHome = true,
          playersCurrentlyPlaying = 0,
          playersYetToPlay = 1,
          gameSecondsRemaining = 3600,
          players = listOf(
            RawMflPlayerScore("qb_inactive", score = 0.0, isStarter = true, gameSecondsRemaining = 3600)
          )
        )
      )
    )

    val snap = BestBallAndPaceCalculator.buildLeagueSnapshot(
      config = cfg,
      week = 11,
      franchisesMap = emptyMap(),
      rawMatchups = listOf(rawMatchup),
      playersDb = playersDb,
      projections = projections,
      breakdowns = emptyMap(),
      previousSnapshot = null,
      nowMillis = 1000L
    )

    val m = snap.matchups.single()
    assertTrue("Must not flag Bench Outscored Starter when starter hasn't played yet", m.away.benchInsights.isEmpty())
    assertEquals(22.5, m.away.projectedScore, 0.01)
    assertEquals("Omitted/inactive player must get 0.0 projection, not 18.5 fallback", 0.0, m.home.projectedScore, 0.01)
  }
}
