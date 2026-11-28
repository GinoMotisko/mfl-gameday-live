package com.mflgameday.live.engine

import com.mflgameday.live.model.FantasyMatchup
import com.mflgameday.live.model.GameEvent
import com.mflgameday.live.model.GameEventKind
import com.mflgameday.live.model.GamePhase
import com.mflgameday.live.model.LiveFieldSituation
import com.mflgameday.live.model.LiveFieldSituationResolver
import com.mflgameday.live.model.NflGameState
import com.mflgameday.live.model.NflPlay
import com.mflgameday.live.model.NflSide
import com.mflgameday.live.model.PlayKind
import com.mflgameday.live.model.PlayLogEntry
import kotlin.math.abs

/**
 * Pure, deterministic state machine that diffs successive NFL scoreboard snapshots,
 * ESPN play-by-play drives, and MFL fantasy matchups into deduplicated [GameEvent]s.
 */
class EventDiffer(
  private val maxPlayAgeMillis: Long = 480_000L,
  private val maxReviewUpgradeAgeMillis: Long = 1_200_000L,
  private val redZoneRepeatCooldownMillis: Long = 90_000L,
  private val fantasyEchoCooldownMillis: Long = 180_000L
) {
  private data class PendingTry(
    val gameId: String,
    val scoredAway: Boolean,
    val scoreBefore: Int
  )

  private val gamesById = LinkedHashMap<String, NflGameState>()
  private val gamesByMatchupKey = HashMap<String, NflGameState>()
  private val seenPlaysByGame = HashMap<String, MutableSet<String>>()
  private val announcedScoringPlayIds = HashSet<String>()
  private val announcedPhaseTransitions = HashSet<String>()
  private val finalizedMatchupKeys = HashSet<String>()
  private val pendingScoreCorrectionByMatchup = HashMap<String, Pair<Int, Int>>()
  private val lastRedZoneAlertMillisByTeamKey = HashMap<String, Long>()
  private val recentFantasyLeadSignatures = HashMap<String, Long>()
  private val pendingTriesByPlayId = LinkedHashMap<String, PendingTry>()
  private val previousMatchupLeaderByKey = HashMap<String, String?>()

  fun currentGame(gameId: String): NflGameState? = gamesById[gameId]

  fun stabilizedGames(orderedRawGames: List<NflGameState>): List<NflGameState> =
    orderedRawGames.map { gamesByMatchupKey[canonicalMatchupKey(it)] ?: gamesById[it.id] ?: it }

  fun refineGameWithPlays(gameId: String, plays: List<NflPlay>): NflGameState? {
    val cur = gamesById[gameId] ?: return null
    val refined = LiveFieldSituationResolver.refineWithPlayHistory(cur, plays)
    if (refined != cur) {
      gamesById[gameId] = refined
      gamesByMatchupKey[canonicalMatchupKey(refined)] = refined
    }
    return refined
  }

  fun reset() {
    gamesById.clear()
    gamesByMatchupKey.clear()
    seenPlaysByGame.clear()
    announcedScoringPlayIds.clear()
    announcedPhaseTransitions.clear()
    finalizedMatchupKeys.clear()
    pendingScoreCorrectionByMatchup.clear()
    lastRedZoneAlertMillisByTeamKey.clear()
    recentFantasyLeadSignatures.clear()
    pendingTriesByPlayId.clear()
    previousMatchupLeaderByKey.clear()
  }

  fun clearFantasyMatchupLeaderForLeague(leagueId: String) {
    val prefix = "${leagueId}_"
    previousMatchupLeaderByKey.keys.removeIf { it.startsWith(prefix) }
    recentFantasyLeadSignatures.keys.removeIf { it.startsWith(prefix) }
  }

  /**
   * Pre-populates seen play IDs and one-shot game phase transitions (`KICKOFF`, `HALFTIME`, `FINAL`)
   * from persisted PlayLog history so restarting or updating the app never re-announces completed
   * phases or plays across CDN flaps and provider failovers.
   */
  fun seedSeenFromLog(entries: Collection<PlayLogEntry>) {
    for (entry in entries) {
      val mKey = canonicalMatchupKey(entry.event.game)
      when (entry.event.kind) {
        GameEventKind.KICKOFF -> {
          announcedPhaseTransitions.add("${mKey}_KICKOFF")
        }
        GameEventKind.HALFTIME -> {
          announcedPhaseTransitions.add("${mKey}_KICKOFF")
          announcedPhaseTransitions.add("${mKey}_HALFTIME")
        }
        GameEventKind.FINAL -> {
          announcedPhaseTransitions.add("${mKey}_KICKOFF")
          announcedPhaseTransitions.add("${mKey}_HALFTIME")
          announcedPhaseTransitions.add("${mKey}_FINAL")
          finalizedMatchupKeys.add(mKey)
        }
        else -> Unit
      }
      val play = entry.event.play ?: continue
      val gameId = entry.event.game.id
      seenPlaysByGame.getOrPut(gameId) { HashSet() }.add(play.id)
      if (play.kind.isScoring) {
        announcedScoringPlayIds.add(play.id)
      }
    }
  }

  /**
   * Ingests a scoreboard snapshot.
   * When [emitFallbackScoreEvents] is true (e.g. MFL-only fallback without ESPN play-by-play),
   * +6/+7/+8 and +3/+2 score jumps also emit synthetic Touchdown/Field Goal/Safety events.
   */
  fun ingestScoreboard(
    scoreboard: List<NflGameState>,
    nowMillis: Long,
    emitFallbackScoreEvents: Boolean = false
  ): List<GameEvent> {
    val emitted = ArrayList<GameEvent>()

    for (rawIncoming in scoreboard) {
      val matchupKey = canonicalMatchupKey(rawIncoming)
      val prev = gamesByMatchupKey[matchupKey] ?: gamesById[rawIncoming.id]
      if (rawIncoming.phase == GamePhase.FINAL) {
        finalizedMatchupKeys.add(matchupKey)
      }
      if (prev == null) {
        if (rawIncoming.phase != GamePhase.SCHEDULED) {
          announcedPhaseTransitions.add("${matchupKey}_KICKOFF")
        }
        if (
          rawIncoming.phase == GamePhase.HALFTIME ||
          rawIncoming.phase == GamePhase.FINAL ||
          (rawIncoming.phase == GamePhase.LIVE && rawIncoming.period >= 3)
        ) {
          announcedPhaseTransitions.add("${matchupKey}_HALFTIME")
        }
        if (rawIncoming.phase == GamePhase.FINAL || matchupKey in finalizedMatchupKeys) {
          announcedPhaseTransitions.add("${matchupKey}_FINAL")
          finalizedMatchupKeys.add(matchupKey)
        }
        val initial = if (matchupKey in finalizedMatchupKeys && rawIncoming.phase != GamePhase.FINAL) {
          rawIncoming.copy(
            phase = GamePhase.FINAL,
            clock = "0:00",
            inRedZone = false,
            isLiveDown = false,
            fieldSituation = LiveFieldSituation.SCRIMMAGE_DRIVE
          )
        } else {
          rawIncoming
        }
        if (initial.inRedZone && initial.phase == GamePhase.LIVE) {
          initial.possessionTeam?.code?.let { teamCode ->
            lastRedZoneAlertMillisByTeamKey["${matchupKey}_$teamCode"] = nowMillis
          }
        }
        gamesById[initial.id] = initial
        gamesByMatchupKey[matchupKey] = initial
        continue
      }

      // Monotonic phase latch: once FINAL (or KICKOFF/HALFTIME started), lagging CDN edges cannot regress backward
      val effectivePhase = when {
        matchupKey in finalizedMatchupKeys || prev.phase == GamePhase.FINAL -> GamePhase.FINAL
        prev.phase != GamePhase.SCHEDULED && rawIncoming.phase == GamePhase.SCHEDULED -> prev.phase
        prev.phase == GamePhase.LIVE && prev.period >= 3 && rawIncoming.phase == GamePhase.HALFTIME -> GamePhase.LIVE
        else -> rawIncoming.phase
      }

      // Require 2 consecutive downward observations before lowering a team's score (suppresses 1-poll stale CDN dips)
      var confirmedScoreCorrection = false
      val rawDownward = rawIncoming.away.score < prev.away.score || rawIncoming.home.score < prev.home.score
      val effectiveAwayScore: Int
      val effectiveHomeScore: Int
      if (rawDownward) {
        val candidate = rawIncoming.away.score to rawIncoming.home.score
        if (pendingScoreCorrectionByMatchup[matchupKey] == candidate) {
          pendingScoreCorrectionByMatchup.remove(matchupKey)
          effectiveAwayScore = rawIncoming.away.score
          effectiveHomeScore = rawIncoming.home.score
          confirmedScoreCorrection = true
        } else {
          pendingScoreCorrectionByMatchup[matchupKey] = candidate
          effectiveAwayScore = maxOf(rawIncoming.away.score, prev.away.score)
          effectiveHomeScore = maxOf(rawIncoming.home.score, prev.home.score)
        }
      } else {
        pendingScoreCorrectionByMatchup.remove(matchupKey)
        effectiveAwayScore = rawIncoming.away.score
        effectiveHomeScore = rawIncoming.home.score
      }

      val incoming = rawIncoming.copy(
        phase = effectivePhase,
        away = rawIncoming.away.copy(score = effectiveAwayScore),
        home = rawIncoming.home.copy(score = effectiveHomeScore),
        clock = if (effectivePhase == GamePhase.FINAL) "0:00" else rawIncoming.clock
      )
      val mappedPrevPossEspnId = when (prev.possessionTeam) {
        incoming.away.team -> incoming.away.espnId
        incoming.home.team -> incoming.home.espnId
        else -> prev.possessionEspnId?.takeIf { incoming.sideByEspnId(it) != null }
      }

      // Distinguish genuine mid-drive blinks from post-score PAT & commercial-break Kickoff states
      val awayDeltaRaw = incoming.away.score - prev.away.score
      val homeDeltaRaw = incoming.home.score - prev.home.score
      val scoreIncreased = awayDeltaRaw > 0 || homeDeltaRaw > 0
      val kickoffCompletedNow = run {
        val lower = incoming.lastPlayText.trim().lowercase()
        (lower.contains("kicks ") || lower.startsWith("kickoff") || lower.contains("touchback")) &&
          !lower.contains("touchdown")
      }
      val stabilized = when {
        incoming.phase != GamePhase.LIVE -> incoming.copy(
          inRedZone = false,
          fieldSituation = LiveFieldSituation.SCRIMMAGE_DRIVE
        )
        incoming.isLiveDown && incoming.possessionEspnId != null -> incoming.copy(
          fieldSituation = LiveFieldSituation.SCRIMMAGE_DRIVE
        )
        scoreIncreased -> {
          val scoredAway = awayDeltaRaw >= homeDeltaRaw
          val maxDelta = maxOf(awayDeltaRaw, homeDeltaRaw)
          val wasPatPending = prev.fieldSituation == LiveFieldSituation.PAT_PENDING
          val nextSit = when {
            wasPatPending -> LiveFieldSituation.KICKOFF_PENDING
            incoming.fieldSituation == LiveFieldSituation.KICKOFF_PENDING -> LiveFieldSituation.KICKOFF_PENDING
            maxDelta == 6 && !(awayDeltaRaw > 0 && homeDeltaRaw > 0) -> LiveFieldSituation.PAT_PENDING
            else -> LiveFieldSituation.KICKOFF_PENDING
          }
          val isSafetyKick = maxDelta == 2 && !wasPatPending && !(awayDeltaRaw > 0 && homeDeltaRaw > 0)
          val kickingOrTryEspnId = when {
            // NFL Rule 11-3-2: after ANY try (including a defensive 2-pt conversion return), the TD-scoring team kicks off
            wasPatPending && mappedPrevPossEspnId != null -> mappedPrevPossEspnId
            isSafetyKick -> if (scoredAway) incoming.home.espnId else incoming.away.espnId
            else -> if (scoredAway) incoming.away.espnId else incoming.home.espnId
          }
          incoming.copy(
            possessionEspnId = kickingOrTryEspnId,
            inRedZone = false,
            fieldSituation = nextSit
          )
        }
        prev.fieldSituation == LiveFieldSituation.KICKOFF_PENDING -> {
          val possessionFlippedToReceiver =
            incoming.possessionEspnId != null &&
              mappedPrevPossEspnId != null &&
              incoming.possessionEspnId != mappedPrevPossEspnId
          if (kickoffCompletedNow || possessionFlippedToReceiver) {
            // Dynamic Kickoff touchback / return completed before 1st & 10 down marker posted -> exit KICKOFF_PENDING!
            incoming.copy(
              inRedZone = false,
              fieldSituation = LiveFieldSituation.SCRIMMAGE_DRIVE
            )
          } else {
            // Once XP/2-Pt/FG resolved, stay in KICKOFF_PENDING across Official Timeouts until kickoff!
            incoming.copy(
              possessionEspnId = incoming.possessionEspnId ?: mappedPrevPossEspnId,
              inRedZone = false,
              fieldSituation = LiveFieldSituation.KICKOFF_PENDING
            )
          }
        }
        incoming.fieldSituation != LiveFieldSituation.SCRIMMAGE_DRIVE -> incoming.copy(
          possessionEspnId = incoming.possessionEspnId ?: mappedPrevPossEspnId,
          inRedZone = false
        )
        prev.fieldSituation != LiveFieldSituation.SCRIMMAGE_DRIVE -> {
          // Official Timeout / TV Commercial break after a TD: keep awaiting PAT/Kickoff until next 1st down!
          incoming.copy(
            possessionEspnId = incoming.possessionEspnId ?: mappedPrevPossEspnId,
            inRedZone = false,
            fieldSituation = prev.fieldSituation
          )
        }
        incoming.possessionEspnId == null || !incoming.isLiveDown -> {
          // Mid-drive timeout without any score change: preserve offensive possession & red-zone state
          incoming.copy(
            possessionEspnId = mappedPrevPossEspnId,
            inRedZone = prev.inRedZone
          )
        }
        else -> incoming
      }
      gamesById[stabilized.id] = stabilized
      gamesByMatchupKey[matchupKey] = stabilized

      fun buildEvent(kind: GameEventKind, team: NflSide? = null): GameEvent = GameEvent(
        id = if (kind == GameEventKind.KICKOFF || kind == GameEventKind.HALFTIME || kind == GameEventKind.FINAL) {
          "${matchupKey}_${kind.name}"
        } else {
          "${matchupKey}_${kind.name}_$nowMillis"
        },
        kind = kind,
        game = stabilized,
        team = team,
        play = null,
        awayScore = stabilized.away.score,
        homeScore = stabilized.home.score,
        observedAtMillis = nowMillis
      )

      when {
        prev.phase == GamePhase.SCHEDULED &&
          stabilized.phase == GamePhase.LIVE &&
          matchupKey !in finalizedMatchupKeys &&
          announcedPhaseTransitions.add("${matchupKey}_KICKOFF") ->
          emitted.add(buildEvent(GameEventKind.KICKOFF))
        prev.phase == GamePhase.LIVE &&
          stabilized.phase == GamePhase.HALFTIME &&
          matchupKey !in finalizedMatchupKeys &&
          announcedPhaseTransitions.add("${matchupKey}_HALFTIME") ->
          emitted.add(buildEvent(GameEventKind.HALFTIME))
        (prev.phase == GamePhase.LIVE || prev.phase == GamePhase.HALFTIME) &&
          stabilized.phase == GamePhase.FINAL &&
          announcedPhaseTransitions.add("${matchupKey}_FINAL") -> {
          finalizedMatchupKeys.add(matchupKey)
          emitted.add(buildEvent(GameEventKind.FINAL))
        }
      }

      if (confirmedScoreCorrection) {
        emitted.add(buildEvent(GameEventKind.SCORE_CORRECTION))
      } else if (emitFallbackScoreEvents) {
        val awayDelta = stabilized.away.score - prev.away.score
        val homeDelta = stabilized.home.score - prev.home.score
        if (awayDelta > 0) {
          emitted.add(buildFallbackScoreEvent(stabilized, stabilized.away, awayDelta, nowMillis))
        }
        if (homeDelta > 0) {
          emitted.add(buildFallbackScoreEvent(stabilized, stabilized.home, homeDelta, nowMillis))
        }
      }

      if (prev.possessionTeam != null && stabilized.possessionTeam != null && prev.possessionTeam != stabilized.possessionTeam) {
        lastRedZoneAlertMillisByTeamKey.remove("${matchupKey}_${prev.possessionTeam?.code}")
      }
      val enteredRedZone = stabilized.inRedZone &&
        !(prev.inRedZone && prev.possessionTeam != null && prev.possessionTeam == stabilized.possessionTeam)
      if (enteredRedZone && stabilized.phase == GamePhase.LIVE) {
        val offenseSide = stabilized.sideByEspnId(stabilized.possessionEspnId)
        if (offenseSide != null) {
          val rzKey = "${matchupKey}_${offenseSide.team.code}"
          val lastRzAt = lastRedZoneAlertMillisByTeamKey[rzKey]
          if (lastRzAt == null || (nowMillis - lastRzAt) >= redZoneRepeatCooldownMillis) {
            lastRedZoneAlertMillisByTeamKey[rzKey] = nowMillis
            emitted.add(buildEvent(GameEventKind.RED_ZONE, team = offenseSide))
          }
        }
      }
    }

    return emitted
  }

  /**
   * Ingests ordered, deduplicated plays for a live NFL game.
   */
  fun ingestPlays(
    plays: List<NflPlay>,
    gameId: String,
    nowMillis: Long
  ): List<GameEvent> {
    val firstSight = !seenPlaysByGame.containsKey(gameId)
    val seen = seenPlaysByGame.getOrPut(gameId) { HashSet() }
    val game = gamesById[gameId]
    if (game == null) {
      plays.forEach { p ->
        seen.add(p.id)
        if (p.kind.isScoring) announcedScoringPlayIds.add(p.id)
      }
      return emptyList()
    }

    val events = ArrayList<GameEvent>()
    events.addAll(resolvePendingTries(plays, game, nowMillis))

    for ((index, play) in plays.withIndex()) {
      val isNewPlay = seen.add(play.id)
      val newlyScoringAfterReview = play.kind.isScoring && !announcedScoringPlayIds.contains(play.id)
      if (!isNewPlay && !newlyScoringAfterReview) continue
      if (play.kind.isScoring) announcedScoringPlayIds.add(play.id)

      val eventKind = mapPlayKindToEventKind(play.kind) ?: continue
      val isFresh = if (!isNewPlay && newlyScoringAfterReview) {
        isWithinAge(play, nowMillis, firstSight = false, maxAge = maxReviewUpgradeAgeMillis)
      } else {
        isFreshPlay(play, nowMillis, firstSight)
      }
      val canBackfillLiveScoreSilently =
        !isFresh &&
          firstSight &&
          play.kind.isScoring &&
          (game.phase == GamePhase.LIVE || game.phase == GamePhase.HALFTIME)
      if (!isFresh && !canBackfillLiveScoreSilently) continue

      val previous = if (index > 0) plays[index - 1] else null
      val creditedSide: NflSide?
      var tryPending = false

      if (play.kind.isScoring) {
        creditedSide = determineScoringSide(play, previous, game)
        if (!canBackfillLiveScoreSilently && play.kind == PlayKind.TOUCHDOWN && creditedSide != null) {
          val scoredAway = creditedSide.espnId == game.away.espnId
          val before = if (scoredAway) (previous?.awayScore ?: 0) else (previous?.homeScore ?: 0)
          val after = if (scoredAway) play.awayScore else play.homeScore
          if (after - before == 6 && !tryFailedInParenthetical(play.text)) {
            pendingTriesByPlayId[play.id] = PendingTry(
              gameId = game.id,
              scoredAway = scoredAway,
              scoreBefore = before
            )
            tryPending = true
          }
        }
      } else if (
        play.kind == PlayKind.SACK ||
        play.kind == PlayKind.INTERCEPTION ||
        play.kind == PlayKind.FUMBLE_LOST ||
        play.kind == PlayKind.FOURTH_DOWN_STOP
      ) {
        creditedSide = listOf(game.away, game.home).firstOrNull { it.espnId != play.offenseTeamId }
      } else {
        creditedSide = game.sideByEspnId(play.offenseTeamId)
      }

      events.add(
        GameEvent(
          id = "${game.id}_play_${play.id}_${eventKind.name}",
          kind = eventKind,
          game = game,
          team = creditedSide,
          play = play,
          awayScore = play.awayScore,
          homeScore = play.homeScore,
          observedAtMillis = if (canBackfillLiveScoreSilently) (play.wallclockEpochMillis ?: nowMillis) else nowMillis,
          tryPending = tryPending,
          isSilentBackfill = canBackfillLiveScoreSilently
        )
      )
    }

    return events
  }

  /**
   * Detects when the user's fantasy matchup flips lead (e.g. trailing -> leading or leading -> trailing).
   */
  fun ingestFantasyMatchups(
    leagueName: String,
    myFranchiseId: String,
    matchups: List<FantasyMatchup>,
    precision: Int,
    nowMillis: Long
  ): List<GameEvent> {
    val events = ArrayList<GameEvent>()
    for (m in matchups) {
      if (!m.involvesMyFranchise) continue
      val key = "${m.leagueId}_${m.id}"
      val diff = m.away.score - m.home.score
      val currentLeaderId: String? = when {
        abs(diff) < 0.001 -> null
        diff > 0 -> m.away.franchiseId
        else -> m.home.franchiseId
      }
      if (!previousMatchupLeaderByKey.containsKey(key)) {
        previousMatchupLeaderByKey[key] = currentLeaderId
        continue
      }
      val prevLeaderId = previousMatchupLeaderByKey[key]
      if (currentLeaderId != null && currentLeaderId != prevLeaderId && (m.away.score + m.home.score) > 0.0) {
        previousMatchupLeaderByKey[key] = currentLeaderId
        val fmt = "%.${precision}f"
        val sig = "${key}_${currentLeaderId}_${fmt.format(m.away.score)}_${fmt.format(m.home.score)}"
        val lastSigAt = recentFantasyLeadSignatures[sig]
        if (lastSigAt != null && (nowMillis - lastSigAt) < fantasyEchoCooldownMillis) {
          continue
        }
        recentFantasyLeadSignatures[sig] = nowMillis
        val leader = if (currentLeaderId == m.away.franchiseId) m.away else m.home
        val trailer = if (currentLeaderId == m.away.franchiseId) m.home else m.away
        val leadVerb = if (leader.franchiseId == myFranchiseId) "takes the lead" else "pulls ahead"
        val headline = "Fantasy lead change in $leagueName! ${leader.franchiseName} $leadVerb over ${trailer.franchiseName}, ${fmt.format(leader.score)} to ${fmt.format(trailer.score)}."
        val syntheticSide = NflSide(
          espnId = leader.franchiseId,
          team = com.mflgameday.live.model.NflTeam("NFL"),
          location = leagueName,
          nickname = leader.franchiseName,
          score = leader.score.toInt()
        )
        val syntheticGame = NflGameState(
          id = key,
          away = NflSide(m.away.franchiseId, com.mflgameday.live.model.NflTeam("NFL"), "", m.away.franchiseName, m.away.score.toInt()),
          home = NflSide(m.home.franchiseId, com.mflgameday.live.model.NflTeam("NFL"), "", m.home.franchiseName, m.home.score.toInt()),
          phase = GamePhase.LIVE
        )
        events.add(
          GameEvent(
            id = "${key}_lead_$nowMillis",
            kind = GameEventKind.FANTASY_LEAD_CHANGE,
            game = syntheticGame,
            team = syntheticSide,
            play = null,
            awayScore = m.away.score.toInt(),
            homeScore = m.home.score.toInt(),
            observedAtMillis = nowMillis,
            customHeadline = headline
          )
        )
      } else {
        previousMatchupLeaderByKey[key] = currentLeaderId
      }
    }
    return events
  }

  private fun resolvePendingTries(
    plays: List<NflPlay>,
    game: NflGameState,
    nowMillis: Long
  ): List<GameEvent> {
    if (pendingTriesByPlayId.isEmpty() || plays.isEmpty()) return emptyList()
    val resolved = ArrayList<GameEvent>()
    val latest = plays.last()
    val iterator = pendingTriesByPlayId.entries.iterator()

    while (iterator.hasNext()) {
      val (playId, pending) = iterator.next()
      if (pending.gameId != game.id) continue
      val idx = plays.indexOfFirst { it.id == playId }
      if (idx == -1) continue
      val tdPlay = plays[idx]
      val failedInText = tryFailedInParenthetical(tdPlay.text)
      if (!failedInText && idx >= plays.lastIndex) continue

      iterator.remove()
      val scoreNow = if (pending.scoredAway) latest.awayScore else latest.homeScore
      val delta = scoreNow - pending.scoreBefore
      val followUpKind: GameEventKind? = when {
        failedInText -> GameEventKind.TRY_FAILED
        delta == 6 -> GameEventKind.TRY_FAILED
        delta == 8 -> GameEventKind.TWO_POINT_GOOD
        else -> null
      }
      if (followUpKind != null) {
        resolved.add(
          GameEvent(
            id = "${game.id}_try_${playId}_${followUpKind.name}",
            kind = followUpKind,
            game = game,
            team = if (pending.scoredAway) game.away else game.home,
            play = tdPlay,
            awayScore = latest.awayScore,
            homeScore = latest.homeScore,
            observedAtMillis = nowMillis
          )
        )
      }
    }
    return resolved
  }

  private fun buildFallbackScoreEvent(
    game: NflGameState,
    side: NflSide,
    delta: Int,
    nowMillis: Long
  ): GameEvent {
    val kind = when (delta) {
      6, 7, 8 -> GameEventKind.TOUCHDOWN
      3 -> GameEventKind.FIELD_GOAL
      2 -> GameEventKind.SAFETY
      else -> GameEventKind.OTHER_SCORE
    }
    return GameEvent(
      id = "${game.id}_fallback_${kind.name}_$nowMillis",
      kind = kind,
      game = game,
      team = side,
      play = null,
      awayScore = game.away.score,
      homeScore = game.home.score,
      observedAtMillis = nowMillis
    )
  }

  private fun isFreshPlay(play: NflPlay, nowMillis: Long, firstSight: Boolean): Boolean =
    isWithinAge(play, nowMillis, firstSight, maxPlayAgeMillis)

  private fun isWithinAge(
    play: NflPlay,
    nowMillis: Long,
    firstSight: Boolean,
    maxAge: Long
  ): Boolean {
    val wc = play.wallclockEpochMillis ?: return !firstSight
    return (nowMillis - wc) <= maxAge
  }

  private fun determineScoringSide(play: NflPlay, previous: NflPlay?, game: NflGameState): NflSide? {
    val awayDelta = play.awayScore - (previous?.awayScore ?: 0)
    val homeDelta = play.homeScore - (previous?.homeScore ?: 0)
    if (awayDelta > 0 && awayDelta >= homeDelta) return game.away
    if (homeDelta > 0) return game.home
    return game.sideByEspnId(play.offenseTeamId)
  }

  companion object {
    fun canonicalMatchupKey(game: NflGameState): String {
      val awayCode = game.away.team.code.trim().uppercase()
      val homeCode = game.home.team.code.trim().uppercase()
      return if (
        awayCode.isNotEmpty() &&
        homeCode.isNotEmpty() &&
        awayCode != "NFL" &&
        homeCode != "NFL"
      ) {
        "${awayCode}@${homeCode}"
      } else {
        game.id
      }
    }

    internal fun tryFailedInParenthetical(text: String): Boolean {
      val openIdx = text.lastIndexOf('(')
      if (openIdx == -1) return false
      val paren = text.substring(openIdx).lowercase()
      return paren.contains("fail") || paren.contains("no good") || paren.contains("blocked")
    }

    internal fun mapPlayKindToEventKind(kind: PlayKind): GameEventKind? = when (kind) {
      PlayKind.TOUCHDOWN -> GameEventKind.TOUCHDOWN
      PlayKind.FIELD_GOAL -> GameEventKind.FIELD_GOAL
      PlayKind.SAFETY -> GameEventKind.SAFETY
      PlayKind.OTHER_SCORE -> GameEventKind.OTHER_SCORE
      PlayKind.SACK -> GameEventKind.SACK
      PlayKind.INTERCEPTION -> GameEventKind.INTERCEPTION
      PlayKind.FUMBLE_LOST -> GameEventKind.FUMBLE_LOST
      PlayKind.BIG_PLAY -> GameEventKind.BIG_PLAY
      PlayKind.FIRST_DOWN -> GameEventKind.FIRST_DOWN
      PlayKind.FOURTH_DOWN_STOP -> GameEventKind.FOURTH_DOWN_STOP
      PlayKind.OTHER -> null
    }

    fun mergePlayLogEntries(
      newEntries: List<PlayLogEntry>,
      existingLog: List<PlayLogEntry>,
      maxEntries: Int = 250
    ): List<PlayLogEntry> {
      val combined = newEntries.reversed() + existingLog
      val ordered = if (newEntries.any { it.event.isSilentBackfill }) {
        combined.sortedByDescending { it.timestampMillis }
      } else {
        combined
      }
      return ordered.take(maxEntries)
    }
  }
}
