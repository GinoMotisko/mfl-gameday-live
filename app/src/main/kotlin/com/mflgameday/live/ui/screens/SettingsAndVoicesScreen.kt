package com.mflgameday.live.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mflgameday.live.service.GameDayCoordinator
import com.mflgameday.live.service.GameDayUiState
import com.mflgameday.live.ui.screens.settings.AnnouncerAndVoicePane
import com.mflgameday.live.ui.screens.settings.HelpSyncAndAdvancedPane
import com.mflgameday.live.ui.screens.settings.LookAndCouchModePane
import com.mflgameday.live.ui.screens.settings.MyLeaguesPane
import com.mflgameday.live.ui.screens.settings.SettingsCategory
import com.mflgameday.live.ui.screens.settings.WhatGetsAnnouncedPane
import com.mflgameday.live.ui.theme.LocalGameDayColors

/**
 * Multi-Resolution Native Broadcast Settings & Announcer Studio:
 * - Compact Phone & Fold Cover (`paneWidth < 520.dp`): Zero-Scroll 5-Row Settings Hub + Slide Drill-In (`BackHandler`)
 * - Unfolded Foldable & 8" Portrait Tablet (`520..819.dp`): Hinge-Safe `280.dp` Category Rail + Focused Detail Pane
 * - 10"+ Landscape Tablet (`>= 820.dp`): Samsung One UI `310.dp` Master Card + Centered `760.dp` Detail Studio
 */
@Composable
fun SettingsAndVoicesScreen(
  uiState: GameDayUiState,
  coordinator: GameDayCoordinator,
  onRequestNotificationPermission: () -> Unit,
  onOpenAddLeague: () -> Unit,
  onOpenQuickTour: () -> Unit = {}
) {
  val ext = LocalGameDayColors.current
  var selectedSplitCategory by rememberSaveable { mutableStateOf(SettingsCategory.ANNOUNCER_VOICE) }
  var drilledPhoneCategory by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val paneWidth = maxWidth
    val isCompactPhone = paneWidth < 520.dp
    val isLargeTablet = paneWidth >= 820.dp

    if (isCompactPhone) {
      BackHandler(enabled = drilledPhoneCategory != null) {
        drilledPhoneCategory = null
      }
      AnimatedContent(
        targetState = drilledPhoneCategory,
        label = "settings_phone_nav",
        modifier = Modifier.fillMaxSize()
      ) { activeCat ->
        if (activeCat == null) {
          CompactPhoneSettingsHub(
            uiState = uiState,
            coordinator = coordinator,
            onSelectCategory = { cat ->
              selectedSplitCategory = cat
              drilledPhoneCategory = cat
            }
          )
        } else {
          Column(modifier = Modifier.fillMaxSize()) {
            Surface(
              color = MaterialTheme.colorScheme.surface,
              tonalElevation = 2.dp,
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                IconButton(onClick = { drilledPhoneCategory = null }) {
                  Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Settings")
                }
                Text(
                  text = "${activeCat.emoji} ${activeCat.title}",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.ExtraBold
                )
              }
            }
            HorizontalDivider(color = ext.cardBorder)
            Column(
              modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp)
            ) {
              CategoryDetailContent(
                category = activeCat,
                uiState = uiState,
                coordinator = coordinator,
                onRequestNotificationPermission = onRequestNotificationPermission,
                onOpenAddLeague = onOpenAddLeague,
                onOpenQuickTour = onOpenQuickTour
              )
              Spacer(Modifier.height(18.dp))
            }
          }
        }
      }
    } else {
      // Hinge-Safe Foldable (`280.dp` rail clears 372-420dp Pixel Fold crease) & 10"+ One UI Tablet (`310.dp` rail)
      val masterWidth = if (isLargeTablet) 310.dp else 280.dp
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = if (isLargeTablet) 20.dp else 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
      ) {
        Surface(
          color = ext.cardSurface,
          shape = RoundedCornerShape(18.dp),
          modifier = Modifier
            .width(masterWidth)
            .fillMaxHeight()
            .border(1.dp, ext.cardBorder, RoundedCornerShape(18.dp))
        ) {
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(
                text = "Broadcast Studio",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
                color = ext.subtleText,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
              )
              SettingsCategory.entries.forEach { cat ->
                val isSelected = selectedSplitCategory == cat
                val hasCrashAlert = cat == SettingsCategory.HELP_SYNC_ADVANCED && uiState.lastCrashReport != null
                Surface(
                  color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.01f),
                  shape = RoundedCornerShape(12.dp),
                  modifier = Modifier
                    .fillMaxWidth()
                    .border(
                      width = if (isSelected) 1.5.dp else 0.dp,
                      color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f) else MaterialTheme.colorScheme.surface,
                      shape = RoundedCornerShape(12.dp)
                    )
                    .clickable {
                      selectedSplitCategory = cat
                      drilledPhoneCategory = null
                    }
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                  ) {
                    Text(cat.emoji, fontSize = 18.sp)
                    Column(modifier = Modifier.weight(1f)) {
                      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                          text = cat.title,
                          style = MaterialTheme.typography.bodyMedium,
                          fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis
                        )
                        if (hasCrashAlert) {
                          Box(
                            modifier = Modifier
                              .size(7.dp)
                              .clip(CircleShape)
                              .background(MaterialTheme.colorScheme.error)
                          )
                        }
                      }
                      Text(
                        text = cat.liveSummary(uiState),
                        style = MaterialTheme.typography.labelSmall,
                        color = ext.subtleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                      )
                    }
                  }
                }
              }
            }

            Surface(
              color = ext.cardElevated,
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text(
                  text = if (uiState.isNetworkAvailable) "Online · Feed ${uiState.telemetrySnapshot.pollSuccessRatePct}%" else "Offline",
                  style = MaterialTheme.typography.labelSmall,
                  color = if (uiState.isNetworkAvailable) ext.turfAccent else ext.redZoneAlert,
                  fontWeight = FontWeight.Bold
                )
                Text(
                  text = "v${com.mflgameday.live.BuildConfig.VERSION_NAME}",
                  style = MaterialTheme.typography.labelSmall,
                  color = ext.subtleText
                )
              }
            }
          }
        }

        Box(
          modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
          contentAlignment = Alignment.TopCenter
        ) {
          Column(
            modifier = Modifier
              .widthIn(max = 760.dp)
              .fillMaxWidth()
              .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
          ) {
            CategoryDetailContent(
              category = selectedSplitCategory,
              uiState = uiState,
              coordinator = coordinator,
              onRequestNotificationPermission = onRequestNotificationPermission,
              onOpenAddLeague = onOpenAddLeague,
              onOpenQuickTour = onOpenQuickTour
            )
            Spacer(Modifier.height(18.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun CompactPhoneSettingsHub(
  uiState: GameDayUiState,
  coordinator: GameDayCoordinator,
  onSelectCategory: (SettingsCategory) -> Unit
) {
  val ext = LocalGameDayColors.current
  val persona = uiState.settings.voicePersona

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    // Active Commentator Hero Card
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = ext.cardElevated,
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, ext.cardBorder, RoundedCornerShape(16.dp))
        .clickable { onSelectCategory(SettingsCategory.ANNOUNCER_VOICE) }
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(
            Brush.horizontalGradient(listOf(ext.headerGradientStart, ext.headerGradientEnd))
          )
          .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("ACTIVE COMMENTATOR", fontSize = 10.sp, fontWeight = FontWeight.Black, color = ext.turfAccent)
          Text(
            text = "${persona.flagEmoji} ${persona.shortName} · ${persona.roleBadge}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold
          )
          Text(
            text = "Scope: ${uiState.settings.scope.shortLabel} · Tap to swap voice",
            style = MaterialTheme.typography.labelSmall,
            color = ext.subtleText
          )
        }
        Button(
          onClick = {
            coordinator.voiceEngine.speakImmediateSample(
              sampleText = persona.sampleLine,
              settings = uiState.settings,
              sampleKind = persona.sampleKind
            )
          }
        ) {
          Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Hear")
        }
      }
    }

    // 5 Grouped Material 3 Expressive Hub Rows
    Surface(
      color = ext.cardSurface,
      shape = RoundedCornerShape(18.dp),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, ext.cardBorder, RoundedCornerShape(18.dp))
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        SettingsCategory.entries.forEachIndexed { idx, cat ->
          val hasCrashAlert = cat == SettingsCategory.HELP_SYNC_ADVANCED && uiState.lastCrashReport != null
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onSelectCategory(cat) }
              .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Box(
              modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
              contentAlignment = Alignment.Center
            ) {
              Text(cat.emoji, fontSize = 17.sp)
            }
            Column(modifier = Modifier.weight(1f)) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(cat.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                if (hasCrashAlert) {
                  Box(
                    modifier = Modifier
                      .size(8.dp)
                      .clip(CircleShape)
                      .background(MaterialTheme.colorScheme.error)
                  )
                }
              }
              Text(
                text = cat.liveSummary(uiState),
                style = MaterialTheme.typography.labelSmall,
                color = ext.subtleText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = ext.subtleText)
          }
          if (idx < SettingsCategory.entries.lastIndex) {
            HorizontalDivider(color = ext.cardBorder.copy(alpha = 0.65f), modifier = Modifier.padding(horizontal = 14.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun CategoryDetailContent(
  category: SettingsCategory,
  uiState: GameDayUiState,
  coordinator: GameDayCoordinator,
  onRequestNotificationPermission: () -> Unit,
  onOpenAddLeague: () -> Unit,
  onOpenQuickTour: () -> Unit
) {
  when (category) {
    SettingsCategory.ANNOUNCER_VOICE -> AnnouncerAndVoicePane(uiState = uiState, coordinator = coordinator)
    SettingsCategory.WHAT_GETS_ANNOUNCED -> WhatGetsAnnouncedPane(uiState = uiState, coordinator = coordinator)
    SettingsCategory.MY_LEAGUES -> MyLeaguesPane(uiState = uiState, coordinator = coordinator, onOpenAddLeague = onOpenAddLeague)
    SettingsCategory.LOOK_AND_COUCH -> LookAndCouchModePane(
      uiState = uiState,
      coordinator = coordinator,
      onRequestNotificationPermission = onRequestNotificationPermission
    )
    SettingsCategory.HELP_SYNC_ADVANCED -> HelpSyncAndAdvancedPane(
      uiState = uiState,
      coordinator = coordinator,
      onOpenQuickTour = onOpenQuickTour
    )
  }
}
