package com.mflgameday.live.diagnostics

import com.mflgameday.live.model.FantasyPlayer
import com.mflgameday.live.model.NflTeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsAndLogoTest {

  @Test
  fun redactSecrets_scrubsPasswordsApiKeysAndMflCookies() {
    val raw =
      "POST https://api.myfantasyleague.com/2026/login?USERNAME=ceo&PASSWORD=MySuperSecret123!&APIKEY=abc987xyz&MFL_USER_ID=tok_456"
    val scrubbed = DiagnosticsLogger.redactSecrets(raw)

    assertFalse(scrubbed.contains("MySuperSecret123!"))
    assertFalse(scrubbed.contains("abc987xyz"))
    assertFalse(scrubbed.contains("tok_456"))
    assertTrue(scrubbed.contains("PASSWORD=<REDACTED>"))
    assertTrue(scrubbed.contains("APIKEY=<REDACTED>"))
    assertTrue(scrubbed.contains("MFL_USER_ID=<REDACTED>"))
  }

  @Test
  fun nflTeamAndPlayerHeadshotUrls_resolveAll32TeamsAndEspnIds() {
    assertEquals(32, NflTeam.catalog.size)
    assertEquals("https://a.espncdn.com/i/teamlogos/nfl/500/tb.png", NflTeam("TBB").espnLogoUrl)
    assertEquals("https://a.espncdn.com/i/teamlogos/nfl/500/wsh.png", NflTeam("WAS").espnLogoUrl)
    assertEquals("https://a.espncdn.com/i/teamlogos/nfl/500/gb.png", NflTeam("GBP").espnLogoUrl)

    val mayfield = FantasyPlayer("1", "Mayfield, Baker", "QB", NflTeam("TBB"), espnId = "3052587")
    assertEquals("https://a.espncdn.com/i/headshots/nfl/players/full/3052587.png", mayfield.avatarImageUrl)

    val seahawksDef = FantasyPlayer("2", "Seahawks, Seattle", "Def", NflTeam("SEA"))
    assertEquals("https://a.espncdn.com/i/teamlogos/nfl/500/sea.png", seahawksDef.avatarImageUrl)
  }
}
