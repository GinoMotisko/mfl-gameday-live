package com.mflgameday.live.data

import com.mflgameday.live.model.DiscoveredMflLeague
import com.mflgameday.live.model.FantasyPlayer
import com.mflgameday.live.model.FranchiseInfo
import com.mflgameday.live.model.GamePhase
import com.mflgameday.live.model.LeagueConfig
import com.mflgameday.live.model.NflGameState
import com.mflgameday.live.model.NflSide
import com.mflgameday.live.model.NflTeam
import com.mflgameday.live.model.ScoringBreakdownLine
import java.io.IOException
import java.time.Year
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class MflRateLimitException(
  message: String = "MyFantasyLeague rate limit reached (HTTP 429). Backing off 60s."
) : IOException(message)
class MflAuthException(message: String) : IOException(message)

data class MflStatusInfo(
  val year: Int,
  val currentWeek: Int,
  val liveScoringWeek: Int,
  val upcomingWeek: Int,
  val lineupWeek: Int = upcomingWeek,
  val completedWeek: Int = 0
) {
  companion object {
    const val MAX_MFL_NFL_WEEK = 22
  }

  /**
   * Once MFL marks `CompletedWeek >= LiveScoringWeek` after Monday Night Football,
   * `LiveScoringWeek` stays pinned to the finished week until Thursday pregame while
   * `CurrentWeek` / `UpcomingWeek` / `LineupWeek` advance to the next week.
   * Capped at `MAX_MFL_NFL_WEEK` (22 = Super Bowl) because MFL `nfl_sched_23.json` does not exist.
   */
  val effectiveActiveWeek: Int
    get() {
      val clampedLive = liveScoringWeek.coerceIn(1, MAX_MFL_NFL_WEEK)
      if (completedWeek >= liveScoringWeek) {
        val nextCandidates = listOf(currentWeek, upcomingWeek, lineupWeek)
          .filter { it in (completedWeek + 1)..MAX_MFL_NFL_WEEK }
        if (nextCandidates.isNotEmpty()) {
          return nextCandidates.min()
        }
      }
      return clampedLive
    }
}

data class MflLeagueMetadata(
  val leagueId: String,
  val name: String,
  val baseUrl: String,
  val isBestBall: Boolean,
  val precision: Int,
  val starterCount: Int,
  val positionLimits: Map<String, String>,
  val franchises: List<FranchiseInfo>
)

data class RawMflPlayerScore(
  val playerId: String,
  val score: Double,
  val isStarter: Boolean,
  val gameSecondsRemaining: Int
)

data class RawMflFranchiseLive(
  val franchiseId: String,
  val score: Double,
  val isHome: Boolean,
  val playersCurrentlyPlaying: Int,
  val playersYetToPlay: Int,
  val gameSecondsRemaining: Int,
  val players: List<RawMflPlayerScore>
)

data class RawMflMatchup(
  val franchises: List<RawMflFranchiseLive>
)

data class ConditionalScheduleResult(
  val notModified: Boolean,
  val etag: String?,
  val week: Int?,
  val games: List<NflGameState>
)

data class ScoringBreakdownFetchResult(
  val breakdowns: Map<String, List<ScoringBreakdownLine>>,
  val hasIncompleteBenchHydration: Boolean = false
)

/**
 * Thread-safe, rate-limited MyFantasyLeague API client.
 * Serializes calls ~1.1s apart per MFL developer guidelines and honors HTTP 429 with a 60s cooldown.
 */
class MflApiClient(
  private val httpClient: OkHttpClient = defaultOkHttpClient(),
  private val apiBaseUrl: String = "https://api.myfantasyleague.com",
  private val userAgent: String = "MFLGameDayAndroid/1.0",
  private val minRequestSpacingMs: Long = 1100L
) {
  private val throttleMutex = Mutex()
  private var lastRequestAtMs: Long = 0L
  private var backoffUntilMs: Long = 0L
  private val ajaxRulesCache = ConcurrentHashMap<String, MflAjaxLeagueRules>()
  private val liveWeekStatsCache = ConcurrentHashMap<String, Pair<Long, MflLiveWeekStats>>()

  companion object {
    private const val LIVE_WEEK_STATS_CACHE_TTL_MS = 12_000L
    private const val AJAX_RULES_MISSING_PLAYER_REFRESH_MS = 300_000L
    fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(12, TimeUnit.SECONDS)
      .pingInterval(25, TimeUnit.SECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .build()

    internal fun extractMflUserIdFromLogin(xmlBody: String, setCookieHeaders: List<String>): String? {
      for (cookie in setCookieHeaders) {
        val m = Regex("""MFL_USER_ID=([^;\s]+)""").find(cookie)
        if (m != null && m.groupValues[1].isNotBlank()) {
          return m.groupValues[1]
        }
      }
      val xmlMatch = Regex("""MFL_USER_ID\s*=\s*["']([^"']+)["']""").find(xmlBody)
      if (xmlMatch != null && xmlMatch.groupValues[1].isNotBlank()) {
        return xmlMatch.groupValues[1]
      }
      return null
    }

    internal fun extractLoginError(xmlBody: String): String? {
      val err = Regex("""<error[^>]*>(.*?)</error>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        .find(xmlBody)?.groupValues?.get(1)?.trim()
      if (!err.isNullOrEmpty()) return err
      val statusErr = Regex("""<status[^>]*>(.*?)</status>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        .find(xmlBody)?.groupValues?.get(1)?.trim()
      if (!statusErr.isNullOrEmpty() && !statusErr.equals("OK", ignoreCase = true)) return statusErr
      return null
    }

    /**
     * Normalizes MFL's `franchise_id` string (which can return `"0000,0007"` for Commissioner accounts)
     * by preferring an actual owned franchise ID over the `"0000"` Commissioner pseudo-ID.
     */
    internal fun normalizeFranchiseId(raw: String?): String? {
      val tokens = raw?.split(',', ' ', ';')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
      if (tokens.isEmpty()) return null
      return tokens.firstOrNull { it != "0000" }
    }
  }

  /**
   * Authenticates an MFL user via HTTPS POST (`XML=1`) and returns their `MFL_USER_ID` session token.
   */
  suspend fun login(username: String, password: String, year: Int = Year.now().value): String {
    val cleanUser = username.trim()
    if (cleanUser.isEmpty() || password.isEmpty()) {
      throw MflAuthException("Please enter both your MFL username and password.")
    }
    val url = "$apiBaseUrl/$year/login".toHttpUrlOrNull()
      ?: throw IOException("Invalid MFL login URL")

    val formBody = FormBody.Builder()
      .add("USERNAME", cleanUser)
      .add("PASSWORD", password)
      .add("XML", "1")
      .build()

    val request = Request.Builder()
      .url(url)
      .header("User-Agent", userAgent)
      .post(formBody)
      .build()

    return throttledExecute(request) { response, bodyText ->
      val userId = extractMflUserIdFromLogin(bodyText, response.headers("Set-Cookie"))
      if (userId != null) {
        userId
      } else {
        val reason = extractLoginError(bodyText) ?: "Invalid MFL username or password."
        throw MflAuthException(reason)
      }
    }
  }

  /**
   * Pulls all leagues belonging to the signed-in MFL user (`TYPE=myleagues&FRANCHISE_NAMES=1&JSON=1`).
   */
  suspend fun fetchMyLeagues(
    mflUserIdCookie: String,
    year: Int = Year.now().value
  ): List<DiscoveredMflLeague> {
    val url = buildExportUrl(
      host = apiBaseUrl,
      year = year,
      type = "myleagues",
      params = mapOf("FRANCHISE_NAMES" to "1", "YEAR" to year.toString())
    )
    val root = getJson(url, mflUserIdCookie = mflUserIdCookie)
    checkForMflApiError(root)
    val leaguesContainer = root.obj("leagues") ?: return emptyList()
    return leaguesContainer.oneOrManyObjects("league").mapNotNull { obj ->
      val rawUrl = obj.str("url") ?: ""
      val leagueId = obj.str("league_id")
        ?: obj.str("id")
        ?: rawUrl.substringAfterLast('/').takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
        ?: return@mapNotNull null
      val name = obj.str("name") ?: "League $leagueId"
      val leagueYear = obj.intOrNull("year")
        ?: Regex("""/(\d{4})/""").find(rawUrl)?.groupValues?.get(1)?.toIntOrNull()
        ?: year
      DiscoveredMflLeague(
        leagueId = leagueId,
        name = name,
        year = leagueYear,
        url = rawUrl,
        franchiseId = normalizeFranchiseId(obj.str("franchise_id")),
        franchiseName = obj.str("franchise_name")
      )
    }
  }

  /**
   * Fetches MFL's global season & live-scoring week status (`fflnetdynamic{year}/mfl_status.json`).
   */
  suspend fun fetchStatus(year: Int = Year.now().value): MflStatusInfo {
    val url = "$apiBaseUrl/fflnetdynamic$year/mfl_status.json".toHttpUrlOrNull()
      ?: throw IOException("Invalid MFL status URL")
    val root = getJson(url)
    val statusObj = root.obj("mfl_status") ?: throw IOException("Missing mfl_status payload")
    val resolvedYear = statusObj.intOrNull("year") ?: year
    val weeks = statusObj.obj("weeks")
    val current = weeks?.intOrNull("CurrentWeek") ?: 1
    val live = weeks?.intOrNull("LiveScoringWeek") ?: current
    val upcoming = weeks?.intOrNull("UpcomingWeek") ?: live
    val lineup = weeks?.intOrNull("LineupWeek") ?: upcoming
    val completed = weeks?.intOrNull("CompletedWeek") ?: 0
    return MflStatusInfo(
      year = resolvedYear,
      currentWeek = current,
      liveScoringWeek = live,
      upcomingWeek = upcoming,
      lineupWeek = lineup,
      completedWeek = completed
    )
  }

  /**
   * Fetches league configuration, franchises, scoring precision, starter limits, and Best Ball setting.
   */
  suspend fun fetchLeagueMetadata(
    leagueId: String,
    year: Int,
    hostOverride: String? = null,
    apiKey: String? = null,
    mflUserIdCookie: String? = null
  ): MflLeagueMetadata {
    val cleanId = leagueId.trim()
    val url = buildExportUrl(
      host = hostOverride ?: apiBaseUrl,
      year = year,
      type = "league",
      leagueId = cleanId,
      apiKey = apiKey
    )
    val root = getJson(url, mflUserIdCookie = mflUserIdCookie)
    checkForMflApiError(root)
    val leagueObj = root.obj("league") ?: throw IOException("League $cleanId not found or private.")
    return parseLeagueMetadata(cleanId, leagueObj)
  }

  internal fun parseLeagueMetadata(leagueId: String, leagueObj: JsonObject): MflLeagueMetadata {
    val name = leagueObj.str("name") ?: "League $leagueId"
    val baseUrl = upgradeHttpToHttpsIfMfl((leagueObj.str("baseURL") ?: apiBaseUrl).trimEnd('/'))
    val isBestBall = leagueObj.boolFromYesOrOne("bestLineup") ||
      leagueObj.str("loadRosters")?.equals("best_ball", ignoreCase = true) == true
    val precision = leagueObj.intOrNull("precision")?.coerceIn(0, 4) ?: 2

    val startersObj = leagueObj.obj("starters")
    val starterCount = startersObj?.intOrNull("count") ?: 9
    val positionLimits = LinkedHashMap<String, String>()
    startersObj?.oneOrManyObjects("position")?.forEach { posObj ->
      val posName = posObj.str("name")
      val limit = posObj.str("limit")
      if (posName != null && limit != null) {
        positionLimits[posName] = limit
      }
    }

    val franchises = leagueObj.obj("franchises")
      ?.oneOrManyObjects("franchise")
      ?.mapNotNull { f ->
        val id = f.str("id") ?: return@mapNotNull null
        FranchiseInfo(
          id = id,
          name = f.str("name") ?: "Team $id",
          abbrev = f.strOrEmpty("abbrev"),
          iconUrl = f.str("icon")?.let(::upgradeHttpToHttpsIfMfl),
          logoUrl = f.str("logo")?.let(::upgradeHttpToHttpsIfMfl),
          division = f.str("division")
        )
      }
      .orEmpty()

    return MflLeagueMetadata(
      leagueId = leagueId,
      name = name,
      baseUrl = baseUrl,
      isBestBall = isBestBall,
      precision = precision,
      starterCount = starterCount,
      positionLimits = positionLimits,
      franchises = franchises
    )
  }

  /**
   * Fetches live fantasy matchup scoring (`TYPE=liveScoring&DETAILS=1`).
   */
  suspend fun fetchLiveScoring(
    config: LeagueConfig,
    week: Int,
    apiKey: String? = null,
    mflUserIdCookie: String? = null
  ): List<RawMflMatchup> {
    val url = buildExportUrl(
      host = config.baseUrl,
      year = config.year,
      type = "liveScoring",
      leagueId = config.leagueId,
      apiKey = apiKey,
      params = mapOf("W" to week.toString(), "DETAILS" to "1")
    )
    val root = getJson(url, mflUserIdCookie = mflUserIdCookie)
    checkForMflApiError(root)
    return parseLiveScoring(root)
  }

  internal fun parseLiveScoring(root: JsonObject): List<RawMflMatchup> {
    val lsObj = root.obj("liveScoring") ?: return emptyList()
    // Some all-play / contest / playoff weeks return `franchise` directly on `liveScoring` without `matchup`!
    val matchupObjs = lsObj.oneOrManyObjects("matchup")
    if (matchupObjs.isNotEmpty()) {
      return matchupObjs.map { mObj ->
        RawMflMatchup(
          franchises = mObj.oneOrManyObjects("franchise").map(::parseFranchiseLive)
        )
      }.filter { it.franchises.isNotEmpty() }
    }
    val standaloneFranchises = lsObj.oneOrManyObjects("franchise").map(::parseFranchiseLive)
    return standaloneFranchises.chunked(2).map { pair -> RawMflMatchup(franchises = pair) }
  }

  private fun parseFranchiseLive(fObj: JsonObject): RawMflFranchiseLive {
    val players = fObj.obj("players")
      ?.oneOrManyObjects("player")
      ?.mapNotNull { pObj ->
        val id = pObj.str("id") ?: return@mapNotNull null
        RawMflPlayerScore(
          playerId = id,
          score = pObj.doubleOrZero("score"),
          isStarter = pObj.str("status").equals("starter", ignoreCase = true),
          gameSecondsRemaining = pObj.intOrZero("gameSecondsRemaining")
        )
      }
      .orEmpty()

    return RawMflFranchiseLive(
      franchiseId = fObj.strOrEmpty("id"),
      score = fObj.doubleOrZero("score"),
      isHome = fObj.boolFromYesOrOne("isHome"),
      playersCurrentlyPlaying = fObj.intOrZero("playersCurrentlyPlaying"),
      playersYetToPlay = fObj.intOrZero("playersYetToPlay"),
      gameSecondsRemaining = fObj.intOrZero("gameSecondsRemaining"),
      players = players
    )
  }

  /**
   * Fetches MFL weekly player projections (`TYPE=projectedScores`) for our Live Pace & Win Probability engine.
   */
  suspend fun fetchProjectedScores(
    config: LeagueConfig,
    week: Int,
    apiKey: String? = null,
    mflUserIdCookie: String? = null
  ): Map<String, Double> {
    val url = buildExportUrl(
      host = config.baseUrl,
      year = config.year,
      type = "projectedScores",
      leagueId = config.leagueId,
      apiKey = apiKey,
      params = mapOf("W" to week.toString(), "COUNT" to "2500")
    )
    val root = getJson(url, mflUserIdCookie = mflUserIdCookie)
    val container = root.obj("projectedScores") ?: return emptyMap()
    val map = HashMap<String, Double>()
    for (pObj in container.oneOrManyObjects("playerScore")) {
      val id = pObj.str("id") ?: continue
      val score = pObj.doubleOrZero("score")
      if (score > 0.0) map[id] = score
    }
    return map
  }

  /**
   * Best-effort fetch of MFL's `live_scoring_summary` web page for granular scoring category
   * breakdowns, with automatic hydration via `ajax_ls` + `live_stats_<week>.txt` for bench
   * (`nonstarter`) or omitted players in standard head-to-head leagues.
   */
  suspend fun fetchScoringBreakdowns(
    config: LeagueConfig,
    week: Int,
    rawMatchups: List<RawMflMatchup> = emptyList(),
    playersDb: Map<String, FantasyPlayer> = emptyMap(),
    apiKey: String? = null,
    mflUserIdCookie: String? = null
  ): Map<String, List<ScoringBreakdownLine>> =
    fetchScoringBreakdownsWithStatus(
      config = config,
      week = week,
      rawMatchups = rawMatchups,
      playersDb = playersDb,
      apiKey = apiKey,
      mflUserIdCookie = mflUserIdCookie
    ).breakdowns

  suspend fun fetchScoringBreakdownsWithStatus(
    config: LeagueConfig,
    week: Int,
    rawMatchups: List<RawMflMatchup> = emptyList(),
    playersDb: Map<String, FantasyPlayer> = emptyMap(),
    apiKey: String? = null,
    mflUserIdCookie: String? = null
  ): ScoringBreakdownFetchResult {
    val base = upgradeHttpToHttpsIfMfl(config.baseUrl.trimEnd('/')).toHttpUrlOrNull()
      ?: return ScoringBreakdownFetchResult(emptyMap())
    val urlBuilder = base.newBuilder()
      .addPathSegment(config.year.toString())
      .addPathSegment("live_scoring_summary")
      .addQueryParameter("L", config.leagueId)
      .addQueryParameter("W", week.toString())
    if (!apiKey.isNullOrBlank()) {
      urlBuilder.addQueryParameter("APIKEY", apiKey)
    }
    val reqBuilder = Request.Builder()
      .url(urlBuilder.build())
      .header("User-Agent", userAgent)
    if (!mflUserIdCookie.isNullOrBlank()) {
      reqBuilder.header("Cookie", "MFL_USER_ID=$mflUserIdCookie")
    }
    val summaryBreakdowns = throttledExecute(reqBuilder.build()) { _, html ->
      ScoringBreakdownParser.parse(html)
    }

    val missingPlayers = rawMatchups
      .flatMap { it.franchises }
      .flatMap { it.players }
      .filter { p ->
        (kotlin.math.abs(p.score) > 0.001 || p.gameSecondsRemaining < 3600) &&
          summaryBreakdowns[p.playerId].isNullOrEmpty()
      }
      .distinctBy { it.playerId }

    if (missingPlayers.isEmpty()) {
      return ScoringBreakdownFetchResult(summaryBreakdowns, hasIncompleteBenchHydration = false)
    }

    val combined = LinkedHashMap(summaryBreakdowns)
    var hydrationIncomplete = false
    try {
      val ajaxLsUrl = base.newBuilder()
        .addPathSegment(config.year.toString())
        .addPathSegment("ajax_ls")
        .addQueryParameter("L", config.leagueId)
        .addQueryParameter("W", week.toString())
        .apply {
          if (!apiKey.isNullOrBlank()) addQueryParameter("APIKEY", apiKey)
        }
        .build()

      val rulesKey = "${config.leagueId}_${config.year}_w$week"
      val now = System.currentTimeMillis()
      var rules = ajaxRulesCache[rulesKey]
      val needsRulesRefresh = rules == null || (
        missingPlayers.any { it.playerId !in rules.players && it.playerId !in playersDb } &&
          now - rules.fetchedAtMs > AJAX_RULES_MISSING_PLAYER_REFRESH_MS
        )
      if (needsRulesRefresh) {
        val ajaxReqBuilder = Request.Builder()
          .url(ajaxLsUrl)
          .header("User-Agent", userAgent)
        if (!mflUserIdCookie.isNullOrBlank()) {
          ajaxReqBuilder.header("Cookie", "MFL_USER_ID=$mflUserIdCookie")
        }
        val parsedRules = throttledExecute(ajaxReqBuilder.build()) { _, ajaxHtml ->
          MflLiveStatsBreakdownCalculator.parseAjaxLeagueRules(ajaxHtml)
        }
        if (parsedRules.ruleBlocks.isNotEmpty()) {
          rules = parsedRules
          ajaxRulesCache[rulesKey] = parsedRules
        }
      }

      val activeRules = rules
      if (activeRules == null) {
        return ScoringBreakdownFetchResult(summaryBreakdowns, hasIncompleteBenchHydration = true)
      }
      val statsBaseRaw = upgradeHttpToHttpsIfMfl(
        activeRules.xmlBaseUrl ?: "${config.baseUrl.trimEnd('/')}/fflnetdynamic${config.year}/"
      ).trimEnd('/')
      val weekPadded = week.coerceIn(1, MflStatusInfo.MAX_MFL_NFL_WEEK).toString().padStart(2, '0')
      val statsFilename = if (activeRules.usesIdps) {
        "live_stats_idp_$weekPadded.txt"
      } else {
        "live_stats_$weekPadded.txt"
      }
      val statsKey = "${statsBaseRaw}_$statsFilename"
      val cachedStats = liveWeekStatsCache[statsKey]
      val statsNow = System.currentTimeMillis()
      val liveStats = if (cachedStats != null && statsNow - cachedStats.first <= LIVE_WEEK_STATS_CACHE_TTL_MS) {
        cachedStats.second
      } else {
        val statsUrl = "$statsBaseRaw/$statsFilename".toHttpUrlOrNull()
          ?.newBuilder()
          ?.addQueryParameter("RANDOM", statsNow.toString())
          ?.build()
        if (statsUrl != null) {
          val statsReq = Request.Builder()
            .url(statsUrl)
            .header("User-Agent", userAgent)
            .header("Referer", ajaxLsUrl.toString())
            .build()
          val parsedStats = throttledExecute(statsReq) { _, statsTxt ->
            MflLiveStatsBreakdownCalculator.parseLiveWeekStats(statsTxt)
          }
          liveWeekStatsCache[statsKey] = statsNow to parsedStats
          parsedStats
        } else {
          return ScoringBreakdownFetchResult(summaryBreakdowns, hasIncompleteBenchHydration = true)
        }
      }

      for (missing in missingPlayers) {
        val computed = MflLiveStatsBreakdownCalculator.computeBreakdownsForPlayer(
          playerId = missing.playerId,
          rawScore = missing,
          fallbackPlayer = playersDb[missing.playerId],
          rules = activeRules,
          liveStats = liveStats
        )
        if (computed.isNotEmpty()) {
          combined[missing.playerId] = computed
        }
      }
    } catch (ce: CancellationException) {
      throw ce
    } catch (_: Exception) {
      // Best-effort fallback: preserve partial `live_scoring_summary` breakdowns while marking retry needed.
      hydrationIncomplete = true
    }
    return ScoringBreakdownFetchResult(
      breakdowns = combined,
      hasIncompleteBenchHydration = hydrationIncomplete
    )
  }

  /**
   * Fetches MFL's full NFL + IDP player database (`TYPE=players&DETAILS=1`).
   */
  suspend fun fetchPlayersRawJson(year: Int): String {
    val url = buildExportUrl(
      host = apiBaseUrl,
      year = year,
      type = "players",
      params = mapOf("DETAILS" to "1")
    )
    val request = Request.Builder()
      .url(url)
      .header("User-Agent", userAgent)
      .build()
    return throttledExecute(request) { _, body -> body }
  }

  fun parsePlayersJson(rawJson: String): Map<String, FantasyPlayer> {
    val root = LenientJson.parser.parseToJsonElement(rawJson).jsonObject
    val playersObj = root.obj("players") ?: return emptyMap()
    val result = HashMap<String, FantasyPlayer>(4096)
    for (row in playersObj.oneOrManyObjects("player")) {
      val id = row.str("id") ?: continue
      val name = row.str("name") ?: continue
      val pos = row.str("position") ?: ""
      val team = NflTeam.fromMfl(row.str("team") ?: "FA")
      result[id] = FantasyPlayer(
        id = id,
        rawMflName = name,
        position = pos,
        team = team,
        espnId = row.str("espn_id"),
        jerseyNumber = row.str("jersey")
      )
    }
    return result
  }

  /**
   * Fetches MFL's live-updating static schedule (`fflnetdynamic{year}/nfl_sched_{week}.json`)
   * with ETag support. Used both as a fallback schedule feed and for explicit "Refresh NFL Schedule".
   */
  suspend fun fetchMflNflSchedule(
    year: Int,
    week: Int,
    etag: String? = null
  ): ConditionalScheduleResult {
    val url = "$apiBaseUrl/fflnetdynamic$year/nfl_sched_$week.json".toHttpUrlOrNull()
      ?: throw IOException("Invalid MFL NFL schedule URL")
    val reqBuilder = Request.Builder()
      .url(url)
      .header("User-Agent", userAgent)
    if (!etag.isNullOrBlank()) {
      reqBuilder.header("If-None-Match", etag)
    }
    return throttledExecute(reqBuilder.build(), allow304 = true) { response, body ->
      if (response.code == 304) {
        ConditionalScheduleResult(notModified = true, etag = etag, week = week, games = emptyList())
      } else {
        val root = LenientJson.parser.parseToJsonElement(body).jsonObject
        val sched = root.obj("nflSchedule")
        val parsedWeek = sched?.intOrNull("week") ?: week
        val games = parseMflScheduleGames(sched)
        ConditionalScheduleResult(
          notModified = false,
          etag = response.header("ETag"),
          week = parsedWeek,
          games = games
        )
      }
    }
  }

  internal fun parseMflScheduleGames(schedObj: JsonObject?): List<NflGameState> {
    if (schedObj == null) return emptyList()
    return schedObj.oneOrManyObjects("matchup").mapIndexedNotNull { idx, mObj ->
      val teams = mObj.oneOrManyObjects("team")
      if (teams.size < 2) return@mapIndexedNotNull null
      val awayObj = teams.firstOrNull { !it.boolFromYesOrOne("isHome") } ?: teams[0]
      val homeObj = teams.firstOrNull { it.boolFromYesOrOne("isHome") } ?: teams[1]

      val awayTeam = NflTeam.fromMfl(awayObj.strOrEmpty("id"))
      val homeTeam = NflTeam.fromMfl(homeObj.strOrEmpty("id"))
      val kickoffSec = mObj.str("kickoff")?.toLongOrNull()
      val kickoffMillis = kickoffSec?.times(1000L)
      val rawStatus = mObj.strOrEmpty("status").uppercase()
      val secRem = mObj.intOrZero("gameSecondsRemaining")

      val phase = when (rawStatus) {
        "FINAL" -> GamePhase.FINAL
        "INPROG" -> if (secRem == 1800) GamePhase.HALFTIME else GamePhase.LIVE
        else -> {
          if (kickoffMillis != null && kickoffMillis <= System.currentTimeMillis() && secRem > 0) {
            GamePhase.LIVE
          } else {
            GamePhase.SCHEDULED
          }
        }
      }

      val period = when {
        phase == GamePhase.SCHEDULED -> 0
        phase == GamePhase.FINAL -> 4
        secRem > 2700 -> 1
        secRem > 1800 -> 2
        secRem > 900 -> 3
        else -> 4
      }
      val qSec = if (secRem > 0) secRem % 900 else 0
      val clockStr = if (phase == GamePhase.LIVE) {
        val mins = qSec / 60
        val secs = qSec % 60
        "%d:%02d".format(mins, secs)
      } else ""

      val awayHasBall = awayObj.boolFromYesOrOne("hasPossession")
      val homeHasBall = homeObj.boolFromYesOrOne("hasPossession")
      val inRz = awayObj.boolFromYesOrOne("inRedZone") || homeObj.boolFromYesOrOne("inRedZone")

      NflGameState(
        id = "mfl_${awayTeam.code}_${homeTeam.code}_${kickoffSec ?: idx}",
        away = NflSide(
          espnId = awayTeam.code,
          team = awayTeam,
          location = awayTeam.location,
          nickname = awayTeam.nickname,
          score = awayObj.intOrZero("score")
        ),
        home = NflSide(
          espnId = homeTeam.code,
          team = homeTeam,
          location = homeTeam.location,
          nickname = homeTeam.nickname,
          score = homeObj.intOrZero("score")
        ),
        phase = phase,
        period = period,
        clock = clockStr,
        possessionEspnId = when {
          awayHasBall -> awayTeam.code
          homeHasBall -> homeTeam.code
          else -> null
        },
        inRedZone = inRz,
        isLiveDown = true,
        kickoffEpochMillis = kickoffMillis
      )
    }
  }

  private fun checkForMflApiError(root: JsonObject) {
    val err = root.obj("error")?.str("\$t") ?: root.str("error")
    if (!err.isNullOrBlank()) {
      if (err.contains("APIKEY", ignoreCase = true) || err.contains("logged in", ignoreCase = true)) {
        throw MflAuthException(err)
      }
      throw IOException(err)
    }
  }

  private fun buildExportUrl(
    host: String,
    year: Int,
    type: String,
    leagueId: String? = null,
    apiKey: String? = null,
    params: Map<String, String> = emptyMap()
  ): HttpUrl {
    val base = upgradeHttpToHttpsIfMfl(host.trimEnd('/')).toHttpUrlOrNull()
      ?: upgradeHttpToHttpsIfMfl(apiBaseUrl).toHttpUrlOrNull()
      ?: throw IOException("Invalid MFL host: $host")
    val builder = base.newBuilder()
      .addPathSegment(year.toString())
      .addPathSegment("export")
      .addQueryParameter("TYPE", type)
      .addQueryParameter("JSON", "1")
    if (!leagueId.isNullOrBlank()) {
      builder.addQueryParameter("L", leagueId)
    }
    if (!apiKey.isNullOrBlank()) {
      builder.addQueryParameter("APIKEY", apiKey)
    }
    for ((k, v) in params.toSortedMap()) {
      builder.addQueryParameter(k, v)
    }
    return builder.build()
  }

  private suspend fun getJson(url: HttpUrl, mflUserIdCookie: String? = null): JsonObject {
    val reqBuilder = Request.Builder()
      .url(url)
      .header("User-Agent", userAgent)
    if (!mflUserIdCookie.isNullOrBlank()) {
      reqBuilder.header("Cookie", "MFL_USER_ID=$mflUserIdCookie")
    }
    return throttledExecute(reqBuilder.build()) { _, body ->
      LenientJson.parser.parseToJsonElement(body).jsonObject
    }
  }

  private suspend fun <T> throttledExecute(
    request: Request,
    allow304: Boolean = false,
    transform: (okhttp3.Response, String) -> T
  ): T = throttleMutex.withLock {
    val now = System.currentTimeMillis()
    if (backoffUntilMs > now) {
      val remSec = ((backoffUntilMs - now) / 1000L).coerceAtLeast(1L)
      throw MflRateLimitException("MFL rate-limited; cooling down ${remSec}s")
    }
    val nextAllowed = lastRequestAtMs + minRequestSpacingMs
    val current = System.currentTimeMillis()
    if (nextAllowed > current) {
      delay(nextAllowed - current)
    }
    lastRequestAtMs = System.currentTimeMillis()

    withContext(Dispatchers.IO) {
      var lastIoError: IOException? = null
      repeat(2) { attempt ->
        try {
          httpClient.newCall(request).execute().use { response ->
            if (response.code == 429) {
              backoffUntilMs = System.currentTimeMillis() + 60_000L
              throw MflRateLimitException()
            }
            if (allow304 && response.code == 304) {
              return@withContext transform(response, "")
            }
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
              if (response.code in 500..599 && attempt == 0) {
                delay(800L)
                return@repeat
              }
              throw IOException("MFL HTTP ${response.code}: ${bodyText.take(180)}")
            }
            return@withContext transform(response, bodyText)
          }
        } catch (rateLimit: MflRateLimitException) {
          throw rateLimit
        } catch (auth: MflAuthException) {
          throw auth
        } catch (io: IOException) {
          lastIoError = io
          if (attempt == 0) delay(750L)
        }
      }
      throw lastIoError ?: IOException("MFL request failed")
    }
  }

  private fun upgradeHttpToHttpsIfMfl(url: String): String =
    if (url.startsWith("http://", ignoreCase = true) && url.contains("myfantasyleague.com", ignoreCase = true)) {
      "https://" + url.substring(7)
    } else url
}
