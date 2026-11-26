package com.mflgameday.live.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mflgameday.live.model.AnnouncementScope
import com.mflgameday.live.model.BroadcastPreset
import com.mflgameday.live.model.SpokenCategory
import com.mflgameday.live.model.VoicePersona
import com.mflgameday.live.service.GameDayCoordinator
import com.mflgameday.live.service.GameDayUiState
import com.mflgameday.live.ui.theme.LocalGameDayColors

enum class SettingsCategory(
  val title: String,
  val emoji: String,
  val icon: ImageVector
) {
  ANNOUNCER_VOICE("Announcer & Voice", "🎙️", Icons.Default.RecordVoiceOver),
  WHAT_GETS_ANNOUNCED("What Gets Announced", "🏈", Icons.Default.Campaign),
  MY_LEAGUES("My Leagues", "🏆", Icons.Default.EmojiEvents),
  LOOK_AND_COUCH("Look & Couch Mode", "🎨", Icons.Default.Palette),
  HELP_SYNC_ADVANCED("Help, Sync & Advanced", "🛠️", Icons.Default.Build);

  fun liveSummary(uiState: GameDayUiState): String {
    val s = uiState.settings
    return when (this) {
      ANNOUNCER_VOICE -> {
        val fx = if (s.playStadiumSoundEffects) "Sound FX On" else "Voice Only"
        "${s.voicePersona.shortName} ${s.voicePersona.flagEmoji} · $fx"
      }
      WHAT_GETS_ANNOUNCED -> {
        val preset = BroadcastPreset.detectActive(s)
        "${preset.title} · ${s.enabledCategories.size} of ${SpokenCategory.entries.size} on"
      }
      MY_LEAGUES -> {
        if (uiState.leagues.isEmpty()) {
          if (com.mflgameday.live.BuildConfig.IS_PLAY_STORE_BUILD) {
            "No fantasy leagues linked · Standalone mode"
          } else {
            "No MFL leagues linked · Standalone mode"
          }
        } else {
          val names = uiState.leagues.take(2).joinToString(", ") { it.leagueName }
          "$names (${uiState.leagues.size} tracked)"
        }
      }
      LOOK_AND_COUCH -> {
        val awake = if (s.keepScreenAwake) "Keep Awake On" else "Standard Sleep"
        "${s.themePreset.displayName} · $awake"
      }
      HELP_SYNC_ADVANCED -> {
        val health = uiState.telemetrySnapshot.pollSuccessRatePct
        if (uiState.lastCrashReport != null) {
          "1 Crash Alert · Tap to view or dismiss"
        } else {
          "${s.pollIntervalSeconds}s Live Speed · $health% Feed Health"
        }
      }
    }
  }
}

@Composable
fun CommentatorStudioCard(
  persona: VoicePersona,
  selected: Boolean,
  capabilityBadge: String,
  onSelectAndPreview: () -> Unit,
  onPlaySampleOnly: () -> Unit,
  modifier: Modifier = Modifier
) {
  val ext = LocalGameDayColors.current
  val accent = Color(persona.accentHex)
  val borderColor = if (selected) MaterialTheme.colorScheme.primary else ext.cardBorder
  val bgColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = if (ext.isDark) 0.14f else 0.10f) else ext.cardElevated

  Surface(
    color = bgColor,
    shape = RoundedCornerShape(14.dp),
    modifier = modifier
      .fillMaxWidth()
      .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
      .clickable(onClick = onSelectAndPreview)
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Box(
        modifier = Modifier
          .size(42.dp)
          .clip(CircleShape)
          .background(accent.copy(alpha = 0.22f))
          .border(1.2.dp, accent.copy(alpha = 0.75f), CircleShape),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = persona.flagEmoji,
          fontSize = 18.sp
        )
      }

      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            text = persona.shortName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(4.dp))
              .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
              .padding(horizontal = 5.dp, vertical = 1.dp)
          ) {
            Text(
              text = capabilityBadge,
              fontSize = 9.sp,
              fontFamily = FontFamily.Monospace,
              color = ext.subtleText,
              maxLines = 1
            )
          }
        }
        Text(
          text = "${persona.roleBadge} · ${persona.subtitle}",
          style = MaterialTheme.typography.labelSmall,
          color = ext.subtleText,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
      }

      IconButton(
        onClick = onPlaySampleOnly,
        modifier = Modifier
          .size(44.dp)
          .clip(CircleShape)
          .background(accent)
      ) {
        Icon(
          imageVector = Icons.Default.PlayArrow,
          contentDescription = "Hear ${persona.shortName}",
          tint = Color.White
        )
      }
    }
  }
}

@Composable
fun SettingToggleRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  val ext = LocalGameDayColors.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .clickable { onCheckedChange(!checked) }
      .padding(vertical = 5.dp, horizontal = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
      Text(subtitle, style = MaterialTheme.typography.labelSmall, color = ext.subtleText)
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

@Composable
fun ExpandableSettingsDrawer(
  title: String,
  subtitle: String? = null,
  expanded: Boolean,
  onToggle: () -> Unit,
  content: @Composable () -> Unit
) {
  val ext = LocalGameDayColors.current
  Surface(
    color = ext.cardElevated.copy(alpha = 0.75f),
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, ext.cardBorder, RoundedCornerShape(14.dp))
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable(onClick = onToggle)
          .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
          if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = ext.subtleText)
          }
        }
        Icon(
          imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
          contentDescription = if (expanded) "Collapse" else "Expand",
          tint = ext.subtleText
        )
      }
      AnimatedVisibility(visible = expanded) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          HorizontalDivider(color = ext.cardBorder)
          content()
        }
      }
    }
  }
}

@Composable
fun StatMetricTile(
  label: String,
  value: String,
  healthy: Boolean = true,
  modifier: Modifier = Modifier
) {
  val ext = LocalGameDayColors.current
  Surface(
    color = ext.cardSurface,
    shape = RoundedCornerShape(10.dp),
    modifier = modifier.border(1.dp, ext.cardBorder, RoundedCornerShape(10.dp))
  ) {
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
      Text(
        text = value,
        style = MaterialTheme.typography.labelLarge,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.ExtraBold,
        color = if (healthy) ext.turfAccent else ext.opponentBadge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        color = ext.subtleText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

/**
 * 1-Tap Quick Booth Bottom Sheet opened from the Top Broadcast Header Pill (`🎙️ David · My Games ▾`)
 * on ANY screen so users can swap commentators or change game scope without leaving live matchups.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickBoothBottomSheet(
  uiState: GameDayUiState,
  coordinator: GameDayCoordinator,
  onOpenFullSettings: () -> Unit,
  onDismiss: () -> Unit
) {
  val ext = LocalGameDayColors.current
  val settings = uiState.settings

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 18.dp, vertical = 6.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text("Live Broadcast Booth", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
          Text("Swap your commentator or filter spoken games on the fly", style = MaterialTheme.typography.labelSmall, color = ext.subtleText)
        }
        TextButton(onClick = {
          onDismiss()
          onOpenFullSettings()
        }) {
          Text("Full Studio ›")
        }
      }

      SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        AnnouncementScope.entries.forEachIndexed { idx, sc ->
          SegmentedButton(
            selected = settings.scope == sc,
            onClick = { coordinator.updateSettings { it.copy(scope = sc) } },
            shape = SegmentedButtonDefaults.itemShape(index = idx, count = AnnouncementScope.entries.size),
            label = { Text(sc.shortLabel, maxLines = 1) }
          )
        }
      }

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        VoicePersona.commentatorRoster.forEach { persona ->
          val isSel = settings.voicePersona == persona && settings.selectedSystemVoiceId == null
          Surface(
            color = if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else ext.cardElevated,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .border(if (isSel) 1.8.dp else 1.dp, if (isSel) MaterialTheme.colorScheme.primary else ext.cardBorder, RoundedCornerShape(12.dp))
              .clickable {
                val updated = settings.copy(voicePersona = persona, selectedSystemVoiceId = null)
                coordinator.updateSettings { updated }
                coordinator.voiceEngine.speakImmediateSample(persona.sampleLine, updated, persona.sampleKind)
              }
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Text(persona.flagEmoji, fontSize = 14.sp)
              Column {
                Text(persona.shortName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
                Text(persona.roleBadge, fontSize = 10.sp, color = ext.subtleText)
              }
            }
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        FilledTonalButton(
          onClick = {
            coordinator.voiceEngine.speakImmediateSample(
              sampleText = settings.voicePersona.sampleLine,
              settings = settings,
              sampleKind = settings.voicePersona.sampleKind
            )
          }
        ) {
          Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Test ${settings.voicePersona.shortName}")
        }
        TextButton(onClick = onDismiss) {
          Text("Done")
        }
      }

      Spacer(Modifier.height(10.dp))
    }
  }
}
