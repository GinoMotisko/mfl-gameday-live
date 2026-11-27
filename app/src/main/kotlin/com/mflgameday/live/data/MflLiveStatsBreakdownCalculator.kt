package com.mflgameday.live.data

import com.mflgameday.live.model.FantasyPlayer
import com.mflgameday.live.model.ScoringBreakdownLine
import kotlin.math.abs
import kotlin.math.round

enum class MflStatSource {
  PLAYER,
  TEAM,
  OPP_TEAM
}

data class MflAjaxPlayerMeta(
  val id: String,
  val pos: String,
  val nflTeam: String,
  val rulePos: String
)

data class MflCompiledScoringRule(
  val stat: String,
  val source: MflStatSource,
  val defaultZeroWhenMissing: Boolean,
  val lower: Double,
  val upper: Double,
  val formula: String,
  val bonus: String
)

data class MflPositionRuleBlock(
  val posRegex: Regex?,
  val exactPositions: Set<String>,
  val rules: List<MflCompiledScoringRule>
) {
  fun matches(position: String): Boolean {
    if (position.isBlank()) return false
    if (posRegex != null && posRegex.containsMatchIn(position)) return true
    return exactPositions.any { it.equals(position, ignoreCase = false) } ||
      exactPositions.any { it.equals(position, ignoreCase = true) }
  }
}

data class MflAjaxLeagueRules(
  val xmlBaseUrl: String?,
  val usesIdps: Boolean,
  val catDesc: Map<String, String>,
  val listCats: Set<String>,
  val teamPositions: Set<String>,
  val players: Map<String, MflAjaxPlayerMeta>,
  val nflGameOpponents: Map<String, String>,
  val nflGameStatuses: Map<String, String>,
  val ruleBlocks: List<MflPositionRuleBlock>,
  val fetchedAtMs: Long = System.currentTimeMillis()
)

data class MflLiveWeekStats(
  val playerStats: Map<String, Map<String, String>>,
  val teamStats: Map<String, Map<String, String>>
)

/**
 * Evaluates MFL's `ajax_ls` compiled `score_player(pid,ppos)` league rules against MFL's
 * global `live_stats_<week>.txt` feed so bench (`nonstarter`) players in standard head-to-head
 * leagues receive exact per-category breakdowns even when MFL's legacy `live_scoring_summary`
 * omits non-starters.
 */
object MflLiveStatsBreakdownCalculator {
  private val xmlBaseRegex = Regex("""xmlBaseURL\s*=\s*['"]([^'"]+)['"]""")
  private val usesIdpsRegex = Regex("""ls_uses_idps\s*=\s*(\d+)""")
  private val catEntryRegex = Regex("""['"]([^'"]+)['"]\s*:\s*['"]([^'"]*)['"]""")
  private val listEntryRegex = Regex("""['"]([^'"]+)['"]\s*:\s*1""")
  private val teamPosEntryRegex = Regex("""([A-Za-z0-9_]+)\s*:\s*1""")
  private val lsPlayerRegex = Regex(
    """ls_player\[\s*['"]([^'"]+)['"]\s*\]\s*=\s*new\s+LSPlayer\(\s*['"]([^'"]+)['"]\s*,\s*(['"]).*?\3\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]"""
  )
  private val gameOppRegex = Regex("""ls_nfl_games\[\s*['"]([A-Za-z0-9]+)['"]\s*\]\.opp\s*=\s*['"]([A-Za-z0-9]+)['"]""")
  private val gameStatusRegex = Regex("""ls_nfl_games\[\s*['"]([A-Za-z0-9]+)['"]\s*\]\.status\s*=\s*['"]([A-Za-z0-9]+)['"]""")
  private val posRegexCond = Regex("""/([^/]+)/([i]*)\.test\(\s*ppos\s*\)""")
  private val posEqualsCond = Regex("""ppos\s*==\s*['"]([^'"]+)['"]""")
  private val valAssignRegex = Regex("""val\s*=\s*ls_(t)?stats\[\s*(pid|opp)\s*\]\[\s*['"]([^'"]+)['"]\s*\]""")
  private val valZeroDefaultRegex = Regex("""val\s*==\s*undefined\s*\)\s*\{\s*val\s*=\s*['"]0['"]""")
  private val lsGetPointsRegex = Regex(
    """ls_get_points\(\s*['"]([^'"]+)['"]\s*,\s*val\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*\)"""
  )

  fun parseAjaxLeagueRules(html: String): MflAjaxLeagueRules {
    val xmlBase = xmlBaseRegex.find(html)?.groupValues?.get(1)?.trim()
    val usesIdps = usesIdpsRegex.find(html)?.groupValues?.get(1)?.toIntOrNull() == 1

    val catDesc = extractJsObjectBlock(html, "ls_cat_desc")?.let { block ->
      catEntryRegex.findAll(block).associate { it.groupValues[1] to it.groupValues[2] }
    }.orEmpty()

    val listCats = extractJsObjectBlock(html, "ls_list_cats")?.let { block ->
      listEntryRegex.findAll(block).map { it.groupValues[1] }.toSet()
    }.orEmpty()

    val teamPositions = extractJsObjectBlock(html, "ls_team_pos")?.let { block ->
      teamPosEntryRegex.findAll(block).map { it.groupValues[1] }.toSet()
    } ?: setOf("Def")

    val players = LinkedHashMap<String, MflAjaxPlayerMeta>()
    for (m in lsPlayerRegex.findAll(html)) {
      val id = m.groupValues[2].ifBlank { m.groupValues[1] }.trim()
      val pos = m.groupValues[4].trim()
      val nflTeam = m.groupValues[5].trim()
      val lpos = m.groupValues[6].trim()
      if (id.isNotEmpty()) {
        players[id] = MflAjaxPlayerMeta(
          id = id,
          pos = pos,
          nflTeam = nflTeam,
          rulePos = pos.ifBlank { lpos }
        )
      }
    }

    val opponents = gameOppRegex.findAll(html).associate {
      it.groupValues[1].uppercase() to it.groupValues[2].uppercase()
    }
    val statuses = gameStatusRegex.findAll(html).associate {
      it.groupValues[1].uppercase() to it.groupValues[2].uppercase()
    }

    val ruleBlocks = parseScorePlayerRuleBlocks(html)

    return MflAjaxLeagueRules(
      xmlBaseUrl = xmlBase,
      usesIdps = usesIdps,
      catDesc = catDesc,
      listCats = listCats,
      teamPositions = teamPositions,
      players = players,
      nflGameOpponents = opponents,
      nflGameStatuses = statuses,
      ruleBlocks = ruleBlocks
    )
  }

  fun parseLiveWeekStats(rawText: String): MflLiveWeekStats {
    if (rawText.isBlank()) return MflLiveWeekStats(emptyMap(), emptyMap())
    val playerStats = HashMap<String, Map<String, String>>(1024)
    val teamStats = HashMap<String, Map<String, String>>(64)
    val normalized = rawText.replace("\r", "")
    for (rawLine in normalized.split('\n')) {
      val line = rawLine.trim()
      if (line.isEmpty()) continue
      val fields = line.split('|')
      val key = fields[0].trim()
      if (key.isEmpty() || key == "DATE" || key == "REFRESH" || key == "TICKER") continue
      val statsMap = LinkedHashMap<String, String>(fields.size)
      for (i in 1 until fields.size) {
        val token = fields[i].trim()
        if (token.isEmpty()) continue
        val spaceIdx = token.indexOf(' ')
        if (spaceIdx <= 0) continue
        val code = token.substring(0, spaceIdx).trim()
        val value = token.substring(spaceIdx + 1).trim()
        if (code.isNotEmpty()) {
          statsMap[code] = value
        }
      }
      if (key.all(Char::isDigit)) {
        playerStats[key] = statsMap
      } else {
        teamStats[key.uppercase()] = statsMap
      }
    }
    return MflLiveWeekStats(playerStats = playerStats, teamStats = teamStats)
  }

  fun computeBreakdownsForPlayer(
    playerId: String,
    rawScore: RawMflPlayerScore?,
    fallbackPlayer: FantasyPlayer?,
    rules: MflAjaxLeagueRules,
    liveStats: MflLiveWeekStats
  ): List<ScoringBreakdownLine> {
    if (rules.ruleBlocks.isEmpty()) return emptyList()
    val ajaxMeta = rules.players[playerId]
    val rulePos = ajaxMeta?.rulePos?.ifBlank { null } ?: fallbackPlayer?.position?.ifBlank { null } ?: return emptyList()
    val nflTeam = (ajaxMeta?.nflTeam?.ifBlank { null } ?: fallbackPlayer?.team?.code)?.uppercase()
    if (nflTeam.isNullOrBlank() || nflTeam == "FA" || nflTeam == "FA*") return emptyList()

    val isTeamPos = rules.teamPositions.any { it.equals(rulePos, ignoreCase = true) } ||
      rulePos.equals("Def", ignoreCase = true) ||
      (fallbackPlayer?.isTeamUnit == true)

    val gameStatus = rules.nflGameStatuses[nflTeam]
    val hasLiveOrFinalGame = gameStatus == "INPROG" || gameStatus == "OVER"
    val gameHasStarted = hasLiveOrFinalGame ||
      (rawScore != null && (rawScore.gameSecondsRemaining < 3600 || abs(rawScore.score) > 0.001))

    if (rawScore != null && rawScore.gameSecondsRemaining >= 3600 && abs(rawScore.score) <= 0.001 && !hasLiveOrFinalGame) {
      return emptyList()
    }
    if (isTeamPos) {
      if (!gameHasStarted || liveStats.teamStats[nflTeam] == null) return emptyList()
    }

    val matchingBlocks = rules.ruleBlocks.filter { it.matches(rulePos) }
    if (matchingBlocks.isEmpty()) return emptyList()

    val oppTeam = rules.nflGameOpponents[nflTeam]
    val playerMap = liveStats.playerStats[playerId]
    val teamMap = liveStats.teamStats[nflTeam]
    val oppMap = oppTeam?.let { liveStats.teamStats[it] }

    val lines = ArrayList<ScoringBreakdownLine>()
    for (block in matchingBlocks) {
      var ruleIdx = 0
      while (ruleIdx < block.rules.size) {
        val anchor = block.rules[ruleIdx]
        var groupEnd = ruleIdx + 1
        while (
          groupEnd < block.rules.size &&
          block.rules[groupEnd].stat == anchor.stat &&
          block.rules[groupEnd].source == anchor.source
        ) {
          groupEnd++
        }
        val groupDefaultZero = (ruleIdx until groupEnd).any { block.rules[it].defaultZeroWhenMissing }
        val mapForSource = when (anchor.source) {
          MflStatSource.PLAYER -> if (isTeamPos) teamMap else playerMap
          MflStatSource.TEAM -> teamMap
          MflStatSource.OPP_TEAM -> oppMap
        }
        val rawValue = mapForSource?.get(anchor.stat) ?: if (groupDefaultZero) "0" else null
        if (rawValue != null) {
          val values = if (anchor.stat in rules.listCats || rawValue.contains(',')) {
            rawValue.split(',').map { it.trim() }.filter { it.isNotEmpty() }
          } else {
            listOf(rawValue)
          }
          for (singleVal in values) {
            for (idx in ruleIdx until groupEnd) {
              val rule = block.rules[idx]
              val pts = evaluateSingleValue(rule, singleVal) ?: continue
              val roundedPts = round(pts * 10_000.0) / 10_000.0
              if (abs(roundedPts) > 1e-6) {
                val categoryDesc = rules.catDesc[rule.stat]?.takeIf { it.isNotBlank() } ?: rule.stat
                val cleanCount = formatRawCount(singleVal)
                lines.add(
                  ScoringBreakdownLine(
                    category = categoryDesc,
                    count = cleanCount,
                    points = roundedPts
                  )
                )
              }
            }
          }
        }
        ruleIdx = groupEnd
      }
    }
    return lines
  }

  private fun evaluateSingleValue(rule: MflCompiledScoringRule, rawVal: String): Double? {
    val trimmed = rawVal.trim()
    if (trimmed.isEmpty()) return null
    val matchValue: Double
    val matched: Boolean
    if (trimmed.contains('&')) {
      val parts = trimmed.split('&')
      val c1 = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
      val c2 = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
      matched = c1 >= rule.lower && c1 <= rule.upper && c2 >= rule.lower && c2 <= rule.upper
      matchValue = c1
    } else {
      val num = trimmed.toDoubleOrNull() ?: return null
      matched = num >= rule.lower && num <= rule.upper
      matchValue = num
    }
    if (!matched) return null

    val form = rule.formula.trim()
    if (form.isEmpty()) return null
    return when {
      form.startsWith("*") -> {
        val mult = form.substring(1).toDoubleOrNull() ?: return null
        matchValue * mult
      }
      form.contains('/') -> {
        val slashIdx = form.indexOf('/')
        val ppuStr = form.substring(0, slashIdx).trim()
        val unitStr = form.substring(slashIdx + 1).trim().ifEmpty { "1" }
        val ppu = ppuStr.toDoubleOrNull()
        val rawUnit = unitStr.toDoubleOrNull() ?: 1.0
        val unit = if (rawUnit == 0.0) 1.0 else rawUnit
        val hasUnitDot = '.' in unitStr
        val hasPpuDot = '.' in ppuStr
        val hasBonus = rule.bonus.isNotBlank() && '/' !in rule.stat
        val baseInput = if (hasBonus) matchValue - rule.lower else matchValue

        var pts = if (!hasUnitDot) {
          (baseInput / unit).toInt().toDouble()
        } else {
          baseInput / unit
        }
        if (ppu != null && ppu != 0.0) {
          pts *= ppu
        }
        if (hasBonus) {
          val bonusVal = rule.bonus.trim().toDoubleOrNull() ?: 0.0
          pts += if (!hasUnitDot && !hasPpuDot) bonusVal.toInt().toDouble() else bonusVal
        }
        pts
      }
      else -> form.toDoubleOrNull()
    }
  }

  private fun formatRawCount(rawVal: String): String {
    val first = rawVal.substringBefore('&').trim()
    val asDouble = first.toDoubleOrNull() ?: return first
    val rounded = round(asDouble)
    return if (abs(asDouble - rounded) < 1e-6) {
      rounded.toLong().toString()
    } else {
      first
    }
  }

  private fun extractJsObjectBlock(html: String, varName: String): String? {
    val marker = Regex("""(?:var\s+)?$varName\s*=\s*\{""").find(html) ?: return null
    val openIdx = html.indexOf('{', startIndex = marker.range.first)
    if (openIdx == -1) return null
    val closeIdx = findMatchingBrace(html, openIdx) ?: return null
    return html.substring(openIdx + 1, closeIdx)
  }

  private fun parseScorePlayerRuleBlocks(html: String): List<MflPositionRuleBlock> {
    val fnMatch = Regex("""function\s+score_player\s*\([^)]*\)\s*\{""").find(html) ?: return emptyList()
    val fnOpenBrace = html.indexOf('{', startIndex = fnMatch.range.first)
    if (fnOpenBrace == -1) return emptyList()
    val fnCloseBrace = findMatchingBrace(html, fnOpenBrace) ?: html.length
    val body = html.substring(fnOpenBrace + 1, fnCloseBrace)

    val blocks = ArrayList<MflPositionRuleBlock>()
    var idx = 0
    var depth = 0
    while (idx < body.length) {
      val c = body[idx]
      when {
        c == '"' || c == '\'' -> idx = skipQuoted(body, idx)
        c == '{' -> {
          depth++
          idx++
        }
        c == '}' -> {
          depth = (depth - 1).coerceAtLeast(0)
          idx++
        }
        depth == 0 && body.startsWith("if", idx) && isKeywordBoundary(body, idx, 2) -> {
          val parenOpen = skipWhitespace(body, idx + 2)
          if (parenOpen < body.length && body[parenOpen] == '(') {
            val parenClose = findMatchingParen(body, parenOpen)
            if (parenClose != null) {
              val cond = body.substring(parenOpen + 1, parenClose)
              val blockOpen = skipWhitespace(body, parenClose + 1)
              if (blockOpen < body.length && body[blockOpen] == '{') {
                val blockClose = findMatchingBrace(body, blockOpen)
                if (blockClose != null) {
                  val blockContent = body.substring(blockOpen + 1, blockClose)
                  parsePositionCondition(cond)?.let { (posRegex, exactPositions) ->
                    val rules = parseBlockRules(blockContent)
                    if (rules.isNotEmpty()) {
                      blocks.add(
                        MflPositionRuleBlock(
                          posRegex = posRegex,
                          exactPositions = exactPositions,
                          rules = rules
                        )
                      )
                    }
                  }
                  idx = blockClose + 1
                  continue
                }
              }
            }
          }
          idx++
        }
        else -> idx++
      }
    }
    return blocks
  }

  private fun parsePositionCondition(cond: String): Pair<Regex?, Set<String>>? {
    val regexMatch = posRegexCond.find(cond)
    val compiledRegex = regexMatch?.let { m ->
      val pattern = m.groupValues[1]
      val opts = if ('i' in m.groupValues[2]) setOf(RegexOption.IGNORE_CASE) else emptySet()
      runCatching { Regex(pattern, opts) }.getOrNull()
    }
    val exactPositions = posEqualsCond.findAll(cond).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toSet()
    if (compiledRegex == null && exactPositions.isEmpty()) return null
    return compiledRegex to exactPositions
  }

  private fun parseBlockRules(blockContent: String): List<MflCompiledScoringRule> {
    val rules = ArrayList<MflCompiledScoringRule>()
    var currentStat: String? = null
    var currentSource = if (blockContent.contains("ls_tstats")) MflStatSource.TEAM else MflStatSource.PLAYER
    var currentDefaultZero = false

    val statements = blockContent.split('\n', ';')
    for (stmtRaw in statements) {
      val stmt = stmtRaw.trim()
      if (stmt.isEmpty()) continue

      val assign = valAssignRegex.find(stmt)
      if (assign != null) {
        val isTeam = assign.groupValues[1] == "t"
        val target = assign.groupValues[2]
        currentSource = when {
          isTeam && target == "opp" -> MflStatSource.OPP_TEAM
          isTeam -> MflStatSource.TEAM
          else -> MflStatSource.PLAYER
        }
        currentStat = assign.groupValues[3]
        currentDefaultZero = false
      }

      if (valZeroDefaultRegex.containsMatchIn(stmt)) {
        currentDefaultZero = true
      }

      val gp = lsGetPointsRegex.find(stmt)
      if (gp != null) {
        val stat = gp.groupValues[1]
        val lower = gp.groupValues[2].toDoubleOrNull() ?: 0.0
        val upper = gp.groupValues[3].toDoubleOrNull() ?: Double.MAX_VALUE
        val formula = gp.groupValues[4]
        val bonus = gp.groupValues[5]
        val useCurrent = stat == currentStat
        rules.add(
          MflCompiledScoringRule(
            stat = stat,
            source = if (useCurrent) currentSource else {
              if (blockContent.contains("ls_tstats")) MflStatSource.TEAM else MflStatSource.PLAYER
            },
            defaultZeroWhenMissing = if (useCurrent) currentDefaultZero else false,
            lower = lower,
            upper = upper,
            formula = formula,
            bonus = bonus
          )
        )
      }
    }
    return rules
  }

  private fun isKeywordBoundary(text: String, start: Int, len: Int): Boolean {
    val beforeOk = start == 0 || !text[start - 1].isLetterOrDigit() && text[start - 1] != '_'
    val afterIdx = start + len
    val afterOk = afterIdx >= text.length || !text[afterIdx].isLetterOrDigit() && text[afterIdx] != '_'
    return beforeOk && afterOk
  }

  private fun skipWhitespace(text: String, start: Int): Int {
    var i = start
    while (i < text.length && text[i].isWhitespace()) i++
    return i
  }

  private fun skipQuoted(text: String, start: Int): Int {
    val quote = text[start]
    var i = start + 1
    while (i < text.length) {
      val c = text[i]
      if (c == '\\') {
        i += 2
        continue
      }
      if (c == quote) return i + 1
      i++
    }
    return text.length
  }

  private fun findMatchingBrace(text: String, openIdx: Int): Int? = findMatchingDelimiter(text, openIdx, '{', '}')

  private fun findMatchingParen(text: String, openIdx: Int): Int? = findMatchingDelimiter(text, openIdx, '(', ')')

  private fun findMatchingDelimiter(text: String, openIdx: Int, openCh: Char, closeCh: Char): Int? {
    var depth = 0
    var i = openIdx
    while (i < text.length) {
      val c = text[i]
      when {
        c == '"' || c == '\'' -> i = skipQuoted(text, i)
        c == openCh -> {
          depth++
          i++
        }
        c == closeCh -> {
          depth--
          if (depth == 0) return i
          i++
        }
        else -> i++
      }
    }
    return null
  }
}
