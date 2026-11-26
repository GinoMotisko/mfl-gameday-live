package com.mflgameday.live.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsFootball
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mflgameday.live.model.FantasyMatchup
import com.mflgameday.live.model.FantasyPlayer
import com.mflgameday.live.model.FranchiseMatchupSide
import com.mflgameday.live.model.GameDaySettings
import com.mflgameday.live.model.GamePhase
import com.mflgameday.live.model.LeagueConfig
import com.mflgameday.live.model.LeagueLiveSnapshot
import com.mflgameday.live.model.NflGameState
import com.mflgameday.live.model.OperatingMode
import com.mflgameday.live.model.PlayerLiveScore
import com.mflgameday.live.service.GameDayCoordinator
import com.mflgameday.live.service.GameDayUiState
import com.mflgameday.live.ui.components.AnimatedScoreText
import com.mflgameday.live.ui.components.FranchiseCrestBadge
import com.mflgameday.live.ui.components.PlayerAvatarRingPolicy
import com.mflgameday.live.ui.components.PlayerHeadshotAvatar
import com.mflgameday.live.ui.components.PlayerMicroDriveStrip
import com.mflgameday.live.ui.components.StadiumTurfCanvasBackdrop
import com.mflgameday.live.ui.theme.LocalGameDayColors
import kotlin.math.roundToInt

@Composable
fun FantasyMatchupsScreen(
  uiState: GameDayUiState,
  coordinator: GameDayCoordinator,
  onNavigateToAddLeague: () -> Unit
) {
  val ext = LocalGameDayColors.current

  if (uiState.settings.operatingMode == OperatingMode.NFL_LIVE_ONLY && uiState.leagues.isEmpty()) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
      Surface(
        color = ext.cardSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
          .width(460.dp)
          .border(1.dp, ext.cardBorder, RoundedCornerShape(20.dp))
      ) {
        Column(
          modifier = Modifier.padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Icon(Icons.Default.SportsFootball, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
          Text(
            text = if (com.mflgameday.live.BuildConfig.IS_PLAY_STORE_BUILD) "Live Scores Broadcast Mode Active" else "NFL Live Broadcast Mode Active",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = if (com.mflgameday.live.BuildConfig.IS_PLAY_STORE_BUILD) {
              "You're currently running standalone Live Score Announcements without a fantasy team. Want to connect one or more MyFantasyLeague leagues?"
            } else {
              "You're currently running standalone NFL Live Announcements without a fantasy team. Want to connect one or more MyFantasyLeague leagues?"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = ext.subtleText
          )
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onNavigateToAddLeague) {
              Icon(Icons.Default.Add, contentDescription = null)
              Spacer(Modifier.width(6.dp))
              Text(if (com.mflgameday.live.BuildConfig.IS_PLAY_STORE_BUILD) "Connect Fantasy League" else "Connect MFL League")
            }
          }
        }
      }
    }
    return
  }

  val activeSnap = uiState.activeLeagueSnapshot
  var selectedMatchupIdx by rememberSaveable(activeSnap?.config?.leagueId) { mutableIntStateOf(0) }

  Column(modifier = Modifier.fillMaxSize()) {
    // Multi-League Pill Switcher
    LeaguePillBar(
      leagues = uiState.leagues,
      snapshots = uiState.leagueSnapshots,
      activeLeagueId = activeSnap?.config?.leagueId ?: uiState.settings.activeLeagueId,
      onSelectLeague = { id ->
        selectedMatchupIdx = 0
        coordinator.selectActiveLeague(id)
      },
      onAddLeague = onNavigateToAddLeague
    )

    if (activeSnap == null || activeSnap.matchups.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
          CircularProgressIndicator()
          Text("Loading live fantasy matchups…", color = ext.subtleText)
        }
      }
      return
    }

    val safeIndex = selectedMatchupIdx.coerceIn(0, activeSnap.matchups.lastIndex)
    val selectedMatchup = activeSnap.matchups[safeIndex]

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val containerWidth = maxWidth
      val isSplitTabletOrFold = containerWidth >= 700.dp
      val leftRailWidth = when {
        containerWidth >= 1080.dp -> 320.dp
        containerWidth >= 880.dp -> 280.dp
        else -> 248.dp
      }

      if (isSplitTabletOrFold) {
        // Master-Detail Split Layout for Pixel 11 Pro Fold Unfolded, Samsung Tab S7, & Desktop!
        Row(modifier = Modifier.fillMaxSize()) {
          LazyColumn(
            modifier = Modifier
              .width(leftRailWidth)
              .fillMaxHeight()
              .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            item {
              LeagueFormatHeaderCard(config = activeSnap.config, week = activeSnap.week)
            }
            itemsIndexed(activeSnap.matchups, key = { _, m -> m.id }) { idx, matchup ->
              MatchupSummaryCard(
                matchup = matchup,
                config = activeSnap.config,
                isSelected = idx == safeIndex,
                showWinProb = uiState.settings.showWinProbabilityAndPace,
                onClick = { selectedMatchupIdx = idx }
              )
            }
          }

          Box(
            modifier = Modifier
              .width(1.dp)
              .fillMaxHeight()
              .background(ext.cardBorder)
          )

          BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val detailPaneWidth = maxWidth
            MatchupDetailBoard(
              matchup = selectedMatchup,
              snapshot = activeSnap,
              playersDb = uiState.playersDb,
              nflGames = uiState.nflGames,
              settings = uiState.settings,
              sideBySideColumns = detailPaneWidth >= 600.dp
            )
          }
        }
      } else {
        // Compact Phone Portrait / Cover Screen Layout
        Column(modifier = Modifier.fillMaxSize()) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            activeSnap.matchups.forEachIndexed { idx, matchup ->
              CompactMatchupPill(
                matchup = matchup,
                config = activeSnap.config,
                isSelected = idx == safeIndex,
                onClick = { selectedMatchupIdx = idx }
              )
            }
          }

          MatchupDetailBoard(
            matchup = selectedMatchup,
            snapshot = activeSnap,
            playersDb = uiState.playersDb,
            nflGames = uiState.nflGames,
            settings = uiState.settings,
            sideBySideColumns = containerWidth >= 600.dp
          )
        }
      }
    }
  }
}

@Composable
private fun LeaguePillBar(
  leagues: List<LeagueConfig>,
  snapshots: Map<String, LeagueLiveSnapshot>,
  activeLeagueId: String?,
  onSelectLeague: (String) -> Unit,
  onAddLeague: () -> Unit
) {
  val ext = LocalGameDayColors.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surface)
      .horizontalScroll(rememberScrollState())
      .padding(horizontal = 12.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    leagues.forEach { cfg ->
      val snap = snapshots[cfg.leagueId]
      val myM = snap?.matchups?.firstOrNull { it.involvesMyFranchise }
      val me = myM?.mySide(cfg.franchiseId)
      val opp = myM?.opponentSide(cfg.franchiseId)
      val fmt = "%.${cfg.precision}f"
      val scoreHint = if (me != null && opp != null) {
        " (${fmt.format(me.score)}-${fmt.format(opp.score)})"
      } else ""
      val label = buildString {
        append(cfg.leagueName)
        if (cfg.isBestBall) append(" · BB")
        append(scoreHint)
      }
      FilterChip(
        selected = cfg.leagueId == activeLeagueId,
        onClick = { onSelectLeague(cfg.leagueId) },
        label = { Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1) }
      )
    }

    AssistChip(
      onClick = onAddLeague,
      leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
      label = { Text("Add League") }
    )
  }
  HorizontalDivider(color = ext.cardBorder.copy(alpha = 0.6f))
}

@Composable
private fun LeagueFormatHeaderCard(config: LeagueConfig, week: Int) {
  val ext = LocalGameDayColors.current
  Surface(
    color = ext.cardElevated,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, ext.cardBorder, RoundedCornerShape(12.dp))
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(config.leagueName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
          text = "Week $week · ${config.franchiseName}" + if (config.isBestBall) " · Optimal ${config.starterCount}" else "",
          style = MaterialTheme.typography.labelSmall,
          color = ext.subtleText
        )
      }
      if (config.isBestBall) {
        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(ext.touchdownGold.copy(alpha = 0.2f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
          Text("BEST BALL", color = ext.touchdownGold, fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
      }
    }
  }
}

private fun orderedMatchupSides(
  matchup: FantasyMatchup,
  myFranchiseId: String
): Pair<FranchiseMatchupSide, FranchiseMatchupSide> {
  return if (matchup.involvesMyFranchise && matchup.home.franchiseId == myFranchiseId) {
    matchup.home to matchup.away
  } else {
    matchup.away to matchup.home
  }
}

private fun shortFranchiseTag(name: String): String {
  val trimmed = name.trim()
  if (trimmed.length <= 12) return trimmed
  return trimmed.take(11).trimEnd() + "…"
}

@Composable
private fun MatchupSummaryCard(
  matchup: FantasyMatchup,
  config: LeagueConfig,
  isSelected: Boolean,
  showWinProb: Boolean,
  onClick: () -> Unit
) {
  val ext = LocalGameDayColors.current
  val fmt = "%.${config.precision}f"
  val (firstSide, secondSide) = orderedMatchupSides(matchup, config.franchiseId)
  val borderColor = when {
    isSelected -> MaterialTheme.colorScheme.primary
    matchup.involvesMyFranchise -> ext.mineBadge.copy(alpha = 0.55f)
    else -> ext.cardBorder
  }

  Surface(
    color = if (isSelected) ext.cardElevated else ext.cardSurface,
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(if (isSelected) 2.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
      .clickable(onClick = onClick)
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      listOf(firstSide, secondSide).forEach { side ->
        val other = if (side == firstSide) secondSide else firstSide
        val isMine = side.franchiseId == config.franchiseId
        val isLeading = side.score >= other.score
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
          FranchiseCrestBadge(
            logoUrl = side.logoUrl,
            franchiseName = side.franchiseName,
            isMine = isMine,
            size = 24.dp
          )
          Text(
            text = if (isMine) "${side.franchiseName} (YOU)" else side.franchiseName,
            fontWeight = if (isMine) FontWeight.ExtraBold else FontWeight.Medium,
            color = if (isMine) ext.mineBadge else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
          )
          Text(
            text = fmt.format(side.score),
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isLeading) FontWeight.ExtraBold else FontWeight.Normal,
            color = if (isLeading) MaterialTheme.colorScheme.onSurface else ext.subtleText
          )
        }
      }

      if (showWinProb) {
        val firstPct = (firstSide.winProbability * 100).roundToInt().coerceIn(0, 100)
        val secondPct = 100 - firstPct
        val barColor = if (matchup.involvesMyFranchise) ext.mineBadge else MaterialTheme.colorScheme.primary
        val trackCol = if (matchup.involvesMyFranchise) ext.opponentBadge.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceVariant
        LinearProgressIndicator(
          progress = { firstSide.winProbability },
          modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(CircleShape),
          color = barColor,
          trackColor = trackCol
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text(
            text = if (matchup.involvesMyFranchise) {
              "YOU $firstPct% · Est ${fmt.format(firstSide.projectedScore)}"
            } else {
              "${shortFranchiseTag(firstSide.franchiseName)} $firstPct%"
            },
            fontSize = 10.sp,
            fontWeight = if (matchup.involvesMyFranchise) FontWeight.Bold else FontWeight.Medium,
            color = if (matchup.involvesMyFranchise) ext.mineBadge else ext.subtleText,
            maxLines = 1
          )
          Text(
            text = if (matchup.involvesMyFranchise) {
              "OPP $secondPct%"
            } else {
              "${shortFranchiseTag(secondSide.franchiseName)} $secondPct%"
            },
            fontSize = 10.sp,
            color = ext.subtleText,
            maxLines = 1
          )
        }
      }
    }
  }
}

@Composable
private fun CompactMatchupPill(
  matchup: FantasyMatchup,
  config: LeagueConfig,
  isSelected: Boolean,
  onClick: () -> Unit
) {
  val ext = LocalGameDayColors.current
  val fmt = "%.${config.precision}f"
  val (leftSide, rightSide) = orderedMatchupSides(matchup, config.franchiseId)
  Surface(
    color = if (isSelected) ext.cardElevated else ext.cardSurface,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier
      .width(185.dp)
      .border(
        width = if (isSelected) 2.dp else 1.dp,
        color = if (isSelected) MaterialTheme.colorScheme.primary else ext.cardBorder,
        shape = RoundedCornerShape(12.dp)
      )
      .clickable(onClick = onClick)
  ) {
    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      listOf(leftSide, rightSide).forEach { side ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = side.franchiseName,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (side.franchiseId == config.franchiseId) FontWeight.ExtraBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
          )
          Spacer(Modifier.width(4.dp))
          Text(
            text = fmt.format(side.score),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
          )
        }
      }
    }
  }
}

@Composable
private fun MatchupDetailBoard(
  matchup: FantasyMatchup,
  snapshot: LeagueLiveSnapshot,
  playersDb: Map<String, FantasyPlayer>,
  nflGames: List<NflGameState>,
  settings: GameDaySettings,
  sideBySideColumns: Boolean
) {
  val ext = LocalGameDayColors.current
  val (leftSide, rightSide) = orderedMatchupSides(matchup, snapshot.config.franchiseId)
  var compactTeamTab by rememberSaveable(matchup.id) { mutableIntStateOf(0) }
  val gamesByTeam = remember(nflGames) {
    val map = HashMap<String, NflGameState>()
    nflGames.forEach { g ->
      map[g.away.team.code] = g
      map[g.home.team.code] = g
    }
    map
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    // Head-to-Head Scoreboard Header + Live Win Probability & Pace Banner
    MatchupHeroHeader(
      leftSide = leftSide,
      rightSide = rightSide,
      involvesMyFranchise = matchup.involvesMyFranchise,
      config = snapshot.config,
      showWinProb = settings.showWinProbabilityAndPace
    )

    if (sideBySideColumns) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
      ) {
        TeamLineupColumn(
          modifier = Modifier.weight(1f),
          side = leftSide,
          isLeading = leftSide.score >= rightSide.score,
          isMyTeam = leftSide.franchiseId == snapshot.config.franchiseId,
          config = snapshot.config,
          playersDb = playersDb,
          gamesByTeam = gamesByTeam,
          settings = settings
        )
        TeamLineupColumn(
          modifier = Modifier.weight(1f),
          side = rightSide,
          isLeading = rightSide.score >= leftSide.score,
          isMyTeam = rightSide.franchiseId == snapshot.config.franchiseId,
          config = snapshot.config,
          playersDb = playersDb,
          gamesByTeam = gamesByTeam,
          settings = settings
        )
      }
    } else {
      // Phone portrait quick toggle between Left / Right
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        FilterChip(
          selected = compactTeamTab == 0,
          onClick = { compactTeamTab = 0 },
          label = { Text(leftSide.franchiseName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
          modifier = Modifier.weight(1f)
        )
        FilterChip(
          selected = compactTeamTab == 1,
          onClick = { compactTeamTab = 1 },
          label = { Text(rightSide.franchiseName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
          modifier = Modifier.weight(1f)
        )
      }
      val activeSide = if (compactTeamTab == 0) leftSide else rightSide
      val otherSide = if (compactTeamTab == 0) rightSide else leftSide
      TeamLineupColumn(
        modifier = Modifier.fillMaxWidth(),
        side = activeSide,
        isLeading = activeSide.score >= otherSide.score,
        isMyTeam = activeSide.franchiseId == snapshot.config.franchiseId,
        config = snapshot.config,
        playersDb = playersDb,
        gamesByTeam = gamesByTeam,
        settings = settings
      )
    }
  }
}

@Composable
private fun MatchupHeroHeader(
  leftSide: FranchiseMatchupSide,
  rightSide: FranchiseMatchupSide,
  involvesMyFranchise: Boolean,
  config: LeagueConfig,
  showWinProb: Boolean
) {
  val ext = LocalGameDayColors.current
  val fmt = "%.${config.precision}f"
  val leftIsMine = leftSide.franchiseId == config.franchiseId

  Surface(
    color = ext.cardElevated,
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, ext.cardBorder, RoundedCornerShape(16.dp))
  ) {
    Box(
      modifier = Modifier.background(
        Brush.horizontalGradient(
          listOf(ext.headerGradientStart, ext.headerGradientEnd)
        )
      )
    ) {
      StadiumTurfCanvasBackdrop(
        accentGlow = ext.turfAccent,
        modifier = Modifier.matchParentSize()
      )
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        HeroTeamSide(side = leftSide, isMine = leftIsMine, fmt = fmt, alignEnd = false, modifier = Modifier.weight(1f))
        Text(
          text = "VS",
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Black,
          color = ext.subtleText,
          modifier = Modifier.padding(horizontal = 10.dp)
        )
        HeroTeamSide(
          side = rightSide,
          isMine = rightSide.franchiseId == config.franchiseId,
          fmt = fmt,
          alignEnd = true,
          modifier = Modifier.weight(1f)
        )
      }

      if (showWinProb) {
        val leftWin = (leftSide.winProbability * 100).roundToInt().coerceIn(0, 100)
        val rightWin = 100 - leftWin
        val leftBarColor = if (involvesMyFranchise) ext.mineBadge else MaterialTheme.colorScheme.primary
        val rightBarColor = if (involvesMyFranchise) ext.opponentBadge.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceVariant
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
              text = "${if (leftIsMine) "YOU · " else "${shortFranchiseTag(leftSide.franchiseName)} · "}$leftWin% Win · Est ${fmt.format(leftSide.projectedScore)}",
              style = MaterialTheme.typography.labelSmall,
              color = if (leftIsMine) ext.mineBadge else MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "Est ${fmt.format(rightSide.projectedScore)} · $rightWin% Win · ${if (involvesMyFranchise) "OPP" else shortFranchiseTag(rightSide.franchiseName)}",
              style = MaterialTheme.typography.labelSmall,
              color = ext.subtleText,
              fontWeight = FontWeight.SemiBold
            )
          }
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(7.dp)
              .clip(CircleShape)
              .background(rightBarColor)
          ) {
            Box(
              modifier = Modifier
                .fillMaxWidth(leftSide.winProbability)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(leftBarColor)
            )
          }
        }
      }
      }
    }
  }
}

@Composable
private fun HeroTeamSide(
  side: FranchiseMatchupSide,
  isMine: Boolean,
  fmt: String,
  alignEnd: Boolean,
  modifier: Modifier = Modifier
) {
  val ext = LocalGameDayColors.current
  Column(
    modifier = modifier,
    horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      if (!alignEnd) {
        FranchiseCrestBadge(
          logoUrl = side.logoUrl,
          franchiseName = side.franchiseName,
          isMine = isMine,
          size = 40.dp
        )
      }
      Text(
        text = if (isMine) "${side.franchiseName} (YOU)" else side.franchiseName,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = if (isMine) ext.mineBadge else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false)
      )
      if (alignEnd) {
        FranchiseCrestBadge(
          logoUrl = side.logoUrl,
          franchiseName = side.franchiseName,
          isMine = isMine,
          size = 40.dp
        )
      }
    }
    Row(
      verticalAlignment = Alignment.Bottom,
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      AnimatedScoreText(
        text = fmt.format(side.score),
        style = MaterialTheme.typography.headlineMedium.copy(
          fontSize = 28.sp,
          fontWeight = FontWeight.Black,
          fontFamily = FontFamily.Monospace
        ),
        color = MaterialTheme.colorScheme.onSurface
      )
      if (side.recentScoreDelta > 0.01) {
        Text(
          text = "+${fmt.format(side.recentScoreDelta)}",
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.ExtraBold,
          color = ext.turfAccent,
          modifier = Modifier.padding(bottom = 4.dp)
        )
      }
    }
    Text(
      text = "${side.playersCurrentlyPlaying} live · ${side.playersYetToPlay} yet · ${side.playersFinal} final",
      style = MaterialTheme.typography.labelSmall,
      color = ext.subtleText
    )
  }
}

@Composable
private fun TeamLineupColumn(
  modifier: Modifier,
  side: FranchiseMatchupSide,
  isLeading: Boolean,
  isMyTeam: Boolean,
  config: LeagueConfig,
  playersDb: Map<String, FantasyPlayer>,
  gamesByTeam: Map<String, NflGameState>,
  settings: GameDaySettings
) {
  val ext = LocalGameDayColors.current
  var expandBench by rememberSaveable(side.franchiseId) { mutableStateOf(config.isBestBall) }
  val fmt = "%.${config.precision}f"

  val visibleStarters = if (settings.onlyPlayersWithStats) {
    side.starters.filter { it.score != 0.0 || it.breakdowns.isNotEmpty() }
  } else {
    side.starters
  }

  Surface(
    color = ext.cardSurface,
    shape = RoundedCornerShape(16.dp),
    modifier = modifier.border(
      width = 1.dp,
      color = if (isMyTeam) ext.mineBadge.copy(alpha = 0.5f) else ext.cardBorder,
      shape = RoundedCornerShape(16.dp)
    )
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            text = if (config.isBestBall) "Optimal Counting (${visibleStarters.size})" else "Active Starters (${visibleStarters.size})",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
            color = if (config.isBestBall) ext.touchdownGold else MaterialTheme.colorScheme.primary
          )
          if (config.isBestBall) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = ext.touchdownGold, modifier = Modifier.size(14.dp))
          }
        }
        if (settings.showGameMinutesProgress) {
          val remMin = side.gameSecondsRemaining / 60
          Text(
            text = "${remMin}m left",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = ext.subtleText
          )
        }
      }

      // Bench Swing Insights (Standard Leagues)
      if (!config.isBestBall && side.benchInsights.isNotEmpty()) {
        side.benchInsights.forEach { insight ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(ext.opponentBadge.copy(alpha = 0.14f))
              .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = null, tint = ext.opponentBadge, modifier = Modifier.size(14.dp))
            Text(
              text = "Bench Outscored Starter: ${insight.benchPlayerName} (${fmt.format(insight.benchScore)}) beat ${insight.targetStarterName} (${fmt.format(insight.starterScore)}) by +${fmt.format(insight.pointSwing)}",
              style = MaterialTheme.typography.labelSmall,
              color = ext.opponentBadge,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }

      HorizontalDivider(color = ext.cardBorder)

      visibleStarters.forEach { pScore ->
        PlayerScoreRow(
          playerScore = pScore,
          player = playersDb[pScore.playerId],
          game = playersDb[pScore.playerId]?.let { gamesByTeam[it.team.code] },
          fmt = fmt,
          settings = settings,
          isBestBall = config.isBestBall,
          isMyTeam = isMyTeam
        )
      }

      if (settings.showBenchAndContenders && side.bench.isNotEmpty()) {
        HorizontalDivider(color = ext.cardBorder.copy(alpha = 0.6f))
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { expandBench = !expandBench }
            .padding(vertical = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          val chasers = side.bench.count { it.canCrackOptimal }
          Text(
            text = if (config.isBestBall) {
              "Roster Contenders (${side.bench.size}" + (if (chasers > 0) " · $chasers Live Chasers" else "") + ")"
            } else {
              "Bench (${side.bench.size})"
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = ext.subtleText
          )
          Icon(
            imageVector = if (expandBench) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = ext.subtleText
          )
        }

        AnimatedVisibility(visible = expandBench) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            side.bench.forEach { bScore ->
              PlayerScoreRow(
                playerScore = bScore,
                player = playersDb[bScore.playerId],
                game = playersDb[bScore.playerId]?.let { gamesByTeam[it.team.code] },
                fmt = fmt,
                settings = settings,
                isBestBall = config.isBestBall,
                isMyTeam = isMyTeam
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun PlayerScoreRow(
  playerScore: PlayerLiveScore,
  player: FantasyPlayer?,
  game: NflGameState?,
  fmt: String,
  settings: GameDaySettings,
  isBestBall: Boolean,
  isMyTeam: Boolean = false
) {
  val ext = LocalGameDayColors.current
  val hasHighlight = playerScore.scoreDeltaRecent > 0.01
  val rowBg by animateColorAsState(
    targetValue = if (hasHighlight) ext.turfAccent.copy(alpha = 0.16f) else Color.Transparent,
    animationSpec = tween(500),
    label = "player_flash"
  )

  val isDefense = player?.isDefensiveSide == true
  val inRz = player != null && game != null && game.phase == GamePhase.LIVE && !isDefense && game.possessionTeam == player.team && game.inRedZone
  val hasBall = player != null && game != null && game.phase == GamePhase.LIVE && !isDefense && game.possessionTeam == player.team
  val isDefendingField = player != null && game != null && game.phase == GamePhase.LIVE && isDefense && game.possessionTeam != null && game.possessionTeam != player.team
  val inGoalLineStand = isDefendingField && game != null && game.inRedZone

  val ringStatus = PlayerAvatarRingPolicy.resolve(
    player = player,
    game = game,
    gameSecondsRemaining = playerScore.gameSecondsRemaining
  )

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(rowBg)
      .padding(horizontal = 4.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(9.dp)
  ) {
    // 52dp 3D Claymation Player / Defense / Coach / IDP Avatar + Pulsing Red Zone Ring
    PlayerHeadshotAvatar(player = player, size = 52.dp, ringStatus = ringStatus)

    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
      ) {
        Text(
          text = player?.displayName ?: "Player #${playerScore.playerId}",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        if (hasBall) {
          Icon(
            imageVector = Icons.Default.SportsFootball,
            contentDescription = if (inRz) "In Red Zone" else "Has Possession",
            tint = if (inRz) ext.redZoneAlert else ext.touchdownGold,
            modifier = Modifier.size(13.dp)
          )
        } else if (isDefendingField) {
          Icon(
            imageVector = Icons.Default.Shield,
            contentDescription = if (inGoalLineStand) "Goal-Line Stand" else "Defense On Field",
            tint = if (inGoalLineStand) ext.touchdownGold else ext.turfAccent,
            modifier = Modifier.size(13.dp)
          )
        }
        if (isBestBall && playerScore.isOptimalBubble) {
          Text(
            text = "BUBBLE",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = ext.opponentBadge
          )
        } else if (isBestBall && playerScore.canCrackOptimal) {
          Text(
            text = "CHASING",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = ext.turfAccent
          )
        }
      }

      // NFL Score + Game Clock line
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        if (player != null && game != null && game.phase != GamePhase.SCHEDULED) {
          val awayEmph = game.away.team == player.team
          Text(
            text = "${game.away.team.code} ${game.away.score}, ${game.home.team.code} ${game.home.score}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (awayEmph || game.home.team == player.team) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
          )
        } else {
          Text(
            text = player?.team?.code ?: "",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = ext.subtleText,
            maxLines = 1,
            softWrap = false
          )
        }

        val statusTxt = game?.statusDisplay() ?: if (playerScore.gameSecondsRemaining == 0) "Final/Bye" else "Upcoming"
        Text(
          text = statusTxt,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = FontFamily.Monospace,
          color = if (game?.phase == GamePhase.LIVE) ext.turfAccent else ext.subtleText,
          maxLines = 1,
          softWrap = false
        )
      }

      // Category Scoring Breakdowns ("235 Passing Yards 9.40 · 2 Passing TDs 8.00")
      if (settings.showScoringBreakdowns && playerScore.breakdowns.isNotEmpty()) {
        val nonZero = playerScore.breakdowns.filter { it.points != 0.0 }
        if (nonZero.isNotEmpty()) {
          Text(
            text = nonZero.joinToString("  ·  ") { b ->
              "${b.count} ${b.category} ${"%.2f".format(b.points)}"
            },
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            color = ext.subtleText
          )
        }
      }

      if (hasBall && game != null) {
        PlayerMicroDriveStrip(
          game = game,
          modifier = Modifier.fillMaxWidth()
        )
      }
    }

    Column(horizontalAlignment = Alignment.End) {
      Text(
        text = fmt.format(playerScore.score),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        color = if (hasHighlight) ext.turfAccent else MaterialTheme.colorScheme.onSurface
      )
      when {
        hasHighlight -> {
          Text(
            text = "+${fmt.format(playerScore.scoreDeltaRecent)}",
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ext.turfAccent
          )
        }
        settings.showWinProbabilityAndPace && playerScore.isYetToPlay && playerScore.preGameProjection > 0.05 -> {
          Text(
            text = "Proj ${fmt.format(playerScore.preGameProjection)}",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = ext.subtleText
          )
        }
        settings.showWinProbabilityAndPace && playerScore.isPlayingNow && playerScore.livePaceProjection > playerScore.score + 0.05 -> {
          Text(
            text = "Est ${fmt.format(playerScore.livePaceProjection)}",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = ext.turfAccent.copy(alpha = 0.85f)
          )
        }
      }
    }
  }
}
