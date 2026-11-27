package com.mflgameday.live.data

import com.mflgameday.live.model.GamePhase
import com.mflgameday.live.model.LeagueConfig
import com.mflgameday.live.model.LiveFieldSituation
import com.mflgameday.live.model.LiveFieldSituationResolver
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

  @Test
  fun espnPostTouchdownOfficialTimeout_clearsStuckRedZoneAndSpotsKickoffTee() = runTest {
    val server = MockWebServer()
    // Simulates ESPN's exact mid-commercial-break payload after TD + XP + "Official Timeout"
    // where ESPN still leaves isRedZone = true, down = -1, yardLine = 15!
    server.enqueue(
      MockResponse().setBody(
        """
        {
          "week": { "number": 12 },
          "events": [
            {
              "id": "401873999",
              "date": "2026-11-26T17:30Z",
              "status": {
                "period": 2,
                "displayClock": "4:12",
                "type": { "name": "STATUS_IN_PROGRESS", "state": "in" }
              },
              "competitions": [
                {
                  "competitors": [
                    {
                      "id": "6",
                      "homeAway": "away",
                      "score": "14",
                      "team": { "id": "6", "abbreviation": "DAL", "location": "Dallas", "name": "Cowboys" }
                    },
                    {
                      "id": "8",
                      "homeAway": "home",
                      "score": "21",
                      "team": { "id": "8", "abbreviation": "DET", "location": "Detroit", "name": "Lions" }
                    }
                  ],
                  "situation": {
                    "possession": "8",
                    "isRedZone": true,
                    "down": -1,
                    "downDistanceText": "",
                    "possessionText": "DAL 15",
                    "yardLine": 15,
                    "lastPlay": {
                      "text": "Official Timeout",
                      "type": { "text": "Official Timeout" }
                    }
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
          "header": { "id": "401873999" },
          "scoringPlays": [
            { "id": "p50", "text": "Jahmyr Gibbs 8 Yd Run (Jake Bates Kick)" }
          ],
          "drives": {
            "current": {
              "plays": [
                {
                  "id": "p50",
                  "sequenceNumber": "500",
                  "type": { "text": "Rushing Touchdown" },
                  "text": "J.Gibbs right tackle for 8 yards, TOUCHDOWN.",
                  "scoringPlay": true,
                  "awayScore": 14,
                  "homeScore": 21
                },
                {
                  "id": "p51",
                  "sequenceNumber": "510",
                  "type": { "text": "Official Timeout" },
                  "text": "Official Timeout at 04:12.",
                  "scoringPlay": false,
                  "awayScore": 14,
                  "homeScore": 21
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
      val espn = EspnApiClient(baseUrl = server.url("/").toString().trimEnd('/'))
      val sb = espn.fetchScoreboard()
      val summary = espn.fetchGameSummary("401873999")
      val refined = LiveFieldSituationResolver.refineWithPlayHistory(sb.games.single(), summary.plays)
      assertEquals(LiveFieldSituation.KICKOFF_PENDING, refined.fieldSituation)
      assertFalse("Refined game must clear ESPN's stuck PAT Red Zone flag", refined.effectiveInRedZone)
      assertEquals("8", refined.possessionEspnId)
      assertEquals(0.35f, refined.fieldProgressFraction ?: 0f, 0.001f)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun espnDefensiveStripSackFumbleRecoveryTouchdown_classifiesAsTouchdownNotOtherScore() = runTest {
    val server = MockWebServer()
    server.enqueue(
      MockResponse().setBody(
        """
        {
          "header": { "id": "401873083" },
          "scoringPlays": [
            {
              "id": "401873083512",
              "type": { "id": "80", "text": "Sack Opp Fumble Recovery", "abbreviation": "SFOP" },
              "text": "T.J. Watt Fumble Recovery in End Zone (Chris Boswell Kick)",
              "awayScore": 0,
              "homeScore": 7,
              "scoringType": { "name": "touchdown", "displayName": "Touchdown", "abbreviation": "TD" }
            }
          ],
          "drives": {
            "previous": [
              {
                "plays": [
                  {
                    "id": "401873083512",
                    "sequenceNumber": "51200",
                    "type": { "id": "80", "text": "Sack Opp Fumble Recovery" },
                    "text": "(Shotgun) B.Nix sacked at DEN 6 for -13 yards. FUMBLES, RECOVERED by PIT-T.Watt at DEN -3. TOUCHDOWN. C.Boswell extra point is GOOD, Center-C.Kuntz, Holder-C.Johnston.",
                    "scoringPlay": true,
                    "isTurnover": true,
                    "statYardage": 0,
                    "awayScore": 0,
                    "homeScore": 7,
                    "period": { "number": 1 },
                    "clock": { "displayValue": "6:03" },
                    "start": { "team": { "id": "7" } },
                    "wallclock": "2026-11-27T20:22:33Z"
                  },
                  {
                    "id": "401873083901",
                    "sequenceNumber": "51300",
                    "type": { "id": "5", "text": "Rush" },
                    "text": "J.Warren left tackle for 12 yards, TOUCHDOWN NULLIFIED by Penalty.",
                    "scoringPlay": false,
                    "awayScore": 0,
                    "homeScore": 7
                  },
                  {
                    "id": "401873083902",
                    "sequenceNumber": "51400",
                    "type": { "id": "24", "text": "Pass Reception" },
                    "text": "B.Nix pass deep right to C.Sutton for 30 yards, TOUCHDOWN. The Replay Official reviewed the runner broke the plane ruling, and the play was REVERSED. B.Nix pass deep right to C.Sutton to PIT 1 for 29 yards.",
                    "scoringPlay": false,
                    "awayScore": 0,
                    "homeScore": 7
                  },
                  {
                    "id": "401873083903",
                    "sequenceNumber": "51500",
                    "type": { "id": "79", "text": "Defensive 2pt Conversion" },
                    "text": "M.Fitzpatrick defensive two-point conversion.",
                    "scoringPlay": true,
                    "awayScore": 0,
                    "homeScore": 9
                  }
                ]
              }
            ]
          }
        }
        """.trimIndent()
      )
    )
    server.start()
    try {
      val espn = EspnApiClient(baseUrl = server.url("/").toString().trimEnd('/'))
      val summary = espn.fetchGameSummary("401873083")
      assertEquals(4, summary.plays.size)

      val wattTd = summary.plays[0]
      assertEquals("401873083512", wattTd.id)
      assertEquals(PlayKind.TOUCHDOWN, wattTd.kind)
      assertTrue(wattTd.isTurnover)
      assertEquals("7", wattTd.offenseTeamId)

      assertFalse(summary.plays[1].kind == PlayKind.TOUCHDOWN)
      assertFalse(summary.plays[2].kind == PlayKind.TOUCHDOWN)
      assertEquals(PlayKind.OTHER_SCORE, summary.plays[3].kind)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun mflNormalizeFranchiseId_stripsCommissionerZeroZeroZeroZeroWhenOwnedFranchisePresent() {
    assertEquals("0007", MflApiClient.normalizeFranchiseId("0000,0007"))
    assertEquals("0002", MflApiClient.normalizeFranchiseId("0002, 0000"))
    assertEquals("0006", MflApiClient.normalizeFranchiseId("0006"))
    assertEquals(null, MflApiClient.normalizeFranchiseId("0000"))
    assertEquals(null, MflApiClient.normalizeFranchiseId(null))
  }

  @Test
  fun mflLiveStatsBreakdownCalculator_computesExactBenchPlayerAndTeamDefenseBreakdowns() {
    val ajaxHtml = """
      <script type="text/javascript">
      var xmlBaseURL = 'https://www43.myfantasyleague.com/fflnetdynamic2026/';
      var ls_nfl_games = [];
      ls_nfl_games["BUF"] = [];
      ls_nfl_games["BUF"].opp = "KCC";
      ls_nfl_games["DEN"] = [];
      ls_nfl_games["DEN"].opp = "PIT";
      ls_nfl_games["PIT"] = [];
      ls_nfl_games["PIT"].opp = "DEN";
      ls_nfl_games["LAR"] = [];
      ls_nfl_games["LAR"].opp = "GBP";
      var ls_cat_desc = {
        '#P' : "Passing TDs",
        'PY' : "Passing Yards",
        '#C' : "Receiving TDs",
        'CC' : "Completions",
        'CY' : "Receiving Yards",
        'RY' : "Rushing Yards",
        'FG' : "yd Field Goal",
        'IC' : "Interceptions",
        'FC' : "Fumble Recoveries (from Opponent)",
        'SK' : "Sacked a QB",
        'TPA' : "Total Points Allowed",
        'DIF' : "Point Differential"
      };
      var ls_list_cats = {
        'FR' : 1,
        'FG' : 1
      };
      var ls_team_pos = { QB:0,RB:0,WR:0,PK:0,TE:0,Def:1 };
      var ls_uses_idps = 0;
      ls_player["13595"] = new LSPlayer("13595","Rudolph, Mason", "QB", "PIT", "QB");
      ls_player["14102"] = new LSPlayer("14102","Metcalf, DK", "WR", "PIT", "WR");
      ls_player["15111"] = new LSPlayer("15111","Boswell, Chris", "PK", "PIT", "PK");
      ls_player["0504"] = new LSPlayer("0504","Bills", "Def", "BUF", "Def");
      ls_player["0528"] = new LSPlayer("0528","Rams", "Def", "LAR", "Def");

      function score_player(pid,ppos) {
        var points = 0;
        var val = undefined;
        if(/QB|RB|WR|PK/.test(ppos)) {
           if(ls_stats[pid] != undefined) {
              val = ls_stats[pid]["#P"];
              points += ls_get_points("#P", val, "0", "10", "*4", "");
              val = ls_stats[pid]["PY"];
              points += ls_get_points("PY", val, "0", "999", "*0.05", "");
              val = ls_stats[pid]["CC"];
              points += ls_get_points("CC", val, "0", "99", "*1", "");
              val = ls_stats[pid]["#C"];
              points += ls_get_points("#C", val, "0", "10", "*6", "");
              val = ls_stats[pid]["CY"];
              points += ls_get_points("CY", val, "0", "999", "*0.1", "");
              val = ls_stats[pid]["RY"];
              points += ls_get_points("RY", val, "0", "999", "1/25", "");
              if (ls_stats[pid]["FG"] !== undefined) {
                 var vals = ls_stats[pid]["FG"].split(",");
                 for(var i in vals) {
                    val = vals[i];
              points += ls_get_points("FG", val, "0", "39", "3", "");
              points += ls_get_points("FG", val, "40", "49", "4", "");
              points += ls_get_points("FG", val, "50", "59", "5", "");
                 }
              }
          }
        }
        if(ppos == "Def") {
           var opp = ls_nfl_games[pid].opp;
           if(ls_tstats[pid] != undefined) {
              val = ls_tstats[pid]["TPA"];
              if(val == undefined) { val = "0"; }
              points += ls_get_points("TPA", val, "0", "1", "10", "");
              points += ls_get_points("TPA", val, "2", "6", "7", "");
              points += ls_get_points("TPA", val, "7", "13", "4", "");
              val = ls_tstats[pid]["SK"];
              if(val == undefined) { val = "0"; }
              points += ls_get_points("SK", val, "0", "25", "*1", "");
              val = ls_tstats[pid]["DIF"];
              if(val == undefined) { val = "0"; }
              points += ls_get_points("DIF", val, "1", "99", "6", "");
          }
        }
        show_math[pid] = pid_str;
        return(points);
      }
      function score_player_proj(pid,ppos) {
        return 999;
      }
      </script>
    """.trimIndent()

    val liveStatsTxt = "DATE|1795818455|Fri Nov 27, 2026\n" +
      "REFRESH|40\n" +
      "13595|#P 1\r|PY 104\r|RY 30\r|FL 0\n" +
      "14102|#C 1\r|CC 3\r|CY 36\n" +
      "15111|FG 47\r,33\r\n" +
      "BUF|DIF 3|SK 1|TPA 10\n"

    val rules = MflLiveStatsBreakdownCalculator.parseAjaxLeagueRules(ajaxHtml)
    val stats = MflLiveStatsBreakdownCalculator.parseLiveWeekStats(liveStatsTxt)

    assertEquals("https://www43.myfantasyleague.com/fflnetdynamic2026/", rules.xmlBaseUrl)
    assertFalse(rules.usesIdps)
    assertTrue("Def" in rules.teamPositions)

    // Mason Rudolph (bench QB): 1 Pass TD (4.00) + 104 Pass Yds (5.20) + 30 Rush Yds int-div 1/25 (1.00)
    val rudolph = MflLiveStatsBreakdownCalculator.computeBreakdownsForPlayer(
      playerId = "13595",
      rawScore = RawMflPlayerScore("13595", score = 10.20, isStarter = false, gameSecondsRemaining = 895),
      fallbackPlayer = null,
      rules = rules,
      liveStats = stats
    )
    assertEquals(3, rudolph.size)
    assertEquals("Passing TDs", rudolph[0].category)
    assertEquals("1", rudolph[0].count)
    assertEquals(4.0, rudolph[0].points, 0.001)
    assertEquals("Passing Yards", rudolph[1].category)
    assertEquals("104", rudolph[1].count)
    assertEquals(5.20, rudolph[1].points, 0.001)
    assertEquals("Rushing Yards", rudolph[2].category)
    assertEquals("30", rudolph[2].count)
    assertEquals(1.0, rudolph[2].points, 0.001)

    // DK Metcalf (bench WR): 3 Completions (3.00) + 1 Rec TD (6.00) + 36 Rec Yds (3.60) = 12.60
    val metcalf = MflLiveStatsBreakdownCalculator.computeBreakdownsForPlayer(
      playerId = "14102",
      rawScore = RawMflPlayerScore("14102", score = 12.60, isStarter = false, gameSecondsRemaining = 895),
      fallbackPlayer = null,
      rules = rules,
      liveStats = stats
    )
    assertEquals(12.60, metcalf.sumOf { it.points }, 0.001)

    // Kicker multi-FG comma list with CRLF inside list (`47\r,33`)
    val boswell = MflLiveStatsBreakdownCalculator.computeBreakdownsForPlayer(
      playerId = "15111",
      rawScore = RawMflPlayerScore("15111", score = 7.0, isStarter = false, gameSecondsRemaining = 895),
      fallbackPlayer = null,
      rules = rules,
      liveStats = stats
    )
    assertEquals(2, boswell.size)
    assertEquals("47", boswell[0].count)
    assertEquals(4.0, boswell[0].points, 0.001)
    assertEquals("33", boswell[1].count)
    assertEquals(3.0, boswell[1].points, 0.001)

    // Buffalo Defense: TPA 10 (4.0) + SK 1 (1.0) + DIF 3 (6.0) = 11.0
    val bills = MflLiveStatsBreakdownCalculator.computeBreakdownsForPlayer(
      playerId = "0504",
      rawScore = RawMflPlayerScore("0504", score = 11.0, isStarter = true, gameSecondsRemaining = 0),
      fallbackPlayer = null,
      rules = rules,
      liveStats = stats
    )
    assertEquals(3, bills.size)
    assertEquals(11.0, bills.sumOf { it.points }, 0.001)

    // Scheduled Def Guard: game not started (3600s left, score 0, missing from tstats) -> MUST NOT fabricate TPA=0 (10 pts)
    val rams = MflLiveStatsBreakdownCalculator.computeBreakdownsForPlayer(
      playerId = "0528",
      rawScore = RawMflPlayerScore("0528", score = 0.0, isStarter = false, gameSecondsRemaining = 3600),
      fallbackPlayer = null,
      rules = rules,
      liveStats = stats
    )
    assertTrue("Scheduled team defense must not emit pre-kickoff TPA points", rams.isEmpty())
  }

  @Test
  fun mflFetchScoringBreakdowns_hydratesMissingBenchPlayersViaAjaxLsAndLiveStatsWithRefererAndCache() = runTest {
    val server = MockWebServer()
    server.start()
    try {
      val baseUrl = server.url("/").toString().trimEnd('/')
      val client = MflApiClient(apiBaseUrl = baseUrl, minRequestSpacingMs = 1L)
      val config = LeagueConfig(
        leagueId = "10479",
        leagueName = "Wyoming Valley Conference",
        year = 2026,
        franchiseId = "0008",
        franchiseName = "Cleaveland Steamers",
        baseUrl = baseUrl
      )

      val ajaxRulesBody = """
        <script type="text/javascript">
        var xmlBaseURL = '$baseUrl/fflnetdynamic2026/';
        var ls_cat_desc = { '#P' : "Passing TDs", 'PY' : "Passing Yards" };
        var ls_list_cats = {};
        var ls_team_pos = { QB:0,Def:1 };
        var ls_uses_idps = 0;
        ls_player["13595"] = new LSPlayer("13595","Rudolph, Mason", "QB", "PIT", "QB");
        function score_player(pid,ppos) {
          var points = 0;
          var val = undefined;
          if(/QB|RB|WR|PK/.test(ppos)) {
            if(ls_stats[pid] != undefined) {
              val = ls_stats[pid]["#P"];
              points += ls_get_points("#P", val, "0", "10", "*4", "");
              val = ls_stats[pid]["PY"];
              points += ls_get_points("PY", val, "0", "999", "*0.05", "");
            }
          }
          show_math[pid] = pid_str;
          return(points);
        }
        </script>
      """.trimIndent()

      // Call 1: summary (starter 0504 only) -> ajax_ls -> live_stats_12.txt (hydrated bench 13595)
      server.enqueue(
        MockResponse().setBody(
          """<td class="points" id="points_0504"><a title='&bull;6.00 pts for 3 Point Differential &bull;4.00 pts for 10 Total Points Allowed &bull;1.00 pts for 1 Sacked a QB'>11.00</a></td>"""
        )
      )
      server.enqueue(MockResponse().setBody(ajaxRulesBody))
      server.enqueue(MockResponse().setBody("DATE|1795818455|Now\n13595|#P 1|PY 104\n"))

      // Call 2 (same week, short window): summary still omits 13595 -> ajax_ls & live_stats_12.txt MUST hit cache!
      server.enqueue(
        MockResponse().setBody(
          """<td class="points" id="points_0504"><a title='&bull;6.00 pts for 3 Point Differential &bull;4.00 pts for 10 Total Points Allowed &bull;1.00 pts for 1 Sacked a QB'>11.00</a></td>"""
        )
      )

      val rawMatchups = listOf(
        RawMflMatchup(
          franchises = listOf(
            RawMflFranchiseLive(
              franchiseId = "0010",
              score = 89.80,
              isHome = true,
              playersCurrentlyPlaying = 2,
              playersYetToPlay = 3,
              gameSecondsRemaining = 1790,
              players = listOf(
                RawMflPlayerScore("0504", score = 11.00, isStarter = true, gameSecondsRemaining = 0),
                RawMflPlayerScore("13595", score = 9.20, isStarter = false, gameSecondsRemaining = 895),
                RawMflPlayerScore("99999", score = 0.0, isStarter = false, gameSecondsRemaining = 3600)
              )
            )
          )
        )
      )

      val firstMap = client.fetchScoringBreakdowns(config, week = 12, rawMatchups = rawMatchups)
      assertEquals(3, firstMap["0504"].orEmpty().size)
      val rudolphLines = firstMap["13595"].orEmpty()
      assertEquals(2, rudolphLines.size)
      assertEquals(4.0, rudolphLines[0].points, 0.001)
      assertEquals(5.2, rudolphLines[1].points, 0.001)
      assertEquals(9.20, rudolphLines.sumOf { it.points }, 0.001)

      val reqSummary = server.takeRequest()
      val reqAjax = server.takeRequest()
      val reqStats = server.takeRequest()
      assertTrue(reqSummary.path.orEmpty().contains("/2026/live_scoring_summary"))
      assertTrue(reqAjax.path.orEmpty().contains("/2026/ajax_ls?L=10479&W=12"))
      assertTrue(reqStats.path.orEmpty().contains("/fflnetdynamic2026/live_stats_12.txt"))
      assertEquals("$baseUrl/2026/ajax_ls?L=10479&W=12", reqStats.getHeader("Referer"))

      // Second call within liveStats TTL reuses both cached ajax_ls rules and cached live_stats_12.txt!
      val secondMap = client.fetchScoringBreakdowns(config, week = 12, rawMatchups = rawMatchups)
      assertEquals(4, server.requestCount)
      assertEquals(9.20, secondMap["13595"].orEmpty().sumOf { it.points }, 0.001)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun mflFetchScoringBreakdownsWithStatus_marksIncompleteBenchHydrationOnTransientAjaxFailure() = runTest {
    val server = MockWebServer()
    server.start()
    try {
      val baseUrl = server.url("/").toString().trimEnd('/')
      val client = MflApiClient(apiBaseUrl = baseUrl, minRequestSpacingMs = 1L)
      val config = LeagueConfig(
        leagueId = "10479",
        leagueName = "Wyoming Valley Conference",
        year = 2026,
        franchiseId = "0008",
        franchiseName = "Cleaveland Steamers",
        baseUrl = baseUrl
      )
      val rawMatchups = listOf(
        RawMflMatchup(
          franchises = listOf(
            RawMflFranchiseLive(
              franchiseId = "0010",
              score = 20.20,
              isHome = true,
              playersCurrentlyPlaying = 1,
              playersYetToPlay = 0,
              gameSecondsRemaining = 895,
              players = listOf(
                RawMflPlayerScore("0504", score = 11.00, isStarter = true, gameSecondsRemaining = 0),
                RawMflPlayerScore("13595", score = 9.20, isStarter = false, gameSecondsRemaining = 895)
              )
            )
          )
        )
      )

      // Summary succeeds with starter 0504, while ajax_ls returns HTTP 500 on both retry attempts
      server.enqueue(
        MockResponse().setBody(
          """<td class="points" id="points_0504"><a title='&bull;4.00 pts for 10 Total Points Allowed'>4.00</a></td>"""
        )
      )
      server.enqueue(MockResponse().setResponseCode(500).setBody("upstream error"))
      server.enqueue(MockResponse().setResponseCode(500).setBody("upstream error"))

      val res = client.fetchScoringBreakdownsWithStatus(config, week = 12, rawMatchups = rawMatchups)
      assertTrue("Must flag incomplete hydration so coordinator retries next poll", res.hasIncompleteBenchHydration)
      assertEquals(1, res.breakdowns["0504"].orEmpty().size)
      assertTrue(res.breakdowns["13595"].isNullOrEmpty())
    } finally {
      server.shutdown()
    }
  }
}
