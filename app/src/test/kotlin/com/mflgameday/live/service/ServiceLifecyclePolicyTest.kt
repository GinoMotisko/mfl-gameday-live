package com.mflgameday.live.service

import com.mflgameday.live.data.LenientJson
import com.mflgameday.live.data.MflStatusInfo
import com.mflgameday.live.data.RawMflFranchiseLive
import com.mflgameday.live.data.RawMflMatchup
import com.mflgameday.live.data.RawMflPlayerScore
import com.mflgameday.live.diagnostics.TelemetrySnapshot
import com.mflgameday.live.engine.BestBallAndPaceCalculator
import com.mflgameday.live.model.AnnouncementScope
import com.mflgameday.live.model.FantasyPlayer
import com.mflgameday.live.model.GameDaySettings
import com.mflgameday.live.model.GamePhase
import com.mflgameday.live.model.LeagueConfig
import com.mflgameday.live.model.NflGameState
import com.mflgameday.live.model.NflSide
import com.mflgameday.live.model.NflTeam
import com.mflgameday.live.model.OperatingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceLifecyclePolicyTest {

  @Test
  fun defaultSettings_stopsWhenSwipedClosed_andAnonymousFirebaseTelemetryCanBeOptedOut() {
    val defaults = GameDaySettings()
    assertTrue("Background service should default ON for Home/screen-off use", defaults.runInBackgroundService)
    assertTrue("Swiping closed from Recents should default to stopping voice & polling", defaults.stopWhenSwipedClosed)
    assertTrue("Anonymous Ad-ID-free Firebase crash/reliability telemetry defaults ON with user opt-out", defaults.optInAnonymousTelemetry)
    assertTrue(ServiceLifecyclePolicy.shouldStopOnTaskRemoved(defaults))
  }

  @Test
  fun legacySettingsJsonWithoutSwipeFlag_defaultsToStoppingOnTaskRemoved_andHonorsExplicitTelemetryOptOut() {
    val legacyJson = """{"operatingMode":"MFL_AND_NFL_LIVE","runInBackgroundService":true}"""
    val decoded = LenientJson.parser.decodeFromString(GameDaySettings.serializer(), legacyJson)
    assertTrue(decoded.runInBackgroundService)
    assertTrue(decoded.stopWhenSwipedClosed)
    assertTrue(decoded.optInAnonymousTelemetry)
    assertTrue(ServiceLifecyclePolicy.shouldStopOnTaskRemoved(decoded))

    val optedOutJson = """{"operatingMode":"MFL_AND_NFL_LIVE","optInAnonymousTelemetry":false}"""
    val optedOutDecoded = LenientJson.parser.decodeFromString(GameDaySettings.serializer(), optedOutJson)
    assertFalse(optedOutDecoded.optInAnonymousTelemetry)
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
    val now = 1_700_000_000_000L
    val liveGames = listOf(sampleGame("G1", GamePhase.LIVE))
    val activeSettings = GameDaySettings(
      operatingMode = OperatingMode.MFL_AND_NFL_LIVE,
      runInBackgroundService = true
    )
    assertTrue(
      ServiceLifecyclePolicy.shouldDispatchStartForegroundService(
        settings = activeSettings,
        isBroadcastPaused = false,
        isServiceAlreadyForeground = false,
        nflGames = liveGames,
        nowMillis = now
      )
    )
    assertFalse(
      "Must not re-dispatch startForegroundService when already active in foreground",
      ServiceLifecyclePolicy.shouldDispatchStartForegroundService(
        settings = activeSettings,
        isBroadcastPaused = false,
        isServiceAlreadyForeground = true,
        nflGames = liveGames,
        nowMillis = now
      )
    )
    assertFalse(
      "Must not dispatch startForegroundService while broadcast is paused",
      ServiceLifecyclePolicy.shouldDispatchStartForegroundService(
        settings = activeSettings,
        isBroadcastPaused = true,
        isServiceAlreadyForeground = false,
        nflGames = liveGames,
        nowMillis = now
      )
    )
  }

  @Test
  fun offHoursAndCompletedSlates_neverRunForegroundServiceOrBackgroundPoll() {
    val now = 1_700_000_000_000L
    val settings = GameDaySettings(
      operatingMode = OperatingMode.MFL_AND_NFL_LIVE,
      runInBackgroundService = true
    )

    // 1. Cold start / offline before scoreboard loads: never pop a foreground notification prematurely,
    //    but retain background retry/network-recovery polling until at least one scoreboard loads
    assertFalse(
      ServiceLifecyclePolicy.shouldRunForegroundService(
        settings = settings,
        isBroadcastPaused = false,
        nflGames = emptyList(),
        nowMillis = now
      )
    )
    assertTrue(
      "OS-recreated START_STICKY service must stay alive while initial scoreboard fetch is still in flight",
      ServiceLifecyclePolicy.shouldRunForegroundService(
        settings = settings,
        isBroadcastPaused = false,
        nflGames = emptyList(),
        nowMillis = now,
        preserveWhileSlateLoading = true
      )
    )
    assertTrue(
      "Must retain background retry loop when scoreboard has not yet loaded (offline/transient failure)",
      ServiceLifecyclePolicy.shouldKeepPollingInBackground(
        settings = settings,
        isBroadcastPaused = false,
        isAppInForeground = false,
        nflGames = emptyList(),
        nowMillis = now
      )
    )
    assertTrue(
      "Swiping closed before initial scoreboard loads (!isServiceActiveOrPending) must still cancel polling",
      ServiceLifecyclePolicy.shouldStopOnMainActivityDestroyed(
        settings = settings,
        isBroadcastPaused = false,
        isChangingConfigurations = false,
        isFinishing = false,
        isServiceActiveOrPending = false
      )
    )
    assertFalse(
      "RAM reclamation of backgrounded MainActivity must NOT kill an active GameDayLiveService",
      ServiceLifecyclePolicy.shouldStopOnMainActivityDestroyed(
        settings = settings,
        isBroadcastPaused = false,
        isChangingConfigurations = false,
        isFinishing = false,
        isServiceActiveOrPending = true
      )
    )
    assertEquals(
      60,
      ServiceLifecyclePolicy.computePollWaitSeconds(
        pollIntervalSeconds = 30,
        nflGames = emptyList(),
        nowMillis = now
      )
    )

    // 2. Thursday 8:28 AM when kickoff is 4 hours away (12:30 PM): outside 90m window -> NO service & NO background poll!
    val fourHoursAway = listOf(
      sampleGame("G_THU", GamePhase.SCHEDULED, kickoffEpochMillis = now + 4L * 60L * 60_000L)
    )
    assertFalse(
      ServiceLifecyclePolicy.isWithinActiveBroadcastWindow(
        operatingMode = OperatingMode.MFL_AND_NFL_LIVE,
        nflGames = fourHoursAway,
        nowMillis = now
      )
    )
    assertFalse(
      ServiceLifecyclePolicy.shouldRunForegroundService(
        settings = settings,
        isBroadcastPaused = false,
        nflGames = fourHoursAway,
        nowMillis = now
      )
    )
    assertFalse(
      "Background polling must halt completely when outside the 90-minute game window",
      ServiceLifecyclePolicy.shouldKeepPollingInBackground(
        settings = settings,
        isBroadcastPaused = false,
        isAppInForeground = false,
        nflGames = fourHoursAway,
        nowMillis = now
      )
    )
    assertTrue(
      "Foreground UI session may still refresh while open on screen",
      ServiceLifecyclePolicy.shouldKeepPollingInBackground(
        settings = settings,
        isBroadcastPaused = false,
        isAppInForeground = true,
        nflGames = fourHoursAway,
        nowMillis = now
      )
    )

    // 3. All games FINAL after Monday Night Football: auto-shutdown once Final call finishes speaking
    val allFinal = listOf(sampleGame("G_MNF", GamePhase.FINAL))
    assertFalse(
      ServiceLifecyclePolicy.shouldRunForegroundService(
        settings = settings,
        isBroadcastPaused = false,
        nflGames = allFinal,
        nowMillis = now,
        isSpeakingNow = false
      )
    )
    assertTrue(
      "Keep FGS alive for the few seconds TTS finishes speaking the Final Score call",
      ServiceLifecyclePolicy.shouldRunForegroundService(
        settings = settings,
        isBroadcastPaused = false,
        nflGames = allFinal,
        nowMillis = now,
        isSpeakingNow = true
      )
    )

    // 4. Inside 90-minute pregame window (e.g. 45m before kickoff, or 40m Sunday late -> SNF gap): active!
    val pregame45m = listOf(
      sampleGame("G_EARLY", GamePhase.FINAL),
      sampleGame("G_SNF", GamePhase.SCHEDULED, kickoffEpochMillis = now + 45L * 60_000L)
    )
    assertTrue(
      ServiceLifecyclePolicy.shouldRunForegroundService(
        settings = settings,
        isBroadcastPaused = false,
        nflGames = pregame45m,
        nowMillis = now
      )
    )
    assertTrue(
      ServiceLifecyclePolicy.shouldKeepPollingInBackground(
        settings = settings,
        isBroadcastPaused = false,
        isAppInForeground = false,
        nflGames = pregame45m,
        nowMillis = now
      )
    )

    // 5. Delayed kickoff (scheduled 20m ago, still SCHEDULED): stays active within 2h weather-delay grace
    val weatherDelayed = listOf(
      sampleGame("G_DELAY", GamePhase.SCHEDULED, kickoffEpochMillis = now - 20L * 60_000L)
    )
    assertTrue(
      ServiceLifecyclePolicy.isWithinActiveBroadcastWindow(
        operatingMode = OperatingMode.MFL_AND_NFL_LIVE,
        nflGames = weatherDelayed,
        nowMillis = now
      )
    )
  }

  @Test
  fun notificationSummary_suppressesStaleAndSimulatedPlaysWhenNoGamesAreLive() {
    val now = 1_700_000_000_000L
    // Simulated Baker Mayfield play or 6-hour-old play while 0 games are live must NOT show on shade
    val pregameSummary = ServiceLifecyclePolicy.buildNotificationSummary(
      appBrand = "MFL GameDay Live",
      operatingMode = OperatingMode.MFL_AND_NFL_LIVE,
      currentWeek = 12,
      muted = false,
      scope = AnnouncementScope.ALL_NFL,
      liveGamesCount = 0,
      matchupHeadline = "Beerfest: 0 – 0 vs Ditkaheads",
      latestPlaySpokenText = "Touchdown, Tampa Bay Buccaneers! Baker Mayfield, 94 yard touchdown pass to Chris Godwin!",
      latestPlayEventId = "sim_tb_td_12345",
      latestPlayTimestampMillis = now - 60_000L,
      nowMillis = now
    )
    assertEquals("Beerfest: 0 – 0 vs Ditkaheads", pregameSummary.title)
    assertEquals("Standing by for kickoff · Scope: ${AnnouncementScope.ALL_NFL.shortLabel}", pregameSummary.body)

    val staleRealPlaySummary = ServiceLifecyclePolicy.buildNotificationSummary(
      appBrand = "MFL GameDay Live",
      operatingMode = OperatingMode.MFL_AND_NFL_LIVE,
      currentWeek = 12,
      muted = false,
      scope = AnnouncementScope.MY_GAMES,
      liveGamesCount = 0,
      matchupHeadline = "Beerfest: 44 – 13 vs Ditkaheads",
      latestPlaySpokenText = "Touchdown, Tampa Bay Buccaneers! Baker Mayfield 5 yard rush.",
      latestPlayEventId = "401671_play_99_TOUCHDOWN",
      latestPlayTimestampMillis = now - 3L * 60L * 60_000L, // 3 hours old, 0 live games
      nowMillis = now
    )
    assertEquals("Standing by for kickoff · Scope: My Games", staleRealPlaySummary.body)

    // Live game active with real play -> shows live spoken play & identical signature deduplicates
    val liveSummary1 = ServiceLifecyclePolicy.buildNotificationSummary(
      appBrand = "MFL GameDay Live",
      operatingMode = OperatingMode.MFL_AND_NFL_LIVE,
      currentWeek = 12,
      muted = false,
      scope = AnnouncementScope.ALL_NFL,
      liveGamesCount = 3,
      matchupHeadline = "Beerfest: 44 – 13 vs Ditkaheads",
      latestPlaySpokenText = "Touchdown, Detroit Lions! Jahmyr Gibbs 12 yard rush.",
      latestPlayEventId = "401671_play_105_TOUCHDOWN",
      latestPlayTimestampMillis = now - 120_000L,
      nowMillis = now
    )
    val liveSummary2 = liveSummary1.copy()
    assertEquals("Touchdown, Detroit Lions! Jahmyr Gibbs 12 yard rush.", liveSummary1.body)
    assertEquals(liveSummary1.signature, liveSummary2.signature)
    assertTrue(ServiceLifecyclePolicy.isSimulatedPlayId("sim_tb_td_999"))
    assertTrue(ServiceLifecyclePolicy.isSimulatedPlayId("seed_3"))
    assertFalse(ServiceLifecyclePolicy.isSimulatedPlayId("401671_play_105_TOUCHDOWN"))

    // Poll interval pacing: live = user interval, pregame 45m = gentle 600s, imminent <60s = 30s, all-final = 900s
    assertEquals(
      30,
      ServiceLifecyclePolicy.computePollWaitSeconds(
        pollIntervalSeconds = 30,
        nflGames = listOf(sampleGame("G1", GamePhase.LIVE)),
        nowMillis = now
      )
    )
    assertEquals(
      600,
      ServiceLifecyclePolicy.computePollWaitSeconds(
        pollIntervalSeconds = 30,
        nflGames = listOf(sampleGame("G2", GamePhase.SCHEDULED, kickoffEpochMillis = now + 45L * 60_000L)),
        nowMillis = now
      )
    )
    assertEquals(
      30,
      ServiceLifecyclePolicy.computePollWaitSeconds(
        pollIntervalSeconds = 30,
        nflGames = listOf(sampleGame("G3", GamePhase.SCHEDULED, kickoffEpochMillis = now + 30_000L)),
        nowMillis = now
      )
    )
    assertEquals(
      900,
      ServiceLifecyclePolicy.computePollWaitSeconds(
        pollIntervalSeconds = 30,
        nflGames = listOf(sampleGame("G4", GamePhase.FINAL)),
        nowMillis = now
      )
    )
  }

  private fun sampleGame(
    id: String,
    phase: GamePhase,
    kickoffEpochMillis: Long? = null
  ): NflGameState = NflGameState(
    id = id,
    away = NflSide("1", NflTeam("TBB"), "Tampa Bay", "Buccaneers", 14),
    home = NflSide("2", NflTeam("DET"), "Detroit", "Lions", 17),
    phase = phase,
    kickoffEpochMillis = kickoffEpochMillis
  )

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
    // All games FINAL: debounce ultra-rapid (<15s) OS focus churn, wake promptly once >=15s elapsed
    assertFalse(
      "Must debounce rapid <15s foreground churn right after all games turn FINAL",
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
    assertTrue(
      "All displayed games FINAL and >=15s since last poll -> wake promptly for post-MNF week rollover check",
      ServiceLifecyclePolicy.shouldWakeActivePollOnAppForeground(
        settings = activeSettings,
        isBroadcastPaused = false,
        isPollLoopActive = true,
        isRefreshing = false,
        lastUpdatedMillis = 180_000L,
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

    assertEquals(
      "Must hold Week 12 even during a temporary ESPN outage within the same season",
      12,
      ServiceLifecyclePolicy.resolveEffectiveWeek(
        operatingMode = OperatingMode.MFL_AND_NFL_LIVE,
        currentUiWeek = 12,
        mflStatus = laggingStatus,
        espnSeasonType = null,
        espnWeekNumber = null,
        previousGamesAllFinal = false,
        sameSeasonAsUi = true
      )
    )
    assertEquals(
      "Must allow new season year (2027 Week 1) to reset from prior season Week 22",
      1,
      ServiceLifecyclePolicy.resolveEffectiveWeek(
        operatingMode = OperatingMode.MFL_AND_NFL_LIVE,
        currentUiWeek = 22,
        mflStatus = MflStatusInfo(2027, currentWeek = 1, liveScoringWeek = 1, upcomingWeek = 1, lineupWeek = 1, completedWeek = 0),
        espnSeasonType = null,
        espnWeekNumber = null,
        previousGamesAllFinal = false,
        sameSeasonAsUi = false
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

  @Test
  fun windowFocusAutoResume_andLocalPregameWakeAlarm_handleShadeOverlayAndSundayStandby() {
    val now = 1_700_000_000_000L
    val liveMode = GameDaySettings(
      operatingMode = OperatingMode.MFL_AND_NFL_LIVE,
      runInBackgroundService = true
    )

    // 1. Non-explicit pause (e.g. OS / shade overlay) auto-resumes when app window regains focus
    assertTrue(
      ServiceLifecyclePolicy.shouldAutoResumeOnWindowFocus(
        settings = liveMode,
        isBroadcastPaused = true,
        pausedExplicitlyByUser = false,
        isPollLoopActive = false
      )
    )
    // 2. Explicit user Stop / Pause must NOT auto-resume merely on window focus change
    assertFalse(
      ServiceLifecyclePolicy.shouldAutoResumeOnWindowFocus(
        settings = liveMode,
        isBroadcastPaused = true,
        pausedExplicitlyByUser = true,
        isPollLoopActive = false
      )
    )

    // 3. Sunday 9:00 AM with 1:00 PM ET kickoff (4h away) schedules local wakeup at 11:30 AM (kickoff - 90m)
    val kickoffIn4Hours = now + 4L * 60L * 60_000L
    val scheduledGames = listOf(
      sampleGame("G_1PM", GamePhase.SCHEDULED, kickoffEpochMillis = kickoffIn4Hours)
    )
    assertEquals(
      kickoffIn4Hours - ServiceLifecyclePolicy.PRE_KICKOFF_STANDBY_WINDOW_MS,
      ServiceLifecyclePolicy.computeNextPregameWakeEpochMillis(
        settings = liveMode,
        isBroadcastPaused = false,
        nflGames = scheduledGames,
        nowMillis = now
      )
    )

    // 4. Inside 90m window, paused, or Demo Simulator -> null (no redundant alarm)
    val inside90m = listOf(
      sampleGame("G_SOON", GamePhase.SCHEDULED, kickoffEpochMillis = now + 45L * 60_000L)
    )
    assertEquals(
      null,
      ServiceLifecyclePolicy.computeNextPregameWakeEpochMillis(
        settings = liveMode,
        isBroadcastPaused = false,
        nflGames = inside90m,
        nowMillis = now
      )
    )
    assertEquals(
      null,
      ServiceLifecyclePolicy.computeNextPregameWakeEpochMillis(
        settings = liveMode,
        isBroadcastPaused = true,
        nflGames = scheduledGames,
        nowMillis = now
      )
    )
  }
}
