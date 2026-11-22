package com.mflgameday.live.data

import com.mflgameday.live.model.ScoringBreakdownLine

/**
 * Parses MyFantasyLeague's `live_scoring_summary` HTML page to extract per-player
 * scoring category breakdowns (e.g., `•4.00 pts for 1 Passing TDs •7.95 pts for 159 Passing Yards`),
 * faithfully reflecting every custom rule in the user's league.
 */
object ScoringBreakdownParser {
  private val cellRegex = Regex(
    """id="points_([^"]+)"[^>]*>\s*<a\b[^>]*?\btitle=(['"])(.*?)\2""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
  )

  private val lineRegex = Regex(
    """^(-?[0-9.]+)\s+pts?\s+for\s+(-?[0-9.]+)\s+(.+?)\s*$""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
  )

  private val namedEntities = mapOf(
    "bull" to "•",
    "amp" to "&",
    "quot" to "\"",
    "apos" to "'",
    "lt" to "<",
    "gt" to ">",
    "nbsp" to " "
  )

  fun parse(html: String): Map<String, List<ScoringBreakdownLine>> {
    if (html.isBlank()) return emptyMap()
    val result = LinkedHashMap<String, List<ScoringBreakdownLine>>()
    for (match in cellRegex.findAll(html)) {
      val playerId = match.groupValues[1].trim()
      if (playerId.isEmpty() || result.containsKey(playerId)) continue
      val decodedTitle = decodeHtmlEntities(match.groupValues[3])
      val lines = decodedTitle
        .split('•')
        .mapNotNull { parseLine(it) }
      if (lines.isNotEmpty()) {
        result[playerId] = lines
      }
    }
    return result
  }

  private fun parseLine(raw: String): ScoringBreakdownLine? {
    val cleaned = raw.replace('\n', ' ').replace('\r', ' ').trim()
    if (cleaned.isEmpty()) return null
    val match = lineRegex.matchEntire(cleaned) ?: return null
    val points = match.groupValues[1].toDoubleOrNull() ?: return null
    val count = match.groupValues[2].trim()
    val category = match.groupValues[3].trim().split(Regex("\\s+")).joinToString(" ")
    return ScoringBreakdownLine(category = category, count = count, points = points)
  }

  internal fun decodeHtmlEntities(input: String): String {
    if (!input.contains('&')) return input
    val sb = StringBuilder(input.length)
    var i = 0
    while (i < input.length) {
      val c = input[i]
      if (c != '&') {
        sb.append(c)
        i++
        continue
      }
      val semi = input.indexOf(';', startIndex = i + 1)
      if (semi == -1 || semi - i > 12) {
        sb.append('&')
        i++
        continue
      }
      val entity = input.substring(i + 1, semi)
      val decoded: String? = when {
        entity.startsWith("#x", ignoreCase = true) ->
          entity.drop(2).toIntOrNull(16)?.let { codePoint -> String(Character.toChars(codePoint)) }
        entity.startsWith("#") ->
          entity.drop(1).toIntOrNull(10)?.let { codePoint -> String(Character.toChars(codePoint)) }
        else -> namedEntities[entity]
      }
      if (decoded != null) {
        sb.append(decoded)
        i = semi + 1
      } else {
        sb.append('&')
        i++
      }
    }
    return sb.toString()
  }
}
