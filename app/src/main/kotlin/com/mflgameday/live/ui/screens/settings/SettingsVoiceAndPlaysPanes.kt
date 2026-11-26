package com.mflgameday.live.ui.screens.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mflgameday.live.BuildConfig
import com.mflgameday.live.audio.VoiceCastingResolver
import com.mflgameday.live.model.AnnouncementScope
import com.mflgameday.live.model.BroadcastPreset
import com.mflgameday.live.model.GameEventKind
import com.mflgameday.live.model.SpokenCategory
import com.mflgameday.live.model.VoicePersona
import com.mflgameday.live.service.GameDayCoordinator
import com.mflgameday.live.service.GameDayUiState
import com.mflgameday.live.ui.theme.LocalGameDayColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnnouncerAndVoicePane(
  uiState: GameDayUiState,
  coordinator: GameDayCoordinator
) {
  val ext = LocalGameDayColors.current
  val settings = uiState.settings
  var fineTuneExpanded by rememberSaveable { mutableStateOf(settings.voicePersona == VoicePersona.CUSTOM_TUNED || settings.selectedSystemVoiceId != null) }
  var showSystemVoiceDialog by rememberSaveable { mutableStateOf(false) }

  val resolvedPlans = remember(uiState.availableVoices) {
    VoiceCastingResolver.resolveAll(coordinator.voiceEngine.installedDescriptors)
  }

  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val cols = when {
      maxWidth >= 680.dp -> 2
      maxWidth >= 430.dp -> 2
      else -> 1
    }
    val roster = VoicePersona.commentatorRoster

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
      Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("Choose Your Commentator", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        Text(
          text = "Each commentator uses a distinct voice & broadcast cadence. Tap any card to select and hear them live.",
          style = MaterialTheme.typography.bodySmall,
          color = ext.subtleText
        )
      }

      roster.chunked(cols).forEach { rowPersonas ->
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          rowPersonas.forEach { persona ->
            val plan = resolvedPlans[persona]
            val badge = when {
              plan?.isStudioHd == true && plan.worksOffline -> "Studio HD"
              plan?.worksOffline == true -> "Offline"
              else -> "Studio HD"
            }
            val isSelected = settings.voicePersona == persona && settings.selectedSystemVoiceId == null
            CommentatorStudioCard(
              persona = persona,
              selected = isSelected,
              capabilityBadge = badge,
              onSelectAndPreview = {
                val next = settings.copy(voicePersona = persona, selectedSystemVoiceId = null)
                coordinator.updateSettings { next }
                coordinator.voiceEngine.speakImmediateSample(persona.sampleLine, next, persona.sampleKind)
              },
              onPlaySampleOnly = {
                val previewSettings = settings.copy(voicePersona = persona, selectedSystemVoiceId = null)
                coordinator.voiceEngine.speakImmediateSample(persona.sampleLine, previewSettings, persona.sampleKind)
              },
              modifier = Modifier.weight(1f)
            )
          }
          if (rowPersonas.size < cols) {
            Spacer(modifier = Modifier.weight(1f))
          }
        }
      }

      val overrideVoiceName = uiState.availableVoices
        .firstOrNull { it.id == settings.selectedSystemVoiceId }
        ?.displayName
      val fineTuneSubtitle = when {
        overrideVoiceName != null -> "Manual engine: $overrideVoiceName"
        settings.voicePersona == VoicePersona.CUSTOM_TUNED ->
          "Custom ${"%.2f".format(settings.customSpeechRate)}x speed · ${"%.2f".format(settings.customPitch)}x pitch"
        else -> "Auto-matched voice · ${"%.2f".format(settings.voicePersona.speechRate)}x speed"
      }

      ExpandableSettingsDrawer(
        title = "Fine-tune speed, pitch, or system voice",
        subtitle = fineTuneSubtitle,
        expanded = fineTuneExpanded,
        onToggle = { fineTuneExpanded = !fineTuneExpanded }
      ) {
        val rawRate = if (settings.voicePersona == VoicePersona.CUSTOM_TUNED) settings.customSpeechRate else settings.voicePersona.speechRate
        val rawPitch = if (settings.voicePersona == VoicePersona.CUSTOM_TUNED) settings.customPitch else settings.voicePersona.pitch
        val displayedRate = rawRate.takeIf { it.isFinite() }?.coerceIn(0.70f, 1.50f) ?: 1.02f
        val displayedPitch = rawPitch.takeIf { it.isFinite() }?.coerceIn(0.70f, 1.40f) ?: 1.0f

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("Speech Speed: ${"%.2f".format(displayedRate)}x", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
          if (settings.voicePersona == VoicePersona.CUSTOM_TUNED || settings.selectedSystemVoiceId != null) {
            TextButton(
              onClick = {
                coordinator.updateSettings {
                  it.copy(
                    voicePersona = VoicePersona.BOOTH_PLAY_BY_PLAY,
                    selectedSystemVoiceId = null,
                    customSpeechRate = 1.02f,
                    customPitch = 1.0f
                  )
                }
              }
            ) {
              Text("Reset to Default")
            }
          }
        }
        Slider(
          value = displayedRate,
          onValueChange = { r ->
            coordinator.updateSettings {
              it.copy(
                voicePersona = VoicePersona.CUSTOM_TUNED,
                customSpeechRate = r.coerceIn(0.70f, 1.50f),
                customPitch = displayedPitch
              )
            }
          },
          valueRange = 0.70f..1.50f
        )

        Text("Voice Pitch: ${"%.2f".format(displayedPitch)}x", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Slider(
          value = displayedPitch,
          onValueChange = { p ->
            coordinator.updateSettings {
              it.copy(
                voicePersona = VoicePersona.CUSTOM_TUNED,
                customSpeechRate = displayedRate,
                customPitch = p.coerceIn(0.70f, 1.40f)
              )
            }
          },
          valueRange = 0.70f..1.40f
        )

        OutlinedButton(
          onClick = {
            coordinator.voiceEngine.refreshAvailableVoices()
            showSystemVoiceDialog = true
          },
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(overrideVoiceName ?: "System Voice: Auto-Matched per Commentator (${uiState.availableVoices.size} installed)")
        }

        Text("Audition Stadium Stingers & Play Calls:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = {
              coordinator.voiceEngine.speakImmediateSample(
                sampleText = "Touchdown, Tampa Bay Buccaneers! Baker Mayfield, 94 yard touchdown pass to Chris Godwin! Buccaneers lead 21 to 17.",
                settings = settings,
                sampleKind = GameEventKind.TOUCHDOWN
              )
            }
          ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (BuildConfig.IS_PLAY_STORE_BUILD) "94-Yd Pass TD" else "94-Yd Bucs TD")
          }
          FilledTonalButton(
            onClick = {
              coordinator.voiceEngine.speakImmediateSample(
                sampleText = "Sack, Buffalo Bills! Tua Tagovailoa sacked by Gregory Rousseau for 8 yards.",
                settings = settings,
                sampleKind = GameEventKind.SACK
              )
            }
          ) {
            Text("Sack Stinger")
          }
          FilledTonalButton(
            onClick = {
              coordinator.voiceEngine.speakImmediateSample(
                sampleText = "Interception, Seattle Seahawks! Devon Witherspoon picks off Brock Purdy. For your lineup in Sunday Showdown.",
                settings = settings,
                sampleKind = GameEventKind.INTERCEPTION
              )
            }
          ) {
            Text("Turnover Call")
          }
        }
      }
    }
  }

  if (showSystemVoiceDialog) {
    AlertDialog(
      onDismissRequest = { showSystemVoiceDialog = false },
      title = { Text("Select Installed System Voice", fontWeight = FontWeight.ExtraBold) },
      text = {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .height(320.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          item {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                  coordinator.updateSettings { it.copy(selectedSystemVoiceId = null) }
                  showSystemVoiceDialog = false
                }
                .padding(vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              RadioButton(
                selected = settings.selectedSystemVoiceId == null,
                onClick = {
                  coordinator.updateSettings { it.copy(selectedSystemVoiceId = null) }
                  showSystemVoiceDialog = false
                }
              )
              Column {
                Text("Auto-Match per Commentator (Recommended)", fontWeight = FontWeight.Bold)
                Text("Automatically binds each commentator to a distinct HD voice", style = MaterialTheme.typography.labelSmall, color = ext.subtleText)
              }
            }
          }
          items(uiState.availableVoices, key = { it.id }) { voice ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                  val next = settings.copy(selectedSystemVoiceId = voice.id)
                  coordinator.updateSettings { next }
                  coordinator.voiceEngine.speakImmediateSample("Voice check: Touchdown from 25 yards out!", next, GameEventKind.TOUCHDOWN)
                  showSystemVoiceDialog = false
                }
                .padding(vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              RadioButton(
                selected = settings.selectedSystemVoiceId == voice.id,
                onClick = {
                  val next = settings.copy(selectedSystemVoiceId = voice.id)
                  coordinator.updateSettings { next }
                  coordinator.voiceEngine.speakImmediateSample("Voice check: Touchdown from 25 yards out!", next, GameEventKind.TOUCHDOWN)
                  showSystemVoiceDialog = false
                }
              )
              Text(voice.displayName, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showSystemVoiceDialog = false }) {
          Text("Close")
        }
      }
    )
  }
}

@Composable
fun WhatGetsAnnouncedPane(
  uiState: GameDayUiState,
  coordinator: GameDayCoordinator
) {
  val ext = LocalGameDayColors.current
  val settings = uiState.settings
  val activePreset = remember(settings.scope, settings.enabledCategories) {
    BroadcastPreset.detectActive(settings)
  }
  var customizeExpanded by rememberSaveable { mutableStateOf(activePreset == BroadcastPreset.CUSTOM) }

  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val twoColChips = maxWidth >= 420.dp
    val widePresets = maxWidth >= 620.dp

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
      Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("One-Tap Sunday Broadcast Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        Text(
          text = "Pick how chatty GameDay Live should be, or customize individual play types below.",
          style = MaterialTheme.typography.bodySmall,
          color = ext.subtleText
        )
      }

      if (widePresets) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          BroadcastPreset.selectablePresets.forEach { preset ->
            PresetModeCard(
              preset = preset,
              selected = activePreset == preset,
              onClick = { coordinator.updateSettings { preset.applyTo(it) } },
              modifier = Modifier.weight(1f)
            )
          }
        }
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          BroadcastPreset.selectablePresets.forEach { preset ->
            PresetModeCard(
              preset = preset,
              selected = activePreset == preset,
              onClick = { coordinator.updateSettings { preset.applyTo(it) } },
              modifier = Modifier.fillMaxWidth()
            )
          }
        }
      }

      Surface(
        color = ext.cardSurface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, ext.cardBorder, RoundedCornerShape(14.dp))
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          SettingToggleRow(
            title = "Stadium Sound FX Before Announcements",
            subtitle = "Short brass fanfare, Red Zone radar pulse & turnover whistle stingers",
            checked = settings.playStadiumSoundEffects,
            onCheckedChange = { v -> coordinator.updateSettings { it.copy(playStadiumSoundEffects = v) } }
          )
          HorizontalDivider(color = ext.cardBorder)
          SettingToggleRow(
            title = "Read My Fantasy Team Involvement",
            subtitle = "Adds 'For your lineup in Beerfest' when your starters or opponents score",
            checked = settings.announceFantasyContextOnPlays,
            onCheckedChange = { v -> coordinator.updateSettings { it.copy(announceFantasyContextOnPlays = v) } }
          )
        }
      }

      ExpandableSettingsDrawer(
        title = "Customize games & play types (${settings.enabledCategories.size} of ${SpokenCategory.entries.size} on)",
        subtitle = "Scope: ${settings.scope.shortLabel} · ${activePreset.title}",
        expanded = customizeExpanded,
        onToggle = { customizeExpanded = !customizeExpanded }
      ) {
        Text("Which games should be announced?", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
        Text(settings.scope.description, style = MaterialTheme.typography.labelSmall, color = ext.subtleText)

        HorizontalDivider(color = ext.cardBorder)
        Text("Play Types to Speak Out Loud:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

        val cols = if (twoColChips) 2 else 1
        SpokenCategory.entries.chunked(cols).forEach { rowCats ->
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowCats.forEach { cat ->
              val checked = cat in settings.enabledCategories
              Surface(
                color = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else ext.cardSurface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                  .weight(1f)
                  .border(1.dp, if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else ext.cardBorder, RoundedCornerShape(10.dp))
                  .clickable {
                    coordinator.updateSettings { cur ->
                      val next = if (checked) cur.enabledCategories - cat else cur.enabledCategories + cat
                      cur.copy(enabledCategories = next)
                    }
                  }
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Checkbox(
                    checked = checked,
                    onCheckedChange = { on ->
                      coordinator.updateSettings { cur ->
                        val next = if (on) cur.enabledCategories + cat else cur.enabledCategories - cat
                        cur.copy(enabledCategories = next)
                      }
                    }
                  )
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = if (cat == SpokenCategory.TOUCHDOWNS) "Touchdowns & Extra Points" else cat.label,
                      style = MaterialTheme.typography.labelMedium,
                      fontWeight = FontWeight.SemiBold,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                    Text(
                      text = cat.subtitle,
                      style = MaterialTheme.typography.labelSmall,
                      color = ext.subtleText,
                      maxLines = 2,
                      overflow = TextOverflow.Ellipsis
                    )
                  }
                }
              }
            }
            if (rowCats.size < cols) {
              Spacer(Modifier.weight(1f))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun PresetModeCard(
  preset: BroadcastPreset,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val ext = LocalGameDayColors.current
  val borderCol = if (selected) MaterialTheme.colorScheme.primary else ext.cardBorder
  val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = if (ext.isDark) 0.15f else 0.10f) else ext.cardElevated

  Surface(
    color = bg,
    shape = RoundedCornerShape(14.dp),
    modifier = modifier
      .border(if (selected) 2.dp else 1.dp, borderCol, RoundedCornerShape(14.dp))
      .clickable(onClick = onClick)
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        text = "${preset.emoji} ${preset.title}",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.ExtraBold
      )
      Text(
        text = preset.subtitle,
        style = MaterialTheme.typography.labelSmall,
        color = ext.subtleText
      )
    }
  }
}
