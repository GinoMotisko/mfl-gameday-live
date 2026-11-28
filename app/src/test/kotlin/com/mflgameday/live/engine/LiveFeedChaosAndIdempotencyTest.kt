package com.mflgameday.live.engine

import com.mflgameday.live.model.FantasyMatchup
import com.mflgameday.live.model.FantasyPlayer
import com.mflgameday.live.model.FranchiseMatchupSide
import com.mflgameday.live.model.GameEvent
import com.mflgameday.live.model.GameEventKind
import com.mflgameday.live.model.GamePhase
import com.mflgameday.live.model.LiveFieldSituation
import com.mflgameday.live.model.NflGameState
import com.mflgameday.live.model.NflSide
import com.mflgameday.live.model.NflTeam
import com.mflgameday.live.model.PlayLogEntry
import com.mflgameday.live.model.PlayerLiveScore
import com.mflgameday.live.service.ServiceLifecyclePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial chaos & idempotency tests modeling live NFL/MFL production wire hazards:
 * - Multi-edge CDN replica flapping after Q4 0:00 (`FINAL <-> LIVE`)
 * - Hotel Wi-Fi failover between ESPN IDs (`401873083`) and MFL IDs (`mfl_DEN_PIT_...`)
 * - App restart / Play update seeded from persisted `PlayLogEntry`
 * - Transient 1-poll CDN score dips vs confirmed 2-poll booth review score corrections
 * - Red Zone 20-yard-line penalty jitter vs opposing-team turnover Red Zone alerts
 * - MFL replica echo suppression on `FANTASY_LEAD_CHANGE`
 */
class LiveFeedChaosAndIdempotencyTest {

  private val t0 = 1_795_827_120_000L

  private fun denPitGame(
    id: String = "401873083",
    awayId: String = "7",
    homeId: String = "23",
    awayScore: Int = 5,
    homeScore: Int = 27,
    phase: GamePhase = GamePhase.LIVE,
    period: Int = 4,
    clock: String = "0:00",
    possession: String? = null,
    redZone: Boolean = false,
    isLiveDown: Boolean = false
  ): NflGameState = NflGameState(
    id = id,
    away = NflSide(awayId, NflTeam("DEN"), "Denver", "Broncos", awayScore),
    home = NflSide(homeId, NflTeam("PIT"), "Pittsburgh", "Steelers", homeScore),
    phase = phase,
    period = period,
    clock = clock,
    possessionEspnId = possession,
    inRedZone = redZone,
    isLiveDown = isLiveDown
  )

  @Test
  fun espnCdnReplicaFlapping_liveToFinalTenTimes_emitsFinalStrictlyOnceAndLatchesFinalPhase() {
    val differ = EventDiffer()
    val liveEnd = denPitGame(phase = GamePhase.LIVE, clock = "0:02", possession = "7", isLiveDown = true)
    assertTrue(differ.ingestScoreboard(listOf(liveEnd), t0).isEmpty())

    val emittedEvents = ArrayList<GameEvent>()
    val finalSnap = denPitGame(phase = GamePhase.FINAL, clock = "0:00")
    val staleLiveReplica = denPitGame(phase = GamePhase.LIVE, clock = "0:00")

    for (cycle in 1..10) {
      val incoming = if (cycle % 2 == 1) finalSnap else staleLiveReplica
      val now = t0 + cycle * 30_000L
      emittedEvents.addAll(differ.ingestScoreboard(listOf(incoming), now))

      val stabilized = differ.stabilizedGames(listOf(incoming))
      assertEquals(
        "Game must remain latched FINAL even when CDN edge #$cycle serves stale LIVE",
        GamePhase.FINAL,
        stabilized.single().phase
      )
      assertEquals(900, ServiceLifecyclePolicy.computePollWaitSeconds(30, stabilized, now))
      assertFalse(
        ServiceLifecyclePolicy.isWithinActiveBroadcastWindow(
          com.mflgameday.live.model.OperatingMode.MFL_AND_NFL_LIVE,
          stabilized,
          now
        )
      )
    }

    val finalCalls = emittedEvents.filter { it.kind == GameEventKind.FINAL }
    assertEquals("FINAL must be announced strictly once across 10 CDN flaps", 1, finalCalls.size)
  }

  @Test
  fun coldStartAlreadyFinalThenStaleLiveReplica_emitsZeroFinalEvents() {
    val differ = EventDiffer()
    val firstSeenFinal = denPitGame(phase = GamePhase.FINAL, clock = "0:00")
    val staleLive = denPitGame(phase = GamePhase.LIVE, clock = "0:00")
    val recoveredFinal = denPitGame(phase = GamePhase.FINAL, clock = "0:00")

    assertTrue(differ.ingestScoreboard(listOf(firstSeenFinal), t0).isEmpty())
    assertTrue(differ.ingestScoreboard(listOf(staleLive), t0 + 30_000L).isEmpty())
    assertTrue(differ.ingestScoreboard(listOf(recoveredFinal), t0 + 60_000L).isEmpty())
    assertEquals(GamePhase.FINAL, differ.stabilizedGames(listOf(staleLive)).single().phase)
  }

  @Test
  fun appRestartSeededFromPlayLog_neverReAnnouncesFinalHalftimeOrKickoffEvenAcrossProviderFailover() {
    val game = denPitGame(phase = GamePhase.FINAL, clock = "0:00")
    val savedLog = listOf(
      PlayLogEntry(
        id = "log_final",
        timestampMillis = t0,
        event = GameEvent(
          id = "401873083_FINAL_$t0",
          kind = GameEventKind.FINAL,
          game = game,
          awayScore = 5,
          homeScore = 27,
          observedAtMillis = t0
        ),
        spokenText = "Final, Denver Broncos at Pittsburgh Steelers. Pittsburgh Steelers win 27 to 5.",
        wasSpoken = true
      ),
      PlayLogEntry(
        id = "log_half",
        timestampMillis = t0 - 3_600_000L,
        event = GameEvent(
          id = "401873083_HALFTIME_${t0 - 3_600_000L}",
          kind = GameEventKind.HALFTIME,
          game = game.copy(phase = GamePhase.HALFTIME),
          awayScore = 5,
          homeScore = 24,
          observedAtMillis = t0 - 3_600_000L
        ),
        spokenText = "Halftime",
        wasSpoken = true
      ),
      PlayLogEntry(
        id = "log_ko",
        timestampMillis = t0 - 9_000_000L,
        event = GameEvent(
          id = "401873083_KICKOFF_${t0 - 9_000_000L}",
          kind = GameEventKind.KICKOFF,
          game = game.copy(phase = GamePhase.LIVE),
          awayScore = 0,
          homeScore = 0,
          observedAtMillis = t0 - 9_000_000L
        ),
        spokenText = "Kickoff",
        wasSpoken = true
      )
    )

    val restartedDiffer = EventDiffer()
    restartedDiffer.seedSeenFromLog(savedLog)

    // Poll 1 after restart hits stale ESPN LIVE edge; Poll 2 hits FINAL; Poll 3 fails over to MFL nfl_sched
    val e1 = restartedDiffer.ingestScoreboard(listOf(denPitGame(phase = GamePhase.LIVE, clock = "0:00")), t0 + 60_000L)
    val e2 = restartedDiffer.ingestScoreboard(listOf(denPitGame(phase = GamePhase.FINAL, clock = "0:00")), t0 + 90_000L)
    val e3 = restartedDiffer.ingestScoreboard(
      listOf(denPitGame(id = "mfl_DEN_PIT_1795809600", awayId = "DEN", homeId = "PIT", phase = GamePhase.FINAL)),
      t0 + 120_000L
    )

    assertTrue(e1.isEmpty())
    assertTrue(e2.isEmpty())
    assertTrue(e3.isEmpty())
  }

  @Test
  fun hotelWifiEspnToMflFailoverMidGame_preservesStateWithoutDuplicateKickoffRedZoneOrFinal() {
    val differ = EventDiffer()
    // 1. Pregame on ESPN
    assertTrue(
      differ.ingestScoreboard(
        listOf(denPitGame(awayScore = 0, homeScore = 0, phase = GamePhase.SCHEDULED, period = 0, clock = "")),
        t0
      ).isEmpty()
    )

    // 2. Kickoff on ESPN
    val kickoffEvents = differ.ingestScoreboard(
      listOf(denPitGame(awayScore = 0, homeScore = 0, phase = GamePhase.LIVE, period = 1, clock = "15:00", possession = "23", isLiveDown = true)),
      t0 + 30_000L
    )
    assertEquals(listOf(GameEventKind.KICKOFF), kickoffEvents.map { it.kind })

    // 3. Steelers enter Red Zone on ESPN (`possessionEspnId = "23"`)
    val rzEvents = differ.ingestScoreboard(
      listOf(denPitGame(awayScore = 0, homeScore = 0, phase = GamePhase.LIVE, period = 1, clock = "10:12", possession = "23", redZone = true, isLiveDown = true)),
      t0 + 120_000L
    )
    assertEquals(listOf(GameEventKind.RED_ZONE), rzEvents.map { it.kind })

    // 4. Wi-Fi drops ESPN -> falls back to MFL `nfl_sched` and advances score to 7-14
    val mflFailoverLive = denPitGame(
      id = "mfl_DEN_PIT_1795809600",
      awayId = "DEN",
      homeId = "PIT",
      awayScore = 7,
      homeScore = 14,
      phase = GamePhase.LIVE,
      period = 2,
      clock = "4:15",
      possession = "PIT",
      redZone = true,
      isLiveDown = true
    )
    val failoverEvents = differ.ingestScoreboard(
      listOf(mflFailoverLive),
      t0 + 150_000L,
      emitFallbackScoreEvents = false
    )
    assertTrue("Provider failover must not duplicate KICKOFF or RED_ZONE", failoverEvents.isEmpty())

    // 4b. ESPN recovers with its own game ID (`401873083`) at the same 7-14 score -> must read latest matchupKey state (7-14), not stale Poll 3 (0-0)!
    val espnRecoverMidGame = denPitGame(
      awayScore = 7,
      homeScore = 14,
      phase = GamePhase.LIVE,
      period = 2,
      clock = "4:15",
      possession = "23",
      redZone = true,
      isLiveDown = true
    )
    val recoverMidEvents = differ.ingestScoreboard(listOf(espnRecoverMidGame), t0 + 180_000L, emitFallbackScoreEvents = true)
    assertTrue("ESPN recovery after MFL failover must not fabricate score jump or duplicate RED_ZONE", recoverMidEvents.isEmpty())

    // 5. Final on MFL, then ESPN recovers with FINAL -> strictly 1 FINAL call total
    val mflFinalEvents = differ.ingestScoreboard(
      listOf(denPitGame(id = "mfl_DEN_PIT_1795809600", awayId = "DEN", homeId = "PIT", awayScore = 7, homeScore = 27, phase = GamePhase.FINAL)),
      t0 + 600_000L
    )
    assertEquals(listOf(GameEventKind.FINAL), mflFinalEvents.map { it.kind })

    val espnRecoverFinalEvents = differ.ingestScoreboard(
      listOf(denPitGame(awayScore = 7, homeScore = 27, phase = GamePhase.FINAL)),
      t0 + 630_000L
    )
    assertTrue("ESPN recovery after MFL FINAL must not emit duplicate FINAL", espnRecoverFinalEvents.isEmpty())

    // 6. Weekly rollover reset unlocks same teams for a future playoff/rematch week
    differ.reset()
    assertTrue(differ.ingestScoreboard(listOf(denPitGame(awayScore = 0, homeScore = 0, phase = GamePhase.SCHEDULED, period = 0, clock = "")), t0 + 9_000_000L).isEmpty())
    val nextWeekKo = differ.ingestScoreboard(listOf(denPitGame(awayScore = 0, homeScore = 0, phase = GamePhase.LIVE, period = 1, clock = "15:00", isLiveDown = true)), t0 + 9_030_000L)
    assertEquals(listOf(GameEventKind.KICKOFF), nextWeekKo.map { it.kind })
  }

  @Test
  fun singlePollCdnScoreDip_isIgnored_whileConfirmedTwoPollBoothReviewEmitsSingleScoreCorrection() {
    val differ = EventDiffer()
    val s21 = denPitGame(awayScore = 21, homeScore = 14, phase = GamePhase.LIVE, period = 3, clock = "6:00", isLiveDown = true)
    assertTrue(differ.ingestScoreboard(listOf(s21), t0).isEmpty())

    // Poll 2: 1-poll stale CDN dip back to 14-14 -> held, zero SCORE_CORRECTION
    val s14 = s21.copy(away = s21.away.copy(score = 14))
    val dipEvents = differ.ingestScoreboard(listOf(s14), t0 + 30_000L, emitFallbackScoreEvents = true)
    assertTrue(dipEvents.isEmpty())
    assertEquals(21, differ.stabilizedGames(listOf(s14)).single().away.score)

    // Poll 3: CDN recovers back to 21-14 -> zero phantom TD event
    val recoverEvents = differ.ingestScoreboard(listOf(s21), t0 + 60_000L, emitFallbackScoreEvents = true)
    assertTrue(recoverEvents.isEmpty())

    // Poll 4 + 5: genuine 2-poll booth review overturns TD to 14-14
    assertTrue(differ.ingestScoreboard(listOf(s14), t0 + 90_000L, emitFallbackScoreEvents = true).isEmpty())
    assertTrue("Pending score correction must bypass MFL 304 ETag cache on next poll", differ.hasPendingScoreCorrections())
    val confirmedEvents = differ.ingestScoreboard(listOf(s14), t0 + 120_000L, emitFallbackScoreEvents = true)
    assertEquals(listOf(GameEventKind.SCORE_CORRECTION), confirmedEvents.map { it.kind })
    assertEquals(14, differ.stabilizedGames(listOf(s14)).single().away.score)
    assertFalse(differ.hasPendingScoreCorrections())

    // Simultaneous MFL fallback TD by both teams in the same poll must produce distinct IDs so neither team is dropped
    val bothScored = s14.copy(
      away = s14.away.copy(score = 21),
      home = s14.home.copy(score = 21)
    )
    val bothEvents = differ.ingestScoreboard(listOf(bothScored), t0 + 180_000L, emitFallbackScoreEvents = true)
    assertEquals(2, bothEvents.size)
    assertEquals(2, bothEvents.map { it.id }.toSet().size)
  }

  @Test
  fun redZoneLineOfScrimmageJitterWithin90s_doesNotSpamDuplicateRedZone_butNewOpponentDriveAlertsImmediately() {
    val differ = EventDiffer(redZoneRepeatCooldownMillis = 90_000L)
    assertTrue(
      differ.ingestScoreboard(
        listOf(denPitGame(possession = "23", redZone = false, isLiveDown = true)),
        t0
      ).isEmpty()
    )

    // Steelers cross 20 -> alerts once
    assertEquals(
      listOf(GameEventKind.RED_ZONE),
      differ.ingestScoreboard(listOf(denPitGame(possession = "23", redZone = true, isLiveDown = true)), t0 + 10_000L).map { it.kind }
    )
    // False start pushes Steelers back to 22 -> exits RZ, then 15-yd pass re-enters RZ 30s later -> suppressed by 90s cooldown
    assertTrue(
      differ.ingestScoreboard(listOf(denPitGame(possession = "23", redZone = false, isLiveDown = true)), t0 + 25_000L).isEmpty()
    )
    assertTrue(
      differ.ingestScoreboard(listOf(denPitGame(possession = "23", redZone = true, isLiveDown = true)), t0 + 40_000L).isEmpty()
    )

    // Turnover: Denver now drives inside Pittsburgh 20 -> immediate RED_ZONE for DEN!
    val denRz = differ.ingestScoreboard(
      listOf(denPitGame(possession = "7", redZone = true, isLiveDown = true)),
      t0 + 60_000L
    )
    assertEquals(listOf(GameEventKind.RED_ZONE), denRz.map { it.kind })
    assertEquals("DEN", denRz.single().team?.team?.code)
  }

  @Test
  fun fantasyLeadChangeReplicaEcho_suppressesIdenticalScoreFlipWithinCooldown_whileAllowingNewScoreLeadFlip() {
    val differ = EventDiffer(fantasyEchoCooldownMillis = 180_000L)

    fun matchup(awayScore: Double, homeScore: Double) = listOf(
      FantasyMatchup(
        id = "0001_0002",
        leagueId = "10479",
        away = FranchiseMatchupSide(
          franchiseId = "0001",
          franchiseName = "Alpha",
          score = awayScore,
          projectedScore = 110.0,
          isHome = false,
          playersCurrentlyPlaying = 1,
          playersYetToPlay = 0,
          playersFinal = 8,
          starters = emptyList<PlayerLiveScore>(),
          bench = emptyList()
        ),
        home = FranchiseMatchupSide(
          franchiseId = "0002",
          franchiseName = "Beta",
          score = homeScore,
          projectedScore = 110.0,
          isHome = true,
          playersCurrentlyPlaying = 1,
          playersYetToPlay = 0,
          playersFinal = 8,
          starters = emptyList(),
          bench = emptyList()
        ),
        involvesMyFranchise = true
      )
    )

    // Seed initial leader (0001: 100.80 vs 101.10 -> 0002 leads; wait let 0001 start ahead 101.20 vs 100.50)
    assertTrue(differ.ingestFantasyMatchups("WVC", "0001", matchup(101.20, 100.50), precision = 2, nowMillis = t0).isEmpty())

    // 1. 0002 pulls ahead 101.20 vs 101.50 -> alerts #1
    val flip1 = differ.ingestFantasyMatchups("WVC", "0001", matchup(101.20, 101.50), precision = 2, nowMillis = t0 + 10_000L)
    assertEquals(1, flip1.size)

    // 2. Stale MFL node returns 101.20 vs 100.50 -> 0001 takes lead
    val flipBack = differ.ingestFantasyMatchups("WVC", "0001", matchup(101.20, 100.50), precision = 2, nowMillis = t0 + 20_000L)
    assertEquals(1, flipBack.size)

    // 3. Exact same 101.20 vs 101.50 score echoes back 10s later -> suppressed as replica echo!
    val echoSuppressed = differ.ingestFantasyMatchups("WVC", "0001", matchup(101.20, 101.50), precision = 2, nowMillis = t0 + 30_000L)
    assertTrue("Identical MFL lead echo within cooldown must be suppressed", echoSuppressed.isEmpty())

    // 4. After another lag to 0001 leading 101.20 vs 100.90, genuinely new score 101.20 vs 105.60 alerts!
    differ.ingestFantasyMatchups("WVC", "0001", matchup(101.20, 100.90), precision = 2, nowMillis = t0 + 40_000L)
    val newFlip = differ.ingestFantasyMatchups("WVC", "0001", matchup(101.20, 105.60), precision = 2, nowMillis = t0 + 50_000L)
    assertEquals(1, newFlip.size)
  }
}
