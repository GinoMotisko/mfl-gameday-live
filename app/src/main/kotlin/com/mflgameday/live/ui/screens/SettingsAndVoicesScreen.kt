package com.mflgameday.live.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mflgameday.live.diagnostics.DiagnosticsLogger
import com.mflgameday.live.diagnostics.LogLevel
import com.mflgameday.live.model.AnnouncementScope
import com.mflgameday.live.model.AppThemePreset
import com.mflgameday.live.model.GameEventKind
import com.mflgameday.live.model.SpokenCategory
import com.mflgameday.live.model.VoicePersona
import com.mflgameday.live.service.GameDayCoordinator
import com.mflgameday.live.service.GameDayUiState
import com.mflgameday.live.ui.theme.LocalGameDayColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsAndVoicesScreen(
  uiState: GameDayUiState,
  coordinator: GameDayCoordinator,
  onRequestNotificationPermission: () -> Unit,
  onOpenAddLeague: () -> Unit,
  onOpenQuickTour: () -> Unit = {}
) {
  val context = LocalContext.current
  val ext = LocalGameDayColors.current
  val settings = uiState.settings
  var voiceDropdownOpen by remember { mutableStateOf(false) }
  var showDiagnosticsDialog by remember { mutableStateOf(false) }
  var copiedFeedback by remember { mutableStateOf(false) }

  val hasPostNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
  } else true

  val powerManager = remember { context.getSystemService(PowerManager::class.java) }
  val isIgnoringBatteryOpt = remember(uiState.lastUpdatedMillis) {
    powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val isTwoColumn = maxWidth >= 880.dp

    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = if (isTwoColumn) 24.dp else 14.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // Section 1: Voice Studio & Sample Auditioner
      SettingsSectionCard(
        title = "Broadcast Voice Engine & Audition Studio",
        icon = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
      ) {
        Text(
          text = "Choose an announcer persona (inspired by classic MFL GameDay's David, Shouty & Callie) and pick any installed Android voice engine:",
          style = MaterialTheme.typography.bodySmall,
          color = ext.subtleText
        )

        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          VoicePersona.entries.forEach { persona ->
            FilterChip(
              selected = settings.voicePersona == persona,
              onClick = { coordinator.updateSettings { it.copy(voicePersona = persona) } },
              label = { Text(persona.title) }
            )
          }
        }

        // System Voice Picker
        val currentVoiceLabel = uiState.availableVoices
          .firstOrNull { it.id == settings.selectedSystemVoiceId }
          ?.displayName
          ?: "System Default US Broadcast Voice (${uiState.availableVoices.size} voices available)"

        Box {
          OutlinedButton(
            onClick = {
              coordinator.voiceEngine.refreshAvailableVoices()
              voiceDropdownOpen = true
            },
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(currentVoiceLabel)
          }

          DropdownMenu(
            expanded = voiceDropdownOpen,
            onDismissRequest = { voiceDropdownOpen = false }
          ) {
            DropdownMenuItem(
              text = { Text("System Default US Broadcast Voice") },
              onClick = {
                coordinator.updateSettings { it.copy(selectedSystemVoiceId = null) }
                voiceDropdownOpen = false
              }
            )
            uiState.availableVoices.forEach { v ->
              DropdownMenuItem(
                text = { Text(v.displayName) },
                onClick = {
                  coordinator.updateSettings { it.copy(selectedSystemVoiceId = v.id) }
                  voiceDropdownOpen = false
                }
              )
            }
          }
        }

        if (settings.voicePersona == VoicePersona.CUSTOM_TUNED) {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Speech Rate: ${"%.2f".format(settings.customSpeechRate)}x", style = MaterialTheme.typography.labelMedium)
            Slider(
              value = settings.customSpeechRate,
              onValueChange = { r -> coordinator.updateSettings { it.copy(customSpeechRate = r) } },
              valueRange = 0.70f..1.50f
            )
            Text("Voice Pitch: ${"%.2f".format(settings.customPitch)}x", style = MaterialTheme.typography.labelMedium)
            Slider(
              value = settings.customPitch,
              onValueChange = { p -> coordinator.updateSettings { it.copy(customPitch = p) } },
              valueRange = 0.75f..1.35f
            )
          }
        }

        Text("Sample Voices Out Loud:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Button(
            onClick = {
              coordinator.voiceEngine.speakImmediateSample(
                sampleText = "Touchdown, Tampa Bay Buccaneers! Baker Mayfield, 94 yard touchdown pass to Chris Godwin! Tampa Bay Buccaneers lead 21 to 17.",
                settings = settings,
                sampleKind = GameEventKind.TOUCHDOWN
              )
            }
          ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Sample 94-Yd Bucs TD")
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
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Sample Sack Call")
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
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Sample Turnover Call")
          }
        }
      }

      // Section 2: Announcement Ticks & Relevance Scope
      SettingsSectionCard(
        title = "Spoken Event Ticks & Scope",
        icon = { Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
      ) {
        Text("Which NFL games should be announced out loud?", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        AnnouncementScope.entries.forEach { sc ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .clickable { coordinator.updateSettings { it.copy(scope = sc) } }
              .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            RadioButton(
              selected = settings.scope == sc,
              onClick = { coordinator.updateSettings { it.copy(scope = sc) } }
            )
            Column {
              Text(sc.shortLabel, fontWeight = FontWeight.SemiBold)
              Text(sc.description, style = MaterialTheme.typography.labelSmall, color = ext.subtleText)
            }
          }
        }

        HorizontalDivider(color = ext.cardBorder)
        Text("Select Event Categories to Speak Out Loud:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)

        SpokenCategory.entries.forEach { cat ->
          val checked = cat in settings.enabledCategories
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .clickable {
                coordinator.updateSettings { cur ->
                  val next = if (checked) cur.enabledCategories - cat else cur.enabledCategories + cat
                  cur.copy(enabledCategories = next)
                }
              }
              .padding(vertical = 3.dp),
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
              Text(cat.label, fontWeight = FontWeight.SemiBold)
              Text(cat.subtitle, style = MaterialTheme.typography.labelSmall, color = ext.subtleText)
            }
          }
        }

        HorizontalDivider(color = ext.cardBorder)
        SettingSwitchRow(
          title = "Play Stadium Sound Effects Before Announcements",
          subtitle = "Touchdown fanfare, Red Zone radar pulse & turnover whistle stingers",
          checked = settings.playStadiumSoundEffects,
          onCheckedChange = { v -> coordinator.updateSettings { it.copy(playStadiumSoundEffects = v) } }
        )
        SettingSwitchRow(
          title = "Read Fantasy League Involvement After Plays",
          subtitle = "Appends e.g. 'For your lineup in Beerfest' when your players make a play",
          checked = settings.announceFantasyContextOnPlays,
          onCheckedChange = { v -> coordinator.updateSettings { it.copy(announceFantasyContextOnPlays = v) } }
        )
      }

      // Section 3: Visual Themes & Scoreboard Display Toggles
      SettingsSectionCard(
        title = "Visual Broadcast Themes & Scoreboard Options",
        icon = { Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
      ) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          AppThemePreset.entries.forEach { preset ->
            val selected = settings.themePreset == preset
            val swatchColor = when (preset) {
              AppThemePreset.SYSTEM_AUTO -> Color(0xFF818CF8)
              AppThemePreset.STADIUM_PRIMETIME -> Color(0xFF00E676)
              AppThemePreset.SUNDAY_DAYLIGHT -> Color(0xFF0B57D0)
              AppThemePreset.CYBER_GRIDIRON_OLED -> Color(0xFF00E5FF)
              AppThemePreset.RETRO_GAMEDAY_GOLD -> Color(0xFFFFB300)
              AppThemePreset.REDZONE_BLITZ -> Color(0xFFFF3D00)
            }
            Surface(
              color = if (selected) ext.cardElevated else ext.cardSurface,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier
                .width(220.dp)
                .border(if (selected) 2.dp else 1.dp, if (selected) swatchColor else ext.cardBorder, RoundedCornerShape(12.dp))
                .clickable { coordinator.updateSettings { it.copy(themePreset = preset) } }
            ) {
              Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                Box(modifier = Modifier.size(20.dp).clip(CircleShape).background(swatchColor))
                Column {
                  Text(preset.displayName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                  Text(preset.tagline, style = MaterialTheme.typography.labelSmall, color = ext.subtleText, maxLines = 2)
                }
              }
            }
          }
        }

        HorizontalDivider(color = ext.cardBorder)
        SettingSwitchRow(
          title = "Live Celebration Banner Animations",
          subtitle = "Slide-down animated scoreboard alert for touchdowns, sacks & turnovers",
          checked = settings.celebrationBannerAnimations,
          onCheckedChange = { v -> coordinator.updateSettings { it.copy(celebrationBannerAnimations = v) } }
        )
        SettingSwitchRow(
          title = "Show Live Win Probability & Pace Projections",
          subtitle = "Calculates expected final score & win % from remaining game clock",
          checked = settings.showWinProbabilityAndPace,
          onCheckedChange = { v -> coordinator.updateSettings { it.copy(showWinProbabilityAndPace = v) } }
        )
        SettingSwitchRow(
          title = "Show Per-Player Scoring Category Breakdowns",
          subtitle = "Displays exact yards, receptions & points under each player",
          checked = settings.showScoringBreakdowns,
          onCheckedChange = { v -> coordinator.updateSettings { it.copy(showScoringBreakdowns = v) } }
        )
        SettingSwitchRow(
          title = "Only Show Players With Active Stats",
          subtitle = "Classic GameDay toggle to hide 0-point upcoming players",
          checked = settings.onlyPlayersWithStats,
          onCheckedChange = { v -> coordinator.updateSettings { it.copy(onlyPlayersWithStats = v) } }
        )
        SettingSwitchRow(
          title = "Command Center Split Ticker on Wide Tablets / Unfolded Fold",
          subtitle = "Pins live Play-by-Play Ticker beside scores on ultra-wide screens",
          checked = settings.commandCenterSplitOnWideScreens,
          onCheckedChange = { v -> coordinator.updateSettings { it.copy(commandCenterSplitOnWideScreens = v) } }
        )
      }

      // Section 4: NFL Schedule Sync & Multi-League Management
      SettingsSectionCard(
        title = "Leagues, Starters & NFL Schedule Refresh",
        icon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
      ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = { coordinator.refreshNflScheduleAndPlayers() }) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Refresh NFL Schedule & Player DB")
          }
          FilledTonalButton(onClick = onOpenAddLeague) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add / Switch MFL Account or League")
          }
          OutlinedButton(onClick = { coordinator.startDemoSimulatorMode() }) {
            Text("Load Interactive Demo League")
          }
        }

        Text("Poll Refresh Interval During Live Games:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf(20, 30, 45, 60).forEach { sec ->
            FilterChip(
              selected = settings.pollIntervalSeconds == sec,
              onClick = { coordinator.updateSettings { it.copy(pollIntervalSeconds = sec) } },
              label = { Text("${sec}s") }
            )
          }
        }

        if (uiState.leagues.isNotEmpty()) {
          HorizontalDivider(color = ext.cardBorder)
          Text("Tracked Leagues (${uiState.leagues.size}):", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
          uiState.leagues.forEach { cfg ->
            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "${cfg.leagueName} (#${cfg.leagueId})" + if (cfg.isBestBall) " · BEST BALL" else "",
                  fontWeight = FontWeight.SemiBold
                )
                Text("${cfg.franchiseName} · ${cfg.precision} decimals", style = MaterialTheme.typography.labelSmall, color = ext.subtleText)
              }
              Switch(
                checked = cfg.includeInAnnouncements,
                onCheckedChange = { on -> coordinator.toggleLeagueAnnouncements(cfg.leagueId, on) }
              )
              IconButton(onClick = { coordinator.removeLeague(cfg.leagueId) }) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Remove league", tint = MaterialTheme.colorScheme.error)
              }
            }
          }
        }
      }

      // Section 5: Android OS Permissions, Tablet Keep-Awake & Background Reliability
      SettingsSectionCard(
        title = "Android OS Permissions & Tablet Stadium Mode",
        icon = { Icon(Icons.Default.ScreenLockPortrait, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
      ) {
        SettingSwitchRow(
          title = "Keep Screen Awake (Prevent Dimming / Sleep)",
          subtitle = "Ideal for Samsung Galaxy Tab S7 & Pixel Fold propped up on Sunday",
          checked = settings.keepScreenAwake,
          onCheckedChange = { v -> coordinator.updateSettings { it.copy(keepScreenAwake = v) } }
        )

        SettingSwitchRow(
          title = "Run Uninterrupted Background Service",
          subtitle = "Keeps live NFL polling & spoken announcements active when screen is off or in other apps",
          checked = settings.runInBackgroundService,
          onCheckedChange = { v -> coordinator.updateSettings { it.copy(runInBackgroundService = v) } }
        )

        SettingSwitchRow(
          title = "Stop Announcements When Swiped Closed",
          subtitle = "Dismissing GameDay Live from Recent Apps stops voice & background polling until you reopen it (pressing Home or locking screen keeps it announcing)",
          checked = settings.stopWhenSwipedClosed,
          onCheckedChange = { v -> coordinator.updateSettings { it.copy(stopWhenSwipedClosed = v) } }
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (uiState.isBroadcastPaused) {
            Button(onClick = { coordinator.resumeLiveBroadcastSession() }) {
              Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("Resume Live Broadcast")
            }
          } else {
            OutlinedButton(
              onClick = { coordinator.pauseLiveBroadcastSession("Paused manually in Settings") }
            ) {
              Text("Pause Background Broadcast Now")
            }
          }
        }

        HorizontalDivider(color = ext.cardBorder)

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            Icon(
              imageVector = if (hasPostNotifications) Icons.Default.CheckCircle else Icons.Default.NotificationsActive,
              contentDescription = null,
              tint = if (hasPostNotifications) ext.turfAccent else ext.opponentBadge
            )
            Column {
              Text("Live Notification Controls Permission", fontWeight = FontWeight.SemiBold)
              Text(
                text = if (hasPostNotifications) "Granted — live score & Mute button visible in shade" else "Grant to see live scores & Mute toggle in notification bar",
                style = MaterialTheme.typography.labelSmall,
                color = ext.subtleText
              )
            }
          }
          if (!hasPostNotifications) {
            FilledTonalButton(onClick = onRequestNotificationPermission) {
              Text("Grant")
            }
          }
        }

        if (!com.mflgameday.live.BuildConfig.IS_PLAY_STORE_BUILD) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
              Icon(
                imageVector = if (isIgnoringBatteryOpt) Icons.Default.CheckCircle else Icons.Default.BatteryChargingFull,
                contentDescription = null,
                tint = if (isIgnoringBatteryOpt) ext.turfAccent else MaterialTheme.colorScheme.secondary
              )
              Column {
                Text("Unrestricted All-Day Sunday Battery", fontWeight = FontWeight.SemiBold)
                Text(
                  text = if (isIgnoringBatteryOpt) "Unrestricted — Samsung One UI / Android Doze won't throttle polling" else "Optional: prevents OS sleep throttling during 10-hour game slates",
                  style = MaterialTheme.typography.labelSmall,
                  color = ext.subtleText
                )
              }
            }
            if (!isIgnoringBatteryOpt) {
              OutlinedButton(
                onClick = {
                  runCatching {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                      data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                  }
                }
              ) {
                Text("Allow")
              }
            }
          }
        }
      }

      // Section 6: Diagnostics, Health & Crash Log Collector
      SettingsSectionCard(
        title = "Diagnostics, Health & Crash Log Collector",
        icon = { Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
      ) {
        val warnOrErrorCount = uiState.diagnosticEntries.count { it.level != LogLevel.INFO }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          FilterChip(
            selected = uiState.isNetworkAvailable,
            onClick = {},
            label = { Text(if (uiState.isNetworkAvailable) "Network: Online" else "Network: Offline") }
          )
          FilterChip(
            selected = uiState.availableVoices.isNotEmpty(),
            onClick = {},
            label = { Text("TTS Voices: ${uiState.availableVoices.size}") }
          )
          FilterChip(
            selected = warnOrErrorCount == 0,
            onClick = { showDiagnosticsDialog = true },
            label = { Text("Events: ${uiState.diagnosticEntries.size} ($warnOrErrorCount alerts)") }
          )
        }

        val lastCrash = uiState.lastCrashReport
        if (lastCrash != null) {
          Surface(
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .fillMaxWidth()
              .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
          ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(
                text = "Captured Prior Crash Report: ${lastCrash.exceptionSummary}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = lastCrash.deviceInfo,
                style = MaterialTheme.typography.labelSmall,
                color = ext.subtleText
              )
              TextButton(onClick = { DiagnosticsLogger.dismissCrashReport() }) {
                Text("Dismiss Crash Alert")
              }
            }
          }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = { showDiagnosticsDialog = true }) {
            Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Open Live Diagnostic Console")
          }
          FilledTonalButton(
            onClick = {
              copiedFeedback = DiagnosticsLogger.copyReportToClipboard(context)
            }
          ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (copiedFeedback) "Copied to Clipboard!" else "Copy Report")
          }
          OutlinedButton(onClick = { DiagnosticsLogger.shareReportIntent(context) }) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Share Logs")
          }
          FilledTonalButton(onClick = { DiagnosticsLogger.emailSupportIntent(context) }) {
            Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Email Support")
          }
          OutlinedButton(onClick = onOpenQuickTour) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Quick Tour Guide")
          }
          OutlinedButton(
            onClick = {
              runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.motisko.com/gameday/privacy"))
                context.startActivity(intent)
              }
            }
          ) {
            Text("Privacy Policy (Motisko Ventures LLC)")
          }
        }
      }

      Spacer(Modifier.height(16.dp))
    }
  }

  if (showDiagnosticsDialog) {
    var onlyAlerts by remember { mutableStateOf(false) }
    val visibleLogs = remember(uiState.diagnosticEntries, onlyAlerts) {
      if (onlyAlerts) uiState.diagnosticEntries.filter { it.level != LogLevel.INFO }
      else uiState.diagnosticEntries
    }

    AlertDialog(
      onDismissRequest = { showDiagnosticsDialog = false },
      title = {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("Diagnostic & Crash Console", fontWeight = FontWeight.ExtraBold)
          FilterChip(
            selected = onlyAlerts,
            onClick = { onlyAlerts = !onlyAlerts },
            label = { Text("Alerts Only") }
          )
        }
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "Credentials, passwords & API keys are automatically scrubbed (<REDACTED>).",
            style = MaterialTheme.typography.labelSmall,
            color = ext.subtleText
          )
          LazyColumn(
            modifier = Modifier
              .fillMaxWidth()
              .height(320.dp)
              .clip(RoundedCornerShape(10.dp))
              .background(MaterialTheme.colorScheme.background)
              .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            items(visibleLogs) { entry ->
              val levelColor = when (entry.level) {
                LogLevel.ERROR -> MaterialTheme.colorScheme.error
                LogLevel.WARN -> ext.opponentBadge
                LogLevel.INFO -> ext.turfAccent
              }
              Column {
                Text(
                  text = "[${entry.level}] [${entry.subsystem}] ${entry.message}",
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Monospace,
                  color = levelColor,
                  fontWeight = if (entry.level != LogLevel.INFO) FontWeight.Bold else FontWeight.Normal
                )
                if (!entry.detail.isNullOrBlank()) {
                  Text(
                    text = entry.detail,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ext.subtleText,
                    maxLines = 4
                  )
                }
              }
            }
          }
        }
      },
      dismissButton = {
        TextButton(onClick = { DiagnosticsLogger.clearLogs() }) {
          Text("Clear Logs")
        }
      },
      confirmButton = {
        Button(
          onClick = {
            DiagnosticsLogger.copyReportToClipboard(context)
            showDiagnosticsDialog = false
          }
        ) {
          Text("Copy & Close")
        }
      }
    )
  }
}

@Composable
private fun SettingsSectionCard(
  title: String,
  icon: @Composable () -> Unit,
  content: @Composable () -> Unit
) {
  val ext = LocalGameDayColors.current
  Surface(
    color = ext.cardSurface,
    shape = RoundedCornerShape(18.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, ext.cardBorder, RoundedCornerShape(18.dp))
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        icon()
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
      }
      content()
    }
  }
}

@Composable
private fun SettingSwitchRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  val ext = LocalGameDayColors.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .clickable { onCheckedChange(!checked) }
      .padding(vertical = 4.dp),
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
