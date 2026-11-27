package com.mflgameday.live.model

import kotlinx.serialization.Serializable

/**
 * Canonical NFL team identity keyed by MyFantasyLeague's 3-letter code,
 * with seamless bidirectional mapping to ESPN abbreviations and rich broadcast colors.
 */
@Serializable
data class NflTeam(
  val code: String
) {
  val info: TeamMeta
    get() = catalog[code] ?: TeamMeta(code, code.lowercase(), code, code, 0xFF1E293B, 0xFF38BDF8)

  val fullName: String
    get() = "${info.location} ${info.nickname}"

  val location: String
    get() = info.location

  val nickname: String
    get() = info.nickname

  val primaryColorHex: Long
    get() = info.primaryHex

  val accentColorHex: Long
    get() = info.accentHex

  /**
   * High-DPI transparent official NFL team logo on ESPN's public CDN.
   */
  val espnLogoUrl: String
    get() = "https://a.espncdn.com/i/teamlogos/nfl/500/${info.espnSlug}.png"

  data class TeamMeta(
    val code: String,
    val espnSlug: String,
    val location: String,
    val nickname: String,
    val primaryHex: Long,
    val accentHex: Long
  )

  companion object {
    private val espnToMfl = mapOf(
      "GB" to "GBP",
      "KC" to "KCC",
      "LV" to "LVR",
      "NE" to "NEP",
      "NO" to "NOS",
      "SF" to "SFO",
      "TB" to "TBB",
      "JAX" to "JAC",
      "WSH" to "WAS",
      "ARZ" to "ARI",
      "BLT" to "BAL",
      "CLV" to "CLE",
      "HST" to "HOU",
      "LA" to "LAR"
    )

    fun fromMfl(code: String): NflTeam {
      val normalized = code.trim().uppercase()
      return NflTeam(espnToMfl[normalized] ?: normalized)
    }

    fun fromEspn(abbreviation: String): NflTeam {
      val normalized = abbreviation.trim().uppercase()
      return NflTeam(espnToMfl[normalized] ?: normalized)
    }

    val catalog: Map<String, TeamMeta> = listOf(
      TeamMeta("ARI", "ari", "Arizona", "Cardinals", 0xFF97233F, 0xFFFFB612),
      TeamMeta("ATL", "atl", "Atlanta", "Falcons", 0xFFA71930, 0xFFA5ACAF),
      TeamMeta("BAL", "bal", "Baltimore", "Ravens", 0xFF241773, 0xFF9E7C0C),
      TeamMeta("BUF", "buf", "Buffalo", "Bills", 0xFF00338D, 0xFFC60C30),
      TeamMeta("CAR", "car", "Carolina", "Panthers", 0xFF0085CA, 0xFFBFC0BF),
      TeamMeta("CHI", "chi", "Chicago", "Bears", 0xFF0B162A, 0xFFC83803),
      TeamMeta("CIN", "cin", "Cincinnati", "Bengals", 0xFFFB4F14, 0xFF000000),
      TeamMeta("CLE", "cle", "Cleveland", "Browns", 0xFF311D00, 0xFFFF3C00),
      TeamMeta("DAL", "dal", "Dallas", "Cowboys", 0xFF003594, 0xFF869397),
      TeamMeta("DEN", "den", "Denver", "Broncos", 0xFFFB4F14, 0xFF002244),
      TeamMeta("DET", "det", "Detroit", "Lions", 0xFF0076B6, 0xFFB0B7BC),
      TeamMeta("GBP", "gb", "Green Bay", "Packers", 0xFF203731, 0xFFFFB612),
      TeamMeta("HOU", "hou", "Houston", "Texans", 0xFF03202F, 0xFFA71930),
      TeamMeta("IND", "ind", "Indianapolis", "Colts", 0xFF002C5F, 0xFFA2AAAD),
      TeamMeta("JAC", "jax", "Jacksonville", "Jaguars", 0xFF006778, 0xFFD7A22A),
      TeamMeta("KCC", "kc", "Kansas City", "Chiefs", 0xFFE31837, 0xFFFFB81C),
      TeamMeta("LAC", "lac", "Los Angeles", "Chargers", 0xFF0080C6, 0xFFFFC20E),
      TeamMeta("LAR", "lar", "Los Angeles", "Rams", 0xFF003594, 0xFFFFA300),
      TeamMeta("LVR", "lv", "Las Vegas", "Raiders", 0xFF111827, 0xFFA5ACAF),
      TeamMeta("MIA", "mia", "Miami", "Dolphins", 0xFF008E97, 0xFFFC4C02),
      TeamMeta("MIN", "min", "Minnesota", "Vikings", 0xFF4F2683, 0xFFFFC62F),
      TeamMeta("NEP", "ne", "New England", "Patriots", 0xFF002244, 0xFFC60C30),
      TeamMeta("NOS", "no", "New Orleans", "Saints", 0xFF101820, 0xFFD3BC8D),
      TeamMeta("NYG", "nyg", "New York", "Giants", 0xFF0B2265, 0xFFA71930),
      TeamMeta("NYJ", "nyj", "New York", "Jets", 0xFF125740, 0xFFFFFFFF),
      TeamMeta("PHI", "phi", "Philadelphia", "Eagles", 0xFF004C54, 0xFFA5ACAF),
      TeamMeta("PIT", "pit", "Pittsburgh", "Steelers", 0xFF101820, 0xFFFFB612),
      TeamMeta("SEA", "sea", "Seattle", "Seahawks", 0xFF002244, 0xFF69BE28),
      TeamMeta("SFO", "sf", "San Francisco", "49ers", 0xFFAA0000, 0xFFB3995D),
      TeamMeta("TBB", "tb", "Tampa Bay", "Buccaneers", 0xFFD50A0A, 0xFFFF7900),
      TeamMeta("TEN", "ten", "Tennessee", "Titans", 0xFF0C2340, 0xFF4B92DB),
      TeamMeta("WAS", "wsh", "Washington", "Commanders", 0xFF5A1414, 0xFFFFB612)
    ).associateBy { it.code }
  }
}

@Serializable
enum class GamePhase {
  SCHEDULED,
  LIVE,
  HALFTIME,
  FINAL
}

@Serializable
enum class LiveFieldSituation {
  SCRIMMAGE_DRIVE,
  PAT_PENDING,
  KICKOFF_PENDING
}

@Serializable
data class NflSide(
  val espnId: String,
  val team: NflTeam,
  val location: String,
  val nickname: String,
  val score: Int,
  val record: String = "",
  val logoUrl: String? = null
) {
  val fullName: String
    get() = "$location $nickname".trim()

  val resolvedLogoUrl: String
    get() = logoUrl?.takeIf { it.isNotBlank() } ?: team.espnLogoUrl
}

@Serializable
data class NflGameState(
  val id: String,
  val away: NflSide,
  val home: NflSide,
  val phase: GamePhase,
  val period: Int = 0,
  val clock: String = "",
  val possessionEspnId: String? = null,
  val inRedZone: Boolean = false,
  val isLiveDown: Boolean = true,
  val fieldSituation: LiveFieldSituation = LiveFieldSituation.SCRIMMAGE_DRIVE,
  val downDistanceText: String = "",
  val possessionText: String = "",
  val yardLine: Int? = null,
  val lastPlayText: String = "",
  val kickoffEpochMillis: Long? = null,
  val venue: String = "",
  val broadcast: String = ""
) {
  val teams: List<NflTeam>
    get() = listOf(away.team, home.team)

  val possessionTeam: NflTeam?
    get() = sideByEspnId(possessionEspnId)?.team

  val effectiveInRedZone: Boolean
    get() = phase == GamePhase.LIVE && fieldSituation == LiveFieldSituation.SCRIMMAGE_DRIVE && inRedZone

  fun sideByEspnId(espnId: String?): NflSide? = when (espnId) {
    null -> null
    away.espnId -> away
    home.espnId -> home
    else -> null
  }

  fun sideForTeam(team: NflTeam): NflSide? = when (team) {
    away.team -> away
    home.team -> home
    else -> null
  }

  /**
   * Approximate progress from own goal line (0f) to opponent end zone (1f)
   * for the visual linear field-position bar.
   * - `KICKOFF_PENDING`: places the ball on the kicking team's tee (own 35 by default, or own 20..50 if spotted)
   *   instead of leaving it frozen inside the opponent's 15-yard Red Zone during commercial breaks.
   * - `PAT_PENDING`: spots the ball at the 2-yard conversion marker (`0.98f`).
   */
  val fieldProgressFraction: Float?
    get() {
      if (phase != GamePhase.LIVE || possessionEspnId == null) return null
      if (fieldSituation == LiveFieldSituation.KICKOFF_PENDING) {
        val parsedOwnSpot = parseOwnSideKickoffSpotFraction()
        return parsedOwnSpot ?: 0.35f
      }
      if (fieldSituation == LiveFieldSituation.PAT_PENDING) {
        return 0.98f
      }
      if (effectiveInRedZone) {
        val yds = yardLine?.coerceIn(1, 20) ?: 12
        return ((100 - yds) / 100f).coerceIn(0.80f, 0.99f)
      }
      val text = possessionText.trim()
      if (text.isEmpty()) return null
      if (text == "50") return 0.5f
      val parts = text.split(" ")
      if (parts.size != 2) return null
      val sideTeam = NflTeam.fromEspn(parts[0])
      val yd = parts[1].toIntOrNull()?.coerceIn(1, 49) ?: return null
      val possTeam = possessionTeam ?: return null
      val yardsFromOwnGoal = if (sideTeam == possTeam) yd else (100 - yd)
      return (yardsFromOwnGoal / 100f).coerceIn(0.02f, 0.99f)
    }

  private fun parseOwnSideKickoffSpotFraction(): Float? {
    val text = possessionText.trim()
    if (text == "50") return 0.50f
    val parts = text.split(" ")
    if (parts.size != 2) return null
    val sideTeam = NflTeam.fromEspn(parts[0])
    val yd = parts[1].toIntOrNull() ?: return null
    val possTeam = possessionTeam ?: return null
    return if (sideTeam == possTeam && yd in 20..49) yd / 100f else null
  }

  fun statusDisplay(formattedKickoff: String? = null): String = when (phase) {
    GamePhase.SCHEDULED -> formattedKickoff ?: "Scheduled"
    GamePhase.HALFTIME -> "Halftime"
    GamePhase.FINAL -> if (period > 4) "Final/OT" else "Final"
    GamePhase.LIVE -> {
      val q = if (period > 4) "OT" else "Q$period"
      "$q $clock".trim()
    }
  }
}

/**
 * Resolves whether a live NFL game is in an active scrimmage drive (`1st..4th down`),
 * awaiting a post-touchdown try (`PAT_PENDING`), or awaiting a kickoff across TV/official
 * timeouts (`KICKOFF_PENDING`) so games never stay stuck in a false Red Zone state after scoring.
 */
object LiveFieldSituationResolver {
  private val completedTryInSummaryRegex = Regex(
    """\((?:[^)]*\b(?:kick|two-point|2-point|pass|run|rush|failed|no good|blocked)\b[^)]*)\)""",
    RegexOption.IGNORE_CASE
  )

  fun classifyStateless(
    phase: GamePhase,
    isLiveDown: Boolean,
    inRedZoneRaw: Boolean,
    possessionTeam: NflTeam?,
    downDistanceText: String,
    possessionText: String,
    lastPlayTypeText: String,
    lastPlayText: String,
    period: Int,
    clock: String
  ): LiveFieldSituation {
    if (phase != GamePhase.LIVE || isLiveDown) {
      return LiveFieldSituation.SCRIMMAGE_DRIVE
    }
    val downTxt = downDistanceText.trim().lowercase()
    val typeTxt = lastPlayTypeText.trim().lowercase()
    val playTxt = lastPlayText.trim().lowercase()

    if (downTxt == "pat" || downTxt.contains("extra point") || downTxt.contains("two-point")) {
      return LiveFieldSituation.PAT_PENDING
    }

    val isCompletedKickoff =
      (typeTxt.contains("kickoff") && !typeTxt.contains("touchdown")) ||
        ((playTxt.contains("kicks ") || playTxt.startsWith("kickoff")) && !playTxt.contains("touchdown"))
    if (isCompletedKickoff) {
      return LiveFieldSituation.SCRIMMAGE_DRIVE
    }

    if (downTxt.contains("kickoff")) {
      return LiveFieldSituation.KICKOFF_PENDING
    }

    val effectivePlayTxt = playTxt.substringAfterLast("reversed.").trim()
    val isTouchdownPlay =
      typeTxt.contains("touchdown") ||
        (effectivePlayTxt.contains("touchdown") && !effectivePlayTxt.contains("nullified"))

    val isCompletedTryOrFgOrSafety =
      typeTxt.contains("extra point") ||
        typeTxt.contains("two-point") ||
        typeTxt.contains("2-point") ||
        typeTxt.contains("field goal good") ||
        typeTxt.contains("safety") ||
        effectivePlayTxt.contains("extra point") ||
        effectivePlayTxt.contains("two-point") ||
        effectivePlayTxt.contains("2-point") ||
        effectivePlayTxt.contains("field goal is good") ||
        effectivePlayTxt.contains(", safety") ||
        (isTouchdownPlay && completedTryInSummaryRegex.containsMatchIn(lastPlayText))

    if (isCompletedTryOrFgOrSafety) {
      return LiveFieldSituation.KICKOFF_PENDING
    }

    if (isTouchdownPlay) {
      return LiveFieldSituation.PAT_PENDING
    }

    // Start of half / overtime before opening kickoff
    if (downTxt.isEmpty() && (period == 1 || period == 3 || period >= 5) && (clock == "15:00" || clock == "10:00")) {
      return LiveFieldSituation.KICKOFF_PENDING
    }

    return LiveFieldSituation.SCRIMMAGE_DRIVE
  }

  /**
   * Refines non-down (`!isLiveDown`) games using ESPN's play-by-play list so even if the app
   * cold-starts mid-commercial break after `"Official Timeout"`, we look past the timeout row
   * to see that the preceding play was a TD/XP/FG and switch cleanly into `KICKOFF_PENDING` / `PAT_PENDING`.
   */
  fun refineWithPlayHistory(game: NflGameState, plays: List<NflPlay>): NflGameState {
    if (game.phase != GamePhase.LIVE || game.isLiveDown || plays.isEmpty()) return game
    val lastAction = plays.lastOrNull { !isAdministrativeStoppage(it) } ?: return game
    val rawLower = lastAction.rawPlayText.ifBlank { lastAction.text }.lowercase()
    val summaryLower = lastAction.text.lowercase()

    val isKickoffAlreadyHappened =
      (rawLower.contains("kicks ") || rawLower.startsWith("kickoff")) &&
        lastAction.kind != PlayKind.TOUCHDOWN
    if (isKickoffAlreadyHappened) {
      return if (game.fieldSituation == LiveFieldSituation.KICKOFF_PENDING) {
        game.copy(fieldSituation = LiveFieldSituation.SCRIMMAGE_DRIVE)
      } else {
        game
      }
    }

    val isPostScoreAwaitingKickoff =
      lastAction.kind == PlayKind.FIELD_GOAL ||
        lastAction.kind == PlayKind.SAFETY ||
        rawLower.contains("extra point") ||
        rawLower.contains("two-point") ||
        rawLower.contains("2-point") ||
        (lastAction.kind == PlayKind.TOUCHDOWN && completedTryInSummaryRegex.containsMatchIn(lastAction.text))

    if (isPostScoreAwaitingKickoff) {
      val kickingEspnId = inferKickingTeamEspnId(game, lastAction, plays) ?: game.possessionEspnId
      return game.copy(
        fieldSituation = LiveFieldSituation.KICKOFF_PENDING,
        inRedZone = false,
        possessionEspnId = kickingEspnId
      )
    }

    if (lastAction.kind == PlayKind.TOUCHDOWN && !summaryLower.contains("nullified")) {
      val scoringEspnId = inferKickingTeamEspnId(game, lastAction, plays) ?: game.possessionEspnId
      return game.copy(
        fieldSituation = LiveFieldSituation.PAT_PENDING,
        inRedZone = false,
        possessionEspnId = scoringEspnId
      )
    }

    return game
  }

  private fun isAdministrativeStoppage(play: NflPlay): Boolean {
    if (play.kind.isScoring || play.isTurnover) return false
    val txt = play.rawPlayText.ifBlank { play.text }.trim().lowercase()
    return txt.startsWith("timeout") ||
      txt.contains("official timeout") ||
      txt.contains("two-minute warning") ||
      txt.startsWith("end quarter") ||
      txt.startsWith("end of quarter") ||
      txt.startsWith("end period") ||
      txt.contains("injury timeout")
  }

  private fun inferKickingTeamEspnId(game: NflGameState, scoringPlay: NflPlay, plays: List<NflPlay>): String? {
    val idx = plays.indexOfFirst { it.id == scoringPlay.id }
    val rawLower = scoringPlay.rawPlayText.ifBlank { scoringPlay.text }.lowercase()
    val isTryAttempt =
      scoringPlay.kind != PlayKind.TOUCHDOWN &&
        (rawLower.contains("extra point") || rawLower.contains("two-point") || rawLower.contains("2-point"))
    if (isTryAttempt) {
      val precedingTd = if (idx > 0) plays.subList(0, idx).lastOrNull { it.kind == PlayKind.TOUCHDOWN } else null
      if (precedingTd != null) {
        return inferKickingTeamEspnId(game, precedingTd, plays)
      }
      if (scoringPlay.offenseTeamId != null) {
        return scoringPlay.offenseTeamId
      }
    }

    val isDefensiveTryReturn = rawLower.contains("defensive two-point") || rawLower.contains("defensive 2-point")
    val invertScoreDeltaWinner = scoringPlay.kind == PlayKind.SAFETY || isDefensiveTryReturn
    val prevAwayScore = if (idx > 0) plays[idx - 1].awayScore else 0
    val prevHomeScore = if (idx > 0) plays[idx - 1].homeScore else 0
    if (idx >= 0) {
      val awayDelta = scoringPlay.awayScore - prevAwayScore
      val homeDelta = scoringPlay.homeScore - prevHomeScore
      if (awayDelta > 0 && homeDelta <= 0) {
        return if (invertScoreDeltaWinner) game.home.espnId else game.away.espnId
      }
      if (homeDelta > 0 && awayDelta <= 0) {
        return if (invertScoreDeltaWinner) game.away.espnId else game.home.espnId
      }
      if (awayDelta >= 6 || homeDelta >= 6) {
        return if (awayDelta >= homeDelta) game.away.espnId else game.home.espnId
      }
    }
    val offenseId = scoringPlay.offenseTeamId
    if (offenseId != null) {
      val invertOffense = (scoringPlay.isTurnover && !isTryAttempt) || scoringPlay.kind == PlayKind.SAFETY
      return if (!invertOffense) {
        offenseId
      } else {
        if (offenseId == game.away.espnId) game.home.espnId else game.away.espnId
      }
    }
    return game.possessionEspnId
  }
}

@Serializable
enum class PlayKind {
  TOUCHDOWN,
  FIELD_GOAL,
  SAFETY,
  OTHER_SCORE,
  SACK,
  INTERCEPTION,
  FUMBLE_LOST,
  FIRST_DOWN,
  FOURTH_DOWN_STOP,
  BIG_PLAY,
  OTHER;

  val isScoring: Boolean
    get() = this == TOUCHDOWN || this == FIELD_GOAL || this == SAFETY || this == OTHER_SCORE

  companion object {
    fun fromEspn(
      espnType: String,
      isScoring: Boolean,
      playText: String = "",
      yardsStat: Int = 0,
      scoringTypeHint: String? = null
    ): PlayKind {
      val t = espnType.lowercase().trim()
      val txt = playText.lowercase()
      val effectiveTxt = txt.substringAfterLast("reversed.").trim()
      val hint = scoringTypeHint?.lowercase()?.trim().orEmpty()
      val isTwoPoint = hint.contains("two-point") || hint.contains("2pt") ||
        t.contains("two-point") || t.contains("2-point") ||
        effectiveTxt.contains("two-point") || effectiveTxt.contains("2-point")
      val isTouchdownByHintOrText = isScoring && !isTwoPoint && (
        hint == "touchdown" || hint == "td" ||
          (effectiveTxt.contains("touchdown") && !effectiveTxt.contains("nullified"))
        )
      val isFieldGoalByHintOrText = isScoring && (
        hint == "field-goal" || hint == "fg" ||
          effectiveTxt.contains("field goal is good")
        )
      val isSafetyByHintOrText = isScoring && (
        hint == "safety" || hint == "sf" ||
          effectiveTxt.contains(", safety") || effectiveTxt.endsWith("safety.")
        )
      return when {
        (t.contains("touchdown") && (isScoring || !effectiveTxt.contains("nullified"))) || isTouchdownByHintOrText -> TOUCHDOWN
        t.contains("field goal good") || isFieldGoalByHintOrText -> FIELD_GOAL
        t.contains("safety") || isSafetyByHintOrText -> SAFETY
        isScoring -> OTHER_SCORE
        t == "sack" -> SACK
        t.contains("interception") -> INTERCEPTION
        t.contains("fumble recovery (opponent)") || t == "sack opp fumble recovery" -> FUMBLE_LOST
        t.contains("downs") && (txt.contains("turnover on downs") || txt.contains("4th")) -> FOURTH_DOWN_STOP
        yardsStat >= 25 && (t.contains("pass") || t.contains("rush")) -> BIG_PLAY
        txt.contains("1st down") -> FIRST_DOWN
        else -> OTHER
      }
    }
  }
}

@Serializable
data class NflPlay(
  val id: String,
  val sequence: Int,
  val kind: PlayKind,
  val text: String,
  val rawPlayText: String = text,
  val awayScore: Int = 0,
  val homeScore: Int = 0,
  val period: Int = 1,
  val clock: String = "",
  val offenseTeamId: String? = null,
  val isTurnover: Boolean = false,
  val statYardage: Int = 0,
  val wallclockEpochMillis: Long? = null
)

@Serializable
enum class GameEventKind {
  TOUCHDOWN,
  FIELD_GOAL,
  SAFETY,
  OTHER_SCORE,
  TRY_FAILED,
  TWO_POINT_GOOD,
  SACK,
  INTERCEPTION,
  FUMBLE_LOST,
  RED_ZONE,
  BIG_PLAY,
  FIRST_DOWN,
  FOURTH_DOWN_STOP,
  KICKOFF,
  HALFTIME,
  FINAL,
  SCORE_CORRECTION,
  FANTASY_LEAD_CHANGE
}

@Serializable
enum class SpokenCategory(val label: String, val subtitle: String) {
  TOUCHDOWNS("Touchdowns & Tries", "Offensive, defensive & return TDs + missed XP / 2pt"),
  FIELD_GOALS("Field Goals & Safeties", "3-point kicks, safeties & score corrections"),
  TURNOVERS("Turnovers", "Interceptions & lost fumbles recovered by defense"),
  SACKS("Sacks", "Quarterback sacks with defender & yardage loss"),
  RED_ZONE("Red Zone Alerts", "When an offense drives inside the opponent 20"),
  BIG_PLAYS("Big Plays (25+ Yds)", "Explosive chunk passes and breakaway runs"),
  DOWNS("1st Downs & 4th Down Stops", "Chain-moving conversions & turnovers on downs"),
  GAME_STATUS("Kickoff, Halftime & Final", "Game start, halftime scores & final results"),
  FANTASY_SWINGS("Fantasy Lead Changes", "Announce when your fantasy matchup lead flips");

  companion object {
    fun forEvent(kind: GameEventKind): SpokenCategory = when (kind) {
      GameEventKind.TOUCHDOWN,
      GameEventKind.TRY_FAILED,
      GameEventKind.TWO_POINT_GOOD -> TOUCHDOWNS
      GameEventKind.FIELD_GOAL,
      GameEventKind.SAFETY,
      GameEventKind.OTHER_SCORE,
      GameEventKind.SCORE_CORRECTION -> FIELD_GOALS
      GameEventKind.INTERCEPTION,
      GameEventKind.FUMBLE_LOST -> TURNOVERS
      GameEventKind.SACK -> SACKS
      GameEventKind.RED_ZONE -> RED_ZONE
      GameEventKind.BIG_PLAY -> BIG_PLAYS
      GameEventKind.FIRST_DOWN,
      GameEventKind.FOURTH_DOWN_STOP -> DOWNS
      GameEventKind.KICKOFF,
      GameEventKind.HALFTIME,
      GameEventKind.FINAL -> GAME_STATUS
      GameEventKind.FANTASY_LEAD_CHANGE -> FANTASY_SWINGS
    }
  }
}

@Serializable
data class GameEvent(
  val id: String,
  val kind: GameEventKind,
  val game: NflGameState,
  val team: NflSide? = null,
  val play: NflPlay? = null,
  val awayScore: Int,
  val homeScore: Int,
  val observedAtMillis: Long,
  val tryPending: Boolean = false,
  val customHeadline: String? = null,
  val isSilentBackfill: Boolean = false
)
