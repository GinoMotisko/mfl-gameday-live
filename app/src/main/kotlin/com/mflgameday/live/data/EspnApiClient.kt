package com.mflgameday.live.data

import com.mflgameday.live.model.GamePhase
import com.mflgameday.live.model.LiveFieldSituation
import com.mflgameday.live.model.LiveFieldSituationResolver
import com.mflgameday.live.model.NflGameState
import com.mflgameday.live.model.NflPlay
import com.mflgameday.live.model.NflSide
import com.mflgameday.live.model.NflTeam
import com.mflgameday.live.model.PlayKind
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class EspnScoreboardSnapshot(
  val weekNumber: Int?,
  val games: List<NflGameState>,
  val liveEventIds: List<String>,
  val seasonType: Int? = null,
  val seasonYear: Int? = null
)

data class EspnGameSummary(
  val eventId: String,
  val plays: List<NflPlay>
)

/**
 * Client for ESPN's live NFL scoreboard and play-by-play summary endpoints.
 * Provides real-time possession, red-zone status, down & distance, field position,
 * and play-level scorer/sack/turnover detail.
 */
class EspnApiClient(
  private val httpClient: OkHttpClient = MflApiClient.defaultOkHttpClient(),
  private val baseUrl: String = "https://site.api.espn.com/apis/site/v2/sports/football/nfl",
  private val userAgent: String = "MFLGameDayAndroid/1.0"
) {

  suspend fun fetchScoreboard(week: Int? = null, seasonType: Int? = null): EspnScoreboardSnapshot {
    val base = "$baseUrl/scoreboard".toHttpUrlOrNull() ?: throw IOException("Invalid ESPN URL")
    val urlBuilder = base.newBuilder()
    if (seasonType != null) {
      urlBuilder.addQueryParameter("seasontype", seasonType.toString())
    }
    if (week != null) {
      urlBuilder.addQueryParameter("week", week.toString())
    }
    val root = fetchJsonObject(urlBuilder.build().toString())
    return parseScoreboard(root)
  }

  suspend fun fetchGameSummary(eventId: String): EspnGameSummary {
    val base = "$baseUrl/summary".toHttpUrlOrNull() ?: throw IOException("Invalid ESPN summary URL")
    val url = base.newBuilder().addQueryParameter("event", eventId).build()
    val root = fetchJsonObject(url.toString())
    return parseSummary(eventId, root)
  }

  internal fun parseScoreboard(root: JsonObject): EspnScoreboardSnapshot {
    val seasonObj = root.obj("season")
    val seasonType = seasonObj?.intOrNull("type")
    val seasonYear = seasonObj?.intOrNull("year")
    val weekNum = root.obj("week")?.intOrNull("number")
    val events = (root["events"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val games = ArrayList<NflGameState>(events.size)
    val liveIds = ArrayList<String>()

    for (ev in events) {
      val id = ev.str("id") ?: continue
      val statusObj = ev.obj("status")
      val typeObj = statusObj?.obj("type")
      val stateStr = typeObj?.strOrEmpty("state")?.lowercase().orEmpty()
      val typeName = typeObj?.strOrEmpty("name").orEmpty()

      if (stateStr == "in") {
        liveIds.add(id)
      }

      val comp = (ev["competitions"] as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
      val competitors = (comp["competitions"] as? JsonArray ?: comp["competitors"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }.orEmpty()
      val awayComp = competitors.firstOrNull { it.str("homeAway") == "away" } ?: continue
      val homeComp = competitors.firstOrNull { it.str("homeAway") == "home" } ?: continue

      val phase = when (stateStr) {
        "in" -> if (typeName == "STATUS_HALFTIME") GamePhase.HALFTIME else GamePhase.LIVE
        "post" -> GamePhase.FINAL
        else -> GamePhase.SCHEDULED
      }

      val situation = comp.obj("situation")
      val down = situation?.intOrNull("down") ?: 0
      val isLiveDown = down >= 1
      val awaySide = parseSide(awayComp)
      val homeSide = parseSide(homeComp)
      val possessionEspnId = situation?.str("possession")
      val possTeam = when (possessionEspnId) {
        awaySide.espnId -> awaySide.team
        homeSide.espnId -> homeSide.team
        else -> null
      }
      val rawInRedZone = situation?.str("isRedZone")?.toBooleanStrictOrNull() ?: false
      val downDistanceText = situation?.strOrEmpty("downDistanceText").orEmpty()
      val possessionText = situation?.strOrEmpty("possessionText").orEmpty()
      val lastPlayObj = situation?.obj("lastPlay")
      val lastPlayText = lastPlayObj?.strOrEmpty("text").orEmpty()
      val lastPlayTypeText = lastPlayObj?.obj("type")?.strOrEmpty("text").orEmpty()
      val period = statusObj?.intOrZero("period") ?: 0
      val clock = statusObj?.strOrEmpty("displayClock").orEmpty()

      val fieldSituation = LiveFieldSituationResolver.classifyStateless(
        phase = phase,
        isLiveDown = isLiveDown,
        inRedZoneRaw = rawInRedZone,
        possessionTeam = possTeam,
        downDistanceText = downDistanceText,
        possessionText = possessionText,
        lastPlayTypeText = lastPlayTypeText,
        lastPlayText = lastPlayText,
        period = period,
        clock = clock
      )

      val broadcasts = (comp["broadcasts"] as? JsonArray)
        ?.firstOrNull()
        ?.let { it as? JsonObject }
        ?.let { b -> (b["names"] as? JsonArray)?.firstOrNull()?.toString()?.trim('"') }
        .orEmpty()
      val venueName = comp.obj("venue")?.strOrEmpty("fullName").orEmpty()

      games.add(
        NflGameState(
          id = id,
          away = awaySide,
          home = homeSide,
          phase = phase,
          period = period,
          clock = clock,
          possessionEspnId = possessionEspnId,
          inRedZone = rawInRedZone && fieldSituation == LiveFieldSituation.SCRIMMAGE_DRIVE,
          isLiveDown = isLiveDown,
          fieldSituation = fieldSituation,
          downDistanceText = downDistanceText,
          possessionText = possessionText,
          yardLine = situation?.intOrNull("yardLine"),
          lastPlayText = lastPlayText,
          kickoffEpochMillis = parseIsoMillis(ev.str("date")),
          venue = venueName,
          broadcast = broadcasts
        )
      )
    }

    val sortedGames = games.sortedWith(
      compareBy<NflGameState> {
        when (it.phase) {
          GamePhase.LIVE -> 0
          GamePhase.HALFTIME -> 1
          GamePhase.SCHEDULED -> 2
          GamePhase.FINAL -> 3
        }
      }.thenBy { it.kickoffEpochMillis ?: Long.MAX_VALUE }
        .thenBy { it.id }
    )

    return EspnScoreboardSnapshot(
      weekNumber = weekNum,
      games = sortedGames,
      liveEventIds = liveIds,
      seasonType = seasonType,
      seasonYear = seasonYear
    )
  }

  private fun parseSide(compObj: JsonObject): NflSide {
    val teamObj = compObj.obj("team")
    val abbrev = teamObj?.str("abbreviation") ?: "NFL"
    val nflTeam = NflTeam.fromEspn(abbrev)
    val record = (compObj["records"] as? JsonArray)
      ?.mapNotNull { it as? JsonObject }
      ?.firstOrNull()
      ?.strOrEmpty("summary")
      .orEmpty()
    val logo = teamObj?.str("logo")
    return NflSide(
      espnId = teamObj?.str("id") ?: compObj.strOrEmpty("id"),
      team = nflTeam,
      location = teamObj?.str("location") ?: nflTeam.location,
      nickname = teamObj?.str("name") ?: nflTeam.nickname,
      score = compObj.intOrZero("score"),
      record = record,
      logoUrl = logo
    )
  }

  internal fun parseSummary(fallbackEventId: String, root: JsonObject): EspnGameSummary {
    val eventId = root.obj("header")?.str("id") ?: fallbackEventId

    // Clean summary text for scoring plays ("Chris Godwin 94 Yd pass from Baker Mayfield (Chase McLaughlin Kick)")
    val cleanScoringText = HashMap<String, String>()
    (root["scoringPlays"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.forEach { sp ->
      val id = sp.str("id")
      val text = sp.str("text")
      if (id != null && text != null && !cleanScoringText.containsKey(id)) {
        cleanScoringText[id] = text
      }
    }

    val drivesObj = root.obj("drives")
    val prevDrives = (drivesObj?.get("previous") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val currentDrive = drivesObj?.obj("current")?.let { listOf(it) }.orEmpty()
    val allPlayObjs = (prevDrives + currentDrive).flatMap { drive ->
      (drive["plays"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    }

    val seenIds = HashSet<String>(allPlayObjs.size)
    val deduplicated = allPlayObjs.filter { p ->
      val id = p.str("id") ?: return@filter false
      seenIds.add(id)
    }

    val plays = deduplicated
      .mapNotNull { p -> parsePlay(p, cleanScoringText) }
      .sortedBy { it.sequence }

    return EspnGameSummary(eventId = eventId, plays = plays)
  }

  private fun parsePlay(pObj: JsonObject, cleanScoringText: Map<String, String>): NflPlay? {
    val id = pObj.str("id") ?: return null
    val seq = pObj.str("sequenceNumber")?.toIntOrNull()
      ?: id.takeLast(6).toIntOrNull()
      ?: 0
    val rawText = pObj.strOrEmpty("text")
    val summaryText = cleanScoringText[id]
    val typeText = pObj.obj("type")?.strOrEmpty("text").orEmpty()
    val isScoring = pObj.str("scoringPlay")?.toBooleanStrictOrNull() ?: false
    val isTurnover = pObj.str("isTurnover")?.toBooleanStrictOrNull() ?: false
    val yardage = pObj.intOrZero("statYardage")

    return NflPlay(
      id = id,
      sequence = seq,
      kind = PlayKind.fromEspn(
        espnType = typeText,
        isScoring = isScoring,
        playText = rawText,
        yardsStat = yardage
      ),
      text = summaryText ?: rawText,
      rawPlayText = rawText,
      awayScore = pObj.intOrZero("awayScore"),
      homeScore = pObj.intOrZero("homeScore"),
      period = pObj.obj("period")?.intOrZero("number") ?: 1,
      clock = pObj.obj("clock")?.strOrEmpty("displayValue").orEmpty(),
      offenseTeamId = pObj.obj("start")?.obj("team")?.str("id"),
      isTurnover = isTurnover,
      statYardage = yardage,
      wallclockEpochMillis = parseIsoMillis(pObj.str("wallclock"))
    )
  }

  internal fun parseIsoMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try {
      Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
      try {
        OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
      } catch (_: Exception) {
        try {
          // ESPN scoreboard dates sometimes omit seconds: "2026-11-22T18:00Z"
          val normalized = if (iso.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}Z"""))) {
            iso.dropLast(1) + ":00Z"
          } else iso
          Instant.parse(normalized).toEpochMilli()
        } catch (_: Exception) {
          null
        }
      }
    }
  }

  private suspend fun fetchJsonObject(url: String): JsonObject = withContext(Dispatchers.IO) {
    val request = Request.Builder()
      .url(url)
      .header("User-Agent", userAgent)
      .build()
    var lastError: IOException? = null
    repeat(2) { attempt ->
      try {
        httpClient.newCall(request).execute().use { response ->
          val body = response.body?.string().orEmpty()
          if (!response.isSuccessful) {
            if (response.code in 500..599 && attempt == 0) {
              kotlinx.coroutines.delay(650L)
              return@repeat
            }
            throw IOException("ESPN HTTP ${response.code}: ${body.take(160)}")
          }
          return@withContext LenientJson.parser.parseToJsonElement(body).jsonObject
        }
      } catch (e: IOException) {
        lastError = e
        if (attempt == 0) kotlinx.coroutines.delay(650L)
      } catch (e: Exception) {
        throw IOException("Malformed ESPN JSON response: ${e.message}", e)
      }
    }
    throw lastError ?: IOException("ESPN request failed")
  }
}
