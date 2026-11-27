package com.mflgameday.live.ui

import com.mflgameday.live.audio.InstalledVoiceDescriptor
import com.mflgameday.live.audio.VoiceCastingResolver
import com.mflgameday.live.data.LenientJson
import com.mflgameday.live.engine.EventDiffer
import com.mflgameday.live.model.AnnouncementScope
import com.mflgameday.live.model.BroadcastPreset
import com.mflgameday.live.model.FantasyPlayer
import com.mflgameday.live.model.GameDaySettings
import com.mflgameday.live.model.GamePhase
import com.mflgameday.live.model.LiveFieldSituation
import com.mflgameday.live.model.LiveFieldSituationResolver
import com.mflgameday.live.model.NflGameState
import com.mflgameday.live.model.NflPlay
import com.mflgameday.live.model.NflSide
import com.mflgameday.live.model.NflTeam
import com.mflgameday.live.model.PlayKind
import com.mflgameday.live.model.PlayerLiveScore
import com.mflgameday.live.model.ScoringBreakdownFormatter
import com.mflgameday.live.model.ScoringBreakdownLine
import com.mflgameday.live.model.SpokenCategory
import com.mflgameday.live.model.StatLineFamily
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

    // Fine-tuning Nigel's speed must preserve Nigel's GB voice rather than reverting to David
    val nigelBase = GameDaySettings(voicePersona = VoicePersona.LONDON_CALLING, selectedSystemVoiceId = null)
    val nigelTuned = VoiceCastingResolver.applyCustomTuning(
      current = nigelBase,
      newRate = 1.14f,
      newPitch = VoicePersona.LONDON_CALLING.pitch,
      resolvedPlans = cast
    )
    val resolvedNigelTuned = VoiceCastingResolver.resolveForSettings(nigelTuned, pixelVoices)
    assertEquals(VoicePersona.CUSTOM_TUNED, nigelTuned.voicePersona)
    assertEquals("en-gb-x-gbd-local", resolvedNigelTuned.voiceId)
    assertEquals("GB", resolvedNigelTuned.fallbackCountry)
    assertEquals(1.14f, resolvedNigelTuned.speechRate, 0.001f)
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

  @Test
  fun postScoreKickoffAndPatStates_clearFalseRedZoneAcrossOfficialTimeoutsAndSpotKickoffTee() {
    val tb = NflSide("27", NflTeam("TBB"), "Tampa Bay", "Buccaneers", 14)
    val det = NflSide("8", NflTeam("DET"), "Detroit", "Lions", 17)
    val godwin = FantasyPlayer("P02", "Godwin, Chris", "WR", NflTeam("TBB"), jerseyNumber = "14")

    val differ = EventDiffer()
    val preTdRedZone = NflGameState(
      id = "g_live",
      away = tb,
      home = det,
      phase = GamePhase.LIVE,
      period = 3,
      clock = "6:35",
      possessionEspnId = "27",
      inRedZone = true,
      isLiveDown = true,
      downDistanceText = "2nd & Goal at DET 6",
      possessionText = "DET 6",
      yardLine = 6
    )
    differ.ingestScoreboard(listOf(preTdRedZone), nowMillis = 1_000L)

    // 1. Touchdown scored (+6, PAT not yet snapped; ESPN still flags isRedZone = true, down = -1)
    val rawAfterTd = preTdRedZone.copy(
      away = tb.copy(score = 20),
      isLiveDown = false,
      inRedZone = true,
      downDistanceText = "",
      yardLine = 2,
      lastPlayText = "B.Mayfield pass short right to C.Godwin for 6 yards, TOUCHDOWN."
    )
    differ.ingestScoreboard(listOf(rawAfterTd), nowMillis = 5_000L)
    val afterTd = differ.currentGame("g_live")!!
    assertEquals(LiveFieldSituation.PAT_PENDING, afterTd.fieldSituation)
    assertFalse("PAT pending must suppress false Red Zone alert", afterTd.effectiveInRedZone)
    assertEquals(PlayerAvatarRingStatus.HAS_POSSESSION, PlayerAvatarRingPolicy.resolve(godwin, afterTd, 1200))

    // 2. Extra Point Good (+1 -> 21-17), followed on the next poll by "Official Timeout" during TV break
    //    while ESPN leaves isRedZone = true and yardLine = 15!
    val rawAfterXp = afterTd.copy(
      away = tb.copy(score = 21),
      isLiveDown = false,
      inRedZone = true,
      possessionText = "DET 15",
      yardLine = 15,
      lastPlayText = "C.McLaughlin extra point is GOOD."
    )
    differ.ingestScoreboard(listOf(rawAfterXp), nowMillis = 15_000L)

    val rawDuringOfficialTimeout = rawAfterXp.copy(
      lastPlayText = "Official Timeout"
    )
    differ.ingestScoreboard(listOf(rawDuringOfficialTimeout), nowMillis = 45_000L)
    val duringTimeout = differ.currentGame("g_live")!!
    assertEquals(LiveFieldSituation.KICKOFF_PENDING, duringTimeout.fieldSituation)
    assertFalse("Official Timeout after TD/XP must NOT show Red Zone!", duringTimeout.effectiveInRedZone)
    assertEquals(
      "Starters must drop out of Red Zone / Possession ring during commercial-break kickoff wait",
      PlayerAvatarRingStatus.GAME_LIVE,
      PlayerAvatarRingPolicy.resolve(godwin, duringTimeout, 1200)
    )

    val kickoffGeo = FieldDriveGeometry.fromGame(duringTimeout)!!
    assertEquals("Awaiting Kickoff", kickoffGeo.downDistanceText)
    assertEquals("Tee at TBB 35", kickoffGeo.possessionText)
    assertEquals(0.35f, kickoffGeo.ballFractionFromLeft, 0.01f)
    assertNull("Kickoff tee graphic must omit yellow 1st-down marker", kickoffGeo.firstDownFractionFromLeft)
    assertFalse(kickoffGeo.inRedZone)

    // 3. Cold-launch mid-commercial break (stateless scoreboard says "Official Timeout", play history resolves it!)
    val coldStartRefined = LiveFieldSituationResolver.refineWithPlayHistory(
      game = rawDuringOfficialTimeout.copy(fieldSituation = LiveFieldSituation.SCRIMMAGE_DRIVE, inRedZone = true),
      plays = listOf(
        NflPlay("p1", 1, PlayKind.TOUCHDOWN, "Chris Godwin 6 Yd pass from Baker Mayfield (Chase McLaughlin Kick)", awayScore = 21, homeScore = 17, offenseTeamId = "27"),
        NflPlay("p2", 2, PlayKind.OTHER, "Official Timeout", rawPlayText = "Official Timeout at 06:28.", awayScore = 21, homeScore = 17)
      )
    )
    assertEquals(LiveFieldSituation.KICKOFF_PENDING, coldStartRefined.fieldSituation)
    assertFalse(coldStartRefined.effectiveInRedZone)

    // 4. Kickoff happens & Detroit starts 1st & 10 -> returns cleanly to SCRIMMAGE_DRIVE
    val detDrive = duringTimeout.copy(
      possessionEspnId = "8",
      isLiveDown = true,
      inRedZone = false,
      downDistanceText = "1st & 10 at DET 30",
      possessionText = "DET 30",
      yardLine = 70
    )
    differ.ingestScoreboard(listOf(detDrive), nowMillis = 90_000L)
    assertEquals(LiveFieldSituation.SCRIMMAGE_DRIVE, differ.currentGame("g_live")!!.fieldSituation)
  }

  @Test
  fun scoringBreakdownFormatter_formatsCatchesYardsRushesTwoPtTdsFumblesAndAggregatesTotals() {
    val mayfield = PlayerLiveScore(
      playerId = "P01",
      score = 21.0, // Whole-point league rounded from 20.62
      isStarter = true,
      gameSecondsRemaining = 1200,
      breakdowns = listOf(
        ScoringBreakdownLine("Passing Yards", "268", 10.72),
        ScoringBreakdownLine("Passing TDs", "2", 8.00),
        ScoringBreakdownLine("Rushing Yards", "19", 1.90),
        ScoringBreakdownLine("2-Pt Passing Conversions", "1", 2.00),
        ScoringBreakdownLine("Fumbles Lost", "1", -2.00)
      )
    )
    val barkley = PlayerLiveScore(
      playerId = "P04",
      score = 20.0, // Rounded from 20.40
      isStarter = true,
      gameSecondsRemaining = 600,
      breakdowns = listOf(
        ScoringBreakdownLine("Rushes", "18", 1.80),
        ScoringBreakdownLine("Rushing Yards", "96", 9.60),
        ScoringBreakdownLine("Rushing TDs", "1", 6.00),
        ScoringBreakdownLine("Receptions", "2", 2.00),
        ScoringBreakdownLine("Receiving Yards", "10", 1.00)
      )
    )

    val mayfieldAudit = ScoringBreakdownFormatter.auditPlayer(mayfield)
    assertEquals(5, mayfieldAudit.items.size)
    assertEquals(20.62, mayfieldAudit.rawSum, 0.001)
    assertTrue("Whole-point rounding 20.62 -> 21 must flag delta", mayfieldAudit.hasRoundingOrBonusDelta)

    val twoPtRow = mayfieldAudit.items[3]
    assertEquals(StatLineFamily.SCORING_2PT_TD, twoPtRow.family)
    assertEquals("1 2-PT", twoPtRow.quantityLabel)
    assertEquals("+2.00", twoPtRow.signedPointsLabel)
    assertTrue(twoPtRow.isTouchdownOrTwoPt)

    val fumbleRow = mayfieldAudit.items[4]
    assertEquals(StatLineFamily.TURNOVER, fumbleRow.family)
    assertEquals("1 fum", fumbleRow.quantityLabel)
    assertEquals("-2.00", fumbleRow.signedPointsLabel)
    assertTrue(fumbleRow.isNegative)

    val barkleyAudit = ScoringBreakdownFormatter.auditPlayer(barkley)
    assertEquals("18 rushes", barkleyAudit.items[0].quantityLabel)
    assertEquals("96 yds", barkleyAudit.items[1].quantityLabel)
    assertEquals("1 TD", barkleyAudit.items[2].quantityLabel)
    assertEquals("2 catches", barkleyAudit.items[3].quantityLabel)
    assertEquals("+1.00/rec", barkleyAudit.items[3].rateLabel)

    val rollups = ScoringBreakdownFormatter.rollupByFamily(listOf(mayfield, barkley)).toMap()
    assertEquals(18.72, rollups.getValue(StatLineFamily.PASSING), 0.001)
    assertEquals(19.30, rollups.getValue(StatLineFamily.RUSHING), 0.001)
    assertEquals(3.00, rollups.getValue(StatLineFamily.RECEIVING), 0.001)
    assertEquals(2.00, rollups.getValue(StatLineFamily.SCORING_2PT_TD), 0.001)
    assertEquals(-2.00, rollups.getValue(StatLineFamily.TURNOVER), 0.001)
  }

  @Test
  fun adversarialEdgeCases_kickoffTouchbackPassDefendedAndMflDistanceCategoriesBehaveCleanly() {
    val tb = NflSide("27", NflTeam("TBB"), "Tampa Bay", "Buccaneers", 21)
    val det = NflSide("8", NflTeam("DET"), "Detroit", "Lions", 17)

    // 1. Dynamic Kickoff touchback spotted at DET 30 with down = -1 BEFORE 1st & 10 must exit KICKOFF_PENDING
    val differ = EventDiffer()
    val awaitingKickoff = NflGameState(
      id = "g_tb_det",
      away = tb,
      home = det,
      phase = GamePhase.LIVE,
      period = 3,
      clock = "6:28",
      possessionEspnId = "27",
      inRedZone = false,
      isLiveDown = false,
      fieldSituation = LiveFieldSituation.KICKOFF_PENDING,
      downDistanceText = "",
      possessionText = "TBB 35"
    )
    differ.ingestScoreboard(listOf(awaitingKickoff), nowMillis = 1_000L)

    val touchbackBeforeFirstDown = awaitingKickoff.copy(
      possessionEspnId = "8",
      isLiveDown = false,
      fieldSituation = classifyOrDefault(
        phase = GamePhase.LIVE,
        isLiveDown = false,
        possessionTeam = NflTeam("DET"),
        possessionText = "DET 30",
        lastPlayText = "C.McLaughlin kicks 65 yards from TB 35 to end zone, Touchback to the DET 30."
      ),
      possessionText = "DET 30",
      yardLine = 30,
      lastPlayText = "C.McLaughlin kicks 65 yards from TB 35 to end zone, Touchback to the DET 30."
    )
    differ.ingestScoreboard(listOf(touchbackBeforeFirstDown), nowMillis = 15_000L)
    assertEquals(
      "Completed kickoff touchback must immediately exit KICKOFF_PENDING to SCRIMMAGE_DRIVE",
      LiveFieldSituation.SCRIMMAGE_DRIVE,
      differ.currentGame("g_tb_det")!!.fieldSituation
    )

    // 2. Non-scoring "(pass defended by ...)" inside Red Zone with down = -1 must NOT trigger KICKOFF_PENDING
    val passDefendedSit = LiveFieldSituationResolver.classifyStateless(
      phase = GamePhase.LIVE,
      isLiveDown = false,
      inRedZoneRaw = true,
      possessionTeam = NflTeam("TBB"),
      downDistanceText = "",
      possessionText = "DET 9",
      lastPlayTypeText = "Pass Incompletion",
      lastPlayText = "(6:45) B.Mayfield pass incomplete short left to M.Evans (pass defended by K.Joseph).",
      period = 3,
      clock = "6:45"
    )
    assertEquals(LiveFieldSituation.SCRIMMAGE_DRIVE, passDefendedSit)

    // 3. Real MFL categories: "40-49 Yard Field Goals", "Passes Defended", "Points Allowed", negative passing yards, 3-decimal precision
    val kickerAndDef = PlayerLiveScore(
      playerId = "P99",
      score = 14.125,
      isStarter = true,
      gameSecondsRemaining = 0,
      breakdowns = listOf(
        ScoringBreakdownLine("40-49 Yard Field Goals", "2", 8.00),
        ScoringBreakdownLine("Passes Defended", "2", 3.00),
        ScoringBreakdownLine("Points Allowed", "17", 1.00),
        ScoringBreakdownLine("Passing Yards", "-3", -0.125),
        ScoringBreakdownLine("Third Down Conversions", "4", 2.25)
      )
    )
    val audit3 = ScoringBreakdownFormatter.auditPlayer(kickerAndDef, precision = 3)
    assertFalse("Exact 3-decimal sum 14.125 must not report false rounding delta", audit3.hasRoundingOrBonusDelta)

    assertEquals(StatLineFamily.KICKING, audit3.items[0].family)
    assertEquals("2 FG", audit3.items[0].quantityLabel)
    assertEquals("+4.00 ea", audit3.items[0].rateLabel)

    assertEquals(StatLineFamily.DEFENSE_IDP, audit3.items[1].family)
    assertEquals(StatLineFamily.DEFENSE_IDP, audit3.items[2].family)

    assertEquals(StatLineFamily.PASSING, audit3.items[3].family)
    assertEquals("-0.125", audit3.items[3].signedPointsLabel)

    assertFalse("Third Down Conversions must not highlight as 2-Pt conversion", audit3.items[4].isTouchdownOrTwoPt)

    // 4. Defensive two-point conversion return (+2 to defense after TD) must keep the TD-scoring team as next kicker!
    val defTwoPtRefined = LiveFieldSituationResolver.refineWithPlayHistory(
      game = awaitingKickoff.copy(
        away = tb.copy(score = 20),
        home = det.copy(score = 19),
        possessionEspnId = "8",
        fieldSituation = LiveFieldSituation.SCRIMMAGE_DRIVE
      ),
      plays = listOf(
        NflPlay("td1", 1, PlayKind.TOUCHDOWN, "Chris Godwin 6 Yd pass from Baker Mayfield", awayScore = 20, homeScore = 17, offenseTeamId = "27"),
        NflPlay(
          id = "try1",
          sequence = 2,
          kind = PlayKind.OTHER_SCORE,
          text = "Defensive Two-Point Conversion: Brian Branch returns intercepted pass for 2 pts",
          rawPlayText = "TWO-POINT CONVERSION ATTEMPT. B.Mayfield pass intercepted by B.Branch, DEFENSIVE TWO-POINT ATTEMPT SUCCEEDS.",
          awayScore = 20,
          homeScore = 19,
          isTurnover = true,
          offenseTeamId = "27"
        )
      )
    )
    assertEquals(LiveFieldSituation.KICKOFF_PENDING, defTwoPtRefined.fieldSituation)
    assertEquals("TD-scoring team (TBB 27) must remain kicking team after defensive 2-pt return", "27", defTwoPtRefined.possessionEspnId)
  }

  private fun classifyOrDefault(
    phase: GamePhase,
    isLiveDown: Boolean,
    possessionTeam: NflTeam?,
    possessionText: String,
    lastPlayText: String
  ): LiveFieldSituation = LiveFieldSituationResolver.classifyStateless(
    phase = phase,
    isLiveDown = isLiveDown,
    inRedZoneRaw = false,
    possessionTeam = possessionTeam,
    downDistanceText = "",
    possessionText = possessionText,
    lastPlayTypeText = "Kickoff",
    lastPlayText = lastPlayText,
    period = 3,
    clock = "6:28"
  )
}
