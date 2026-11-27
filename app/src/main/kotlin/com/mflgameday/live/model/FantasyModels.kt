package com.mflgameday.live.model

import kotlinx.serialization.Serializable

/**
 * Configuration for a tracked MyFantasyLeague league.
 * Multiple leagues can be active simultaneously with distinct scoring rules, decimal precision,
 * doubleheaders, and Best Ball (`isBestBall`) behavior.
 */
@Serializable
data class LeagueConfig(
  val leagueId: String,
  val leagueName: String,
  val year: Int,
  val franchiseId: String,
  val franchiseName: String,
  val baseUrl: String = "https://api.myfantasyleague.com",
  val hasCustomApiKey: Boolean = false,
  val isBestBall: Boolean = false,
  val precision: Int = 2,
  val starterCount: Int = 9,
  val positionLimits: Map<String, String> = emptyMap(),
  val includeInAnnouncements: Boolean = true,
  val bestBallTrackFullRoster: Boolean = true
)

@Serializable
data class DiscoveredMflLeague(
  val leagueId: String,
  val name: String,
  val year: Int,
  val url: String,
  val franchiseId: String? = null,
  val franchiseName: String? = null
)

@Serializable
data class FantasyPlayer(
  val id: String,
  val rawMflName: String, // "Godwin, Chris" or "Buccaneers, Tampa Bay"
  val position: String,
  val team: NflTeam,
  val espnId: String? = null,
  val jerseyNumber: String? = null
) {
  val isDefensiveTeamUnit: Boolean
    get() = position.equals("Def", ignoreCase = true) ||
      position.equals("ST", ignoreCase = true) ||
      position.equals("TMDL", ignoreCase = true) ||
      position.equals("TMLB", ignoreCase = true) ||
      position.equals("TMDB", ignoreCase = true)

  val isOffensiveTeamUnit: Boolean
    get() = position.equals("Off", ignoreCase = true) ||
      (position.startsWith("TM", ignoreCase = true) && !isDefensiveTeamUnit)

  val isTeamDefense: Boolean
    get() = isDefensiveTeamUnit

  val isTeamUnit: Boolean
    get() = isDefensiveTeamUnit || isOffensiveTeamUnit

  val isHeadCoach: Boolean
    get() = position.equals("Coach", ignoreCase = true)

  val isIdp: Boolean
    get() {
      val p = position.trim().uppercase()
      return p == "DT" || p == "DE" || p == "LB" || p == "CB" || p == "S" || p == "DL" || p == "DB"
    }

  /**
   * True for defensive units (`Def`, `ST`, `TMDL`, `TMLB`, `TMDB`) and individual defensive players (`IDP`),
   * who are on the field when the *opposing* NFL team has offensive possession.
   */
  val isDefensiveSide: Boolean
    get() = isDefensiveTeamUnit || isIdp

  /**
   * Canonical lowercase `first_last` key (stripping punctuation & generational suffixes)
   * used as a secondary lookup in `ClayPackManifest.bySlug` across live MFL & simulator IDs.
   */
  val claySlug: String
    get() {
      val cleaned = displayName
        .lowercase()
        .replace("'", "")
        .replace("’", "")
        .replace(".", "")
        .replace("-", " ")
      val tokens = cleaned
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() && it !in setOf("jr", "sr", "ii", "iii", "iv", "v") }
      return tokens.joinToString("_") { it.replace(Regex("[^a-z0-9]"), "") }.trim('_')
    }

  val displayName: String
    get() {
      val parts = rawMflName.split(",", limit = 2).map { it.trim() }
      return if (parts.size == 2 && parts[1].isNotEmpty()) "${parts[1]} ${parts[0]}" else rawMflName
    }

  val abbreviatedEspnName: String?
    get() {
      val parts = rawMflName.split(",", limit = 2).map { it.trim() }
      if (parts.size != 2 || parts[1].isEmpty()) return null
      return "${parts[1].first()}.${parts[0]}"
    }

  val abbreviatedEspnNameWithoutSuffix: String?
    get() {
      val parts = rawMflName.split(",", limit = 2).map { it.trim() }
      if (parts.size != 2 || parts[1].isEmpty()) return null
      val lastNoSuffix = parts[0]
        .replace(Regex("""\s+(?:Jr\.?|Sr\.?|II|III|IV|V)$""", RegexOption.IGNORE_CASE), "")
        .trim()
      if (lastNoSuffix == parts[0] || lastNoSuffix.isEmpty()) return null
      return "${parts[1].first()}.$lastNoSuffix"
    }

  /**
   * Resolves either the player's official NFL headshot (via MFL's `espn_id` cross-reference)
   * or the NFL team logo for Team Defenses / Special Teams.
   */
  val avatarImageUrl: String?
    get() = when {
      isTeamUnit && team.code in NflTeam.catalog -> team.espnLogoUrl
      !espnId.isNullOrBlank() -> "https://a.espncdn.com/i/headshots/nfl/players/full/$espnId.png"
      team.code in NflTeam.catalog -> team.espnLogoUrl
      else -> null
    }
}

@Serializable
data class ScoringBreakdownLine(
  val category: String,
  val count: String,
  val points: Double
)

enum class StatLineFamily(val badge: String, val displayLabel: String) {
  PASSING("PASS", "Passing"),
  RUSHING("RUSH", "Rushing"),
  RECEIVING("REC", "Receiving"),
  SCORING_2PT_TD("TD/2P", "TDs & 2-Pt"),
  TURNOVER("TO", "Turnovers"),
  KICKING("KICK", "Kicking"),
  DEFENSE_IDP("DEF", "Defense/IDP"),
  OTHER("STAT", "Other")
}

data class FormattedScoringLineItem(
  val family: StatLineFamily,
  val categoryLabel: String,
  val quantityLabel: String,
  val rateLabel: String,
  val points: Double,
  val signedPointsLabel: String,
  val isNegative: Boolean,
  val isTouchdownOrTwoPt: Boolean
)

data class PlayerScoringAudit(
  val items: List<FormattedScoringLineItem>,
  val rawSum: Double,
  val officialScore: Double,
  val hasRoundingOrBonusDelta: Boolean
)

/**
 * Pure formatter and aggregator that transforms raw MFL `ScoringBreakdownLine` entries
 * into structured, human-friendly box-score table rows (catches, yards, rushes, 2-pt conversions,
 * TDs, fumbles, kicking, and IDP/Defense) and rolls them up to player & team totals.
 */
object ScoringBreakdownFormatter {

  fun auditPlayer(playerScore: PlayerLiveScore, precision: Int = 2): PlayerScoringAudit {
    val linePrecision = maxOf(2, precision.coerceIn(0, 4))
    val items = playerScore.breakdowns
      .filter { it.points != 0.0 || (it.count.toDoubleOrNull() ?: 0.0) != 0.0 }
      .map { formatLine(it, precision = linePrecision) }
    val rawSum = playerScore.breakdowns.sumOf { it.points }
    val tolerance = if (precision >= 3) 0.0005 else 0.005
    val hasDelta = items.isNotEmpty() && kotlin.math.abs(rawSum - playerScore.score) >= tolerance
    return PlayerScoringAudit(
      items = items,
      rawSum = rawSum,
      officialScore = playerScore.score,
      hasRoundingOrBonusDelta = hasDelta
    )
  }

  fun rollupByFamily(players: List<PlayerLiveScore>): List<Pair<StatLineFamily, Double>> {
    val totals = LinkedHashMap<StatLineFamily, Double>()
    for (p in players) {
      for (line in p.breakdowns) {
        if (line.points == 0.0) continue
        val fam = classifyFamily(line.category, line.points)
        totals[fam] = (totals[fam] ?: 0.0) + line.points
      }
    }
    return StatLineFamily.entries.mapNotNull { fam ->
      val sum = totals[fam] ?: return@mapNotNull null
      if (kotlin.math.abs(sum) < 0.0005) null else fam to sum
    }
  }

  fun formatLine(line: ScoringBreakdownLine, precision: Int = 2): FormattedScoringLineItem {
    val linePrecision = maxOf(2, precision.coerceIn(0, 4))
    val cat = line.category.trim()
    val lower = cat.lowercase()
    val isNeg = line.points < -0.0001
    val isTdOr2Pt = Regex("""\btd(?:s)?\b""", RegexOption.IGNORE_CASE).containsMatchIn(cat) ||
      lower.contains("touchdown") ||
      lower.contains("2-pt") ||
      lower.contains("2pt") ||
      lower.contains("two-point")
    val family = classifyFamily(cat, line.points)
    val qtyLabel = formatQuantity(line.count.trim(), lower)
    val rateLabel = formatImpliedRate(line.count.trim(), line.points, lower)
    val signedPts = when {
      line.points > 0.0001 -> "+%.${linePrecision}f".format(line.points)
      line.points < -0.0001 -> "%.${linePrecision}f".format(line.points)
      else -> "%.${linePrecision}f".format(0.0)
    }
    return FormattedScoringLineItem(
      family = family,
      categoryLabel = prettifyCategory(cat),
      quantityLabel = qtyLabel,
      rateLabel = rateLabel,
      points = line.points,
      signedPointsLabel = signedPts,
      isNegative = isNeg,
      isTouchdownOrTwoPt = isTdOr2Pt && !isNeg
    )
  }

  fun classifyFamily(category: String, points: Double): StatLineFamily {
    val lower = category.lowercase()
    if (lower.contains("fumble") && !lower.contains("recover") && !lower.contains("force")) {
      return StatLineFamily.TURNOVER
    }
    if ((lower.contains("interception") || lower.contains("pick")) && points < -0.0001) {
      return StatLineFamily.TURNOVER
    }
    if (lower.contains("2-pt") || lower.contains("2pt") || lower.contains("two-point")) {
      return StatLineFamily.SCORING_2PT_TD
    }
    if (
      lower.contains("field goal") ||
      lower.contains("extra point") ||
      Regex("""\bpat(?:s)?\b""").containsMatchIn(lower)
    ) {
      return StatLineFamily.KICKING
    }
    if (
      lower.contains("passes defended") ||
      lower.contains("pass defense") ||
      lower.contains("pass breakup") ||
      lower.contains("points allowed") ||
      lower.contains("yards allowed") ||
      lower.contains("tackle") ||
      lower.contains("assist") ||
      lower.contains("defensive") ||
      lower.contains("fumbles recovered") ||
      lower.contains("forced fumble") ||
      lower.contains("safety") ||
      lower.contains("blocked") ||
      (lower.contains("sack") && !lower.contains("sacked") && points >= -0.0001) ||
      (lower.contains("interception") && points >= -0.0001)
    ) {
      return StatLineFamily.DEFENSE_IDP
    }
    if (
      lower.contains("pass") ||
      lower.contains("sacked") ||
      lower.contains("completion") ||
      lower.contains("incompletion") ||
      (lower.contains("sack") && points < -0.0001)
    ) {
      return StatLineFamily.PASSING
    }
    if (lower.contains("rush") || lower.contains("carry") || lower.contains("carries")) {
      return StatLineFamily.RUSHING
    }
    if (
      lower.contains("receiv") ||
      lower.contains("reception") ||
      lower.contains("catch") ||
      lower.contains("target")
    ) {
      return StatLineFamily.RECEIVING
    }
    if (Regex("""\btd(?:s)?\b""").containsMatchIn(lower) || lower.contains("touchdown")) {
      return StatLineFamily.SCORING_2PT_TD
    }
    return StatLineFamily.OTHER
  }

  private fun prettifyCategory(raw: String): String {
    return raw
      .replace(Regex("\\bTDs\\b"), "TD")
      .replace(Regex("\\b2-Pt\\b", RegexOption.IGNORE_CASE), "2-Pt")
      .trim()
  }

  private fun formatQuantity(countStr: String, lowerCategory: String): String {
    val num = countStr.toDoubleOrNull()
    val cleanNum = if (num != null && kotlin.math.abs(num - kotlin.math.round(num)) < 0.0001) {
      kotlin.math.round(num).toLong().toString()
    } else {
      countStr
    }
    val isSingular = cleanNum == "1" || cleanNum == "-1"
    return when {
      lowerCategory.contains("field goal") -> "$cleanNum FG"
      lowerCategory.contains("extra point") || Regex("""\bpat(?:s)?\b""").containsMatchIn(lowerCategory) ->
        "$cleanNum XP"
      lowerCategory.contains("2-pt") || lowerCategory.contains("2pt") || lowerCategory.contains("two-point") ->
        "$cleanNum 2-PT"
      Regex("""\btd(?:s)?\b""").containsMatchIn(lowerCategory) || lowerCategory.contains("touchdown") ->
        "$cleanNum TD"
      lowerCategory.contains("fumble") ->
        if (isSingular) "1 fum" else "$cleanNum fum"
      lowerCategory.contains("interception") ->
        "$cleanNum INT"
      lowerCategory.contains("sack") ->
        if (isSingular) "1 sack" else "$cleanNum sacks"
      lowerCategory.contains("reception") || lowerCategory.contains("catch") ->
        if (isSingular) "1 catch" else "$cleanNum catches"
      lowerCategory == "rushes" || lowerCategory.contains("rushing attempt") || lowerCategory.contains("carries") ->
        if (isSingular) "1 rush" else "$cleanNum rushes"
      lowerCategory.contains("game") || lowerCategory.contains("bonus") ->
        "$cleanNum bonus"
      lowerCategory.contains("yard") -> "$cleanNum yds"
      else -> cleanNum
    }
  }

  private fun formatImpliedRate(countStr: String, points: Double, lowerCategory: String): String {
    val qty = countStr.toDoubleOrNull() ?: return ""
    if (kotlin.math.abs(qty) < 0.0001 || kotlin.math.abs(points) < 0.0001) return ""
    val rate = points / qty
    return when {
      lowerCategory.contains("field goal") || lowerCategory.contains("extra point") -> "%+.2f ea".format(rate)
      lowerCategory.contains("2-pt") || lowerCategory.contains("2pt") || lowerCategory.contains("two-point") ->
        "%+.1f/2PT".format(rate)
      Regex("""\btd(?:s)?\b""").containsMatchIn(lowerCategory) || lowerCategory.contains("touchdown") ->
        "%+.1f/TD".format(rate)
      lowerCategory.contains("reception") || lowerCategory.contains("catch") -> "%+.2f/rec".format(rate)
      lowerCategory == "rushes" || lowerCategory.contains("rushing attempt") || lowerCategory.contains("carries") ->
        "%+.2f/rush".format(rate)
      lowerCategory.contains("game") || lowerCategory.contains("bonus") -> "%+.2f ea".format(rate)
      lowerCategory.contains("yard") -> "%+.2f/yd".format(rate)
      else -> "%+.2f ea".format(rate)
    }
  }
}

@Serializable
data class PlayerLiveScore(
  val playerId: String,
  val score: Double,
  val isStarter: Boolean,
  val gameSecondsRemaining: Int,
  val preGameProjection: Double = 0.0,
  val livePaceProjection: Double = score,
  val breakdowns: List<ScoringBreakdownLine> = emptyList(),
  val isOptimalBubble: Boolean = false,
  val canCrackOptimal: Boolean = false,
  val scoreDeltaRecent: Double = 0.0
) {
  val isFinal: Boolean
    get() = gameSecondsRemaining <= 0

  val isYetToPlay: Boolean
    get() = gameSecondsRemaining >= 3600

  val isPlayingNow: Boolean
    get() = gameSecondsRemaining in 1..3599

  val gameProgressFraction: Float
    get() = ((3600 - gameSecondsRemaining.coerceIn(0, 3600)) / 3600f).coerceIn(0f, 1f)
}

@Serializable
data class FranchiseInfo(
  val id: String,
  val name: String,
  val abbrev: String = "",
  val iconUrl: String? = null,
  val logoUrl: String? = null,
  val division: String? = null
)

@Serializable
data class BenchInsight(
  val benchPlayerName: String,
  val benchScore: Double,
  val targetStarterName: String,
  val starterScore: Double,
  val position: String,
  val pointSwing: Double
)

@Serializable
data class FranchiseMatchupSide(
  val franchiseId: String,
  val franchiseName: String,
  val logoUrl: String? = null,
  val score: Double,
  val projectedScore: Double,
  val winProbability: Float = 0.5f,
  val isHome: Boolean,
  val playersCurrentlyPlaying: Int,
  val playersYetToPlay: Int,
  val playersFinal: Int,
  val gameSecondsRemaining: Int = 0,
  val starters: List<PlayerLiveScore>,
  val bench: List<PlayerLiveScore>,
  val benchInsights: List<BenchInsight> = emptyList(),
  val recentScoreDelta: Double = 0.0
)

@Serializable
data class FantasyMatchup(
  val id: String,
  val leagueId: String,
  val away: FranchiseMatchupSide,
  val home: FranchiseMatchupSide,
  val involvesMyFranchise: Boolean
) {
  val franchises: List<FranchiseMatchupSide>
    get() = listOf(away, home)

  fun mySide(myFranchiseId: String): FranchiseMatchupSide? =
    franchises.firstOrNull { it.franchiseId == myFranchiseId }

  fun opponentSide(myFranchiseId: String): FranchiseMatchupSide? =
    franchises.firstOrNull { it.franchiseId != myFranchiseId }
}

@Serializable
data class LeagueLiveSnapshot(
  val config: LeagueConfig,
  val week: Int,
  val franchises: Map<String, FranchiseInfo>,
  val matchups: List<FantasyMatchup>,
  val updatedAtMillis: Long,
  val errorMessage: String? = null
)

@Serializable
enum class ExposureRole {
  MINE,
  OPPONENT,
  CONFLICT // Both starting on my team in one league AND against me in another league!
}

@Serializable
data class PlayerCrossLeagueExposure(
  val player: FantasyPlayer,
  val role: ExposureRole,
  val myLeagueNames: List<String>,
  val opponentLeagueNames: List<String>,
  val isBestBallBenchContender: Boolean = false
)

@Serializable
data class PlayLogEntry(
  val id: String,
  val timestampMillis: Long,
  val event: GameEvent,
  val spokenText: String,
  val leagueTags: List<String> = emptyList(),
  val involvesMine: Boolean = false,
  val involvesOpponent: Boolean = false,
  val isConflictPlay: Boolean = false,
  val wasSpoken: Boolean = false
)
