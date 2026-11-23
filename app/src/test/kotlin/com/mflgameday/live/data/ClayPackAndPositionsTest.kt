package com.mflgameday.live.data

import com.mflgameday.live.engine.BestBallAndPaceCalculator
import com.mflgameday.live.engine.DemoSimulatorData
import com.mflgameday.live.model.ExposureRole
import com.mflgameday.live.model.FantasyPlayer
import com.mflgameday.live.model.GameEvent
import com.mflgameday.live.model.GameEventKind
import com.mflgameday.live.model.GamePhase
import com.mflgameday.live.model.NflGameState
import com.mflgameday.live.model.NflPlay
import com.mflgameday.live.model.NflSide
import com.mflgameday.live.model.NflTeam
import com.mflgameday.live.model.PlayKind
import com.mflgameday.live.model.PlayerCrossLeagueExposure
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClayPackAndPositionsTest {

  private lateinit var server: MockWebServer
  private lateinit var tempDir: java.io.File

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    tempDir = Files.createTempDirectory("clay_pack_test").toFile()
  }

  @After
  fun tearDown() {
    server.shutdown()
    tempDir.deleteRecursively()
  }

  @Test
  fun `all 25 MFL position codes classify cleanly across offense, defense, IDP, coach, and team units`() {
    val defUnits = listOf("Def", "ST", "TMDL", "TMLB", "TMDB")
    for (pos in defUnits) {
      val p = FantasyPlayer("0501", "Bills, Buffalo", pos, NflTeam("BUF"))
      assertTrue("$pos should be defensive team unit", p.isDefensiveTeamUnit)
      assertFalse("$pos should not be offensive team unit", p.isOffensiveTeamUnit)
      assertTrue("$pos should be defensive side", p.isDefensiveSide)
      assertTrue("$pos should be team defense", p.isTeamDefense)
      assertTrue("$pos should be team unit", p.isTeamUnit)
    }

    val offUnits = listOf("Off", "TMQB", "TMRB", "TMWR", "TMTE", "TMPK", "TMPN")
    for (pos in offUnits) {
      val p = FantasyPlayer("0651", "Bills, Buffalo", pos, NflTeam("BUF"))
      assertTrue("$pos should be offensive team unit", p.isOffensiveTeamUnit)
      assertFalse("$pos should not be defensive team unit", p.isDefensiveTeamUnit)
      assertFalse("$pos should not be defensive side", p.isDefensiveSide)
      assertFalse("$pos should NOT be classified as team defense", p.isTeamDefense)
      assertTrue("$pos should be team unit", p.isTeamUnit)
    }

    val idps = listOf("DT", "DE", "LB", "CB", "S")
    for (pos in idps) {
      val p = FantasyPlayer("9826", "Graham, Brandon", pos, NflTeam("PHI"), jerseyNumber = "55")
      assertTrue("$pos should be IDP", p.isIdp)
      assertTrue("$pos should be defensive side", p.isDefensiveSide)
      assertFalse("$pos should not be team defense", p.isTeamDefense)
      assertFalse("$pos should not be team unit", p.isTeamUnit)
    }

    val coach = FantasyPlayer("1276", "Harbaugh, Jim", "Coach", NflTeam("LAC"), jerseyNumber = "4")
    assertTrue(coach.isHeadCoach)
    assertFalse(coach.isDefensiveSide)
    assertFalse(coach.isTeamUnit)
    assertEquals("jim_harbaugh", coach.claySlug)

    val skillPositions = listOf("QB", "RB", "WR", "TE", "PK", "PN", "XX")
    for (pos in skillPositions) {
      val p = FantasyPlayer("13589", "Jackson, Lamar", pos, NflTeam("BAL"), jerseyNumber = "8")
      assertFalse(p.isTeamDefense)
      assertFalse(p.isTeamUnit)
      assertFalse(p.isDefensiveSide)
      assertFalse(p.isHeadCoach)
    }
  }

  @Test
  fun `offensive team units (TMQB, TMPK, Off) trigger offensive events while Def triggers defensive events`() {
    val bufSide = NflSide("2", NflTeam("BUF"), "Buffalo", "Bills", 21)
    val kccSide = NflSide("12", NflTeam("KCC"), "Kansas City", "Chiefs", 17)
    val game = NflGameState(
      id = "401",
      away = bufSide,
      home = kccSide,
      phase = GamePhase.LIVE,
      period = 3,
      clock = "4:12",
      possessionEspnId = "2",
      inRedZone = true
    )

    val bufTmqb = FantasyPlayer("0601", "Bills, Buffalo", "TMQB", NflTeam("BUF"))
    val bufTmpk = FantasyPlayer("0621", "Bills, Buffalo", "TMPK", NflTeam("BUF"))
    val bufDef = FantasyPlayer("0501", "Bills, Buffalo", "Def", NflTeam("BUF"))
    val exposures = mapOf(
      bufTmqb.id to PlayerCrossLeagueExposure(bufTmqb, ExposureRole.MINE, listOf("Dynasty"), emptyList()),
      bufTmpk.id to PlayerCrossLeagueExposure(bufTmpk, ExposureRole.MINE, listOf("Dynasty"), emptyList()),
      bufDef.id to PlayerCrossLeagueExposure(bufDef, ExposureRole.OPPONENT, emptyList(), listOf("Dynasty"))
    )

    val offTdEvent = GameEvent(
      id = "ev_td",
      kind = GameEventKind.TOUCHDOWN,
      game = game,
      team = bufSide,
      play = NflPlay("p1", 1, PlayKind.TOUCHDOWN, "Josh Allen 9 yd pass TD", offenseTeamId = "2"),
      awayScore = 21,
      homeScore = 17,
      observedAtMillis = 1000L
    )
    val involvedOnOffTd = BestBallAndPaceCalculator.findInvolvedExposures(offTdEvent, exposures).map { it.player.id }
    assertTrue("TMQB should match offensive TD", "0601" in involvedOnOffTd)
    assertFalse("TMPK should not match offensive TD", "0621" in involvedOnOffTd)
    assertFalse("Def should not match own offense TD", "0501" in involvedOnOffTd)

    val fgEvent = GameEvent(
      id = "ev_fg",
      kind = GameEventKind.FIELD_GOAL,
      game = game,
      team = bufSide,
      play = NflPlay("p2", 2, PlayKind.FIELD_GOAL, "Tyler Bass 44 yd FG GOOD", offenseTeamId = "2"),
      awayScore = 24,
      homeScore = 17,
      observedAtMillis = 2000L
    )
    val involvedOnFg = BestBallAndPaceCalculator.findInvolvedExposures(fgEvent, exposures).map { it.player.id }
    assertEquals(listOf("0621"), involvedOnFg)

    val sackEvent = GameEvent(
      id = "ev_sack",
      kind = GameEventKind.SACK,
      game = game,
      team = bufSide,
      play = NflPlay("p3", 3, PlayKind.SACK, "Greg Rousseau sacks Patrick Mahomes", offenseTeamId = "12"),
      awayScore = 24,
      homeScore = 17,
      observedAtMillis = 3000L
    )
    val involvedOnSack = BestBallAndPaceCalculator.findInvolvedExposures(sackEvent, exposures).map { it.player.id }
    assertEquals(listOf("0501"), involvedOnSack)
  }

  @Test
  fun `claySlug normalizes punctuation, hyphens, and generational suffixes`() {
    assertEquals(
      "amon_ra_st_brown",
      FantasyPlayer("1", "St. Brown, Amon-Ra", "WR", NflTeam("DET")).claySlug
    )
    assertEquals(
      "jamarr_chase",
      FantasyPlayer("2", "Chase, Ja'Marr", "WR", NflTeam("CIN")).claySlug
    )
    assertEquals(
      "kenneth_walker",
      FantasyPlayer("3", "Walker III, Kenneth", "RB", NflTeam("SEA")).claySlug
    )
  }

  @Test
  fun `manifest resolves byMflId priority, local file cache, host safety, and killSwitch rollback`() {
    val manifest = ClayPackManifest(
      manifestVersion = 3,
      killSwitch = false,
      baseUrl = "https://www.motisko.com/assets/gameday/clay/heads",
      byMflId = mapOf(
        "13593" to "josh_allen_qb_v1",
        "14102" to "josh_hines_allen_v1",
        "bad" to "../etc/passwd"
      ),
      byEspnId = mapOf("3052587" to "baker_mayfield_v1"),
      bySlug = mapOf("josh_allen" to "josh_allen_qb_v1", "cam_ward" to "cam_ward_v1")
    )

    // MFL ID wins over generic slug so DE Josh Allen never gets QB Josh Allen's head
    val deJosh = FantasyPlayer("14102", "Allen, Josh", "DE", NflTeam("JAC"))
    assertEquals(
      "https://www.motisko.com/assets/gameday/clay/heads/josh_hines_allen_v1.webp",
      manifest.resolveHeadUrl(deJosh)
    )

    // DemoSimulatorData P01 resolves via espnId / slug and prefers staged local File when present
    val demoBaker = DemoSimulatorData.playersDb["P01"]
    assertNotNull(demoBaker)
    val headsDir = File(tempDir, "clay_heads").apply { mkdirs() }
    val stagedBaker = File(headsDir, "baker_mayfield_v1.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    assertEquals(stagedBaker, manifest.resolveHeadModel(demoBaker, headsDir))

    // Spoofed localhost prefix or query-injected baseUrl rejected
    assertNull(manifest.copy(baseUrl = "http://localhost.attacker.com/heads").resolveHeadUrl(demoBaker))
    assertNull(manifest.copy(baseUrl = "https://www.motisko.com/heads?token=leak").resolveHeadUrl(demoBaker))

    // Unsafe asset key rejected
    val bad = FantasyPlayer("bad", "Bad, Actor", "WR", NflTeam("BUF"))
    assertNull(manifest.resolveHeadUrl(bad))

    // Team defense and offensive team units return null (use bundled Clay Shield / Mascot)
    val seaDef = FantasyPlayer("0530", "Seahawks, Seattle", "Def", NflTeam("SEA"))
    val bufTmqb = FantasyPlayer("0601", "Bills, Buffalo", "TMQB", NflTeam("BUF"))
    assertNull(manifest.resolveHeadUrl(seaDef))
    assertNull(manifest.resolveHeadUrl(bufTmqb))

    // KillSwitch rolls back immediately
    val killed = manifest.copy(killSwitch = true)
    assertNull(killed.resolveHeadModel(demoBaker, headsDir))
  }

  @Test
  fun `ClayPackManager performs conditional ETag 304 check, self-heals corrupted cache, and applies deltas`() = runBlocking {
    val v1Body = """
      {
        "manifestVersion": 1,
        "updatedAt": "2026-09-01T00:00:00Z",
        "killSwitch": false,
        "baseUrl": "https://www.motisko.com/assets/gameday/clay/heads",
        "byMflId": { "13589": "lamar_jackson_v1" },
        "bySlug": { "lamar_jackson": "lamar_jackson_v1" }
      }
    """.trimIndent()

    val v2Body = """
      {
        "manifestVersion": 2,
        "updatedAt": "2026-10-15T00:00:00Z",
        "killSwitch": false,
        "baseUrl": "https://www.motisko.com/assets/gameday/clay/heads",
        "byMflId": { "13589": "lamar_jackson_v2", "17104": "bhayshul_tuten_v1" },
        "bySlug": { "lamar_jackson": "lamar_jackson_v2", "bhayshul_tuten": "bhayshul_tuten_v1" }
      }
    """.trimIndent()

    // Seed corrupted manifest file + stale ETag to verify self-healing (must NOT send If-None-Match)
    File(tempDir, ClayPackManager.MANIFEST_FILE).writeText("{corrupted_json")
    File(tempDir, ClayPackManager.ETAG_FILE).writeText("\"stale-etag\"")

    server.enqueue(MockResponse().setResponseCode(200).addHeader("ETag", "\"etag-v1\"").setBody(v1Body))
    server.enqueue(MockResponse().setResponseCode(304))
    server.enqueue(MockResponse().setResponseCode(200).addHeader("ETag", "\"etag-v2\"").setBody(v2Body))

    val manager = ClayPackManager(
      cacheDir = tempDir,
      manifestUrl = server.url("/assets/gameday/clay/manifest.json").toString()
    )

    // 1. Corrupted manifest self-heals by omitting If-None-Match and downloading v1
    assertEquals(0, manager.manifest.value.manifestVersion)
    assertEquals(ManifestSyncOutcome.UPDATED, manager.syncManifestIfUpdated())
    assertEquals(1, manager.manifest.value.manifestVersion)
    val req1 = server.takeRequest()
    assertNull(req1.getHeader("If-None-Match"))

    // 2. Unchanged startup check sends If-None-Match and receives 304 (0 bytes)
    assertEquals(ManifestSyncOutcome.NOT_MODIFIED, manager.syncManifestIfUpdated())
    assertEquals(1, manager.manifest.value.manifestVersion)
    val req2 = server.takeRequest()
    assertEquals("\"etag-v1\"", req2.getHeader("If-None-Match"))

    // 3. Weekly delta update bumps Lamar to v2 and adds rookie Tuten
    assertEquals(ManifestSyncOutcome.UPDATED, manager.syncManifestIfUpdated())
    assertEquals(2, manager.manifest.value.manifestVersion)
    val lamar = FantasyPlayer("13589", "Jackson, Lamar", "QB", NflTeam("BAL"), jerseyNumber = "8")
    val tuten = FantasyPlayer("17104", "Tuten, Bhayshul", "RB", NflTeam("JAC"), jerseyNumber = "33")
    assertEquals(
      "https://www.motisko.com/assets/gameday/clay/heads/lamar_jackson_v2.webp",
      manager.manifest.value.resolveHeadUrl(lamar)
    )
    assertEquals(
      "https://www.motisko.com/assets/gameday/clay/heads/bhayshul_tuten_v1.webp",
      manager.manifest.value.resolveHeadUrl(tuten)
    )
  }
}
