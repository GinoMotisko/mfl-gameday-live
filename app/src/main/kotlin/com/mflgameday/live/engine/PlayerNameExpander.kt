package com.mflgameday.live.engine

import com.mflgameday.live.model.FantasyPlayer
import com.mflgameday.live.model.NflTeam

/**
 * Expands ESPN's abbreviated play-by-play names ("B.Mayfield", "C.Godwin", "T.Parker", "M.Penix")
 * into full broadcast names ("Baker Mayfield", "Chris Godwin", "T.J. Parker", "Michael Penix Jr.")
 * using MFL's comprehensive offensive + IDP player database.
 */
class PlayerNameExpander(players: Collection<FantasyPlayer>) {
  private val byLastName: Map<String, List<FantasyPlayer>>

  init {
    val nonTeamPositions = setOf("def", "st", "off", "coach", "xx")
    val index = HashMap<String, MutableList<FantasyPlayer>>()
    for (p in players) {
      val posLower = p.position.lowercase()
      if (posLower in nonTeamPositions || posLower.startsWith("tm")) continue
      val last = extractLastName(p).lowercase()
      if (last.isEmpty()) continue
      index.getOrPut(last) { ArrayList(2) }.add(p)
      val stripped = stripSuffix(last)
      if (stripped != last && stripped.isNotEmpty()) {
        index.getOrPut(stripped) { ArrayList(2) }.add(p)
      }
    }
    byLastName = index
  }

  fun expand(abbreviated: String, team: NflTeam?): String {
    val cleaned = abbreviated.trim().trim('[', ']', '(', ')', ',', ';')
    val dotIdx = cleaned.indexOf('.')
    if (dotIdx <= 0 || dotIdx >= cleaned.length - 1) return cleaned

    val initialsRaw = cleaned.substring(0, dotIdx).trim()
    val initialsLower = initialsRaw.lowercase()
    val lastRaw = cleaned.substring(dotIdx + 1).trim().trimEnd('.')
    if (lastRaw.isEmpty()) return cleaned

    val fallback = "$initialsRaw. $lastRaw"
    val candidates = byLastName[lastRaw.lowercase()].orEmpty().filter { candidate ->
      (team == null || candidate.team == team) &&
        extractFirstNameNormalized(candidate).startsWith(initialsLower)
    }

    return when {
      candidates.size == 1 -> candidates[0].displayName
      candidates.size > 1 && team != null -> candidates.first().displayName
      else -> {
        // Fallback without team constraint if traded mid-season and unambiguous league-wide
        val anyTeam = byLastName[lastRaw.lowercase()].orEmpty().filter { candidate ->
          extractFirstNameNormalized(candidate).startsWith(initialsLower)
        }
        if (anyTeam.size == 1) anyTeam[0].displayName else fallback
      }
    }
  }

  private fun extractLastName(player: FantasyPlayer): String =
    player.rawMflName.substringBefore(',').trim()

  private fun extractFirstNameNormalized(player: FantasyPlayer): String {
    val parts = player.rawMflName.split(',', limit = 2)
    if (parts.size < 2) return ""
    return parts[1].trim().replace(".", "").lowercase()
  }

  private fun stripSuffix(lastLower: String): String {
    val suffixes = listOf(" jr.", " jr", " sr.", " sr", " ii", " iii", " iv", " v")
    for (s in suffixes) {
      if (lastLower.endsWith(s)) {
        return lastLower.dropLast(s.length).trim()
      }
    }
    return lastLower
  }
}
