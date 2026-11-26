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
  private val maxPlayAgeMillis: Long = 300_000L
) {
  private data class PendingTry(
    val gameId: String,
    val scoredAway: Boolean,
    val scoreBefore: Int
  )

  private val gamesById = LinkedHashMap<String, NflGameState>()
  private val seenPlaysByGame = HashMap<String, MutableSet<String>>()
  private val announcedScoringPlayIds = HashSet<String>()
  private val pendingTriesByPlayId = LinkedHashMap<String, PendingTry>()
  private val previousMatchupLeaderByKey = HashMap<String, String?>()

  fun currentGame(gameId: String): NflGameState? = gamesById[gameId]

  fun stabilizedGames(orderedRawGames: List<NflGameState>): List<NflGameState> =
    orderedRawGames.map { gamesById[it.id] ?: it }

  fun refineGameWithPlays(gameId: String, plays: List<NflPlay>): NflGameState? {
    val cur = gamesById[gameId] ?: return null
    val refined = LiveFieldSituationResolver.refineWithPlayHistory(cur, plays)
    if (refined != cur) {
      gamesById[gameId] = refined
    }
    return refined
  }

  fun reset() {
    gamesById.clear()
    seenPlaysByGame.clear()
    announcedScoringPlayIds.clear()
    pendingTriesByPlayId.clear()
    previousMatchupLeaderByKey.clear()
  }

  /**
   * Pre-populates seen play IDs from persisted PlayLog history so restarting the app mid-game
   * never re-announces plays that were already logged earlier in the quarter.
   */
  fun seedSeenFromLog(entries: Collection<PlayLogEntry>) {
    for (entry in entries) {
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

    for (incoming in scoreboard) {
      val prev = gamesById[incoming.id]
      if (prev == null) {
        gamesById[incoming.id] = incoming
        continue
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
            wasPatPending && prev.possessionEspnId != null -> prev.possessionEspnId
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
              prev.possessionEspnId != null &&
              incoming.possessionEspnId != prev.possessionEspnId
          if (kickoffCompletedNow || possessionFlippedToReceiver) {
            // Dynamic Kickoff touchback / return completed before 1st & 10 down marker posted -> exit KICKOFF_PENDING!
            incoming.copy(
              inRedZone = false,
              fieldSituation = LiveFieldSituation.SCRIMMAGE_DRIVE
            )
          } else {
            // Once XP/2-Pt/FG resolved, stay in KICKOFF_PENDING across Official Timeouts until kickoff!
            incoming.copy(
              possessionEspnId = incoming.possessionEspnId ?: prev.possessionEspnId,
              inRedZone = false,
              fieldSituation = LiveFieldSituation.KICKOFF_PENDING
            )
          }
        }
        incoming.fieldSituation != LiveFieldSituation.SCRIMMAGE_DRIVE -> incoming.copy(
          possessionEspnId = incoming.possessionEspnId ?: prev.possessionEspnId,
          inRedZone = false
        )
        prev.fieldSituation != LiveFieldSituation.SCRIMMAGE_DRIVE -> {
          // Official Timeout / TV Commercial break after a TD: keep awaiting PAT/Kickoff until next 1st down!
          incoming.copy(
            possessionEspnId = incoming.possessionEspnId ?: prev.possessionEspnId,
            inRedZone = false,
            fieldSituation = prev.fieldSituation
          )
        }
        incoming.possessionEspnId == null || !incoming.isLiveDown -> {
          // Mid-drive timeout without any score change: preserve offensive possession & red-zone state
          incoming.copy(
            possessionEspnId = prev.possessionEspnId,
            inRedZone = prev.inRedZone
          )
        }
        else -> incoming
      }
      gamesById[stabilized.id] = stabilized

      fun buildEvent(kind: GameEventKind, team: NflSide? = null): GameEvent = GameEvent(
        id = "${stabilized.id}_${kind.name}_$nowMillis",
        kind = kind,
        game = stabilized,
        team = team,
        play = null,
        awayScore = stabilized.away.score,
        homeScore = stabilized.home.score,
        observedAtMillis = nowMillis
      )

      when {
        prev.phase == GamePhase.SCHEDULED && stabilized.phase == GamePhase.LIVE ->
          emitted.add(buildEvent(GameEventKind.KICKOFF))
        prev.phase == GamePhase.LIVE && stabilized.phase == GamePhase.HALFTIME ->
          emitted.add(buildEvent(GameEventKind.HALFTIME))
        (prev.phase == GamePhase.LIVE || prev.phase == GamePhase.HALFTIME) && stabilized.phase == GamePhase.FINAL ->
          emitted.add(buildEvent(GameEventKind.FINAL))
      }

      if (stabilized.away.score < prev.away.score || stabilized.home.score < prev.home.score) {
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

      val enteredRedZone = stabilized.inRedZone &&
        !(prev.inRedZone && prev.possessionEspnId == stabilized.possessionEspnId)
      if (enteredRedZone && stabilized.phase == GamePhase.LIVE) {
        val offenseSide = stabilized.sideByEspnId(stabilized.possessionEspnId)
        if (offenseSide != null) {
          emitted.add(buildEvent(GameEventKind.RED_ZONE, team = offenseSide))
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
      if (!isFreshPlay(play, nowMillis, firstSight)) continue

      val previous = if (index > 0) plays[index - 1] else null
      val creditedSide: NflSide?
      var tryPending = false

      if (play.kind.isScoring) {
        creditedSide = determineScoringSide(play, previous, game)
        if (play.kind == PlayKind.TOUCHDOWN && creditedSide != null) {
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
          observedAtMillis = nowMillis,
          tryPending = tryPending
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
        val leader = if (currentLeaderId == m.away.franchiseId) m.away else m.home
        val trailer = if (currentLeaderId == m.away.franchiseId) m.home else m.away
        val fmt = "%.${precision}f"
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

  private fun isFreshPlay(play: NflPlay, nowMillis: Long, firstSight: Boolean): Boolean {
    val wc = play.wallclockEpochMillis ?: return !firstSight
    return (nowMillis - wc) <= maxPlayAgeMillis
  }

  private fun determineScoringSide(play: NflPlay, previous: NflPlay?, game: NflGameState): NflSide? {
    val awayDelta = play.awayScore - (previous?.awayScore ?: 0)
    val homeDelta = play.homeScore - (previous?.homeScore ?: 0)
    if (awayDelta > 0 && awayDelta >= homeDelta) return game.away
    if (homeDelta > 0) return game.home
    return game.sideByEspnId(play.offenseTeamId)
  }

  companion object {
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
  }
}
