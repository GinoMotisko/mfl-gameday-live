package com.mflgameday.live.data

import com.mflgameday.live.model.AnnouncementScope
import com.mflgameday.live.model.AppThemePreset
import com.mflgameday.live.model.GameDaySettings
import com.mflgameday.live.model.LeagueConfig
import com.mflgameday.live.model.OperatingMode
import com.mflgameday.live.model.SpokenCategory
import com.mflgameday.live.model.VoicePersona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSyncCodecTest {

  @Test
  fun encodeAndDecodeProfile_roundTripsLeaguesAndVoicePreferencesWhileStrippingSecrets() {
    val sourceSettings = GameDaySettings(
      operatingMode = OperatingMode.MFL_AND_NFL_LIVE,
      activeLeagueId = "54321",
      mflUsername = "secret_commissioner_login",
      themePreset = AppThemePreset.CYBER_GRIDIRON_OLED,
      scope = AnnouncementScope.MY_GAMES,
      enabledCategories = setOf(SpokenCategory.TOUCHDOWNS, SpokenCategory.RED_ZONE, SpokenCategory.FANTASY_SWINGS),
      voicePersona = VoicePersona.LONDON_CALLING,
      customSpeechRate = 1.08f,
      customPitch = 0.96f,
      playStadiumSoundEffects = true,
      announceFantasyContextOnPlays = true,
      pollIntervalSeconds = 20,
      keepScreenAwake = true,
      commandCenterSplitOnWideScreens = true
    )

    val sourceLeagues = listOf(
      LeagueConfig(
        leagueId = "54321",
        leagueName = "Sunday Showdown PPR",
        year = 2026,
        franchiseId = "0004",
        franchiseName = "Gridiron Gurus",
        baseUrl = "https://www48.myfantasyleague.com",
        hasCustomApiKey = true, // MUST be stripped to false on export!
        isBestBall = false,
        precision = 2,
        starterCount = 9,
        positionLimits = mapOf("QB" to "1", "RB" to "2-3"),
        includeInAnnouncements = true
      ),
      LeagueConfig(
        leagueId = "98765",
        leagueName = "Thunderdome Best Ball",
        year = 2026,
        franchiseId = "0007",
        franchiseName = "Blitz Brigade",
        baseUrl = "https://www42.myfantasyleague.com",
        hasCustomApiKey = false,
        isBestBall = true,
        precision = 2,
        starterCount = 10
      )
    )

    val encodedJson = ProfileSyncCodec.encodeProfile(
      settings = sourceSettings,
      leagues = sourceLeagues
    )

    assertFalse("Exported JSON must never include MFL username", encodedJson.contains("secret_commissioner_login"))
    assertFalse("Exported JSON must never mark custom API keys true", encodedJson.contains("\"hasCustomApiKey\":true"))

    val imported = ProfileSyncCodec.decodeAndApply(
      rawJson = encodedJson,
      existingSettings = GameDaySettings(),
      existingLeagues = emptyList()
    )

    assertEquals(2, imported.leagues.size)
    assertFalse(imported.leagues[0].hasCustomApiKey)
    assertEquals("54321", imported.leagues[0].leagueId)
    assertEquals("0004", imported.leagues[0].franchiseId)
    assertTrue(imported.leagues[1].isBestBall)
    assertEquals(OperatingMode.MFL_AND_NFL_LIVE, imported.settings.operatingMode)
    assertEquals("54321", imported.settings.activeLeagueId)
    assertEquals(VoicePersona.LONDON_CALLING, imported.settings.voicePersona)
    assertEquals(AppThemePreset.CYBER_GRIDIRON_OLED, imported.settings.themePreset)
    assertEquals(AnnouncementScope.MY_GAMES, imported.settings.scope)
    assertEquals(20, imported.settings.pollIntervalSeconds)
    assertEquals("", imported.settings.mflUsername)
  }

  @Test
  fun decodeProfile_rejectsNonHttpsOrUntrustedHostUrlsAndClampsOutOfRangeValues() {
    val maliciousJson = """
      {
        "schemaVersion": 1,
        "leagues": [
          {
            "leagueId": "12345",
            "leagueName": "Safe Fallback League",
            "year": 2026,
            "franchiseId": "0001",
            "franchiseName": "Team 1",
            "baseUrl": "http://evil.example.com/steal"
          }
        ],
        "preferences": {
          "voicePersona": "REDZONE_SHOUTY",
          "customSpeechRate": 9.99,
          "customPitch": -3.0,
          "pollIntervalSeconds": 5
        }
      }
    """.trimIndent()

    val imported = ProfileSyncCodec.decodeAndApply(
      rawJson = maliciousJson,
      existingSettings = GameDaySettings(),
      existingLeagues = emptyList()
    )

    assertEquals("https://api.myfantasyleague.com", imported.leagues.single().baseUrl)
    assertEquals(1.50f, imported.settings.customSpeechRate, 0.001f)
    assertEquals(0.70f, imported.settings.customPitch, 0.001f)
    assertEquals(20, imported.settings.pollIntervalSeconds)

    assertNull(ProfileSyncCodec.decodeOrNull("not json at all"))
    assertNull(ProfileSyncCodec.decodeOrNull("x".repeat(70_000)))
  }

  @Test
  fun decodeAndApply_deduplicatesCapsLeaguesAndExitsDemoSimulatorCleanly() {
    val thirtyDuplicateAndExtraLeagues = (1..30).joinToString(",") { idx ->
      val id = if (idx <= 3) "54321" else "${10000 + idx}"
      """{"leagueId":"$id","leagueName":"League $idx","year":2026,"franchiseId":"0001","franchiseName":"Team 1"}"""
    }
    val json = """{"schemaVersion":1,"leagues":[$thirtyDuplicateAndExtraLeagues],"preferences":{"operatingMode":"DEMO_SIMULATOR"}}"""

    val applied = ProfileSyncCodec.decodeAndApply(
      rawJson = json,
      existingSettings = GameDaySettings(operatingMode = OperatingMode.DEMO_SIMULATOR),
      existingLeagues = emptyList()
    )

    assertEquals(ProfileSyncCodec.MAX_IMPORTED_LEAGUES, applied.leagues.size)
    assertEquals(applied.leagues.size, applied.leagues.map { it.leagueId }.distinct().size)
    assertEquals(OperatingMode.MFL_AND_NFL_LIVE, applied.settings.operatingMode)

    val emptyLeaguesFromDemo = ProfileSyncCodec.decodeAndApply(
      rawJson = """{"schemaVersion":1,"leagues":[],"preferences":{"operatingMode":"DEMO_SIMULATOR","enabledCategories":[]}}""",
      existingSettings = GameDaySettings(operatingMode = OperatingMode.DEMO_SIMULATOR),
      existingLeagues = listOf(
        LeagueConfig(leagueId = "99999", leagueName = "Existing Local League", year = 2026, franchiseId = "0001", franchiseName = "Local Team")
      )
    )
    assertEquals(OperatingMode.MFL_AND_NFL_LIVE, emptyLeaguesFromDemo.settings.operatingMode)
    assertTrue("Explicitly empty enabledCategories [] must be preserved on import", emptyLeaguesFromDemo.settings.enabledCategories.isEmpty())
    assertEquals("Existing local leagues must never be dropped when merging", 1, emptyLeaguesFromDemo.leagues.size)

    val existingWithCap = ProfileSyncCodec.decodeAndApply(
      rawJson = json,
      existingSettings = GameDaySettings(operatingMode = OperatingMode.MFL_AND_NFL_LIVE),
      existingLeagues = listOf(
        LeagueConfig(leagueId = "88888", leagueName = "Preserved 26th Local League", year = 2026, franchiseId = "0002", franchiseName = "Local 2")
      )
    )
    assertEquals(ProfileSyncCodec.MAX_IMPORTED_LEAGUES + 1, existingWithCap.leagues.size)
    assertTrue(existingWithCap.leagues.any { it.leagueId == "88888" })
  }
}
