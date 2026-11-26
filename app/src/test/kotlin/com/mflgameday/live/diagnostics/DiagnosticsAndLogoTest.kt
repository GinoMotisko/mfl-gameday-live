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
    assertFalse(scrubbed.contains("=ceo"))
    assertTrue(scrubbed.contains("USERNAME=<REDACTED>"))
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

  @Test
  fun buildRedactedException_preservesOriginalStackFramesWhileScrubbingSecretMessagesAndLeagueIds() {
    val original = IllegalStateException("Failed fetch https://api.myfantasyleague.com/2026/export?TYPE=league&L=54321&APIKEY=super_secret_token&MFL_USER_ID=cookie_99")
    val wrapped = GameDayFirebaseBridge.buildRedactedException(
      redactedContext = "MFL poll error for League #54321 USERNAME=gino_commish PASSWORD=hidden_pw",
      throwable = original
    )

    assertFalse(wrapped.message.orEmpty().contains("super_secret_token"))
    assertFalse(wrapped.message.orEmpty().contains("cookie_99"))
    assertFalse(wrapped.message.orEmpty().contains("hidden_pw"))
    assertFalse(wrapped.message.orEmpty().contains("gino_commish"))
    assertFalse(wrapped.message.orEmpty().contains("54321"))
    assertTrue(wrapped.message.orEmpty().contains("APIKEY=<REDACTED>"))
    assertTrue(wrapped.message.orEmpty().contains("L=<LEAGUE_ID>"))
    assertEquals(original.stackTrace.firstOrNull(), wrapped.stackTrace.firstOrNull())
  }
}
