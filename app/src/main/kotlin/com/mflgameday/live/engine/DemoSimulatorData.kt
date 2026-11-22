package com.mflgameday.live.engine

import com.mflgameday.live.data.RawMflFranchiseLive
import com.mflgameday.live.data.RawMflMatchup
import com.mflgameday.live.data.RawMflPlayerScore
import com.mflgameday.live.model.FantasyPlayer
import com.mflgameday.live.model.FranchiseInfo
import com.mflgameday.live.model.GameEvent
import com.mflgameday.live.model.GameEventKind
import com.mflgameday.live.model.GamePhase
import com.mflgameday.live.model.LeagueConfig
import com.mflgameday.live.model.LeagueLiveSnapshot
import com.mflgameday.live.model.NflGameState
import com.mflgameday.live.model.NflPlay
import com.mflgameday.live.model.NflSide
import com.mflgameday.live.model.NflTeam
import com.mflgameday.live.model.PlayKind
import com.mflgameday.live.model.ScoringBreakdownLine

/**
 * Rich, colorful multi-league NFL Sunday showcase & live play simulator.
 * Includes BOTH a Standard PPR Doubleheader League AND a Best Ball Whole-Point League,
 * 10 NFL games across Live/RedZone/Halftime/Final/Scheduled states, and an interactive
 * Live Broadcast Play Generator (featuring Baker Mayfield's 94-yard TD bomb to Chris Godwin!).
 */
object DemoSimulatorData {

  val standardLeagueConfig = LeagueConfig(
    leagueId = "DEMO_STD",
    leagueName = "Sunday Showdown PPR",
    year = 2026,
    franchiseId = "0001",
    franchiseName = "Gridiron Gurus",
    isBestBall = false,
    precision = 2,
    starterCount = 9,
    positionLimits = mapOf("QB" to "1-2", "RB" to "2-4", "WR" to "2-4", "TE" to "1-3", "PK" to "1", "Def" to "1")
  )

  val bestBallLeagueConfig = LeagueConfig(
    leagueId = "DEMO_BB",
    leagueName = "Thunderdome Best Ball",
    year = 2026,
    franchiseId = "0014",
    franchiseName = "Tampa Bay Titans",
    isBestBall = true,
    precision = 0,
    starterCount = 9,
    positionLimits = mapOf("QB" to "1-2", "RB" to "2-4", "WR" to "2-4", "TE" to "1-3", "Def" to "1"),
    bestBallTrackFullRoster = true
  )

  val playersDb: Map<String, FantasyPlayer> = listOf(
    FantasyPlayer("P01", "Mayfield, Baker", "QB", NflTeam("TBB"), espnId = "3052587"),
    FantasyPlayer("P02", "Godwin, Chris", "WR", NflTeam("TBB"), espnId = "3116165"),
    FantasyPlayer("P03", "Allen, Josh", "QB", NflTeam("BUF"), espnId = "3918298"),
    FantasyPlayer("P04", "Barkley, Saquon", "RB", NflTeam("PHI"), espnId = "3929630"),
    FantasyPlayer("P05", "Gibbs, Jahmyr", "RB", NflTeam("DET"), espnId = "4429795"),
    FantasyPlayer("P06", "St. Brown, Amon-Ra", "WR", NflTeam("DET"), espnId = "4374302"),
    FantasyPlayer("P07", "Chase, Ja'Marr", "WR", NflTeam("CIN"), espnId = "4362628"),
    FantasyPlayer("P08", "McBride, Trey", "TE", NflTeam("ARI"), espnId = "4361307"),
    FantasyPlayer("P09", "McLaughlin, Chase", "PK", NflTeam("TBB"), espnId = "3150744"),
    FantasyPlayer("P10", "Seahawks, Seattle", "Def", NflTeam("SEA")),
    FantasyPlayer("P11", "Irving, Bucky", "RB", NflTeam("TBB"), espnId = "4596448"),
    FantasyPlayer("P12", "Evans, Mike", "WR", NflTeam("TBB"), espnId = "16737"),
    // Opponent & League stars
    FantasyPlayer("P21", "Goff, Jared", "QB", NflTeam("DET"), espnId = "3046779"),
    FantasyPlayer("P22", "Lamb, CeeDee", "WR", NflTeam("DAL"), espnId = "4241389"),
    FantasyPlayer("P23", "Achane, De'Von", "RB", NflTeam("MIA"), espnId = "4429160"),
    FantasyPlayer("P24", "Walker, Kenneth", "RB", NflTeam("SEA"), espnId = "4567048"),
    FantasyPlayer("P25", "Jefferson, Justin", "WR", NflTeam("MIN"), espnId = "4262921"),
    FantasyPlayer("P26", "Kelce, Travis", "TE", NflTeam("KCC"), espnId = "15847"),
    FantasyPlayer("P27", "Aubrey, Brandon", "PK", NflTeam("DAL")),
    FantasyPlayer("P28", "Bills, Buffalo", "Def", NflTeam("BUF")),
    FantasyPlayer("P29", "Henry, Derrick", "RB", NflTeam("BAL"), espnId = "3043078"),
    FantasyPlayer("P30", "Tagovailoa, Tua", "QB", NflTeam("MIA"), espnId = "4241479"),
    // IDP / Defenders referenced in play-by-play
    FantasyPlayer("P40", "Rousseau, Gregory", "DE", NflTeam("BUF")),
    FantasyPlayer("P41", "Witherspoon, Devon", "CB", NflTeam("SEA")),
    FantasyPlayer("P42", "Purdy, Brock", "QB", NflTeam("SFO")),
    FantasyPlayer("P43", "Hutchinson, Aidan", "DE", NflTeam("DET")),
    FantasyPlayer("P44", "Parsons, Micah", "LB", NflTeam("DAL"))
  ).associateBy { it.id }

  val breakdowns: Map<String, List<ScoringBreakdownLine>> = mapOf(
    "P01" to listOf(
      ScoringBreakdownLine("Passing Yards", "268", 10.72),
      ScoringBreakdownLine("Passing TDs", "2", 8.00),
      ScoringBreakdownLine("Rushing Yards", "19", 1.90)
    ),
    "P02" to listOf(
      ScoringBreakdownLine("Receptions", "6", 6.00),
      ScoringBreakdownLine("Receiving Yards", "88", 8.80),
      ScoringBreakdownLine("Receiving TDs", "1", 6.00)
    ),
    "P03" to listOf(
      ScoringBreakdownLine("Passing Yards", "242", 9.68),
      ScoringBreakdownLine("Passing TDs", "2", 8.00),
      ScoringBreakdownLine("Rushing TDs", "1", 6.00)
    ),
    "P04" to listOf(
      ScoringBreakdownLine("Rushing Yards", "114", 11.40),
      ScoringBreakdownLine("Rushing TDs", "1", 6.00),
      ScoringBreakdownLine("Receptions", "3", 3.00)
    ),
    "P05" to listOf(
      ScoringBreakdownLine("Rushing Yards", "79", 7.90),
      ScoringBreakdownLine("Receptions", "4", 4.00),
      ScoringBreakdownLine("Receiving Yards", "42", 4.20)
    ),
    "P06" to listOf(
      ScoringBreakdownLine("Receptions", "7", 7.00),
      ScoringBreakdownLine("Receiving Yards", "95", 9.50)
    ),
    "P10" to listOf(
      ScoringBreakdownLine("Defensive TDs", "1", 6.00),
      ScoringBreakdownLine("Interceptions", "2", 4.00),
      ScoringBreakdownLine("Sacks", "3", 3.00)
    ),
    "P11" to listOf(
      ScoringBreakdownLine("Rushing Yards", "92", 9.20),
      ScoringBreakdownLine("Rushing TDs", "1", 6.00),
      ScoringBreakdownLine("Receptions", "4", 4.00)
    ),
    "P21" to listOf(
      ScoringBreakdownLine("Passing Yards", "254", 10.16),
      ScoringBreakdownLine("Passing TDs", "2", 8.00)
    ),
    "P22" to listOf(
      ScoringBreakdownLine("Receptions", "8", 8.00),
      ScoringBreakdownLine("Receiving Yards", "112", 11.20),
      ScoringBreakdownLine("Receiving TDs", "1", 6.00)
    ),
    "P28" to listOf(
      ScoringBreakdownLine("Sacks", "4", 4.00),
      ScoringBreakdownLine("Fumbles Recovered", "1", 2.00)
    )
  )

  private fun side(id: String, code: String, loc: String, nick: String, score: Int, rec: String): NflSide =
    NflSide(id, NflTeam(code), loc, nick, score, rec)

  fun initialGames(nowMillis: Long): List<NflGameState> = listOf(
    NflGameState(
      id = "G_TB_DET",
      away = side("27", "TBB", "Tampa Bay", "Buccaneers", 14, "7-3"),
      home = side("8", "DET", "Detroit", "Lions", 17, "8-2"),
      phase = GamePhase.LIVE,
      period = 3,
      clock = "6:42",
      possessionEspnId = "27",
      inRedZone = false,
      isLiveDown = true,
      downDistanceText = "1st & 10 at TBB 6",
      possessionText = "TB 6",
      yardLine = 94,
      lastPlayText = "B.Irving right guard to TB 6 for 4 yards.",
      venue = "Ford Field",
      broadcast = "FOX"
    ),
    NflGameState(
      id = "G_MIA_BUF",
      away = side("15", "MIA", "Miami", "Dolphins", 13, "5-5"),
      home = side("2", "BUF", "Buffalo", "Bills", 20, "8-2"),
      phase = GamePhase.LIVE,
      period = 3,
      clock = "3:18",
      possessionEspnId = "2",
      inRedZone = true,
      isLiveDown = true,
      downDistanceText = "2nd & Goal at MIA 5",
      possessionText = "MIA 5",
      yardLine = 5,
      lastPlayText = "J.Allen pass short right to K.Shakir pushed ob at MIA 5 for 14 yards.",
      venue = "Highmark Stadium",
      broadcast = "CBS"
    ),
    NflGameState(
      id = "G_PHI_DAL",
      away = side("21", "PHI", "Philadelphia", "Eagles", 21, "8-2"),
      home = side("6", "DAL", "Dallas", "Cowboys", 20, "5-5"),
      phase = GamePhase.LIVE,
      period = 4,
      clock = "9:14",
      possessionEspnId = "6",
      inRedZone = true,
      isLiveDown = true,
      downDistanceText = "1st & 10 at PHI 14",
      possessionText = "PHI 14",
      yardLine = 14,
      lastPlayText = "D.Prescott pass deep left to C.Lamb to PHI 14 for 29 yards.",
      venue = "AT&T Stadium",
      broadcast = "CBS"
    ),
    NflGameState(
      id = "G_SF_SEA",
      away = side("25", "SFO", "San Francisco", "49ers", 17, "6-4"),
      home = side("26", "SEA", "Seattle", "Seahawks", 24, "6-4"),
      phase = GamePhase.LIVE,
      period = 4,
      clock = "4:51",
      possessionEspnId = "25",
      inRedZone = false,
      isLiveDown = true,
      downDistanceText = "3rd & 6 at SEA 38",
      possessionText = "SEA 38",
      yardLine = 38,
      venue = "Lumen Field",
      broadcast = "FOX"
    ),
    NflGameState(
      id = "G_LAR_ARI",
      away = side("14", "LAR", "Los Angeles", "Rams", 10, "5-5"),
      home = side("22", "ARI", "Arizona", "Cardinals", 10, "6-4"),
      phase = GamePhase.HALFTIME,
      period = 2,
      clock = "0:00",
      venue = "State Farm Stadium",
      broadcast = "FOX"
    ),
    NflGameState(
      id = "G_KC_DEN",
      away = side("12", "KCC", "Kansas City", "Chiefs", 27, "9-1"),
      home = side("7", "DEN", "Denver", "Broncos", 20, "6-5"),
      phase = GamePhase.FINAL,
      period = 4,
      clock = "0:00",
      venue = "Empower Field",
      broadcast = "CBS"
    ),
    NflGameState(
      id = "G_CIN_BAL",
      away = side("4", "CIN", "Cincinnati", "Bengals", 0, "5-5"),
      home = side("33", "BAL", "Baltimore", "Ravens", 0, "7-3"),
      phase = GamePhase.SCHEDULED,
      kickoffEpochMillis = nowMillis + 5_400_000L,
      venue = "M&T Bank Stadium",
      broadcast = "NBC"
    ),
    NflGameState(
      id = "G_CHI_MIN",
      away = side("3", "CHI", "Chicago", "Bears", 0, "4-6"),
      home = side("16", "MIN", "Minnesota", "Vikings", 0, "7-3"),
      phase = GamePhase.SCHEDULED,
      kickoffEpochMillis = nowMillis + 18_000_000L,
      venue = "U.S. Bank Stadium",
      broadcast = "ESPN"
    )
  )

  fun buildDemoSnapshots(nowMillis: Long, godwinTdScored: Boolean = false): List<LeagueLiveSnapshot> {
    val mayfieldPts = if (godwinTdScored) 28.48 else 20.62
    val godwinPts = if (godwinTdScored) 37.20 else 20.80
    val myStdTotal = 98.40 + (if (godwinTdScored) 16.40 else 0.0)
    val myBbTotal = 104.0 + (if (godwinTdScored) 24.0 else 0.0)

    val stdFranchises = mapOf(
      "0001" to FranchiseInfo("0001", "Gridiron Gurus", "GURU"),
      "0002" to FranchiseInfo("0002", "Motor City Maulers", "MCM"),
      "0003" to FranchiseInfo("0003", "Red Zone Rebels", "RZR"),
      "0004" to FranchiseInfo("0004", "Blitz Brigade", "BLTZ"),
      "0005" to FranchiseInfo("0005", "Hail Mary Heroes", "HMH"),
      "0006" to FranchiseInfo("0006", "Sunday Legends", "LGND")
    )

    val stdMatchups = listOf(
      RawMflMatchup(
        franchises = listOf(
          RawMflFranchiseLive(
            franchiseId = "0002",
            score = 103.85,
            isHome = false,
            playersCurrentlyPlaying = 5,
            playersYetToPlay = 2,
            gameSecondsRemaining = 14200,
            players = listOf(
              RawMflPlayerScore("P21", 18.16, true, 1302),
              RawMflPlayerScore("P23", 14.50, true, 1098),
              RawMflPlayerScore("P24", 16.80, true, 291),
              RawMflPlayerScore("P22", 25.20, true, 554),
              RawMflPlayerScore("P06", 16.50, true, 1302), // Conflict! Starting against me in Std, for me in BB!
              RawMflPlayerScore("P26", 8.69, true, 0),
              RawMflPlayerScore("P29", 0.00, true, 3600),
              RawMflPlayerScore("P27", 4.00, true, 554),
              RawMflPlayerScore("P28", 6.00, true, 1098),
              RawMflPlayerScore("P25", 0.00, false, 3600)
            )
          ),
          RawMflFranchiseLive(
            franchiseId = "0001",
            score = myStdTotal,
            isHome = true,
            playersCurrentlyPlaying = 6,
            playersYetToPlay = 1,
            gameSecondsRemaining = 13800,
            players = listOf(
              RawMflPlayerScore("P03", 23.68, true, 1098),
              RawMflPlayerScore("P04", 20.40, true, 554),
              RawMflPlayerScore("P05", 16.10, true, 1302),
              RawMflPlayerScore("P02", godwinPts, true, 1302),
              RawMflPlayerScore("P07", 0.00, true, 3600),
              RawMflPlayerScore("P08", 8.42, true, 1800),
              RawMflPlayerScore("P09", 9.00, true, 1302),
              RawMflPlayerScore("P10", 13.00, true, 291),
              RawMflPlayerScore("P11", 19.20, false, 1302), // Bench outscoring RB2 Gibbs (16.10)!
              RawMflPlayerScore("P01", mayfieldPts, false, 1302)
            )
          )
        )
      ),
      RawMflMatchup(
        franchises = listOf(
          RawMflFranchiseLive("0003", 112.40, false, 3, 1, 9400, emptyList()),
          RawMflFranchiseLive("0004", 94.15, true, 4, 2, 12800, emptyList())
        )
      ),
      RawMflMatchup(
        franchises = listOf(
          RawMflFranchiseLive("0005", 81.60, false, 2, 3, 15200, emptyList()),
          RawMflFranchiseLive("0006", 89.90, true, 5, 1, 11000, emptyList())
        )
      )
    )

    val bbFranchises = mapOf(
      "0014" to FranchiseInfo("0014", "Tampa Bay Titans", "TBT"),
      "0007" to FranchiseInfo("0007", "Beerfest Brawlers", "BREW"),
      "0008" to FranchiseInfo("0008", "Optimal Prime", "OPT"),
      "0009" to FranchiseInfo("0009", "Zero RB Syndicate", "ZRB")
    )

    val bbMatchups = listOf(
      RawMflMatchup(
        franchises = listOf(
          RawMflFranchiseLive(
            franchiseId = "0007",
            score = 108.0,
            isHome = false,
            playersCurrentlyPlaying = 5,
            playersYetToPlay = 2,
            gameSecondsRemaining = 14800,
            players = listOf(
              RawMflPlayerScore("P21", 18.0, true, 1302),
              RawMflPlayerScore("P23", 15.0, true, 1098),
              RawMflPlayerScore("P24", 17.0, true, 291),
              RawMflPlayerScore("P22", 25.0, true, 554),
              RawMflPlayerScore("P26", 9.0, true, 0),
              RawMflPlayerScore("P29", 0.0, false, 3600),
              RawMflPlayerScore("P28", 6.0, true, 1098)
            )
          ),
          RawMflFranchiseLive(
            franchiseId = "0014",
            score = myBbTotal,
            isHome = true,
            playersCurrentlyPlaying = 6,
            playersYetToPlay = 2,
            gameSecondsRemaining = 15400,
            players = listOf(
              RawMflPlayerScore("P01", if (godwinTdScored) 28.0 else 21.0, true, 1302),
              RawMflPlayerScore("P11", 19.0, true, 1302),
              RawMflPlayerScore("P04", 20.0, true, 554),
              RawMflPlayerScore("P02", if (godwinTdScored) 37.0 else 21.0, true, 1302),
              RawMflPlayerScore("P06", 17.0, true, 1302),
              RawMflPlayerScore("P12", 9.0, true, 1302),
              RawMflPlayerScore("P08", 8.0, true, 1800),
              RawMflPlayerScore("P10", 13.0, true, 291),
              RawMflPlayerScore("P07", 0.0, false, 3600), // Can crack optimal tonight!
              RawMflPlayerScore("P25", 0.0, false, 3600)
            )
          )
        )
      ),
      RawMflMatchup(
        franchises = listOf(
          RawMflFranchiseLive("0008", 119.0, false, 4, 1, 10200, emptyList()),
          RawMflFranchiseLive("0009", 102.0, true, 5, 2, 14100, emptyList())
        )
      )
    )

    val stdSnap = BestBallAndPaceCalculator.buildLeagueSnapshot(
      config = standardLeagueConfig,
      week = 11,
      franchisesMap = stdFranchises,
      rawMatchups = stdMatchups,
      playersDb = playersDb,
      projections = emptyMap(),
      breakdowns = breakdowns,
      previousSnapshot = null,
      nowMillis = nowMillis
    )

    val bbSnap = BestBallAndPaceCalculator.buildLeagueSnapshot(
      config = bestBallLeagueConfig,
      week = 11,
      franchisesMap = bbFranchises,
      rawMatchups = bbMatchups,
      playersDb = playersDb,
      projections = emptyMap(),
      breakdowns = breakdowns,
      previousSnapshot = null,
      nowMillis = nowMillis
    )

    return listOf(stdSnap, bbSnap)
  }

  fun initialSeedEvents(games: List<NflGameState>, nowMillis: Long): List<GameEvent> {
    val miaBuf = games.first { it.id == "G_MIA_BUF" }
    val sfSea = games.first { it.id == "G_SF_SEA" }
    val phiDal = games.first { it.id == "G_PHI_DAL" }
    val kcDen = games.first { it.id == "G_KC_DEN" }
    return listOf(
      GameEvent(
        id = "seed_1",
        kind = GameEventKind.FINAL,
        game = kcDen,
        team = kcDen.away,
        awayScore = 27,
        homeScore = 20,
        observedAtMillis = nowMillis - 420_000L
      ),
      GameEvent(
        id = "seed_2",
        kind = GameEventKind.SACK,
        game = miaBuf,
        team = miaBuf.home,
        play = NflPlay(
          id = "sp2",
          sequence = 2,
          kind = PlayKind.SACK,
          text = "(Shotgun) T.Tagovailoa sacked at MIA 29 for -8 yards (G.Rousseau).",
          offenseTeamId = "15",
          awayScore = 13,
          homeScore = 13
        ),
        awayScore = 13,
        homeScore = 13,
        observedAtMillis = nowMillis - 300_000L
      ),
      GameEvent(
        id = "seed_3",
        kind = GameEventKind.TOUCHDOWN,
        game = sfSea,
        team = sfSea.home,
        play = NflPlay(
          id = "sp3",
          sequence = 3,
          kind = PlayKind.TOUCHDOWN,
          text = "Devon Witherspoon 38 Yd Interception Return (Jason Myers Kick)",
          offenseTeamId = "25",
          awayScore = 17,
          homeScore = 24
        ),
        awayScore = 17,
        homeScore = 24,
        observedAtMillis = nowMillis - 180_000L
      ),
      GameEvent(
        id = "seed_4",
        kind = GameEventKind.TOUCHDOWN,
        game = miaBuf,
        team = miaBuf.home,
        play = NflPlay(
          id = "sp4",
          sequence = 4,
          kind = PlayKind.TOUCHDOWN,
          text = "Josh Allen 5 Yd Rush (Tyler Bass Kick)",
          offenseTeamId = "2",
          awayScore = 13,
          homeScore = 20
        ),
        awayScore = 13,
        homeScore = 20,
        observedAtMillis = nowMillis - 95_000L
      ),
      GameEvent(
        id = "seed_5",
        kind = GameEventKind.RED_ZONE,
        game = phiDal,
        team = phiDal.home,
        awayScore = 21,
        homeScore = 20,
        observedAtMillis = nowMillis - 35_000L
      )
    )
  }

  /**
   * Generates rotating live showcase events whenever the user taps "Simulate Live Play"
   * so every announcement type, score animation, and voice call can be tested on demand!
   */
  fun buildSimulatedBurstPlay(
    step: Int,
    currentGames: List<NflGameState>,
    nowMillis: Long
  ): Pair<List<NflGameState>, GameEvent> {
    val idx = step % 5
    return when (idx) {
      0 -> {
        // Baker Mayfield -> Chris Godwin 94-yard TD pass!
        val tbDet = currentGames.firstOrNull { it.id == "G_TB_DET" } ?: initialGames(nowMillis).first()
        val newAwayScore = tbDet.away.score + 7
        val updated = tbDet.copy(
          away = tbDet.away.copy(score = newAwayScore),
          clock = "6:28",
          downDistanceText = "Kickoff",
          lastPlayText = "Baker Mayfield pass deep middle to Chris Godwin for 94 yards, TOUCHDOWN."
        )
        val ev = GameEvent(
          id = "sim_tb_td_$nowMillis",
          kind = GameEventKind.TOUCHDOWN,
          game = updated,
          team = updated.away,
          play = NflPlay(
            id = "play_tb_$nowMillis",
            sequence = 500 + step,
            kind = PlayKind.TOUCHDOWN,
            text = "Chris Godwin 94 Yd pass from Baker Mayfield (Chase McLaughlin Kick)",
            offenseTeamId = "27",
            awayScore = newAwayScore,
            homeScore = updated.home.score,
            statYardage = 94,
            wallclockEpochMillis = nowMillis
          ),
          awayScore = newAwayScore,
          homeScore = updated.home.score,
          observedAtMillis = nowMillis
        )
        replaceGame(currentGames, updated) to ev
      }
      1 -> {
        // Sack in Detroit!
        val tbDet = currentGames.firstOrNull { it.id == "G_TB_DET" } ?: initialGames(nowMillis).first()
        val updated = tbDet.copy(
          possessionEspnId = "8",
          clock = "5:44",
          downDistanceText = "2nd & 19 at DET 21",
          possessionText = "DET 21",
          lastPlayText = "(Shotgun) J.Goff sacked at DET 21 for -9 yards (L.David)."
        )
        val ev = GameEvent(
          id = "sim_sack_$nowMillis",
          kind = GameEventKind.SACK,
          game = updated,
          team = updated.away,
          play = NflPlay(
            id = "play_sack_$nowMillis",
            sequence = 500 + step,
            kind = PlayKind.SACK,
            text = "(Shotgun) J.Goff sacked at DET 21 for -9 yards (A.Hutchinson).",
            rawPlayText = "(Shotgun) J.Goff sacked at DET 21 for -9 yards (A.Hutchinson).",
            offenseTeamId = "8",
            awayScore = updated.away.score,
            homeScore = updated.home.score,
            wallclockEpochMillis = nowMillis
          ),
          awayScore = updated.away.score,
          homeScore = updated.home.score,
          observedAtMillis = nowMillis
        )
        replaceGame(currentGames, updated) to ev
      }
      2 -> {
        // Interception by Seattle Seahawks Defense!
        val sfSea = currentGames.firstOrNull { it.id == "G_SF_SEA" } ?: initialGames(nowMillis)[3]
        val updated = sfSea.copy(
          possessionEspnId = "26",
          clock = "4:12",
          downDistanceText = "1st & 10 at SFO 42",
          possessionText = "SF 42",
          lastPlayText = "B.Purdy pass deep right INTERCEPTED by D.Witherspoon at SEA 45."
        )
        val ev = GameEvent(
          id = "sim_int_$nowMillis",
          kind = GameEventKind.INTERCEPTION,
          game = updated,
          team = updated.home,
          play = NflPlay(
            id = "play_int_$nowMillis",
            sequence = 500 + step,
            kind = PlayKind.INTERCEPTION,
            text = "B.Purdy pass deep right intended for B.Aiyuk INTERCEPTED by D.Witherspoon at SEA 45.",
            rawPlayText = "B.Purdy pass deep right intended for B.Aiyuk INTERCEPTED by D.Witherspoon at SEA 45.",
            offenseTeamId = "25",
            isTurnover = true,
            awayScore = updated.away.score,
            homeScore = updated.home.score,
            wallclockEpochMillis = nowMillis
          ),
          awayScore = updated.away.score,
          homeScore = updated.home.score,
          observedAtMillis = nowMillis
        )
        replaceGame(currentGames, updated) to ev
      }
      3 -> {
        // Red Zone Alert: Philadelphia Eagles!
        val phiDal = currentGames.firstOrNull { it.id == "G_PHI_DAL" } ?: initialGames(nowMillis)[2]
        val updated = phiDal.copy(
          possessionEspnId = "21",
          inRedZone = true,
          clock = "3:05",
          downDistanceText = "1st & Goal at DAL 8",
          possessionText = "DAL 8",
          yardLine = 8,
          lastPlayText = "S.Barkley left end pushed ob at DAL 8 for 34 yards."
        )
        val ev = GameEvent(
          id = "sim_rz_$nowMillis",
          kind = GameEventKind.RED_ZONE,
          game = updated,
          team = updated.away,
          awayScore = updated.away.score,
          homeScore = updated.home.score,
          observedAtMillis = nowMillis
        )
        replaceGame(currentGames, updated) to ev
      }
      else -> {
        // 56-yard Field Goal in Dallas!
        val phiDal = currentGames.firstOrNull { it.id == "G_PHI_DAL" } ?: initialGames(nowMillis)[2]
        val newHome = phiDal.home.score + 3
        val updated = phiDal.copy(
          home = phiDal.home.copy(score = newHome),
          inRedZone = false,
          clock = "1:52",
          downDistanceText = "Kickoff",
          lastPlayText = "Brandon Aubrey 56 yard field goal is GOOD."
        )
        val ev = GameEvent(
          id = "sim_fg_$nowMillis",
          kind = GameEventKind.FIELD_GOAL,
          game = updated,
          team = updated.home,
          play = NflPlay(
            id = "play_fg_$nowMillis",
            sequence = 500 + step,
            kind = PlayKind.FIELD_GOAL,
            text = "Brandon Aubrey 56 Yd Field Goal",
            offenseTeamId = "6",
            awayScore = updated.away.score,
            homeScore = newHome,
            wallclockEpochMillis = nowMillis
          ),
          awayScore = updated.away.score,
          homeScore = newHome,
          observedAtMillis = nowMillis
        )
        replaceGame(currentGames, updated) to ev
      }
    }
  }

  private fun replaceGame(list: List<NflGameState>, updated: NflGameState): List<NflGameState> =
    list.map { if (it.id == updated.id) updated else it }
}
