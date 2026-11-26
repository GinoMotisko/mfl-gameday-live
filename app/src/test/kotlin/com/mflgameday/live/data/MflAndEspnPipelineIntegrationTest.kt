package com.mflgameday.live.data

import com.mflgameday.live.model.GamePhase
import com.mflgameday.live.model.LeagueConfig
import com.mflgameday.live.model.PlayKind
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MflAndEspnPipelineIntegrationTest {

  @Test
  fun mflLoginAndMyLeagues_extractsCookieAndMultiLeagueList() = runTest {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader("Set-Cookie", "MFL_USER_ID=aGVsbG9fdG9rZW4=; Path=/; HttpOnly")
        .setBody("""<?xml version="1.0" encoding="utf-8"?><status MFL_USER_ID="aGVsbG9fdG9rZW4=">OK</status>""")
    )
    // Single-league object vs multi-league array polymorphism test
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """
          {
            "leagues": {
              "league": [
                {
                  "league_id": "22539",
                  "name": "Beerfest",
                  "url": "https://www47.myfantasyleague.com/2026/home/22539",
                  "franchise_id": "0014",
                  "franchise_name": "Tampa Titans"
                },
                {
                  "league_id": "10479",
                  "name": "Wyoming Valley Conference",
                  "url": "https://www43.myfantasyleague.com/2026/home/10479",
                  "franchise_id": "0002",
                  "franchise_name": "Gridiron Kings"
                }
              ]
            }
          }
          """.trimIndent()
        )
    )
    server.start()
    try {
      val baseUrl = server.url("/").toString().trimEnd('/')
      val client = MflApiClient(apiBaseUrl = baseUrl, minRequestSpacingMs = 1L)

      val cookie = client.login("ceo_user", "secret_pw", year = 2026)
      assertEquals("aGVsbG9fdG9rZW4=", cookie)

      val leagues = client.fetchMyLeagues(cookie, year = 2026)
      assertEquals(2, leagues.size)
      assertEquals("22539", leagues[0].leagueId)
      assertEquals("0014", leagues[0].franchiseId)
      assertEquals("10479", leagues[1].leagueId)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun mflBestBallLeagueMetadataAndSingleMatchupLiveScoring_parseAccurately() = runTest {
    val server = MockWebServer()
    server.enqueue(
      MockResponse().setBody(
        """
        {
          "league": {
            "id": "22539",
            "name": "Beerfest",
            "baseURL": "https://www47.myfantasyleague.com",
            "bestLineup": "Yes",
            "precision": "0",
            "starters": {
              "count": "9",
              "position": { "name": "QB", "limit": "1-2" }
            },
            "franchises": {
              "franchise": { "id": "0014", "name": "Beerfest Champs", "abbrev": "BFC" }
            }
          }
        }
        """.trimIndent()
      )
    )
    server.enqueue(
      MockResponse().setBody(
        """
        {
          "liveScoring": {
            "matchup": {
              "franchise": [
                {
                  "id": "0014",
                  "score": "108",
                  "isHome": "1",
                  "playersCurrentlyPlaying": "5",
                  "playersYetToPlay": "2",
                  "gameSecondsRemaining": "15400",
                  "players": {
                    "player": {
                      "id": "14777",
                      "score": "24",
                      "status": "starter",
                      "gameSecondsRemaining": "1200"
                    }
                  }
                },
                {
                  "id": "0007",
                  "score": "95",
                  "isHome": "0",
                  "playersCurrentlyPlaying": "4",
                  "playersYetToPlay": "1"
                }
              ]
            }
          }
        }
        """.trimIndent()
      )
    )
    server.start()
    try {
      val base = server.url("/").toString().trimEnd('/')
      val client = MflApiClient(apiBaseUrl = base, minRequestSpacingMs = 1L)

      val meta = client.fetchLeagueMetadata("22539", 2026)
      assertTrue(meta.isBestBall)
      assertEquals(0, meta.precision)
      assertEquals(9, meta.starterCount)
      assertEquals("1-2", meta.positionLimits["QB"])
      assertEquals(1, meta.franchises.size)

      val cfg = LeagueConfig("22539", "Beerfest", 2026, "0014", "Beerfest Champs", baseUrl = base)
      val live = client.fetchLiveScoring(cfg, week = 11)
      assertEquals(1, live.size)
      assertEquals(2, live.first().franchises.size)
      assertEquals(108.0, live.first().franchises[0].score, 0.001)
      assertEquals(1, live.first().franchises[0].players.size)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun scoringBreakdownHtmlParser_decodesEntitiesAndLineBreaks() {
    val html = """
      <td class="points" id="points_14777"><a href="javascript:void(0);" title='&bull;4.00 pts for 1 Passing TDs &#xA;&bull;7.95 pts for 159&#xA; Passing Yards &#xA;&bull;-2.00 pts for 1 Interceptions &#xA;'>9.95</a></td>
      <td class="points" id="points_99999">-</td>
    """.trimIndent()

    val parsed = ScoringBreakdownParser.parse(html)
    val lines = parsed["14777"].orEmpty()
    assertEquals(3, lines.size)
    assertEquals("Passing TDs", lines[0].category)
    assertEquals("159", lines[1].count)
    assertEquals(7.95, lines[1].points, 0.001)
    assertEquals(-2.0, lines[2].points, 0.001)
    assertFalse(parsed.containsKey("99999"))
  }

  @Test
  fun espnScoreboardAndSummary_parsesSituationFieldProgressAndDeduplicatesDrives() = runTest {
    val server = MockWebServer()
    server.enqueue(
      MockResponse().setBody(
        """
        {
          "week": { "number": 11 },
          "events": [
            {
              "id": "401873072",
              "date": "2026-11-22T18:00Z",
              "shortName": "TB @ DET",
              "status": {
                "period": 3,
                "displayClock": "6:28",
                "type": { "name": "STATUS_IN_PROGRESS", "state": "in" }
              },
              "competitions": [
                {
                  "competitors": [
                    {
                      "id": "27",
                      "homeAway": "away",
                      "score": "21",
                      "team": { "id": "27", "abbreviation": "TB", "location": "Tampa Bay", "name": "Buccaneers" }
                    },
                    {
                      "id": "8",
                      "homeAway": "home",
                      "score": "17",
                      "team": { "id": "8", "abbreviation": "DET", "location": "Detroit", "name": "Lions" }
                    }
                  ],
                  "situation": {
                    "possession": "8",
                    "isRedZone": true,
                    "down": 2,
                    "downDistanceText": "2nd & Goal at TB 6",
                    "possessionText": "TB 6",
                    "yardLine": 6
                  }
                }
              ]
            }
          ]
        }
        """.trimIndent()
      )
    )
    server.enqueue(
      MockResponse().setBody(
        """
        {
          "header": { "id": "401873072", "competitions": [] },
          "scoringPlays": [
            { "id": "p2", "text": "Chris Godwin 94 Yd pass from Baker Mayfield (Chase McLaughlin Kick)" }
          ],
          "drives": {
            "previous": [
              {
                "plays": [
                  { "id": "p2", "sequenceNumber": "200", "type": { "text": "Passing Touchdown" }, "scoringPlay": true, "awayScore": 21, "homeScore": 17 },
                  { "id": "p1", "sequenceNumber": "100", "type": { "text": "Rush" }, "scoringPlay": false, "awayScore": 14, "homeScore": 17 }
                ]
              }
            ],
            "current": {
              "plays": [
                { "id": "p2", "sequenceNumber": "200", "type": { "text": "Passing Touchdown" }, "scoringPlay": true, "awayScore": 21, "homeScore": 17 }
              ]
            }
          }
        }
        """.trimIndent()
      )
    )
    server.start()
    try {
      val espn = EspnApiClient(baseUrl = server.url("/").toString().trimEnd('/'))
      val sb = espn.fetchScoreboard()
      assertEquals(1, sb.games.size)
      val g = sb.games.single()
      assertEquals(GamePhase.LIVE, g.phase)
      assertEquals("TBB", g.away.team.code)
      assertTrue(g.inRedZone)
      assertEquals("2nd & Goal at TB 6", g.downDistanceText)
      assertTrue((g.fieldProgressFraction ?: 0f) >= 0.90f)

      val summary = espn.fetchGameSummary("401873072")
      // Duplicate play `p2` across previous + current drive must be deduplicated and sorted after `p1`!
      assertEquals(2, summary.plays.size)
      assertEquals("p1", summary.plays[0].id)
      assertEquals("p2", summary.plays[1].id)
      assertEquals(PlayKind.TOUCHDOWN, summary.plays[1].kind)
      assertEquals("Chris Godwin 94 Yd pass from Baker Mayfield (Chase McLaughlin Kick)", summary.plays[1].text)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun mflStatus_parsesCompletedAndLineupWeek_andAdvancesEffectiveActiveWeekMidWeek() = runTest {
    val server = MockWebServer()
    // 1. Mid-week (Tue/Wed): CompletedWeek == LiveScoringWeek == 11, while CurrentWeek/UpcomingWeek/LineupWeek == 12
    server.enqueue(
      MockResponse().setBody(
        """
        {
          "version": "1.0",
          "encoding": "utf-8",
          "mfl_status": {
            "year": "2026",
            "weeks": {
              "LiveScoringWeek": "11",
              "CompletedWeek": "11",
              "CurrentWeek": "12",
              "UpcomingWeek": "12",
              "LineupWeek": "12"
            }
          }
        }
        """.trimIndent()
      )
    )
    // 2. Monday Night Football still active: CompletedWeek == 10, LiveScoringWeek == 11, UpcomingWeek == 12
    server.enqueue(
      MockResponse().setBody(
        """
        {
          "version": "1.0",
          "encoding": "utf-8",
          "mfl_status": {
            "year": "2026",
            "weeks": {
              "LiveScoringWeek": "11",
              "CompletedWeek": "10",
              "CurrentWeek": "11",
              "UpcomingWeek": "12",
              "LineupWeek": "12"
            }
          }
        }
        """.trimIndent()
      )
    )
    server.start()
    try {
      val client = MflApiClient(apiBaseUrl = server.url("/").toString().trimEnd('/'), minRequestSpacingMs = 1L)
      val midWeek = client.fetchStatus(2026)
      assertEquals(11, midWeek.completedWeek)
      assertEquals(11, midWeek.liveScoringWeek)
      assertEquals(12, midWeek.currentWeek)
      assertEquals("Must advance to Week 12 once Week 11 is marked CompletedWeek", 12, midWeek.effectiveActiveWeek)

      val activeMonday = client.fetchStatus(2026)
      assertEquals(10, activeMonday.completedWeek)
      assertEquals(11, activeMonday.liveScoringWeek)
      assertEquals("Must stay on Week 11 while Week 11 is not yet CompletedWeek", 11, activeMonday.effectiveActiveWeek)
    } finally {
      server.shutdown()
    }
  }
}
