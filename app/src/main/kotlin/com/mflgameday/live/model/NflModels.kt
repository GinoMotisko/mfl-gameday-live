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
   * for the visual mini field-position bar.
   */
  val fieldProgressFraction: Float?
    get() {
      if (phase != GamePhase.LIVE || possessionEspnId == null) return null
      if (inRedZone) {
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
    fun fromEspn(espnType: String, isScoring: Boolean, playText: String = "", yardsStat: Int = 0): PlayKind {
      val t = espnType.lowercase().trim()
      val txt = playText.lowercase()
      return when {
        t.contains("touchdown") -> TOUCHDOWN
        t.contains("field goal good") -> FIELD_GOAL
        t.contains("safety") -> SAFETY
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
  val customHeadline: String? = null
)
