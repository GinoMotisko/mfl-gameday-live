package com.mflgameday.live.engine

import com.mflgameday.live.model.GameEvent
import com.mflgameday.live.model.GameEventKind
import com.mflgameday.live.model.NflPlay
import com.mflgameday.live.model.NflSide

/**
 * Transforms [GameEvent]s into natural, exciting sportscaster sentences ready for
 * Android TextToSpeech, expanding abbreviated player names and formatting passes,
 * rushes, sacks, turnovers, defensive pick-sixes, return TDs, and scores cleanly.
 */
object PlaySpeechFormatter {

  private sealed interface ReturnKind {
    val spoken: String
    data class Defense(override val spoken: String) : ReturnKind
    data class SpecialTeams(override val spoken: String) : ReturnKind
  }

  private val returnPhrases: List<Pair<String, ReturnKind>> = listOf(
    "Blocked Field Goal Return" to ReturnKind.SpecialTeams("blocked field goal return"),
    "Missed Field Goal Return" to ReturnKind.SpecialTeams("missed field goal return"),
    "Blocked Punt Return" to ReturnKind.SpecialTeams("blocked punt return"),
    "Blocked Punt Recovery" to ReturnKind.SpecialTeams("blocked punt recovery"),
    "Interception Return" to ReturnKind.Defense("interception"),
    "Fumble Return" to ReturnKind.Defense("fumble recovery"),
    "Fumble Recovery" to ReturnKind.Defense("fumble recovery"),
    "Kickoff Return" to ReturnKind.SpecialTeams("kickoff return"),
    "Punt Return" to ReturnKind.SpecialTeams("punt return")
  )

  private val endZoneReturnPhrases: List<Pair<String, ReturnKind>> = listOf(
    "Blocked Field Goal Recovery in End Zone" to ReturnKind.SpecialTeams("blocked field goal recovery"),
    "Blocked Punt Recovery in End Zone" to ReturnKind.SpecialTeams("blocked punt recovery"),
    "Offensive Fumble Recovery in End Zone" to ReturnKind.Defense("fumble recovery"),
    "Fumble Recovery in End Zone" to ReturnKind.Defense("fumble recovery"),
    "Interception in End Zone" to ReturnKind.Defense("interception")
  )

  private val endZoneSpotRegex = Regex(
    """\bat\s+[A-Z]{2,4}\s+-\d+\b|\bin\s+(?:the\s+)?end\s+zone\b""",
    RegexOption.IGNORE_CASE
  )

  private val clauseEnds = setOf("Fumble", "Rush", "Return", "Recovery", "Interception", "Lateral")

  // Matches ESPN summary pass TD: "Chris Godwin 94 Yd pass from Baker Mayfield"
  private val passTouchdownSummaryRegex = Regex(
    """^(.+?)\s+(-?\d+)\s+Yd\s+pass\s+from\s+(.+?)$""",
    RegexOption.IGNORE_CASE
  )

  // Matches ESPN summary rush TD: "Josh Allen 5 Yd Rush"
  private val rushTouchdownSummaryRegex = Regex(
    """^(.+?)\s+(-?\d+)\s+Yd\s+Rush$""",
    RegexOption.IGNORE_CASE
  )

  // "(Shotgun) B.Mayfield sacked at TB 28 for -7 yards (A.Hutchinson)." or "... for no gain (A.B, C.D)."
  private val sackRegex = Regex(
    """(\S+)\s+sacked\s+.*?for\s+(?:(-?\d+)\s+yards?|no gain)\s*\(([^)]*)\)""",
    RegexOption.IGNORE_CASE
  )

  // "J.Allen pass short middle intended for K.Shakir INTERCEPTED by B.Washington [R.Smith] at BUF 17."
  private val interceptionRegex = Regex(
    """(\S+)\s+pass\s+.*?INTERCEPTED\s+by\s+(\S+)""",
    RegexOption.IGNORE_CASE
  )

  private val bracketedRegex = Regex("""\([^)]*\)|\[[^\]]*\]""")
  private val playerAbbrevRegex = Regex("""\b[A-Z][a-zA-Z]*\.[A-Z][\w'\s.-]*?[\w'-]+""")
  private val simplePlayerAbbrevRegex = Regex("""\b[A-Z][a-zA-Z]*\.(?:St\.\s*)?[A-Z][\w'-]+""")
  private val recoveredByRegex = Regex(
    """recovered\s+by\s+(?:[A-Z]{2,4}-)?([A-Z][a-zA-Z]*\.(?:St\.\s*)?[A-Z][\w'-]+)""",
    RegexOption.IGNORE_CASE
  )

  private val spokenReplacements = listOf(
    " Yd Rush" to " yard run",
    " Yd Field Goal" to " yard field goal",
    " Yd Interception Return" to " yard interception return",
    " Yd Fumble Return" to " yard fumble return",
    " Yd Fumble Recovery" to " yard fumble recovery",
    " Yd Kickoff Return" to " yard kickoff return",
    " Yd Punt Return" to " yard punt return",
    " Yd " to " yard "
  )

  fun format(
    event: GameEvent,
    nameExpander: PlayerNameExpander? = null,
    fantasyTagSuffix: String? = null
  ): String {
    event.customHeadline?.takeIf { it.isNotBlank() }?.let { return cleanPeriods(it) }

    val game = event.game
    val team = event.team
    val opponent = team?.let { if (it.espnId == game.away.espnId) game.home else game.away }
    val teamName = team?.fullName ?: game.home.fullName
    val scoreSuffix = scoreSentence(event)

    val core = when (event.kind) {
      GameEventKind.TOUCHDOWN -> {
        val afterScore = if (event.tryPending) "" else scoreSuffix
        val returnTd = if (team != null) formatReturnTouchdown(event, team, nameExpander) else null
        if (returnTd != null) {
          val (_, parenLower) = stripTrailingParenthetical(event.play?.text?.trim().orEmpty())
          val tryNote = if (event.tryPending) "" else formatTryNote(parenLower)
          "Touchdown, $returnTd.$tryNote$afterScore"
        } else {
          val detail = formatTouchdownDetail(event, team, nameExpander)
          if (detail.isEmpty()) "Touchdown, $teamName!$afterScore"
          else "Touchdown, $teamName! $detail$afterScore"
        }
      }
      GameEventKind.FIELD_GOAL -> {
        val detail = formatGeneralScoringPlay(event)
        if (detail.isEmpty()) "Field goal, $teamName.$scoreSuffix"
        else "Field goal, $teamName. $detail$scoreSuffix"
      }
      GameEventKind.SAFETY -> {
        val detail = formatGeneralScoringPlay(event)
        if (detail.isEmpty()) "Safety! Two points for $teamName.$scoreSuffix"
        else "Safety! Two points for $teamName. $detail$scoreSuffix"
      }
      GameEventKind.OTHER_SCORE -> {
        val detail = formatGeneralScoringPlay(event)
        if (detail.isEmpty()) "Score for $teamName.$scoreSuffix"
        else "Score for $teamName. $detail$scoreSuffix"
      }
      GameEventKind.TRY_FAILED -> "$teamName misses the try after the touchdown.$scoreSuffix"
      GameEventKind.TWO_POINT_GOOD -> "Two-point conversion is good for $teamName!$scoreSuffix"
      GameEventKind.SACK -> formatSack(event, defense = team, offense = opponent, nameExpander = nameExpander)
      GameEventKind.INTERCEPTION -> formatInterception(event, defense = team, offense = opponent, nameExpander = nameExpander)
      GameEventKind.FUMBLE_LOST -> formatFumble(event, defense = team, offense = opponent, nameExpander = nameExpander)
      GameEventKind.RED_ZONE -> "$teamName in the red zone against the ${opponent?.fullName ?: "defense"}."
      GameEventKind.BIG_PLAY -> formatBigPlay(event, offense = team, nameExpander = nameExpander)
      GameEventKind.FIRST_DOWN -> "First down, $teamName."
      GameEventKind.FOURTH_DOWN_STOP -> "Fourth down stop by the $teamName defense!"
      GameEventKind.KICKOFF -> "Kickoff: ${game.away.fullName} at ${game.home.fullName}."
      GameEventKind.HALFTIME -> "Halftime, ${game.away.fullName} at ${game.home.fullName}.$scoreSuffix"
      GameEventKind.FINAL -> "Final, ${game.away.fullName} at ${game.home.fullName}.$scoreSuffix"
      GameEventKind.SCORE_CORRECTION -> "Score correction, ${game.away.fullName} at ${game.home.fullName}.$scoreSuffix"
      GameEventKind.FANTASY_LEAD_CHANGE -> "Fantasy matchup lead change!$scoreSuffix"
    }

    val combined = if (!fantasyTagSuffix.isNullOrBlank()) "$core $fantasyTagSuffix" else core
    return cleanPeriods(combined)
  }

  private fun formatTouchdownDetail(
    event: GameEvent,
    scoringTeam: NflSide?,
    nameExpander: PlayerNameExpander?
  ): String {
    val raw = event.play?.text?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    val (bodyRaw, parenLower) = stripTrailingParenthetical(raw)
    val tryNote = formatTryNote(parenLower)

    // Convert "Chris Godwin 94 Yd pass from Baker Mayfield" -> "Baker Mayfield, 94 yard touchdown pass to Chris Godwin."
    passTouchdownSummaryRegex.matchEntire(bodyRaw)?.let { m ->
      val receiver = expandIfAbbrev(m.groupValues[1].trim(), scoringTeam, nameExpander)
      val yards = m.groupValues[2].trim()
      val passer = expandIfAbbrev(m.groupValues[3].trim(), scoringTeam, nameExpander)
      return "$passer, $yards yard touchdown pass to $receiver.$tryNote"
    }

    // Convert "Josh Allen 5 Yd Rush" -> "Josh Allen, 5 yard touchdown run."
    rushTouchdownSummaryRegex.matchEntire(bodyRaw)?.let { m ->
      val runner = expandIfAbbrev(m.groupValues[1].trim(), scoringTeam, nameExpander)
      val yards = m.groupValues[2].trim()
      return "$runner, $yards yard touchdown run.$tryNote"
    }

    return formatGeneralScoringPlay(event)
  }

  private fun formatGeneralScoringPlay(event: GameEvent): String {
    val raw = event.play?.text?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    val (stripped, parenLower) = stripTrailingParenthetical(raw)
    var text = stripped
    for ((abbr, spoken) in spokenReplacements) {
      text = text.replace(abbr, spoken)
    }
    if (!text.endsWith('.')) text += "."
    return text + formatTryNote(parenLower)
  }

  private fun formatTryNote(parenLower: String?): String {
    if (parenLower == null) return ""
    val failed = parenLower.contains("fail") || parenLower.contains("no good") || parenLower.contains("blocked")
    return when {
      parenLower.contains("two-point") || parenLower.contains("2pt") ->
        if (failed) " Two-point try failed." else " Two-point conversion good."
      failed -> " Extra point no good."
      else -> ""
    }
  }

  private fun formatSack(
    event: GameEvent,
    defense: NflSide?,
    offense: NflSide?,
    nameExpander: PlayerNameExpander?
  ): String {
    val teamName = defense?.fullName ?: "the defense"
    val callText = event.play?.let(::finalReviewedCall) ?: return "Sack, $teamName."
    val match = sackRegex.find(callText) ?: return "Sack, $teamName."

    val qbRaw = match.groupValues[1]
    val yardsNum = match.groupValues[2].toIntOrNull()?.let { kotlin.math.abs(it) } ?: 0
    val tacklersRaw = match.groupValues[3]

    val defenders = tacklersRaw
      .replace("sack split by ", "", ignoreCase = true)
      .replace(" and ", ",", ignoreCase = true)
      .split(',', ';')
      .map { expandIfAbbrev(it, defense, nameExpander) }
      .filter { it.isNotBlank() }

    val lossPhrase = when (yardsNum) {
      0 -> "for no gain"
      1 -> "for 1 yard"
      else -> "for $yardsNum yards"
    }
    val byClause = if (defenders.isEmpty()) "" else " by ${defenders.joinToString(" and ")}"
    val qbExpanded = expandIfAbbrev(qbRaw, offense, nameExpander)
    return "Sack, $teamName, $qbExpanded sacked$byClause $lossPhrase."
  }

  private fun formatInterception(
    event: GameEvent,
    defense: NflSide?,
    offense: NflSide?,
    nameExpander: PlayerNameExpander?
  ): String {
    val teamName = defense?.fullName ?: "the defense"
    val callText = event.play?.let(::finalReviewedCall) ?: return "Interception, $teamName."
    val match = interceptionRegex.find(callText) ?: return "Interception, $teamName."
    val passer = expandIfAbbrev(match.groupValues[1], offense, nameExpander)
    val interceptor = expandIfAbbrev(match.groupValues[2], defense, nameExpander)
    return "Interception, $teamName, $interceptor picks off $passer."
  }

  private fun formatFumble(
    event: GameEvent,
    defense: NflSide?,
    offense: NflSide?,
    nameExpander: PlayerNameExpander?
  ): String {
    val teamName = defense?.fullName ?: "the defense"
    val callText = event.play?.let(::finalReviewedCall) ?: return "Fumble, recovered by $teamName."
    val fumbleIdx = callText.indexOf("FUMBLES", ignoreCase = true)
    if (fumbleIdx == -1) return "Fumble, recovered by $teamName."

    val leadSegment = callText.substring(0, fumbleIdx).replace(bracketedRegex, "")
    val fumbler = simplePlayerAbbrevRegex.findAll(leadSegment).lastOrNull()?.value
      ?.let { expandIfAbbrev(it, offense, nameExpander) }

    val afterSegment = callText.substring(fumbleIdx + "FUMBLES".length)
    val recoverer = recoveredByRegex.find(afterSegment)?.groupValues?.get(1)
      ?.let { expandIfAbbrev(it, defense, nameExpander) }

    val byWho = if (recoverer != null) "$teamName $recoverer" else teamName
    return if (fumbler == null) {
      "Fumble, recovered by $byWho."
    } else {
      "Fumble, $fumbler fumbles and is recovered by $byWho."
    }
  }

  private fun formatBigPlay(
    event: GameEvent,
    offense: NflSide?,
    nameExpander: PlayerNameExpander?
  ): String {
    val teamName = offense?.fullName ?: "Offense"
    val yds = event.play?.statYardage ?: 0
    val callText = event.play?.let(::finalReviewedCall).orEmpty()
    val players = simplePlayerAbbrevRegex.findAll(callText.replace(bracketedRegex, ""))
      .map { expandIfAbbrev(it.value, offense, nameExpander) }
      .distinct()
      .take(2)
      .toList()
    return if (players.isNotEmpty() && yds > 0) {
      "Big play, $teamName! ${players.joinToString(" to ")} for $yds yards."
    } else if (yds > 0) {
      "Big play, $teamName, $yds yard gain!"
    } else {
      "Explosive play for $teamName!"
    }
  }

  private fun formatReturnTouchdown(
    event: GameEvent,
    scoringTeam: NflSide,
    nameExpander: PlayerNameExpander?
  ): String? {
    val play = event.play ?: return null
    val (cleanText, _) = stripTrailingParenthetical(play.text.trim())
    val isDefensiveScore = play.offenseTeamId != null && play.offenseTeamId != scoringTeam.espnId

    for ((espnPhrase, returnKind) in endZoneReturnPhrases) {
      if (!cleanText.endsWith(espnPhrase, ignoreCase = true)) continue
      val prefix = cleanText.dropLast(espnPhrase.length).trim()
      val scorerTokens = prefix.split(Regex("\\s+")).filter { it.isNotEmpty() }.dropLastWhile { it in clauseEnds }
      val rawScorer = scorerTokens.joinToString(" ")
      val scorer = expandSingleAbbrevToken(rawScorer, scoringTeam, nameExpander)
      val teamLabel = if (returnKind is ReturnKind.Defense && isDefensiveScore) {
        "${scoringTeam.fullName} Defense"
      } else {
        scoringTeam.fullName
      }
      val distance = "${returnKind.spoken} in the end zone"
      return if (scorer.isEmpty()) "$teamLabel, $distance" else "$teamLabel, $scorer, $distance"
    }

    for ((espnPhrase, returnKind) in returnPhrases) {
      val needle = " Yd $espnPhrase"
      val idx = cleanText.lastIndexOf(needle, ignoreCase = true)
      if (idx == -1) continue
      val wordsBefore = cleanText.substring(0, idx).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
      val yards = wordsBefore.lastOrNull()?.toIntOrNull() ?: continue
      val scorerWords = wordsBefore.dropLast(1).takeLastWhile { it !in clauseEnds }
      val rawScorer = scorerWords.joinToString(" ")
      val scorer = expandSingleAbbrevToken(rawScorer, scoringTeam, nameExpander)

      var teamLabel = scoringTeam.fullName
      val spokenPhrase = when (returnKind) {
        is ReturnKind.Defense -> {
          if (isDefensiveScore) {
            teamLabel += " Defense"
          }
          returnKind.spoken
        }
        is ReturnKind.SpecialTeams -> returnKind.spoken
      }
      val distance = if (yards == 0) "$spokenPhrase in the end zone" else "$yards yard $spokenPhrase"
      return if (scorer.isEmpty()) "$teamLabel, $distance" else "$teamLabel, $scorer, $distance"
    }

    if (isDefensiveScore) {
      val callText = finalReviewedCall(play)
      if (callText.contains("FUMBLES", ignoreCase = true)) {
        val afterFumble = callText.substringAfter("FUMBLES", missingDelimiterValue = callText)
        val recoverer = recoveredByRegex.find(afterFumble)?.groupValues?.get(1)
          ?.let { expandIfAbbrev(it, scoringTeam, nameExpander) }
        val inEndZone = endZoneSpotRegex.containsMatchIn(callText)
        val phrase = if (inEndZone) "fumble recovery in the end zone" else "fumble recovery"
        val teamLabel = "${scoringTeam.fullName} Defense"
        return if (recoverer != null) "$teamLabel, $recoverer, $phrase" else "$teamLabel, $phrase"
      }
      if (callText.contains("INTERCEPTED", ignoreCase = true)) {
        val interceptor = interceptionRegex.find(callText)?.groupValues?.get(2)
          ?.let { expandIfAbbrev(it, scoringTeam, nameExpander) }
        val teamLabel = "${scoringTeam.fullName} Defense"
        return if (interceptor != null) "$teamLabel, $interceptor, interception" else "$teamLabel, interception"
      }
    }

    return null
  }

  private fun expandSingleAbbrevToken(
    scorer: String,
    scoringTeam: NflSide,
    nameExpander: PlayerNameExpander?
  ): String {
    if (scorer.isEmpty() || scorer.contains(' ') || !scorer.contains('.')) return scorer
    return expandIfAbbrev(scorer, scoringTeam, nameExpander)
  }

  fun scoreSentence(event: GameEvent): String {
    val away = event.awayScore
    val home = event.homeScore
    if (away == home) return " Tied at $away."
    val (leader, high, low) = if (away > home) {
      Triple(event.game.away.fullName, away, home)
    } else {
      Triple(event.game.home.fullName, home, away)
    }
    val verb = if (event.kind == GameEventKind.FINAL) "win" else "lead"
    return " $leader $verb $high to $low."
  }

  private fun finalReviewedCall(play: NflPlay): String {
    val src = play.rawPlayText.ifBlank { play.text }
    val idx = src.lastIndexOf("REVERSED.")
    return if (idx != -1) src.substring(idx + "REVERSED.".length).trim() else src
  }

  private fun stripTrailingParenthetical(text: String): Pair<String, String?> {
    if (!text.endsWith(')')) return text to null
    val openIdx = text.lastIndexOf('(')
    if (openIdx == -1) return text to null
    return text.substring(0, openIdx).trim() to text.substring(openIdx).lowercase()
  }

  private fun expandIfAbbrev(
    rawToken: String,
    side: NflSide?,
    expander: PlayerNameExpander?
  ): String {
    val trimmed = rawToken.trim().trim('[', ']', '(', ')', '.')
    if (expander == null || !trimmed.contains('.')) return rawToken.trim().trim('[', ']', '(', ')')
    return expander.expand(trimmed, side?.team)
  }

  private fun cleanPeriods(s: String): String =
    s.replace("..", ".").replace(" .", ".").replace(Regex("\\s+"), " ").trim()
}
