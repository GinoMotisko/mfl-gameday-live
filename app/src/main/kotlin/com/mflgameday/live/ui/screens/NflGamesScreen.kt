package com.mflgameday.live.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsFootball
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mflgameday.live.model.ExposureRole
import com.mflgameday.live.model.GamePhase
import com.mflgameday.live.model.LiveFieldSituation
import com.mflgameday.live.model.NflGameState
import com.mflgameday.live.model.NflSide
import com.mflgameday.live.model.PlayLogEntry
import com.mflgameday.live.model.PlayerCrossLeagueExposure
import com.mflgameday.live.service.GameDayCoordinator
import com.mflgameday.live.service.GameDayUiState
import com.mflgameday.live.ui.components.AnimatedScoreText
import com.mflgameday.live.ui.components.ExposurePill
import com.mflgameday.live.ui.components.GooglePinnedLinearField
import com.mflgameday.live.ui.components.RedZonePulseBadge
import com.mflgameday.live.ui.components.SpecialTeamsStatusBadge
import com.mflgameday.live.ui.components.TeamShieldBadge
import com.mflgameday.live.ui.theme.LocalGameDayColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class NflFilter(val label: String) {
  ALL("All Games"),
  LIVE("Live Now"),
  RED_ZONE("Red Zone"),
  MY_PLAYERS("My Starters"),
  FINAL_UPCOMING("Final & Later")
}

@Composable
fun NflGamesScreen(
  uiState: GameDayUiState,
  coordinator: GameDayCoordinator
) {
  val ext = LocalGameDayColors.current
  var filter by rememberSaveable { mutableStateOf(NflFilter.ALL) }
  var inspectedGameId by rememberSaveable { mutableStateOf<String?>(null) }

  val kickoffFmt = remember { DateTimeFormatter.ofPattern("EEE h:mm a").withZone(ZoneId.systemDefault()) }

  val filteredGames = remember(uiState.nflGames, filter, uiState.crossLeagueExposures) {
    uiState.nflGames.filter { game ->
      when (filter) {
        NflFilter.ALL -> true
        NflFilter.LIVE -> game.phase == GamePhase.LIVE || game.phase == GamePhase.HALFTIME
        NflFilter.RED_ZONE -> game.effectiveInRedZone
        NflFilter.MY_PLAYERS -> {
          val gameTeams = game.teams.toSet()
          uiState.crossLeagueExposures.values.any { it.player.team in gameTeams }
        }
        NflFilter.FINAL_UPCOMING -> game.phase == GamePhase.FINAL || game.phase == GamePhase.SCHEDULED
      }
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    // Filter & Schedule Refresh Action Bar
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surface)
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      NflFilter.entries.forEach { f ->
        FilterChip(
          selected = filter == f,
          onClick = { filter = f },
          label = { Text(f.label) }
        )
      }

      AssistChip(
        onClick = { coordinator.refreshNflScheduleAndPlayers() },
        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp)) },
        label = {
          Text(
            if (com.mflgameday.live.BuildConfig.IS_PLAY_STORE_BUILD) "Refresh Schedule" else "Refresh NFL Schedule"
          )
        }
      )

      AssistChip(
        onClick = { coordinator.simulateNextLivePlay() },
        leadingIcon = { Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp)) },
        label = { Text("Test Live Call") }
      )
    }
    HorizontalDivider(color = ext.cardBorder.copy(alpha = 0.6f))

    if (filteredGames.isEmpty()) {
      val sportTag = if (com.mflgameday.live.BuildConfig.IS_PLAY_STORE_BUILD) "live" else "NFL"
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
          text = if (uiState.nflGames.isEmpty()) "Loading $sportTag scoreboard…" else "No $sportTag games match '${filter.label}'.",
          color = ext.subtleText
        )
      }
    } else {
      BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gridWidth = maxWidth
        val gridCells = if (gridWidth >= 660.dp) {
          GridCells.Adaptive(minSize = 330.dp)
        } else {
          GridCells.Fixed(1)
        }
        LazyVerticalGrid(
          columns = gridCells,
          contentPadding = PaddingValues(12.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier.fillMaxSize()
        ) {
          items(filteredGames, key = { it.id }) { game ->
            val gameTeams = game.teams.toSet()
            val exposuresInGame = uiState.crossLeagueExposures.values.filter { it.player.team in gameTeams }
            val kickoffStr = game.kickoffEpochMillis?.let { kickoffFmt.format(Instant.ofEpochMilli(it)) }
            Box(modifier = Modifier.animateItem()) {
              NflBroadcastCard(
                game = game,
                kickoffFormatted = kickoffStr,
                exposures = exposuresInGame,
                showRootingBadges = uiState.settings.showCrossLeagueRootingBadges,
                onClick = { inspectedGameId = game.id }
              )
            }
          }
        }
      }
    }
  }

  val inspectedGame = inspectedGameId?.let { id -> uiState.nflGames.firstOrNull { it.id == id } }
  if (inspectedGame != null) {
    val gameEvents = uiState.playLog.filter {
      it.event.game.id == inspectedGame.id ||
        (it.event.game.away.team == inspectedGame.away.team && it.event.game.home.team == inspectedGame.home.team)
    }
    GameCenterDetailDialog(
      game = inspectedGame,
      events = gameEvents,
      onSpeakPlay = { entry ->
        coordinator.voiceEngine.speakImmediateSample(entry.spokenText, uiState.settings, entry.event.kind)
      },
      onDismiss = { inspectedGameId = null }
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NflBroadcastCard(
  game: NflGameState,
  kickoffFormatted: String?,
  exposures: List<PlayerCrossLeagueExposure>,
  showRootingBadges: Boolean,
  onClick: () -> Unit
) {
  val ext = LocalGameDayColors.current
  val hasMine = exposures.any { it.role == ExposureRole.MINE || it.role == ExposureRole.CONFLICT }
  val borderColor = when {
    game.effectiveInRedZone -> ext.redZoneAlert
    game.phase == GamePhase.LIVE && game.fieldSituation == LiveFieldSituation.PAT_PENDING -> ext.touchdownGold.copy(alpha = 0.85f)
    hasMine -> ext.mineBadge.copy(alpha = 0.7f)
    else -> ext.cardBorder
  }

  Surface(
    color = ext.cardSurface,
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(if (game.effectiveInRedZone) 2.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
      .clickable(onClick = onClick)
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      NflSideScoreRow(side = game.away, game = game)
      NflSideScoreRow(side = game.home, game = game)

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = game.statusDisplay(kickoffFormatted),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (game.phase == GamePhase.LIVE) ext.turfAccent else ext.subtleText
          )
          if (game.broadcast.isNotBlank()) {
            Text(
              text = "· ${game.broadcast}",
              style = MaterialTheme.typography.labelSmall,
              color = ext.subtleText
            )
          }
        }

        when {
          game.effectiveInRedZone -> {
            val rzTeam = game.possessionTeam?.code ?: ""
            RedZonePulseBadge(teamCode = rzTeam)
          }
          game.phase == GamePhase.LIVE && game.fieldSituation != LiveFieldSituation.SCRIMMAGE_DRIVE -> {
            val kickingOrTryCode = game.possessionTeam?.code ?: ""
            SpecialTeamsStatusBadge(
              situation = game.fieldSituation,
              teamCode = kickingOrTryCode
            )
          }
        }
      }

      if (game.phase == GamePhase.LIVE && game.fieldProgressFraction != null) {
        GooglePinnedLinearField(
          game = game,
          fieldHeight = 40.dp,
          modifier = Modifier.fillMaxWidth()
        )
      }

      if (game.lastPlayText.isNotBlank() && game.phase == GamePhase.LIVE) {
        Text(
          text = game.lastPlayText,
          style = MaterialTheme.typography.bodySmall,
          fontSize = 11.sp,
          color = ext.subtleText,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
      }

      if (showRootingBadges && exposures.isNotEmpty()) {
        HorizontalDivider(color = ext.cardBorder.copy(alpha = 0.55f))
        val mine = exposures.filter { it.role == ExposureRole.MINE }
        val conflict = exposures.filter { it.role == ExposureRole.CONFLICT }
        val opp = exposures.filter { it.role == ExposureRole.OPPONENT }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          if (mine.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
              ExposurePill(ExposureRole.MINE)
              Text(
                text = mine.joinToString(", ") { it.player.displayName },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
              )
            }
          }
          if (conflict.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
              ExposurePill(ExposureRole.CONFLICT)
              Text(
                text = conflict.joinToString(", ") { it.player.displayName },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
              )
            }
          }
          if (opp.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
              ExposurePill(ExposureRole.OPPONENT)
              Text(
                text = opp.joinToString(", ") { it.player.displayName },
                style = MaterialTheme.typography.labelSmall,
                color = ext.subtleText
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun NflSideScoreRow(side: NflSide, game: NflGameState) {
  val ext = LocalGameDayColors.current
  val hasBall = game.phase == GamePhase.LIVE &&
    game.fieldSituation != LiveFieldSituation.KICKOFF_PENDING &&
    game.possessionEspnId == side.espnId
  val isKickingOff = game.phase == GamePhase.LIVE &&
    game.fieldSituation == LiveFieldSituation.KICKOFF_PENDING &&
    game.possessionEspnId == side.espnId
  val otherScore = if (side.espnId == game.away.espnId) game.home.score else game.away.score
  val winning = side.score > otherScore

  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    TeamShieldBadge(
      team = side.team,
      size = 48.dp,
      hasPossession = hasBall,
      inRedZone = game.effectiveInRedZone,
      logoUrlOverride = side.logoUrl
    )

    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
          text = side.fullName,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = if (winning || game.phase == GamePhase.LIVE) FontWeight.Bold else FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        if (hasBall) {
          Icon(
            imageVector = Icons.Default.SportsFootball,
            contentDescription = "Possession",
            tint = if (game.effectiveInRedZone) ext.redZoneAlert else ext.touchdownGold,
            modifier = Modifier.size(14.dp)
          )
        } else if (isKickingOff) {
          Text(
            text = "KICKING",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold,
            color = ext.turfAccent
          )
        }
      }
      if (side.record.isNotBlank()) {
        Text(text = side.record, style = MaterialTheme.typography.labelSmall, color = ext.subtleText)
      }
    }

    if (game.phase != GamePhase.SCHEDULED) {
      AnimatedScoreText(
        text = side.score.toString(),
        style = MaterialTheme.typography.headlineSmall.copy(
          fontSize = 24.sp,
          fontWeight = FontWeight.Black,
          fontFamily = FontFamily.Monospace
        ),
        color = if (winning || game.phase != GamePhase.FINAL) MaterialTheme.colorScheme.onSurface else ext.subtleText
      )
    }
  }
}

@Composable
private fun GameCenterDetailDialog(
  game: NflGameState,
  events: List<PlayLogEntry>,
  onSpeakPlay: (PlayLogEntry) -> Unit,
  onDismiss: () -> Unit
) {
  val ext = LocalGameDayColors.current
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        TeamShieldBadge(team = game.away.team, size = 44.dp, logoUrlOverride = game.away.logoUrl)
        Column(modifier = Modifier.weight(1f)) {
          Text("${game.away.fullName} at ${game.home.fullName}", fontWeight = FontWeight.ExtraBold)
          Text(
            text = "${game.away.team.code} ${game.away.score} – ${game.home.team.code} ${game.home.score} · ${game.statusDisplay()}" +
              if (game.venue.isNotBlank()) " · ${game.venue}" else "",
            style = MaterialTheme.typography.labelMedium,
            color = ext.subtleText
          )
        }
        TeamShieldBadge(team = game.home.team, size = 44.dp, logoUrlOverride = game.home.logoUrl)
      }
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (game.phase == GamePhase.LIVE && game.fieldProgressFraction != null) {
          GooglePinnedLinearField(
            game = game,
            fieldHeight = 54.dp,
            modifier = Modifier.fillMaxWidth()
          )
        } else if (game.downDistanceText.isNotBlank()) {
          Text(
            text = "Situation: ${game.downDistanceText}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = ext.turfAccent
          )
        }
        if (game.lastPlayText.isNotBlank()) {
          Text(
            text = "Latest Play: ${game.lastPlayText}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        HorizontalDivider(color = ext.cardBorder)
        Text("Game Announcements (${events.size})", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        if (events.isEmpty()) {
          Text("No scoring/turnover alerts logged for this game yet during this session.", style = MaterialTheme.typography.bodySmall, color = ext.subtleText)
        } else {
          LazyColumn(
            modifier = Modifier.height(240.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            items(events, key = { it.id }) { entry ->
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(8.dp))
                  .background(ext.cardElevated)
                  .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = entry.spokenText,
                  style = MaterialTheme.typography.bodySmall,
                  modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onSpeakPlay(entry) }) {
                  Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Speak play", tint = MaterialTheme.colorScheme.primary)
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Close")
      }
    }
  )
}
