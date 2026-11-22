package com.mflgameday.live.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mflgameday.live.model.ExposureRole
import com.mflgameday.live.model.PlayLogEntry
import com.mflgameday.live.model.SpokenCategory
import com.mflgameday.live.service.GameDayCoordinator
import com.mflgameday.live.service.GameDayUiState
import com.mflgameday.live.ui.components.ExposurePill
import com.mflgameday.live.ui.components.TeamShieldBadge
import com.mflgameday.live.ui.theme.LocalGameDayColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class FeedFilter(val label: String) {
  ALL("All Plays"),
  SCORES("Scores Only"),
  BIG_DEFENSE("Turnovers & Sacks"),
  RED_ZONE("Red Zone"),
  MY_LEAGUES("My Lineups")
}

@Composable
fun PlayFeedScreen(
  uiState: GameDayUiState,
  coordinator: GameDayCoordinator,
  compactSideRailMode: Boolean = false
) {
  val ext = LocalGameDayColors.current
  var filter by rememberSaveable { mutableStateOf(FeedFilter.ALL) }
  val timeFmt = remember { DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault()) }

  val filtered = remember(uiState.playLog, filter) {
    uiState.playLog.filter { entry ->
      val cat = SpokenCategory.forEvent(entry.event.kind)
      when (filter) {
        FeedFilter.ALL -> true
        FeedFilter.SCORES -> cat == SpokenCategory.TOUCHDOWNS ||
          cat == SpokenCategory.FIELD_GOALS ||
          cat == SpokenCategory.FANTASY_SWINGS
        FeedFilter.BIG_DEFENSE -> cat == SpokenCategory.TURNOVERS || cat == SpokenCategory.SACKS
        FeedFilter.RED_ZONE -> cat == SpokenCategory.RED_ZONE
        FeedFilter.MY_LEAGUES -> entry.involvesMine || entry.involvesOpponent || entry.isConflictPlay
      }
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    if (!compactSideRailMode) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surface)
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        FeedFilter.entries.forEach { f ->
          FilterChip(
            selected = filter == f,
            onClick = { filter = f },
            label = { Text(f.label) }
          )
        }

        AssistChip(
          onClick = { coordinator.simulateNextLivePlay() },
          leadingIcon = { Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp)) },
          label = { Text("Simulate Live Play") }
        )

        if (!uiState.lastSpokenText.isNullOrBlank()) {
          AssistChip(
            onClick = { coordinator.voiceEngine.replayLastCall(uiState.settings) },
            leadingIcon = { Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp)) },
            label = { Text("Replay Last Call") }
          )
        }
      }
      HorizontalDivider(color = ext.cardBorder.copy(alpha = 0.6f))
    } else {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("LIVE ANNOUNCEMENT TICKER", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black)
        Text("${uiState.playLog.size} calls", style = MaterialTheme.typography.labelSmall, color = ext.subtleText)
      }
      HorizontalDivider(color = ext.cardBorder.copy(alpha = 0.6f))
    }

    if (filtered.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Waiting for live scoring & play-by-play…", fontWeight = FontWeight.SemiBold)
          Text(
            text = "Tap 'Simulate Live Play' anytime to test visual alerts and spoken announcements!",
            style = MaterialTheme.typography.bodySmall,
            color = ext.subtleText
          )
        }
      }
    } else {
      LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
      ) {
        items(filtered, key = { it.id }) { entry ->
          PlayLogCard(
            entry = entry,
            timeText = timeFmt.format(Instant.ofEpochMilli(entry.timestampMillis)),
            onSpeakNow = {
              coordinator.voiceEngine.speakImmediateSample(
                sampleText = entry.spokenText,
                settings = uiState.settings,
                sampleKind = entry.event.kind
              )
            }
          )
        }
      }
    }
  }
}

@Composable
private fun PlayLogCard(
  entry: PlayLogEntry,
  timeText: String,
  onSpeakNow: () -> Unit
) {
  val ext = LocalGameDayColors.current
  val category = SpokenCategory.forEvent(entry.event.kind)
  val (icon, tint) = when (category) {
    SpokenCategory.TOUCHDOWNS -> Icons.Default.EmojiEvents to ext.touchdownGold
    SpokenCategory.FIELD_GOALS -> Icons.Default.Flag to ext.turfAccent
    SpokenCategory.TURNOVERS -> Icons.Default.SyncAlt to ext.opponentBadge
    SpokenCategory.SACKS -> Icons.Default.Bolt to ext.conflictBadge
    SpokenCategory.RED_ZONE -> Icons.Default.Warning to ext.redZoneAlert
    SpokenCategory.FANTASY_SWINGS -> Icons.Default.EmojiEvents to ext.mineBadge
    else -> Icons.Default.Schedule to ext.subtleText
  }
  val shortTag = when (category) {
    SpokenCategory.TOUCHDOWNS -> "TD"
    SpokenCategory.FIELD_GOALS -> "FG"
    SpokenCategory.TURNOVERS -> "TO"
    SpokenCategory.SACKS -> "SACK"
    SpokenCategory.RED_ZONE -> "RZ"
    SpokenCategory.FANTASY_SWINGS -> "LEAD"
    else -> "LIVE"
  }
  val playSide = entry.event.team

  Surface(
    color = ext.cardSurface,
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(
        width = 1.dp,
        color = if (entry.involvesMine) ext.mineBadge.copy(alpha = 0.55f) else ext.cardBorder,
        shape = RoundedCornerShape(14.dp)
      )
      .clickable(onClick = onSpeakNow)
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
      ) {
        if (playSide != null) {
          TeamShieldBadge(
            team = playSide.team,
            size = 42.dp,
            hasPossession = category == SpokenCategory.TOUCHDOWNS || category == SpokenCategory.RED_ZONE,
            inRedZone = category == SpokenCategory.RED_ZONE,
            logoUrlOverride = playSide.logoUrl
          )
        } else {
          Box(
            modifier = Modifier
              .size(42.dp)
              .clip(CircleShape)
              .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(icon, contentDescription = category.label, tint = tint, modifier = Modifier.size(20.dp))
          }
        }
        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(tint.copy(alpha = 0.18f))
            .border(0.75.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = shortTag,
            fontSize = 8.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            color = tint
          )
        }
      }

      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Text(
            text = timeText,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = ext.subtleText
          )
          if (entry.isConflictPlay) {
            ExposurePill(ExposureRole.CONFLICT)
          } else if (entry.involvesMine) {
            ExposurePill(ExposureRole.MINE)
          } else if (entry.involvesOpponent) {
            ExposurePill(ExposureRole.OPPONENT)
          }
          if (entry.leagueTags.isNotEmpty()) {
            Text(
              text = entry.leagueTags.joinToString(" · "),
              fontSize = 10.sp,
              color = ext.subtleText,
              fontWeight = FontWeight.Medium
            )
          }
        }

        Text(
          text = entry.spokenText,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = if (entry.involvesMine || category == SpokenCategory.TOUCHDOWNS) FontWeight.SemiBold else FontWeight.Normal,
          color = MaterialTheme.colorScheme.onSurface
        )
      }

      IconButton(onClick = onSpeakNow, modifier = Modifier.size(34.dp)) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.VolumeUp,
          contentDescription = "Speak announcement",
          tint = if (entry.wasSpoken) MaterialTheme.colorScheme.primary else ext.subtleText,
          modifier = Modifier.size(18.dp)
        )
      }
    }
  }
}
