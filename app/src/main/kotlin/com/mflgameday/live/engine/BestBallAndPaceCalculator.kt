package com.mflgameday.live.engine

import com.mflgameday.live.data.RawMflFranchiseLive
import com.mflgameday.live.data.RawMflMatchup
import com.mflgameday.live.model.AnnouncementScope
import com.mflgameday.live.model.BenchInsight
import com.mflgameday.live.model.ExposureRole
import com.mflgameday.live.model.FantasyMatchup
import com.mflgameday.live.model.FantasyPlayer
import com.mflgameday.live.model.FranchiseInfo
import com.mflgameday.live.model.FranchiseMatchupSide
import com.mflgameday.live.model.GameEvent
import com.mflgameday.live.model.GameEventKind
import com.mflgameday.live.model.LeagueConfig
import com.mflgameday.live.model.LeagueLiveSnapshot
import com.mflgameday.live.model.PlayerCrossLeagueExposure
import com.mflgameday.live.model.PlayerLiveScore
import com.mflgameday.live.model.ScoringBreakdownLine
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Builds enriched [LeagueLiveSnapshot]s with:
 * - Live Pace Projected Scores (`currentScore + remainingClockFraction * preGameProjection`)
 * - Logistic Live Win Probability (`0.02..0.98`) scaled by remaining game variance
 * - Best Ball (`isBestBall == true`) optimal-bubble & live bench contender intelligence
 * - Standard-league "Bench Outscoring Starter" insights
 * - Multi-League Cross-Exposure ("Rooting Guide" & Conflict Player detection)
 */
object BestBallAndPaceCalculator {

  private val positionOrder = listOf("QB", "RB", "WR", "TE", "PK", "Def", "ST", "DL", "DE", "DT", "LB", "CB", "S", "DB")

  fun buildLeagueSnapshot(
    config: LeagueConfig,
    week: Int,
    franchisesMap: Map<String, FranchiseInfo>,
    rawMatchups: List<RawMflMatchup>,
    playersDb: Map<String, FantasyPlayer>,
    projections: Map<String, Double>,
    breakdowns: Map<String, List<ScoringBreakdownLine>>,
    previousSnapshot: LeagueLiveSnapshot?,
    nowMillis: Long
  ): LeagueLiveSnapshot {
    val prevPlayerScores = HashMap<String, Double>()
    val prevFranchiseScores = HashMap<String, Double>()
    previousSnapshot?.matchups?.forEach { m ->
      m.franchises.forEach { f ->
        prevFranchiseScores[f.franchiseId] = f.score
        (f.starters + f.bench).forEach { p -> prevPlayerScores["${f.franchiseId}_${p.playerId}"] = p.score }
      }
    }

    val builtMatchups = rawMatchups.mapIndexedNotNull { index, raw ->
      if (raw.franchises.isEmpty()) return@mapIndexedNotNull null
      val awayRaw = raw.franchises.firstOrNull { !it.isHome } ?: raw.franchises.first()
      val homeRaw = raw.franchises.firstOrNull { it.isHome && it.franchiseId != awayRaw.franchiseId }
        ?: raw.franchises.getOrNull(1)
        ?: RawMflFranchiseLive(
          franchiseId = "AVG",
          score = awayRaw.score,
          isHome = true,
          playersCurrentlyPlaying = 0,
          playersYetToPlay = 0,
          gameSecondsRemaining = 0,
          players = emptyList()
        )

      val awaySide = buildFranchiseSide(
        raw = awayRaw,
        config = config,
        franchisesMap = franchisesMap,
        playersDb = playersDb,
        projections = projections,
        breakdowns = breakdowns,
        prevPlayerScores = prevPlayerScores,
        prevFranchiseScores = prevFranchiseScores
      )
      val homeSide = buildFranchiseSide(
        raw = homeRaw,
        config = config,
        franchisesMap = franchisesMap,
        playersDb = playersDb,
        projections = projections,
        breakdowns = breakdowns,
        prevPlayerScores = prevPlayerScores,
        prevFranchiseScores = prevFranchiseScores
      )

      val (awayWinProb, homeWinProb) = computeWinProbabilities(awaySide, homeSide)
      val involvesMe = awaySide.franchiseId == config.franchiseId || homeSide.franchiseId == config.franchiseId

      FantasyMatchup(
        id = "${config.leagueId}_w${week}_m${index}_${awaySide.franchiseId}_${homeSide.franchiseId}",
        leagueId = config.leagueId,
        away = awaySide.copy(winProbability = awayWinProb),
        home = homeSide.copy(winProbability = homeWinProb),
        involvesMyFranchise = involvesMe
      )
    }.sortedWith(
      compareByDescending<FantasyMatchup> { it.involvesMyFranchise }
    )

    return LeagueLiveSnapshot(
      config = config,
      week = week,
      franchises = franchisesMap,
      matchups = builtMatchups,
      updatedAtMillis = nowMillis
    )
  }

  private fun buildFranchiseSide(
    raw: RawMflFranchiseLive,
    config: LeagueConfig,
    franchisesMap: Map<String, FranchiseInfo>,
    playersDb: Map<String, FantasyPlayer>,
    projections: Map<String, Double>,
    breakdowns: Map<String, List<ScoringBreakdownLine>>,
    prevPlayerScores: Map<String, Double>,
    prevFranchiseScores: Map<String, Double>
  ): FranchiseMatchupSide {
    val fInfo = franchisesMap[raw.franchiseId]
    val fName = fInfo?.name ?: if (raw.franchiseId == "AVG") "League Average" else "Team ${raw.franchiseId}"

    val hasLeagueProjections = projections.isNotEmpty()
    val mappedPlayers = raw.players.map { p ->
      // When MFL's league-specific `projectedScores` feed is available, respect 0.0 for Bye/Out/Inactive players
      // instead of inventing phantom position-average points; only fall back if projections couldn't be fetched at all.
      val preProj = if (hasLeagueProjections) {
        projections[p.playerId] ?: 0.0
      } else {
        defaultPositionProjection(playersDb[p.playerId]?.position)
      }
      val remFraction = (p.gameSecondsRemaining.coerceIn(0, 3600) / 3600.0)
      val livePace = p.score + (preProj * remFraction)
      val prevPScore = prevPlayerScores["${raw.franchiseId}_${p.playerId}"]
      val pDelta = if (prevPScore != null && p.score > prevPScore) (p.score - prevPScore) else 0.0
      PlayerLiveScore(
        playerId = p.playerId,
        score = p.score,
        isStarter = p.isStarter,
        gameSecondsRemaining = p.gameSecondsRemaining,
        preGameProjection = preProj,
        livePaceProjection = livePace,
        breakdowns = breakdowns[p.playerId].orEmpty(),
        scoreDeltaRecent = pDelta
      )
    }

    var starters = mappedPlayers.filter { it.isStarter }.sortedWith(playerComparator(playersDb))
    var bench = mappedPlayers.filter { !it.isStarter }.sortedWith(playerComparator(playersDb))

    // In Best Ball leagues before any starter flags are set by MFL, synthesize optimal starters
    if (config.isBestBall && starters.isEmpty() && bench.isNotEmpty()) {
      val sortedByPace = bench.sortedByDescending { max(it.score, it.livePaceProjection) }
      val count = config.starterCount.coerceAtLeast(1)
      starters = sortedByPace.take(count).map { it.copy(isStarter = true) }.sortedWith(playerComparator(playersDb))
      val starterIds = starters.map { it.playerId }.toSet()
      bench = bench.filter { it.playerId !in starterIds }
    }

    // Best Ball bubble & contender tagging
    if (config.isBestBall && starters.isNotEmpty()) {
      val nonQbDefStarters = starters.filter {
        val pos = playersDb[it.playerId]?.position.orEmpty()
        pos != "QB" && pos != "Def" && pos != "PK"
      }
      val bubbleStarter = (nonQbDefStarters.ifEmpty { starters }).minByOrNull { it.score }
      if (bubbleStarter != null) {
        starters = starters.map {
          if (it.playerId == bubbleStarter.playerId) it.copy(isOptimalBubble = true) else it
        }
        bench = bench.map { b ->
          val canCrack = b.gameSecondsRemaining > 0 && b.livePaceProjection >= (bubbleStarter.score - 2.0)
          b.copy(canCrackOptimal = canCrack)
        }
      }
    }

    // Bench insights (Standard: bench outscored a finished/4Q starter; never compare against unplayed starters!)
    val insights = ArrayList<BenchInsight>()
    if (!config.isBestBall) {
      for (b in bench) {
        if (b.score <= 0.0) continue
        val bPlayer = playersDb[b.playerId] ?: continue
        val worstFinishedOrLateStarter = starters
          .filter {
            playersDb[it.playerId]?.position == bPlayer.position &&
              it.gameSecondsRemaining <= 900
          }
          .minByOrNull { max(it.score, it.livePaceProjection) }
          ?: continue
        val expectedStarterFinish = max(worstFinishedOrLateStarter.score, worstFinishedOrLateStarter.livePaceProjection)
        val swing = b.score - worstFinishedOrLateStarter.score
        if (b.score - expectedStarterFinish >= 3.0 && swing >= 3.0) {
          val sPlayer = playersDb[worstFinishedOrLateStarter.playerId]
          insights.add(
            BenchInsight(
              benchPlayerName = bPlayer.displayName,
              benchScore = b.score,
              targetStarterName = sPlayer?.displayName ?: worstFinishedOrLateStarter.playerId,
              starterScore = worstFinishedOrLateStarter.score,
              position = bPlayer.position,
              pointSwing = swing
            )
          )
        }
      }
    }

    // Accurate Playing / Yet-to-Play / Final counts across counting slots (or roster in Best Ball)
    val trackedPool = if (config.isBestBall && starters.isEmpty()) mappedPlayers else starters
    val playingCount = if (trackedPool.isNotEmpty()) trackedPool.count { it.isPlayingNow } else raw.playersCurrentlyPlaying
    val yetCount = if (trackedPool.isNotEmpty()) trackedPool.count { it.isYetToPlay } else raw.playersYetToPlay
    val finalCount = trackedPool.count { it.isFinal }

    val projTotal = if (starters.isNotEmpty()) {
      val starterScoreSum = starters.sumOf { it.score }
      val bonusAdjustment = raw.score - starterScoreSum // Preserves home-field / team adjustments!
      starters.sumOf { it.livePaceProjection } + bonusAdjustment
    } else {
      raw.score
    }

    val prevFScore = prevFranchiseScores[raw.franchiseId]
    val fDelta = if (prevFScore != null && raw.score > prevFScore) (raw.score - prevFScore) else 0.0

    return FranchiseMatchupSide(
      franchiseId = raw.franchiseId,
      franchiseName = fName,
      logoUrl = fInfo?.logoUrl ?: fInfo?.iconUrl,
      score = raw.score,
      projectedScore = max(raw.score, projTotal),
      isHome = raw.isHome,
      playersCurrentlyPlaying = playingCount,
      playersYetToPlay = yetCount,
      playersFinal = finalCount,
      gameSecondsRemaining = raw.gameSecondsRemaining.takeIf { it > 0 }
        ?: starters.sumOf { it.gameSecondsRemaining },
      starters = starters,
      bench = bench,
      benchInsights = insights.sortedByDescending { it.pointSwing }.take(3),
      recentScoreDelta = fDelta
    )
  }

  internal fun computeWinProbabilities(
    away: FranchiseMatchupSide,
    home: FranchiseMatchupSide
  ): Pair<Float, Float> {
    val totalRemSeconds = (away.gameSecondsRemaining + home.gameSecondsRemaining).coerceAtLeast(0)
    if (totalRemSeconds == 0) {
      return when {
        away.score > home.score -> 1.0f to 0.0f
        home.score > away.score -> 0.0f to 1.0f
        else -> 0.5f to 0.5f
      }
    }
    // Standard deviation scales with sqrt(remaining player-games); ~7.5 pts per full player game
    val remainingPlayerGames = totalRemSeconds / 3600.0
    val sigma = max(3.5, 7.5 * sqrt(remainingPlayerGames))
    val expectedDiff = away.projectedScore - home.projectedScore
    val z = expectedDiff / sigma
    val pAway = (1.0 / (1.0 + exp(-1.65 * z))).toFloat().coerceIn(0.02f, 0.98f)
    return pAway to (1.0f - pAway)
  }

  private fun defaultPositionProjection(position: String?): Double = when (position?.uppercase()) {
    "QB" -> 18.5
    "RB" -> 12.0
    "WR" -> 11.5
    "TE" -> 8.5
    "PK" -> 8.0
    "DEF", "ST" -> 7.5
    else -> 7.0
  }

  private fun playerComparator(playersDb: Map<String, FantasyPlayer>): Comparator<PlayerLiveScore> =
    compareBy<PlayerLiveScore> { p ->
      val pos = playersDb[p.playerId]?.position.orEmpty()
      val idx = positionOrder.indexOfFirst { it.equals(pos, ignoreCase = true) }
      if (idx == -1) positionOrder.size else idx
    }.thenByDescending { it.score }

  /**
   * Builds a unified Cross-League Player Exposure map across ALL active leagues,
   * identifying `MINE`, `OPPONENT`, and `CONFLICT` (starting for me in League A, against me in League B!).
   */
  fun buildCrossLeagueExposure(
    snapshots: Collection<LeagueLiveSnapshot>,
    playersDb: Map<String, FantasyPlayer>
  ): Map<String, PlayerCrossLeagueExposure> {
    val myLeaguesByPlayer = HashMap<String, MutableSet<String>>()
    val oppLeaguesByPlayer = HashMap<String, MutableSet<String>>()
    val bestBallContenderIds = HashSet<String>()

    for (snap in snapshots) {
      if (!snap.config.includeInAnnouncements) continue
      val myId = snap.config.franchiseId
      val includeRoster = snap.config.isBestBall && snap.config.bestBallTrackFullRoster
      for (m in snap.matchups) {
        if (!m.involvesMyFranchise) continue
        for (side in m.franchises) {
          val pool = if (includeRoster) (side.starters + side.bench) else side.starters
          val isMine = side.franchiseId == myId
          for (ps in pool) {
            if (isMine) {
              myLeaguesByPlayer.getOrPut(ps.playerId) { LinkedHashSet() }.add(snap.config.leagueName)
              if (!ps.isStarter && snap.config.isBestBall) bestBallContenderIds.add(ps.playerId)
            } else {
              // For opponents in Best Ball, track starters + live optimal contenders so noise stays smart
              if (ps.isStarter || ps.canCrackOptimal) {
                oppLeaguesByPlayer.getOrPut(ps.playerId) { LinkedHashSet() }.add(snap.config.leagueName)
              }
            }
          }
        }
      }
    }

    val allIds = myLeaguesByPlayer.keys + oppLeaguesByPlayer.keys
    val result = HashMap<String, PlayerCrossLeagueExposure>(allIds.size)
    for (pid in allIds) {
      val player = playersDb[pid] ?: continue
      val mine = myLeaguesByPlayer[pid]?.toList().orEmpty()
      val opp = oppLeaguesByPlayer[pid]?.toList().orEmpty()
      val role = when {
        mine.isNotEmpty() && opp.isNotEmpty() -> ExposureRole.CONFLICT
        mine.isNotEmpty() -> ExposureRole.MINE
        else -> ExposureRole.OPPONENT
      }
      result[pid] = PlayerCrossLeagueExposure(
        player = player,
        role = role,
        myLeagueNames = mine,
        opponentLeagueNames = opp,
        isBestBallBenchContender = pid in bestBallContenderIds && mine.size == 1
      )
    }
    return result
  }

  /**
   * Evaluates which tracked fantasy players were involved in a [GameEvent] and whether
   * the event passes the user's [AnnouncementScope].
   */
  fun findInvolvedExposures(
    event: GameEvent,
    exposures: Map<String, PlayerCrossLeagueExposure>,
    playersDb: Map<String, FantasyPlayer> = emptyMap()
  ): List<PlayerCrossLeagueExposure> {
    if (event.kind == GameEventKind.FANTASY_LEAD_CHANGE) return emptyList()
    val play = event.play ?: return emptyList()
    val gameTeams = event.game.teams.toSet()
    val combinedText = "${play.text} ${play.rawPlayText}"

    return exposures.values.filter { exp ->
      val p = exp.player
      if (p.team !in gameTeams) return@filter false
      if (p.isDefensiveTeamUnit) {
        val evTeam = event.team ?: return@filter false
        if (evTeam.team != p.team) return@filter false
        when (event.kind) {
          GameEventKind.SACK,
          GameEventKind.INTERCEPTION,
          GameEventKind.FUMBLE_LOST,
          GameEventKind.SAFETY,
          GameEventKind.FOURTH_DOWN_STOP -> true
          GameEventKind.TOUCHDOWN -> play.offenseTeamId != null && play.offenseTeamId != evTeam.espnId
          else -> false
        }
      } else if (p.isOffensiveTeamUnit) {
        val evTeam = event.team ?: return@filter false
        if (evTeam.team != p.team) return@filter false
        val isTeamOffensePlay = play.offenseTeamId == null || play.offenseTeamId == evTeam.espnId
        val posCode = p.position.trim().uppercase()
        when (posCode) {
          "TMPN" -> false
          "TMPK" -> isTeamOffensePlay && (
            event.kind == GameEventKind.FIELD_GOAL || (
              event.kind == GameEventKind.TRY_FAILED &&
                !combinedText.contains("two-point", ignoreCase = true) &&
                !combinedText.contains("2-point", ignoreCase = true)
              )
            )
          "OFF" -> isTeamOffensePlay && (
            event.kind == GameEventKind.TOUCHDOWN ||
              event.kind == GameEventKind.TWO_POINT_GOOD ||
              event.kind == GameEventKind.BIG_PLAY ||
              event.kind == GameEventKind.FIRST_DOWN
            )
          "TMQB", "TMRB", "TMWR", "TMTE" -> {
            val isOffensiveSkillEvent = isTeamOffensePlay && (
              event.kind == GameEventKind.TOUCHDOWN ||
                event.kind == GameEventKind.TWO_POINT_GOOD ||
                event.kind == GameEventKind.BIG_PLAY ||
                event.kind == GameEventKind.FIRST_DOWN
              )
            if (!isOffensiveSkillEvent) {
              false
            } else {
              val targetPos = posCode.removePrefix("TM")
              val candidates = playersDb.values.filter {
                it.team == p.team && it.position.equals(targetPos, ignoreCase = true)
              }
              if (candidates.isNotEmpty()) {
                candidates.any { candidate ->
                  combinedText.contains(candidate.displayName, ignoreCase = true) ||
                    (candidate.abbreviatedEspnName != null && combinedText.contains(candidate.abbreviatedEspnName!!, ignoreCase = true))
                }
              } else {
                when (targetPos) {
                  "QB" -> combinedText.contains("pass", ignoreCase = true) ||
                    combinedText.contains("scramble", ignoreCase = true) ||
                    combinedText.contains("sneak", ignoreCase = true)
                  else -> false
                }
              }
            }
          }
          else -> false
        }
      } else {
        combinedText.contains(p.displayName, ignoreCase = true) ||
          (p.abbreviatedEspnName != null && combinedText.contains(p.abbreviatedEspnName!!, ignoreCase = true))
      }
    }
  }

  fun isEventRelevantForScope(
    event: GameEvent,
    scope: AnnouncementScope,
    exposures: Map<String, PlayerCrossLeagueExposure>,
    involved: List<PlayerCrossLeagueExposure>
  ): Boolean {
    if (event.kind == GameEventKind.FANTASY_LEAD_CHANGE) return true
    if (exposures.isEmpty()) return true // NFL Live Only mode -> announce all NFL games!
    return when (scope) {
      AnnouncementScope.ALL_NFL -> true
      AnnouncementScope.MY_GAMES -> {
        val activeTeams = exposures.values.map { it.player.team }.toSet()
        event.game.teams.any { it in activeTeams }
      }
      AnnouncementScope.MY_PLAYERS -> involved.isNotEmpty()
    }
  }
}
