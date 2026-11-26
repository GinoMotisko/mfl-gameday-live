package com.mflgameday.live.ui

import com.mflgameday.live.audio.InstalledVoiceDescriptor
import com.mflgameday.live.audio.VoiceCastingResolver
import com.mflgameday.live.data.LenientJson
import com.mflgameday.live.model.AnnouncementScope
import com.mflgameday.live.model.BroadcastPreset
import com.mflgameday.live.model.FantasyPlayer
import com.mflgameday.live.model.GameDaySettings
import com.mflgameday.live.model.GamePhase
import com.mflgameday.live.model.NflGameState
import com.mflgameday.live.model.NflSide
import com.mflgameday.live.model.NflTeam
import com.mflgameday.live.model.SpokenCategory
import com.mflgameday.live.model.VoicePersona
import com.mflgameday.live.ui.components.FieldDriveGeometry
import com.mflgameday.live.ui.components.PlayerAvatarRingPolicy
import com.mflgameday.live.ui.components.PlayerAvatarRingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAndFieldVisualPolicyTest {

  @Test
  fun voiceCastingResolver_assignsSixDistinctVoicesAcrossGoogleTtsPool() {
    val pixelVoices = listOf(
      InstalledVoiceDescriptor("en-us-x-iom-local", "en-US", "US", quality = 400, isNetwork = false),
      InstalledVoiceDescriptor("en-us-x-tpd-local", "en-US", "US", quality = 400, isNetwork = false),
      InstalledVoiceDescriptor("en-us-x-iob-local", "en-US", "US", quality = 400, isNetwork = false),
      InstalledVoiceDescriptor("en-us-x-sfg-local", "en-US", "US", quality = 400, isNetwork = false),
      InstalledVoiceDescriptor("en-us-x-tpf-local", "en-US", "US", quality = 400, isNetwork = false),
      InstalledVoiceDescriptor("en-gb-x-gbd-local", "en-GB", "GB", quality = 400, isNetwork = false),
      InstalledVoiceDescriptor("en-au-x-aud-local", "en-AU", "AU", quality = 400, isNetwork = false),
      InstalledVoiceDescriptor("en-us-x-iom-network", "en-US", "US", quality = 500, isNetwork = true)
    )

    val cast = VoiceCastingResolver.resolveAll(pixelVoices)
    val sixNamed = VoicePersona.commentatorRoster
    assertEquals(6, sixNamed.size)

    val assignedIds = sixNamed.mapNotNull { cast[it]?.voiceId }
    assertEquals("Every commentator must receive an installed voice", 6, assignedIds.size)
    assertEquals("All 6 commentators must map to 6 distinct Voice IDs", 6, assignedIds.toSet().size)

    assertEquals("en-us-x-iom-local", cast[VoicePersona.BOOTH_PLAY_BY_PLAY]?.voiceId)
    assertEquals("en-us-x-tpd-local", cast[VoicePersona.REDZONE_SHOUTY]?.voiceId)
    assertTrue(
      "Callie must bind to a US female neural voice",
      cast[VoicePersona.STUDIO_HOST]?.voiceId in setOf("en-us-x-iob-local", "en-us-x-sfg-local")
    )
    assertEquals("en-gb-x-gbd-local", cast[VoicePersona.LONDON_CALLING]?.voiceId)
    assertEquals("en-au-x-aud-local", cast[VoicePersona.AUSSIE_SIDELINE]?.voiceId)
    assertEquals("en-us-x-tpf-local", cast[VoicePersona.FILM_ROOM]?.voiceId)
  }

  @Test
  fun voiceCastingResolver_gracefullySeparatesSingleVoiceEmulatorAndRespectsManualOverride() {
    val singleVoice = listOf(
      InstalledVoiceDescriptor("en-us-default", "en-US", "US", quality = 300, isNetwork = false)
    )
    val cast = VoiceCastingResolver.resolveAll(singleVoice)
    val david = cast.getValue(VoicePersona.BOOTH_PLAY_BY_PLAY)
    val rex = cast.getValue(VoicePersona.REDZONE_SHOUTY)
    val callie = cast.getValue(VoicePersona.STUDIO_HOST)

    assertNotEquals(
      "Single-voice fallback must keep David and Callie acoustically distinct via rate/pitch",
      david.pitch to david.speechRate,
      callie.pitch to callie.speechRate
    )
    assertNotEquals(david.speechRate, rex.speechRate)

    val overridden = VoiceCastingResolver.resolveForSettings(
      settings = GameDaySettings(
        voicePersona = VoicePersona.BOOTH_PLAY_BY_PLAY,
        selectedSystemVoiceId = "custom_samsung_smt_voice"
      ),
      installedVoices = singleVoice
    )
    assertEquals("custom_samsung_smt_voice", overridden.voiceId)
  }

  @Test
  fun broadcastPresets_andLegacyV125SettingsDeserializeCleanly() {
    val legacyJson = """{"operatingMode":"MFL_AND_NFL_LIVE","voicePersona":"CUSTOM_TUNED","pollIntervalSeconds":45,"customSpeechRate":1.12,"customPitch":0.95}"""
    val restored = LenientJson.parser.decodeFromString(GameDaySettings.serializer(), legacyJson)
    assertEquals(VoicePersona.CUSTOM_TUNED, restored.voicePersona)
    assertEquals(45, restored.pollIntervalSeconds)

    val party = BroadcastPreset.SUNDAY_REDZONE_PARTY.applyTo(GameDaySettings())
    assertEquals(AnnouncementScope.ALL_NFL, party.scope)
    assertEquals(BroadcastPreset.SUNDAY_REDZONE_PARTY, BroadcastPreset.detectActive(party))

    val myMatchup = BroadcastPreset.JUST_MY_MATCHUP.applyTo(party)
    assertEquals(AnnouncementScope.MY_PLAYERS, myMatchup.scope)
    assertEquals(BroadcastPreset.JUST_MY_MATCHUP, BroadcastPreset.detectActive(myMatchup))

    val bigScores = BroadcastPreset.BIG_SCORES_ONLY.applyTo(myMatchup)
    assertEquals(AnnouncementScope.MY_GAMES, bigScores.scope)
    assertTrue(SpokenCategory.TOUCHDOWNS in bigScores.enabledCategories)
    assertFalse(SpokenCategory.SACKS in bigScores.enabledCategories)
    assertEquals(BroadcastPreset.BIG_SCORES_ONLY, BroadcastPreset.detectActive(bigScores))
  }

  @Test
  fun avatarRingPolicy_distinguishesFinalScheduledLivePossessionAndRedZoneWithoutYellowCollisions() {
    val steelersWr = FantasyPlayer("p1", "Pickens, George", "WR", NflTeam("PIT"), jerseyNumber = "14")
    val lionsRb = FantasyPlayer("p2", "Gibbs, Jahmyr", "RB", NflTeam("DET"), jerseyNumber = "26")
    val billsDef = FantasyPlayer("p3", "Bills, Buffalo", "Def", NflTeam("BUF"))

    val pitSide = NflSide("23", NflTeam("PIT"), "Pittsburgh", "Steelers", 20)
    val detSide = NflSide("8", NflTeam("DET"), "Detroit", "Lions", 24)

    // 1. FINAL game -> FINAL_DONE (zero outer ring even for yellow-accent Steelers!)
    val finalGame = NflGameState("g1", away = pitSide, home = detSide, phase = GamePhase.FINAL)
    assertEquals(
      PlayerAvatarRingStatus.FINAL_DONE,
      PlayerAvatarRingPolicy.resolve(player = steelersWr, game = finalGame, gameSecondsRemaining = 0)
    )

    // 2. SCHEDULED game -> SCHEDULED_UPCOMING
    val schedGame = NflGameState("g2", away = pitSide, home = detSide, phase = GamePhase.SCHEDULED)
    assertEquals(
      PlayerAvatarRingStatus.SCHEDULED_UPCOMING,
      PlayerAvatarRingPolicy.resolve(player = steelersWr, game = schedGame, gameSecondsRemaining = 3600)
    )

    // 3. LIVE game without ball -> GAME_LIVE (both user's & opponent's starters)
    val liveDetBall = NflGameState(
      id = "g3",
      away = pitSide,
      home = detSide,
      phase = GamePhase.LIVE,
      possessionEspnId = "8",
      inRedZone = false
    )
    assertEquals(
      PlayerAvatarRingStatus.GAME_LIVE,
      PlayerAvatarRingPolicy.resolve(player = steelersWr, game = liveDetBall, gameSecondsRemaining = 1800)
    )

    // 4. LIVE game WITH ball outside Red Zone -> HAS_POSSESSION (Gold)
    assertEquals(
      PlayerAvatarRingStatus.HAS_POSSESSION,
      PlayerAvatarRingPolicy.resolve(player = lionsRb, game = liveDetBall, gameSecondsRemaining = 1800)
    )

    // 5. LIVE game WITH ball inside Red Zone -> IN_RED_ZONE (Pulsing Crimson)
    val liveDetRz = liveDetBall.copy(inRedZone = true, yardLine = 11)
    assertEquals(
      PlayerAvatarRingStatus.IN_RED_ZONE,
      PlayerAvatarRingPolicy.resolve(player = lionsRb, game = liveDetRz, gameSecondsRemaining = 1800)
    )

    // 6. Defense defending inside own 20 -> DEFENDING_RED_ZONE
    val bufSide = NflSide("2", NflTeam("BUF"), "Buffalo", "Bills", 17)
    val liveMiaRz = NflGameState(
      id = "g4",
      away = detSide,
      home = bufSide,
      phase = GamePhase.LIVE,
      possessionEspnId = "8",
      inRedZone = true
    )
    assertEquals(
      PlayerAvatarRingStatus.DEFENDING_RED_ZONE,
      PlayerAvatarRingPolicy.resolve(player = billsDef, game = liveMiaRz, gameSecondsRemaining = 900)
    )
  }

  @Test
  fun fieldDriveGeometry_computesBidirectionalAwayAndHomeDrivesAndFirstDownMarkers() {
    val tb = NflSide("27", NflTeam("TBB"), "Tampa Bay", "Buccaneers", 14)
    val det = NflSide("8", NflTeam("DET"), "Detroit", "Lions", 17)

    // Away team (TB, Left Endzone) driving Left -> Right at DET 38 (62% across field), 2nd & 6 -> 1st down at 68%
    val awayDrive = NflGameState(
      id = "g_tb_det",
      away = tb,
      home = det,
      phase = GamePhase.LIVE,
      possessionEspnId = "27",
      inRedZone = false,
      downDistanceText = "2nd & 6 at DET 38",
      possessionText = "DET 38",
      yardLine = 38
    )
    val awayGeo = FieldDriveGeometry.fromGame(awayDrive)
    assertNotNull(awayGeo)
    assertTrue(awayGeo!!.drivesLeftToRight)
    assertEquals(0.62f, awayGeo.ballFractionFromLeft, 0.015f)
    assertEquals(0.68f, awayGeo.firstDownFractionFromLeft!!, 0.015f)
    assertTrue(awayGeo.targetEndzoneOnRight)

    // Home team (DET, Right Endzone) driving Right -> Left in Red Zone (1st & Goal at TB 8 -> 8% from Left Goal Line!)
    val homeRzDrive = NflGameState(
      id = "g_tb_det_rz",
      away = tb,
      home = det,
      phase = GamePhase.LIVE,
      possessionEspnId = "8",
      inRedZone = true,
      downDistanceText = "1st & Goal at TB 8",
      possessionText = "TB 8",
      yardLine = 8
    )
    val homeGeo = FieldDriveGeometry.fromGame(homeRzDrive)
    assertNotNull(homeGeo)
    assertFalse(homeGeo!!.drivesLeftToRight)
    assertEquals(0.08f, homeGeo.ballFractionFromLeft, 0.015f)
    assertEquals(0.0f, homeGeo.firstDownFractionFromLeft!!, 0.015f)
    assertFalse(homeGeo.targetEndzoneOnRight)

    // Final game -> null drive geometry
    assertNull(FieldDriveGeometry.fromGame(awayDrive.copy(phase = GamePhase.FINAL)))
  }
}
