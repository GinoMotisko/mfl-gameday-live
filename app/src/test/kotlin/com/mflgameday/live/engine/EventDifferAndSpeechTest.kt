package com.mflgameday.live.engine

import com.mflgameday.live.data.RawMflFranchiseLive
import com.mflgameday.live.data.RawMflMatchup
import com.mflgameday.live.data.RawMflPlayerScore
import com.mflgameday.live.model.AnnouncementScope
import com.mflgameday.live.model.ExposureRole
import com.mflgameday.live.model.FantasyPlayer
import com.mflgameday.live.model.FranchiseInfo
import com.mflgameday.live.model.GameEventKind
import com.mflgameday.live.model.GamePhase
import com.mflgameday.live.model.LeagueConfig
import com.mflgameday.live.model.NflGameState
import com.mflgameday.live.model.NflPlay
import com.mflgameday.live.model.NflSide
import com.mflgameday.live.model.NflTeam
import com.mflgameday.live.model.PlayKind
import com.mflgameday.live.model.PlayerCrossLeagueExposure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDifferAndSpeechTest {

  private val t0 = 1_795_373_000_000L

  private val expander = PlayerNameExpander(
    listOf(
      FantasyPlayer("1", "Mayfield, Baker", "QB", NflTeam("TBB")),
      FantasyPlayer("2", "Godwin, Chris", "WR", NflTeam("TBB")),
      FantasyPlayer("3", "Allen, Josh", "QB", NflTeam("BUF")),
      FantasyPlayer("4", "Parker, T.J.", "DE", NflTeam("BUF")),
      FantasyPlayer("5", "Rousseau, Gregory", "DE", NflTeam("BUF")),
      FantasyPlayer("6", "Penix Jr., Michael", "QB", NflTeam("BAL")),
      FantasyPlayer("7", "Moore, D.J.", "WR", NflTeam("BUF")),
      FantasyPlayer("8", "Tampa, T.J.", "CB", NflTeam("BAL"))
    )
  )

  private fun sampleGame(
    id: String = "G1",
    phase: GamePhase = GamePhase.LIVE,
    awayTeam: NflSide = NflSide("33", NflTeam("BAL"), "Baltimore", "Ravens", 0),
    homeTeam: NflSide = NflSide("2", NflTeam("BUF"), "Buffalo", "Bills", 0),
    possession: String? = null,
    redZone: Boolean = false,
    isLiveDown: Boolean = true
  ) = NflGameState(
    id = id,
    away = awayTeam,
    home = homeTeam,
    phase = phase,
    period = 2,
    clock = "8:15",
    possessionEspnId = possession,
    inRedZone = redZone,
    isLiveDown = isLiveDown
  )

  @Test
  fun bakerMayfield94YardTouchdownPassToChrisGodwin_formatsCleanly() {
    val differ = EventDiffer()
    val tbGame = NflGameState(
      id = "TB_DET",
      away = NflSide("27", NflTeam("TBB"), "Tampa Bay", "Buccaneers", 14),
      home = NflSide("8", NflTeam("DET"), "Detroit", "Lions", 17),
      phase = GamePhase.LIVE,
      period = 3,
      clock = "6:28"
    )
    assertTrue(differ.ingestScoreboard(listOf(tbGame), t0).isEmpty())

    val tdPlay = NflPlay(
      id = "101",
      sequence = 101,
      kind = PlayKind.TOUCHDOWN,
      text = "Chris Godwin 94 Yd pass from Baker Mayfield (Chase McLaughlin Kick)",
      awayScore = 21,
      homeScore = 17,
      offenseTeamId = "27",
      wallclockEpochMillis = t0
    )
    val events = differ.ingestPlays(listOf(tdPlay), "TB_DET", t0)
    assertEquals(1, events.size)
    val spoken = PlaySpeechFormatter.format(events.first(), expander)
    assertEquals(
      "Touchdown, Tampa Bay Buccaneers! Baker Mayfield, 94 yard touchdown pass to Chris Godwin. Tampa Bay Buccaneers lead 21 to 17.",
      spoken
    )
  }

  @Test
  fun pendingTouchdown_omitsScoreUntilTryResolves_reportsMissedKickAndTwoPoint() {
    val differ = EventDiffer()
    differ.ingestScoreboard(listOf(sampleGame()), t0)

    val tdBeforeKick = NflPlay(
      id = "1",
      sequence = 1,
      kind = PlayKind.TOUCHDOWN,
      text = "Josh Allen 5 Yd Rush",
      homeScore = 6,
      offenseTeamId = "2",
      wallclockEpochMillis = t0
    )
    val ev1 = differ.ingestPlays(listOf(tdBeforeKick), "G1", t0).single()
    assertTrue(ev1.tryPending)
    assertEquals(
      "Touchdown, Buffalo Bills! Josh Allen, 5 yard touchdown run.",
      PlaySpeechFormatter.format(ev1, expander)
    )

    // Subsequent kickoff shows score stayed 6 -> missed extra point!
    val kickoff = NflPlay(
      id = "2",
      sequence = 2,
      kind = PlayKind.OTHER,
      text = "Kickoff",
      homeScore = 6,
      offenseTeamId = "33",
      wallclockEpochMillis = t0 + 45_000L
    )
    val followUp = differ.ingestPlays(listOf(tdBeforeKick, kickoff), "G1", t0 + 45_000L).single()
    assertEquals(GameEventKind.TRY_FAILED, followUp.kind)
    assertEquals(
      "Buffalo Bills misses the try after the touchdown. Buffalo Bills lead 6 to 0.",
      PlaySpeechFormatter.format(followUp, expander)
    )
  }

  @Test
  fun redZone_ignoresExtraPointNonDownAndPossessionDrop() {
    val differ = EventDiffer()
    differ.ingestScoreboard(listOf(sampleGame(possession = "2", redZone = false)), t0)

    // Enters Red Zone once
    val rz1 = differ.ingestScoreboard(listOf(sampleGame(possession = "2", redZone = true)), t0 + 1000)
    assertEquals(listOf(GameEventKind.RED_ZONE), rz1.map { it.kind })

    // Same possession stays in Red Zone -> silent
    assertTrue(differ.ingestScoreboard(listOf(sampleGame(possession = "2", redZone = true)), t0 + 2000).isEmpty())

    // Possession drops momentarily after timeout -> does not reset red zone state
    assertTrue(differ.ingestScoreboard(listOf(sampleGame(possession = null, redZone = true)), t0 + 3000).isEmpty())
    assertTrue(differ.ingestScoreboard(listOf(sampleGame(possession = "2", redZone = true)), t0 + 4000).isEmpty())

    // Extra point try (isLiveDown = false) does not retrigger
    val trySnap = sampleGame(
      homeTeam = NflSide("2", NflTeam("BUF"), "Buffalo", "Bills", 6),
      possession = "2",
      redZone = true,
      isLiveDown = false
    )
    assertTrue(differ.ingestScoreboard(listOf(trySnap), t0 + 5000).isEmpty())
  }

  @Test
  fun sacksTurnoversAndReplayReversals_expandNamesAndCreditDefense() {
    val differ = EventDiffer()
    differ.ingestScoreboard(listOf(sampleGame()), t0)

    val splitSack = NflPlay(
      id = "10",
      sequence = 10,
      kind = PlayKind.SACK,
      text = "M.Penix sacked at BLT 30 for -6 yards (sack split by T.Parker and G.Rousseau).",
      offenseTeamId = "33",
      wallclockEpochMillis = t0
    )
    val sackEvent = differ.ingestPlays(listOf(splitSack), "G1", t0).single()
    assertEquals(
      "Sack, Buffalo Bills, Michael Penix Jr. sacked by T.J. Parker and Gregory Rousseau for 6 yards.",
      PlaySpeechFormatter.format(sackEvent, expander)
    )

    val reversedFumbleText =
      "(Shotgun) J.Allen pass short middle to D.Moore to BLT 20 for 19 yards.The Replay Official reviewed the runner was down by contact ruling, and the play was REVERSED.(Shotgun) J.Allen pass short middle to D.Moore to BLT 21 for 18 yards (R.Smith). FUMBLES (R.Smith), RECOVERED by BLT-T.Tampa at BLT 18."
    val fumblePlay = NflPlay(
      id = "11",
      sequence = 11,
      kind = PlayKind.FUMBLE_LOST,
      text = reversedFumbleText,
      rawPlayText = reversedFumbleText,
      offenseTeamId = "2",
      wallclockEpochMillis = t0
    )
    val fumbleEvent = differ.ingestPlays(listOf(splitSack, fumblePlay), "G1", t0).single()
    assertEquals(
      "Fumble, D.J. Moore fumbles and is recovered by Baltimore Ravens T.J. Tampa.",
      PlaySpeechFormatter.format(fumbleEvent, expander)
    )
  }

  @Test
  fun defensivePickSix_distinguishedFromOffensiveEndzoneFumbleRecovery() {
    val differ = EventDiffer()
    differ.ingestScoreboard(listOf(sampleGame()), t0)

    val pickSix = NflPlay(
      id = "20",
      sequence = 20,
      kind = PlayKind.TOUCHDOWN,
      text = "Rayuan Lane III 92 Yd Interception Return (Tyler Bass Kick)",
      homeScore = 7,
      offenseTeamId = "33",
      wallclockEpochMillis = t0
    )
    val ev = differ.ingestPlays(listOf(pickSix), "G1", t0).single()
    assertEquals(
      "Touchdown, Buffalo Bills Defense, Rayuan Lane III, 92 yard interception. Buffalo Bills lead 7 to 0.",
      PlaySpeechFormatter.format(ev, expander)
    )
  }

  @Test
  fun bestBallAndCrossLeagueConflictDetection_tracksRosterAndConflicts() {
    val playersDb = mapOf(
      "P1" to FantasyPlayer("P1", "Godwin, Chris", "WR", NflTeam("TBB")),
      "P2" to FantasyPlayer("P2", "Allen, Josh", "QB", NflTeam("BUF")),
      "P3" to FantasyPlayer("P3", "Chase, Ja'Marr", "WR", NflTeam("CIN"))
    )

    val leagueA = LeagueConfig("L1", "Standard League", 2026, "0001", "My Team A", isBestBall = false)
    val leagueB = LeagueConfig("L2", "Beerfest Best Ball", 2026, "0014", "My Team B", isBestBall = true, precision = 0)

    val snapA = BestBallAndPaceCalculator.buildLeagueSnapshot(
      config = leagueA,
      week = 11,
      franchisesMap = mapOf("0001" to FranchiseInfo("0001", "My Team A"), "0002" to FranchiseInfo("0002", "Opp A")),
      rawMatchups = listOf(
        RawMflMatchup(
          listOf(
            RawMflFranchiseLive("0001", 25.0, false, 1, 0, 1800, listOf(RawMflPlayerScore("P1", 25.0, true, 1800))),
            RawMflFranchiseLive("0002", 20.0, true, 1, 0, 1800, listOf(RawMflPlayerScore("P2", 20.0, true, 1800)))
          )
        )
      ),
      playersDb = playersDb,
      projections = emptyMap(),
      breakdowns = emptyMap(),
      previousSnapshot = null,
      nowMillis = t0
    )

    val snapB = BestBallAndPaceCalculator.buildLeagueSnapshot(
      config = leagueB,
      week = 11,
      franchisesMap = mapOf("0014" to FranchiseInfo("0014", "My Team B"), "0007" to FranchiseInfo("0007", "Opp B")),
      rawMatchups = listOf(
        RawMflMatchup(
          listOf(
            // Josh Allen (P2) is on MY team in League B, and Chase (P3) is a Best Ball nonstarter yet to play!
            RawMflFranchiseLive(
              "0014",
              22.0,
              false,
              1,
              1,
              5400,
              listOf(
                RawMflPlayerScore("P2", 22.0, true, 1800),
                RawMflPlayerScore("P3", 0.0, false, 3600)
              )
            ),
            RawMflFranchiseLive("0007", 18.0, true, 1, 0, 1800, emptyList())
          )
        )
      ),
      playersDb = playersDb,
      projections = emptyMap(),
      breakdowns = emptyMap(),
      previousSnapshot = null,
      nowMillis = t0
    )

    val exposures = BestBallAndPaceCalculator.buildCrossLeagueExposure(listOf(snapA, snapB), playersDb)
    assertEquals(ExposureRole.MINE, exposures["P1"]?.role)
    assertEquals(ExposureRole.CONFLICT, exposures["P2"]?.role) // Mine in B, Opponent in A!
    // Best Ball full-roster tracking includes nonstarter P3 (Ja'Marr Chase) so big bench TDs are announced!
    assertEquals(ExposureRole.MINE, exposures["P3"]?.role)
    assertTrue(exposures["P3"]?.isBestBallBenchContender == true)

    // Scope filtering check
    val cinGame = sampleGame(
      awayTeam = NflSide("4", NflTeam("CIN"), "Cincinnati", "Bengals", 7),
      homeTeam = NflSide("33", NflTeam("BAL"), "Baltimore", "Ravens", 0)
    )
    val chaseTdEvent = com.mflgameday.live.model.GameEvent(
      id = "e_chase",
      kind = GameEventKind.TOUCHDOWN,
      game = cinGame,
      team = cinGame.away,
      play = NflPlay("99", 99, PlayKind.TOUCHDOWN, "Ja'Marr Chase 62 Yd pass from Joe Burrow"),
      awayScore = 7,
      homeScore = 0,
      observedAtMillis = t0
    )
    val involved = BestBallAndPaceCalculator.findInvolvedExposures(chaseTdEvent, exposures)
    assertEquals(1, involved.size)
    assertTrue(BestBallAndPaceCalculator.isEventRelevantForScope(chaseTdEvent, AnnouncementScope.MY_PLAYERS, exposures, involved))
    assertFalse(BestBallAndPaceCalculator.isEventRelevantForScope(chaseTdEvent, AnnouncementScope.MY_PLAYERS, emptyMap<String, com.mflgameday.live.model.PlayerCrossLeagueExposure>().let { mapOf("P1" to exposures["P1"]!!) }, emptyList()))
  }

  @Test
  fun seedSeenFromLog_preventsDuplicateAnnouncementsAfterAppRestart() {
    val game = sampleGame()
    val sackPlay = NflPlay(
      id = "sack_55",
      sequence = 55,
      kind = PlayKind.SACK,
      text = "(Shotgun) M.Penix sacked at BLT 25 for -8 yards (G.Rousseau).",
      offenseTeamId = "33",
      wallclockEpochMillis = t0
    )
    val savedEntry = com.mflgameday.live.model.PlayLogEntry(
      id = "log_1",
      timestampMillis = t0,
      event = com.mflgameday.live.model.GameEvent(
        id = "ev_1",
        kind = GameEventKind.SACK,
        game = game,
        team = game.home,
        play = sackPlay,
        awayScore = 0,
        homeScore = 0,
        observedAtMillis = t0
      ),
      spokenText = "Sack, Buffalo Bills!",
      wasSpoken = true
    )

    val restartedDiffer = EventDiffer()
    restartedDiffer.seedSeenFromLog(listOf(savedEntry))
    restartedDiffer.ingestScoreboard(listOf(game), t0 + 10_000L)

    // Re-ingesting the same play within maxPlayAgeMillis after restart must NOT re-emit it
    val duplicateCheck = restartedDiffer.ingestPlays(listOf(sackPlay), game.id, t0 + 10_000L)
    assertTrue(duplicateCheck.isEmpty())
  }

  @Test
  fun tjWattEndZoneStripSackTouchdown_formatsDefenseCallAndCreditsTeamDefenseAndIdp() {
    val pitExpander = PlayerNameExpander(
      listOf(
        FantasyPlayer("0510", "Steelers, Pittsburgh", "Def", NflTeam("PIT")),
        FantasyPlayer("13214", "Watt, T.J.", "DE", NflTeam("PIT")),
        FantasyPlayer("14800", "Warren, Jaylen", "RB", NflTeam("PIT")),
        FantasyPlayer("15900", "Nix, Bo", "QB", NflTeam("DEN"))
      )
    )
    val pitGame = sampleGame(
      id = "401873083",
      awayTeam = NflSide("7", NflTeam("DEN"), "Denver", "Broncos", 0),
      homeTeam = NflSide("23", NflTeam("PIT"), "Pittsburgh", "Steelers", 0)
    )
    val differ = EventDiffer()
    differ.ingestScoreboard(listOf(pitGame), t0)

    // 1. Live summary text from ESPN scoringPlays[]
    val wattSummaryPlay = NflPlay(
      id = "401873083512",
      sequence = 51200,
      kind = PlayKind.TOUCHDOWN,
      text = "T.J. Watt Fumble Recovery in End Zone (Chris Boswell Kick)",
      rawPlayText = "(Shotgun) B.Nix sacked at DEN 6 for -13 yards. FUMBLES, RECOVERED by PIT-T.Watt at DEN -3. TOUCHDOWN. C.Boswell extra point is GOOD, Center-C.Kuntz, Holder-C.Johnston.",
      awayScore = 0,
      homeScore = 7,
      offenseTeamId = "7",
      isTurnover = true,
      wallclockEpochMillis = t0
    )
    val summaryEv = differ.ingestPlays(listOf(wattSummaryPlay), pitGame.id, t0).single()
    assertEquals(GameEventKind.TOUCHDOWN, summaryEv.kind)
    assertFalse(summaryEv.isSilentBackfill)
    assertEquals("PIT", summaryEv.team?.team?.code)
    assertEquals(
      "Touchdown, Pittsburgh Steelers Defense, T.J. Watt, fumble recovery in the end zone. Pittsburgh Steelers lead 7 to 0.",
      PlaySpeechFormatter.format(summaryEv, pitExpander)
    )

    // 2. Raw play-by-play text fallback when scoringPlays[] hasn't arrived yet (tryPending == true)
    val rawDiffer = EventDiffer()
    rawDiffer.ingestScoreboard(listOf(pitGame), t0)
    val wattRawPlay = NflPlay(
      id = "401873083512_raw",
      sequence = 51200,
      kind = PlayKind.TOUCHDOWN,
      text = "(Shotgun) B.Nix sacked at DEN 6 for -13 yards. FUMBLES, RECOVERED by PIT-T.Watt at DEN -3. TOUCHDOWN.",
      rawPlayText = "(Shotgun) B.Nix sacked at DEN 6 for -13 yards. FUMBLES, RECOVERED by PIT-T.Watt at DEN -3. TOUCHDOWN.",
      awayScore = 0,
      homeScore = 6,
      offenseTeamId = "7",
      isTurnover = true,
      wallclockEpochMillis = t0
    )
    val rawEv = rawDiffer.ingestPlays(listOf(wattRawPlay), pitGame.id, t0).single()
    assertTrue(rawEv.tryPending)
    assertEquals(
      "Touchdown, Pittsburgh Steelers Defense, T.J. Watt, fumble recovery in the end zone.",
      PlaySpeechFormatter.format(rawEv, pitExpander)
    )

    // 3. Offensive end-zone fumble recovery must NOT say "Defense"
    val offDiffer = EventDiffer()
    offDiffer.ingestScoreboard(listOf(pitGame), t0)
    val offFumblePlay = NflPlay(
      id = "off_ez_1",
      sequence = 51201,
      kind = PlayKind.TOUCHDOWN,
      text = "Jaylen Warren Offensive Fumble Recovery in End Zone (Chris Boswell Kick)",
      awayScore = 0,
      homeScore = 7,
      offenseTeamId = "23",
      isTurnover = false,
      wallclockEpochMillis = t0
    )
    val offEv = offDiffer.ingestPlays(listOf(offFumblePlay), pitGame.id, t0).single()
    assertEquals(
      "Touchdown, Pittsburgh Steelers, Jaylen Warren, fumble recovery in the end zone. Pittsburgh Steelers lead 7 to 0.",
      PlaySpeechFormatter.format(offEv, pitExpander)
    )

    // 4. Team Defense (DST) & IDP exposure matching for MY_PLAYERS scope
    val dstOnlyExposures = mapOf(
      "0510" to PlayerCrossLeagueExposure(
        player = FantasyPlayer("0510", "Steelers, Pittsburgh", "Def", NflTeam("PIT")),
        role = ExposureRole.MINE,
        myLeagueNames = listOf("Steel City"),
        opponentLeagueNames = emptyList()
      )
    )
    val dstInvolved = BestBallAndPaceCalculator.findInvolvedExposures(summaryEv, dstOnlyExposures)
    assertEquals(listOf("0510"), dstInvolved.map { it.player.id })
    assertTrue(
      BestBallAndPaceCalculator.isEventRelevantForScope(
        summaryEv,
        AnnouncementScope.MY_PLAYERS,
        dstOnlyExposures,
        dstInvolved
      )
    )

    val idpOnlyExposures = mapOf(
      "13214" to PlayerCrossLeagueExposure(
        player = FantasyPlayer("13214", "Watt, T.J.", "DE", NflTeam("PIT")),
        role = ExposureRole.MINE,
        myLeagueNames = listOf("IDP Dynasty"),
        opponentLeagueNames = emptyList()
      )
    )
    val idpInvolved = BestBallAndPaceCalculator.findInvolvedExposures(summaryEv, idpOnlyExposures)
    assertEquals(listOf("13214"), idpInvolved.map { it.player.id })
    assertTrue(
      BestBallAndPaceCalculator.isEventRelevantForScope(
        summaryEv,
        AnnouncementScope.MY_PLAYERS,
        idpOnlyExposures,
        idpInvolved
      )
    )
  }

  @Test
  fun coldOpenMidGameAndSlowBoothReview_backfillsLiveGameScoresSilentlyAndSpeaksReviewedScore() {
    val pitGame = sampleGame(
      id = "401873083",
      awayTeam = NflSide("7", NflTeam("DEN"), "Denver", "Broncos", 0),
      homeTeam = NflSide("23", NflTeam("PIT"), "Pittsburgh", "Steelers", 7)
    )
    val coldDiffer = EventDiffer()
    coldDiffer.ingestScoreboard(listOf(pitGame), t0 + 25L * 60_000L)

    val oldSack = NflPlay(
      id = "old_sack",
      sequence = 100,
      kind = PlayKind.SACK,
      text = "M.Rudolph sacked at PIT 48 for -12 yards (N.Bonitto).",
      offenseTeamId = "23",
      wallclockEpochMillis = t0 - 300_000L
    )
    val oldTd = NflPlay(
      id = "401873083512",
      sequence = 51200,
      kind = PlayKind.TOUCHDOWN,
      text = "T.J. Watt Fumble Recovery in End Zone (Chris Boswell Kick)",
      awayScore = 0,
      homeScore = 7,
      offenseTeamId = "7",
      isTurnover = true,
      wallclockEpochMillis = t0
    )

    val backfilled = coldDiffer.ingestPlays(listOf(oldSack, oldTd), pitGame.id, t0 + 25L * 60_000L)
    assertEquals(1, backfilled.size)
    assertEquals(GameEventKind.TOUCHDOWN, backfilled.single().kind)
    assertTrue(backfilled.single().isSilentBackfill)
    assertEquals(t0, backfilled.single().observedAtMillis)

    // Slow NFL booth review (> 5 min, <= 20 min) flipping FUMBLE_LOST -> TOUCHDOWN speaks live!
    val reviewDiffer = EventDiffer()
    reviewDiffer.ingestScoreboard(listOf(pitGame), t0)
    val initialFumble = NflPlay(
      id = "401873083512",
      sequence = 51200,
      kind = PlayKind.FUMBLE_LOST,
      text = "(Shotgun) B.Nix sacked at DEN 6 for -13 yards. FUMBLES, RECOVERED by PIT-T.Watt at DEN -3.",
      awayScore = 0,
      homeScore = 0,
      offenseTeamId = "7",
      isTurnover = true,
      wallclockEpochMillis = t0
    )
    val evInitial = reviewDiffer.ingestPlays(listOf(initialFumble), pitGame.id, t0 + 10_000L).single()
    assertEquals(GameEventKind.FUMBLE_LOST, evInitial.kind)

    val reviewedTd = initialFumble.copy(
      kind = PlayKind.TOUCHDOWN,
      text = "T.J. Watt Fumble Recovery in End Zone (Chris Boswell Kick)",
      awayScore = 0,
      homeScore = 7
    )
    val evReviewed = reviewDiffer.ingestPlays(listOf(reviewedTd), pitGame.id, t0 + 7L * 60_000L).single()
    assertEquals(GameEventKind.TOUCHDOWN, evReviewed.kind)
    assertFalse(evReviewed.isSilentBackfill)
  }

  @Test
  fun sackAndSafetyVictimAttribution_namesPlayerAndDistinguishesVictimFromScoringDefense() {
    val pitDenExpander = PlayerNameExpander(
      listOf(
        FantasyPlayer("0510", "Steelers, Pittsburgh", "Def", NflTeam("PIT")),
        FantasyPlayer("0507", "Broncos, Denver", "Def", NflTeam("DEN")),
        FantasyPlayer("14800", "Warren, Jaylen", "RB", NflTeam("PIT")),
        FantasyPlayer("15900", "Nix, Bo", "QB", NflTeam("DEN")),
        FantasyPlayer("16001", "Waddle, Jaylen", "WR", NflTeam("DEN")),
        FantasyPlayer("16002", "Metcalf, DK", "WR", NflTeam("PIT")),
        FantasyPlayer("16003", "Rudolph, Mason", "QB", NflTeam("PIT")),
        FantasyPlayer("16004", "Boswell, Chris", "PK", NflTeam("PIT")),
        FantasyPlayer("16005", "Surtain, Patrick", "CB", NflTeam("DEN")),
        FantasyPlayer("16006", "Fitzpatrick, Minkah", "S", NflTeam("PIT"))
      )
    )
    val game = sampleGame(
      id = "401873083",
      awayTeam = NflSide("7", NflTeam("DEN"), "Denver", "Broncos", 2),
      homeTeam = NflSide("23", NflTeam("PIT"), "Pittsburgh", "Steelers", 14)
    )

    // 1. Denver Safety on Steelers RB Jaylen Warren (4018730831114)
    val safetyPlay = NflPlay(
      id = "4018730831114",
      sequence = 111400,
      kind = PlayKind.SAFETY,
      text = "Jaylen Warren Rush, Loss of 1 Yd for Safety",
      rawPlayText = "J.Warren up the middle tackled in End Zone for -1 yards, SAFETY (A.Singleton).",
      awayScore = 2,
      homeScore = 14,
      offenseTeamId = "23",
      wallclockEpochMillis = t0
    )
    val safetyEvent = com.mflgameday.live.model.GameEvent(
      id = "ev_safety",
      kind = GameEventKind.SAFETY,
      game = game,
      team = game.away,
      play = safetyPlay,
      awayScore = 2,
      homeScore = 14,
      observedAtMillis = t0
    )
    assertEquals(
      "Safety! Two points for Denver Broncos. Jaylen Warren Rush, Loss of 1 yard for Safety. Pittsburgh Steelers lead 14 to 2.",
      PlaySpeechFormatter.format(safetyEvent, pitDenExpander)
    )

    val oppWarrenExposures = mapOf(
      "14800" to PlayerCrossLeagueExposure(
        player = FantasyPlayer("14800", "Warren, Jaylen", "RB", NflTeam("PIT")),
        role = ExposureRole.OPPONENT,
        myLeagueNames = emptyList(),
        opponentLeagueNames = listOf("Wyoming Valley Conference")
      )
    )
    val warrenInvolved = BestBallAndPaceCalculator.findInvolvedExposures(safetyEvent, oppWarrenExposures)
    assertEquals(listOf("14800"), warrenInvolved.map { it.player.id })
    assertEquals(
      "Hurts opponent's Jaylen Warren in Wyoming Valley Conference.",
      BestBallAndPaceCalculator.formatFantasyContextSuffix(safetyEvent, warrenInvolved)
    )

    val myWarrenExposures = mapOf(
      "14800" to PlayerCrossLeagueExposure(
        player = FantasyPlayer("14800", "Warren, Jaylen", "RB", NflTeam("PIT")),
        role = ExposureRole.MINE,
        myLeagueNames = listOf("Wyoming Valley Conference"),
        opponentLeagueNames = emptyList()
      )
    )
    assertEquals(
      "Against your starter Jaylen Warren in Wyoming Valley Conference.",
      BestBallAndPaceCalculator.formatFantasyContextSuffix(
        safetyEvent,
        BestBallAndPaceCalculator.findInvolvedExposures(safetyEvent, myWarrenExposures)
      )
    )

    // 2. Steelers Sack on Broncos QB Bo Nix (4018730831051)
    val sackPlay = NflPlay(
      id = "4018730831051",
      sequence = 105100,
      kind = PlayKind.SACK,
      text = "(Shotgun) B.Nix sacked at PIT 36 for -12 yards (N.Herbig).",
      rawPlayText = "(Shotgun) B.Nix sacked at PIT 36 for -12 yards (N.Herbig).",
      awayScore = 0,
      homeScore = 14,
      offenseTeamId = "7",
      wallclockEpochMillis = t0
    )
    val sackEvent = com.mflgameday.live.model.GameEvent(
      id = "ev_sack",
      kind = GameEventKind.SACK,
      game = game,
      team = game.home,
      play = sackPlay,
      awayScore = 0,
      homeScore = 14,
      observedAtMillis = t0
    )
    val oppNixExposures = mapOf(
      "15900" to PlayerCrossLeagueExposure(
        player = FantasyPlayer("15900", "Nix, Bo", "QB", NflTeam("DEN")),
        role = ExposureRole.OPPONENT,
        myLeagueNames = emptyList(),
        opponentLeagueNames = listOf("Wyoming Valley Conference")
      )
    )
    val nixInvolved = BestBallAndPaceCalculator.findInvolvedExposures(sackEvent, oppNixExposures)
    assertEquals(listOf("15900"), nixInvolved.map { it.player.id })
    assertEquals(
      "Hurts opponent's Bo Nix in Wyoming Valley Conference.",
      BestBallAndPaceCalculator.formatFantasyContextSuffix(sackEvent, nixInvolved)
    )

    // 3. Credited side (OFFENSE / DEFENSE) names the involved player cleanly
    val metcalfTdEvent = com.mflgameday.live.model.GameEvent(
      id = "ev_metcalf",
      kind = GameEventKind.TOUCHDOWN,
      game = game,
      team = game.home,
      play = NflPlay(
        id = "401873083773",
        sequence = 77300,
        kind = PlayKind.TOUCHDOWN,
        text = "DK Metcalf 22 Yd pass from Mason Rudolph (Chris Boswell Kick)",
        rawPlayText = "(Shotgun) M.Rudolph pass deep left to D.Metcalf for 22 yards, TOUCHDOWN (P.Surtain). C.Boswell extra point is GOOD, Center-C.Kuntz, Holder-C.Johnston.",
        awayScore = 0,
        homeScore = 14,
        offenseTeamId = "23",
        wallclockEpochMillis = t0
      ),
      awayScore = 0,
      homeScore = 14,
      observedAtMillis = t0
    )
    val allPlayersExposures = mapOf(
      "16002" to PlayerCrossLeagueExposure(
        player = FantasyPlayer("16002", "Metcalf, DK", "WR", NflTeam("PIT")),
        role = ExposureRole.OPPONENT,
        myLeagueNames = emptyList(),
        opponentLeagueNames = listOf("Beerfest")
      ),
      "16003" to PlayerCrossLeagueExposure(
        player = FantasyPlayer("16003", "Rudolph, Mason", "QB", NflTeam("PIT")),
        role = ExposureRole.MINE,
        myLeagueNames = listOf("Beerfest"),
        opponentLeagueNames = emptyList()
      ),
      "16004" to PlayerCrossLeagueExposure(
        player = FantasyPlayer("16004", "Boswell, Chris", "PK", NflTeam("PIT")),
        role = ExposureRole.MINE,
        myLeagueNames = listOf("Beerfest"),
        opponentLeagueNames = emptyList()
      ),
      "16005" to PlayerCrossLeagueExposure(
        player = FantasyPlayer("16005", "Surtain, Patrick", "CB", NflTeam("DEN")),
        role = ExposureRole.OPPONENT,
        myLeagueNames = emptyList(),
        opponentLeagueNames = listOf("Beerfest")
      )
    )
    val tdInvolved = BestBallAndPaceCalculator.findInvolvedExposures(metcalfTdEvent, allPlayersExposures)
    // Bystander PAT kicker Boswell and opposing CB tackler Surtain must NOT be included!
    assertEquals(setOf("16002", "16003"), tdInvolved.map { it.player.id }.toSet())

    val metcalfOnly = tdInvolved.filter { it.player.id == "16002" }
    assertEquals(
      "Against you — DK Metcalf in Beerfest.",
      BestBallAndPaceCalculator.formatFantasyContextSuffix(metcalfTdEvent, metcalfOnly)
    )

    val godwinMine = listOf(
      PlayerCrossLeagueExposure(
        player = FantasyPlayer("2", "Godwin, Chris", "WR", NflTeam("TBB")),
        role = ExposureRole.MINE,
        myLeagueNames = listOf("Beerfest"),
        opponentLeagueNames = emptyList()
      )
    )
    val tbGame = sampleGame(
      awayTeam = NflSide("27", NflTeam("TBB"), "Tampa Bay", "Buccaneers", 21),
      homeTeam = NflSide("8", NflTeam("DET"), "Detroit", "Lions", 17)
    )
    val godwinTd = com.mflgameday.live.model.GameEvent(
      id = "ev_godwin",
      kind = GameEventKind.TOUCHDOWN,
      game = tbGame,
      team = tbGame.away,
      play = NflPlay("p_g", 1, PlayKind.TOUCHDOWN, "Chris Godwin 94 Yd pass from Baker Mayfield"),
      awayScore = 21,
      homeScore = 17,
      observedAtMillis = t0
    )
    assertEquals(
      "For your lineup — Chris Godwin in Beerfest.",
      BestBallAndPaceCalculator.formatFantasyContextSuffix(godwinTd, godwinMine)
    )

    val godwinConflict = listOf(
      PlayerCrossLeagueExposure(
        player = FantasyPlayer("2", "Godwin, Chris", "WR", NflTeam("TBB")),
        role = ExposureRole.CONFLICT,
        myLeagueNames = listOf("Beerfest"),
        opponentLeagueNames = listOf("Wyoming Valley Conference")
      )
    )
    assertEquals(
      "Conflict play on Chris Godwin: starting for you in Beerfest, and against you in Wyoming Valley Conference.",
      BestBallAndPaceCalculator.formatFantasyContextSuffix(godwinTd, godwinConflict)
    )

    // 4. Interception must ignore intended target WR Waddle, while still matching passer Nix & interceptor Fitzpatrick
    val intEvent = com.mflgameday.live.model.GameEvent(
      id = "ev_int",
      kind = GameEventKind.INTERCEPTION,
      game = game,
      team = game.home,
      play = NflPlay(
        id = "p_int",
        sequence = 999,
        kind = PlayKind.INTERCEPTION,
        text = "B.Nix pass deep middle intended for J.Waddle INTERCEPTED by M.Fitzpatrick at PIT 15.",
        offenseTeamId = "7",
        isTurnover = true
      ),
      awayScore = 2,
      homeScore = 14,
      observedAtMillis = t0
    )
    val intExposures = mapOf(
      "15900" to oppNixExposures["15900"]!!,
      "16001" to PlayerCrossLeagueExposure(
        player = FantasyPlayer("16001", "Waddle, Jaylen", "WR", NflTeam("DEN")),
        role = ExposureRole.OPPONENT,
        myLeagueNames = emptyList(),
        opponentLeagueNames = listOf("Wyoming Valley Conference")
      ),
      "16006" to PlayerCrossLeagueExposure(
        player = FantasyPlayer("16006", "Fitzpatrick, Minkah", "S", NflTeam("PIT")),
        role = ExposureRole.MINE,
        myLeagueNames = listOf("IDP Dynasty"),
        opponentLeagueNames = emptyList()
      )
    )
    val intInvolved = BestBallAndPaceCalculator.findInvolvedExposures(intEvent, intExposures)
    assertEquals(setOf("15900", "16006"), intInvolved.map { it.player.id }.toSet())
  }

  @Test
  fun mergePlayLogEntries_sortsSilentBackfillChronologicallyBelowFreshPlays() {
    val game = sampleGame()
    fun makeEntry(id: String, ts: Long, silent: Boolean) = com.mflgameday.live.model.PlayLogEntry(
      id = id,
      timestampMillis = ts,
      event = com.mflgameday.live.model.GameEvent(
        id = id,
        kind = GameEventKind.TOUCHDOWN,
        game = game,
        team = game.home,
        awayScore = 0,
        homeScore = 7,
        observedAtMillis = ts,
        isSilentBackfill = silent
      ),
      spokenText = "Call $id",
      wasSpoken = !silent
    )

    val existing = listOf(makeEntry("existing_mid", t0 + 300_000L, silent = false))
    val newBatch = listOf(
      makeEntry("fresh_top", t0 + 600_000L, silent = false),
      makeEntry("backfill_old", t0 - 900_000L, silent = true)
    )
    val merged = EventDiffer.mergePlayLogEntries(newBatch, existing, maxEntries = 250)
    assertEquals(listOf("fresh_top", "existing_mid", "backfill_old"), merged.map { it.id })
  }
}
