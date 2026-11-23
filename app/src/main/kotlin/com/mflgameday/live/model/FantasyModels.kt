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
