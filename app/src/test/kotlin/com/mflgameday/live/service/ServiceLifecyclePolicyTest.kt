package com.mflgameday.live.service

import com.mflgameday.live.data.LenientJson
import com.mflgameday.live.data.MflStatusInfo
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

  @Test
  fun foregroundReturnWhilePolling_wakesStaleOrFinishedSlateImmediately() {
    val activeSettings = GameDaySettings(operatingMode = OperatingMode.MFL_AND_NFL_LIVE)
    // User backgrounded app Monday night and reopened Wednesday (stale >= 45s while loop sleeping)
    assertTrue(
      ServiceLifecyclePolicy.shouldWakeActivePollOnAppForeground(
        settings = activeSettings,
        isBroadcastPaused = false,
        isPollLoopActive = true,
        isRefreshing = false,
        lastUpdatedMillis = 100_000L,
        nowMillis = 200_000L,
        allGamesFinal = false
      )
    )
    // All displayed games from previous week are FINAL -> wake immediately even if recently polled
    assertTrue(
      ServiceLifecyclePolicy.shouldWakeActivePollOnAppForeground(
        settings = activeSettings,
        isBroadcastPaused = false,
        isPollLoopActive = true,
        isRefreshing = false,
        lastUpdatedMillis = 195_000L,
        nowMillis = 200_000L,
        allGamesFinal = true
      )
    )
    // Freshly updated 5s ago with active/scheduled games -> do not spam duplicate network poll
    assertFalse(
      ServiceLifecyclePolicy.shouldWakeActivePollOnAppForeground(
        settings = activeSettings,
        isBroadcastPaused = false,
        isPollLoopActive = true,
        isRefreshing = false,
        lastUpdatedMillis = 195_000L,
        nowMillis = 200_000L,
        allGamesFinal = false
      )
    )
    // Demo simulator or paused session should not wake live network poll here
    assertFalse(
      ServiceLifecyclePolicy.shouldWakeActivePollOnAppForeground(
        settings = GameDaySettings(operatingMode = OperatingMode.DEMO_SIMULATOR),
        isBroadcastPaused = false,
        isPollLoopActive = true,
        isRefreshing = false,
        lastUpdatedMillis = 0L,
        nowMillis = 200_000L,
        allGamesFinal = true
      )
    )
  }

  @Test
  fun weekResolution_autoAdvancesBothNflAndMflWithoutSplitBrain() {
    // Case A: Mid-week MFL status where CompletedWeek == LiveScoringWeek (11) and Current/Upcoming == 12
    val midWeekStatus = MflStatusInfo(
      year = 2026,
      currentWeek = 12,
      liveScoringWeek = 11,
      upcomingWeek = 12,
      lineupWeek = 12,
      completedWeek = 11
    )
    assertEquals(12, midWeekStatus.effectiveActiveWeek)
    assertEquals(
      12,
      ServiceLifecyclePolicy.resolveEffectiveWeek(
        operatingMode = OperatingMode.MFL_AND_NFL_LIVE,
        currentUiWeek = 11,
        mflStatus = midWeekStatus,
        espnSeasonType = 2,
        espnWeekNumber = 12,
        previousGamesAllFinal = true
      )
    )

    // Case B: Wednesday morning where all Week 11 games are FINAL and ESPN rolled to Week 12
    // even if MFL CompletedWeek lags briefly at 10 — AND Cycle 2 holds Week 12 once SCHEDULED games load!
    val laggingStatus = MflStatusInfo(
      year = 2026,
      currentWeek = 11,
      liveScoringWeek = 11,
      upcomingWeek = 12,
      lineupWeek = 12,
      completedWeek = 10
    )
    assertEquals(
      12,
      ServiceLifecyclePolicy.resolveEffectiveWeek(
        operatingMode = OperatingMode.MFL_AND_NFL_LIVE,
        currentUiWeek = 11,
        mflStatus = laggingStatus,
        espnSeasonType = 2,
        espnWeekNumber = 12,
        previousGamesAllFinal = true
      )
    )
    assertEquals(
      "Must hold Week 12 on subsequent poll cycles after Week 12 SCHEDULED games replace FINAL games",
      12,
      ServiceLifecyclePolicy.resolveEffectiveWeek(
        operatingMode = OperatingMode.MFL_AND_NFL_LIVE,
        currentUiWeek = 12,
        mflStatus = laggingStatus,
        espnSeasonType = 2,
        espnWeekNumber = 12,
        previousGamesAllFinal = false
      )
    )

    // Case C: Monday Night Football still active/scheduled (previousGamesAllFinal == false, currentUiWeek == 11) -> stay on Week 11!
    assertEquals(
      11,
      ServiceLifecyclePolicy.resolveEffectiveWeek(
        operatingMode = OperatingMode.MFL_AND_NFL_LIVE,
        currentUiWeek = 11,
        mflStatus = laggingStatus,
        espnSeasonType = 2,
        espnWeekNumber = 12,
        previousGamesAllFinal = false
      )
    )

    // Case D: NFL Postseason Wild Card (1->19) & Super Bowl (seasonType = 3, week = 5 -> capped at MFL Week 22!)
    assertEquals(
      19,
      ServiceLifecyclePolicy.resolveEffectiveWeek(
        operatingMode = OperatingMode.NFL_LIVE_ONLY,
        currentUiWeek = 18,
        mflStatus = null,
        espnSeasonType = 3,
        espnWeekNumber = 1,
        previousGamesAllFinal = true
      )
    )
    assertEquals(
      22,
      ServiceLifecyclePolicy.resolveEffectiveWeek(
        operatingMode = OperatingMode.MFL_AND_NFL_LIVE,
        currentUiWeek = 22,
        mflStatus = MflStatusInfo(2025, currentWeek = 23, liveScoringWeek = 22, upcomingWeek = 23, lineupWeek = 23, completedWeek = 22),
        espnSeasonType = 3,
        espnWeekNumber = 5,
        previousGamesAllFinal = true
      )
    )

    // Case E: ESPN Offseason (seasonType = 4, week = 1) must NEVER reset post-Super-Bowl Week 22 back to Week 1
    assertEquals(
      22,
      ServiceLifecyclePolicy.resolveEffectiveWeek(
        operatingMode = OperatingMode.NFL_LIVE_ONLY,
        currentUiWeek = 22,
        mflStatus = MflStatusInfo(2025, currentWeek = 23, liveScoringWeek = 22, upcomingWeek = 23, lineupWeek = 23, completedWeek = 22),
        espnSeasonType = 4,
        espnWeekNumber = 1,
        previousGamesAllFinal = true
      )
    )
    assertEquals(2 to 12, ServiceLifecyclePolicy.toEspnTargetSeasonTypeAndWeek(12))
    assertEquals(3 to 1, ServiceLifecyclePolicy.toEspnTargetSeasonTypeAndWeek(19))
    assertEquals(3 to 5, ServiceLifecyclePolicy.toEspnTargetSeasonTypeAndWeek(22))
  }
}
