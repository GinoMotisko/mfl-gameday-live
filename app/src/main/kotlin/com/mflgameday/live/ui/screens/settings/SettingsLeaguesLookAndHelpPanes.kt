package com.mflgameday.live.ui.screens.settings

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mflgameday.live.BuildConfig
import com.mflgameday.live.diagnostics.DiagnosticsLogger
import com.mflgameday.live.diagnostics.GameDayTelemetry
import com.mflgameday.live.diagnostics.LogLevel
import com.mflgameday.live.model.AppThemePreset
import com.mflgameday.live.model.LeagueConfig
import com.mflgameday.live.service.GameDayCoordinator
import com.mflgameday.live.service.GameDayUiState
import com.mflgameday.live.ui.theme.LocalGameDayColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyLeaguesPane(
  uiState: GameDayUiState,
  coordinator: GameDayCoordinator,
  onOpenAddLeague: () -> Unit
) {
  val ext = LocalGameDayColors.current
  var pendingRemoveLeague by remember { mutableStateOf<LeagueConfig?>(null) }

  Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text("My Fantasy Leagues", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
      Text(
        text = "Track multiple MyFantasyLeague leagues simultaneously with live lead-flip announcements and cross-league conflict alerts.",
        style = MaterialTheme.typography.bodySmall,
        color = ext.subtleText
      )
    }

    val addLeagueBtnLabel = if (com.mflgameday.live.BuildConfig.IS_PLAY_STORE_BUILD) "Add / Switch Fantasy League" else "Add / Switch MFL League"
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = onOpenAddLeague) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(addLeagueBtnLabel)
      }
      FilledTonalButton(onClick = { coordinator.startDemoSimulatorMode() }) {
        Text("Try Interactive Sunday Simulator")
      }
    }

    if (uiState.leagues.isEmpty()) {
      Surface(
        color = ext.cardSurface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, ext.cardBorder, RoundedCornerShape(14.dp))
      ) {
        Text(
          text = "No MyFantasyLeague teams linked right now. Tap '$addLeagueBtnLabel' to sign in or enter a 5-digit League ID.",
          style = MaterialTheme.typography.bodySmall,
          color = ext.subtleText,
          modifier = Modifier.padding(16.dp)
        )
      }
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        uiState.leagues.forEach { cfg ->
          Surface(
            color = ext.cardSurface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
              .fillMaxWidth()
              .border(1.dp, ext.cardBorder, RoundedCornerShape(14.dp))
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  Text(cfg.leagueName, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.bodyLarge)
                  if (cfg.isBestBall) {
                    Box(
                      modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(ext.touchdownGold.copy(alpha = 0.20f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                      Text("BEST BALL", color = ext.touchdownGold, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    }
                  }
                }
                Text(
                  text = "${cfg.franchiseName} · League #${cfg.leagueId}",
                  style = MaterialTheme.typography.labelSmall,
                  color = ext.subtleText
                )
              }
              Switch(
                checked = cfg.includeInAnnouncements,
                onCheckedChange = { on -> coordinator.toggleLeagueAnnouncements(cfg.leagueId, on) }
              )
              IconButton(onClick = { pendingRemoveLeague = cfg }) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Remove ${cfg.leagueName}", tint = MaterialTheme.colorScheme.error)
              }
            }
          }
        }
      }
    }
  }

  val targetRemove = pendingRemoveLeague
  if (targetRemove != null) {
    AlertDialog(
      onDismissRequest = { pendingRemoveLeague = null },
      title = { Text("Remove ${targetRemove.leagueName}?", fontWeight = FontWeight.ExtraBold) },
      text = {
        Text("This removes ${targetRemove.leagueName} (#${targetRemove.leagueId}) from your tracked leagues. You can re-add it anytime.")
      },
      dismissButton = {
        TextButton(onClick = { pendingRemoveLeague = null }) {
          Text("Cancel")
        }
      },
      confirmButton = {
        Button(
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          onClick = {
            coordinator.removeLeague(targetRemove.leagueId)
            pendingRemoveLeague = null
          }
        ) {
          Text("Remove League")
        }
      }
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LookAndCouchModePane(
  uiState: GameDayUiState,
  coordinator: GameDayCoordinator,
  onRequestNotificationPermission: () -> Unit
) {
  val context = LocalContext.current
  val ext = LocalGameDayColors.current
  val settings = uiState.settings
  var displayDetailsExpanded by rememberSaveable { mutableStateOf(false) }
  var resumeTick by remember { mutableIntStateOf(0) }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        resumeTick += 1
      }
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }

  val hasPostNotifications = remember(resumeTick, uiState.lastUpdatedMillis) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else true
  }

  val powerManager = remember { context.getSystemService(PowerManager::class.java) }
  val isIgnoringBatteryOpt = remember(resumeTick, uiState.lastUpdatedMillis) {
    powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
  }

  Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
    // 1. Compact 6-Swatch Theme Strip
    Surface(
      color = ext.cardSurface,
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, ext.cardBorder, RoundedCornerShape(14.dp))
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Broadcast Color Theme", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Box(
              modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(swatchColor.copy(alpha = 0.20f))
                .border(if (selected) 2.5.dp else 1.dp, if (selected) swatchColor else ext.cardBorder, CircleShape)
                .clickable { coordinator.updateSettings { it.copy(themePreset = preset) } },
              contentAlignment = Alignment.Center
            ) {
              Box(
                modifier = Modifier
                  .size(30.dp)
                  .clip(CircleShape)
                  .background(swatchColor),
                contentAlignment = Alignment.Center
              ) {
                if (selected) {
                  Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = preset.displayName,
                    tint = Color(0xFF071018),
                    modifier = Modifier.size(18.dp)
                  )
                }
              }
            }
          }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(settings.themePreset.displayName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
          Text(settings.themePreset.tagline, style = MaterialTheme.typography.labelSmall, color = ext.subtleText)
        }
      }
    }

    // 2. Couch & Screen-Off Audio Behavior
    Surface(
      color = ext.cardSurface,
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, ext.cardBorder, RoundedCornerShape(14.dp))
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Couch Mode & Background Audio", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)

        SettingToggleRow(
          title = "Keep Screen Awake (Couch / Bar Mode)",
          subtitle = "Prevents screen dimming while propped up watching live scores",
          checked = settings.keepScreenAwake,
          onCheckedChange = { v -> coordinator.updateSettings { it.copy(keepScreenAwake = v) } }
        )
        HorizontalDivider(color = ext.cardBorder)
        SettingToggleRow(
          title = "Keep Announcing With Screen Off",
          subtitle = "Stays live 90m pre-kickoff through Final; sleeps automatically off-hours",
          checked = settings.runInBackgroundService,
          onCheckedChange = { v -> coordinator.updateSettings { it.copy(runInBackgroundService = v) } }
        )
        HorizontalDivider(color = ext.cardBorder)
        SettingToggleRow(
          title = "Mute When App Is Swiped Away",
          subtitle = "Closing from Recent Apps stops audio; locking your phone keeps it going",
          checked = settings.stopWhenSwipedClosed,
          onCheckedChange = { v -> coordinator.updateSettings { it.copy(stopWhenSwipedClosed = v) } }
        )

        Row(modifier = Modifier.padding(top = 4.dp)) {
          if (uiState.isBroadcastPaused) {
            Button(onClick = { coordinator.resumeLiveBroadcastSession() }) {
              Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("Resume Live Broadcast")
            }
          } else {
            OutlinedButton(onClick = { coordinator.pauseLiveBroadcastSession("Paused manually in Settings") }) {
              Text("Pause Background Broadcast Now")
            }
          }
        }
      }
    }

    // 3. Conditional OS Permission Cards (shown ONLY when ungranted!)
    if (!hasPostNotifications) {
      Surface(
        color = ext.opponentBadge.copy(alpha = 0.12f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, ext.opponentBadge.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
      ) {
        Row(
          modifier = Modifier.padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = ext.opponentBadge)
          Column(modifier = Modifier.weight(1f)) {
            Text("Lock-Screen Score & Mute Button", fontWeight = FontWeight.Bold)
            Text("Allow notifications to see your live matchup score & Mute button when locked.", style = MaterialTheme.typography.labelSmall, color = ext.subtleText)
          }
          FilledTonalButton(onClick = onRequestNotificationPermission) {
            Text("Allow")
          }
        }
      }
    }

    if (!BuildConfig.IS_PLAY_STORE_BUILD && !isIgnoringBatteryOpt) {
      Surface(
        color = ext.cardElevated,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, ext.cardBorder, RoundedCornerShape(14.dp))
      ) {
        Row(
          modifier = Modifier.padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
          Column(modifier = Modifier.weight(1f)) {
            Text("Prevent Game-Day Sleep", fontWeight = FontWeight.Bold)
            Text("Stops Android from pausing live calls during 10-hour Sunday slates.", style = MaterialTheme.typography.labelSmall, color = ext.subtleText)
          }
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

    // 4. Collapsed Scoreboard Display Details
    ExpandableSettingsDrawer(
      title = "Scoreboard Display Details",
      subtitle = "Win %, player stat lines, active-scorer filter & wide-screen split ticker",
      expanded = displayDetailsExpanded,
      onToggle = { displayDetailsExpanded = !displayDetailsExpanded }
    ) {
      SettingToggleRow(
        title = "Live Celebration Banner Animations",
        subtitle = "Slide-down scoreboard banner for touchdowns, sacks & turnovers",
        checked = settings.celebrationBannerAnimations,
        onCheckedChange = { v -> coordinator.updateSettings { it.copy(celebrationBannerAnimations = v) } }
      )
      SettingToggleRow(
        title = "Show Live Win Probability & Pace Projections",
        subtitle = "Estimates final score & win % from remaining game clock",
        checked = settings.showWinProbabilityAndPace,
        onCheckedChange = { v -> coordinator.updateSettings { it.copy(showWinProbabilityAndPace = v) } }
      )
      SettingToggleRow(
        title = "Show Per-Player Stat Breakdowns",
        subtitle = "Displays exact yards, catches & points under each player",
        checked = settings.showScoringBreakdowns,
        onCheckedChange = { v -> coordinator.updateSettings { it.copy(showScoringBreakdowns = v) } }
      )
      SettingToggleRow(
        title = "Only Show Players With Active Stats",
        subtitle = "Hides 0-point upcoming players until their game starts",
        checked = settings.onlyPlayersWithStats,
        onCheckedChange = { v -> coordinator.updateSettings { it.copy(onlyPlayersWithStats = v) } }
      )
      SettingToggleRow(
        title = "Side-by-Side Play Ticker on Large Screens",
        subtitle = "Pins live play feed beside matchups on landscape tablets & unfolded foldables",
        checked = settings.commandCenterSplitOnWideScreens,
        onCheckedChange = { v -> coordinator.updateSettings { it.copy(commandCenterSplitOnWideScreens = v) } }
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HelpSyncAndAdvancedPane(
  uiState: GameDayUiState,
  coordinator: GameDayCoordinator,
  onOpenQuickTour: () -> Unit
) {
  val context = LocalContext.current
  val ext = LocalGameDayColors.current
  val settings = uiState.settings
  var logsExpanded by rememberSaveable { mutableStateOf(false) }
  var showDiagnosticsDialog by rememberSaveable { mutableStateOf(false) }
  var copiedFeedback by remember { mutableStateOf(false) }

  val warnOrErrorCount = uiState.diagnosticEntries.count { it.level != LogLevel.INFO }
  val tel = uiState.telemetrySnapshot

  Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
    val lastCrash = uiState.lastCrashReport
    if (lastCrash != null) {
      Surface(
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            text = "Captured Prior Crash Report: ${lastCrash.exceptionSummary}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold
          )
          Text(lastCrash.deviceInfo, style = MaterialTheme.typography.labelSmall, color = ext.subtleText)
          TextButton(onClick = { DiagnosticsLogger.dismissCrashReport() }) {
            Text("Dismiss Crash Alert")
          }
        }
      }
    }

    // Live Update Speed (4-segment parity: 20s | 30s | 45s | 60s)
    Surface(
      color = ext.cardSurface,
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, ext.cardBorder, RoundedCornerShape(14.dp))
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Live Update Speed During Active Games", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
        val speeds = listOf(20 to "Fast 20s", 30 to "Std 30s", 45 to "45s", 60 to "Saver 60s")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
          speeds.forEachIndexed { idx, (sec, label) ->
            SegmentedButton(
              selected = settings.pollIntervalSeconds == sec,
              onClick = { coordinator.updateSettings { it.copy(pollIntervalSeconds = sec) } },
              shape = SegmentedButtonDefaults.itemShape(index = idx, count = speeds.size),
              label = { Text(label, maxLines = 1) }
            )
          }
        }
      }
    }

    // Help, Roster Sync & Support Actions
    Surface(
      color = ext.cardSurface,
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, ext.cardBorder, RoundedCornerShape(14.dp))
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Help, Schedule Sync & Support", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = { coordinator.refreshNflScheduleAndPlayers() }) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (BuildConfig.IS_PLAY_STORE_BUILD) "Sync Rosters & Schedule" else "Sync NFL Schedule & Rosters")
          }
          FilledTonalButton(onClick = onOpenQuickTour) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Replay Quick Tour")
          }
          FilledTonalButton(onClick = { DiagnosticsLogger.emailSupportIntent(context) }) {
            Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Email Support")
          }
          OutlinedButton(
            onClick = {
              runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.motisko.com/gameday/privacy")))
              }
            }
          ) {
            Text("Privacy Policy")
          }
        }

        HorizontalDivider(color = ext.cardBorder)
        SettingToggleRow(
          title = "Share Anonymous Crash & Feed Health Reports",
          subtitle = "Optional redacted diagnostics to Motisko Ventures LLC (never shares logins or league IDs)",
          checked = settings.optInAnonymousTelemetry,
          onCheckedChange = { enabled ->
            coordinator.updateSettings { it.copy(optInAnonymousTelemetry = enabled) }
            if (enabled) {
              GameDayTelemetry.maybeSendAutoHeartbeat(
                operatingMode = settings.operatingMode.name,
                optInEnabled = true,
                lastCrash = uiState.lastCrashReport
              )
            }
          }
        )
        if (!uiState.telemetryBeaconStatus.isNullOrBlank()) {
          Text(uiState.telemetryBeaconStatus, style = MaterialTheme.typography.labelSmall, color = ext.turfAccent)
        }
      }
    }

    // Tucked Troubleshooting & Logs Drawer
    ExpandableSettingsDrawer(
      title = "Troubleshooting & Diagnostic Logs",
      subtitle = "Feed Health: ${tel.pollSuccessRatePct}% · $warnOrErrorCount alerts",
      expanded = logsExpanded,
      onToggle = { logsExpanded = !logsExpanded }
    ) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatMetricTile("Network", if (uiState.isNetworkAvailable) "Online" else "Offline", uiState.isNetworkAvailable, Modifier.weight(1f))
        StatMetricTile("Feed Health", "${tel.pollSuccessRatePct}%", tel.pollSuccessRatePct >= 90, Modifier.weight(1f))
        StatMetricTile("Avg Latency", "${tel.espnAvgLatencyMs}ms", tel.espnAvgLatencyMs in 0..2000, Modifier.weight(1f))
      }
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatMetricTile("TTS Voices", "${uiState.availableVoices.size}", uiState.availableVoices.isNotEmpty(), Modifier.weight(1f))
        StatMetricTile("Spoken Calls", "${tel.ttsAnnouncementsSpoken}", true, Modifier.weight(1f))
        StatMetricTile("Alerts", "$warnOrErrorCount", warnOrErrorCount == 0, Modifier.weight(1f))
      }

      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { showDiagnosticsDialog = true }) {
          Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text("Open Live Console")
        }
        FilledTonalButton(onClick = { copiedFeedback = DiagnosticsLogger.copyReportToClipboard(context) }) {
          Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text(if (copiedFeedback) "Copied!" else "Copy Report")
        }
        OutlinedButton(onClick = { DiagnosticsLogger.shareReportIntent(context) }) {
          Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text("Share Logs")
        }
        OutlinedButton(
          onClick = {
            GameDayTelemetry.sendManualSnapshotNow(
              operatingMode = settings.operatingMode.name,
              lastCrash = uiState.lastCrashReport
            )
          }
        ) {
          Text("Send Health Snapshot")
        }
      }
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
          Text("Diagnostic Console", fontWeight = FontWeight.ExtraBold)
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
              .height(300.dp)
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
