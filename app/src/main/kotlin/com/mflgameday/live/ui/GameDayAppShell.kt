package com.mflgameday.live.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsFootball
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mflgameday.live.model.GamePhase
import com.mflgameday.live.model.OperatingMode
import com.mflgameday.live.service.GameDayCoordinator
import com.mflgameday.live.service.GameDayUiState
import com.mflgameday.live.ui.components.GameDayStatusBar
import com.mflgameday.live.ui.components.LiveCelebrationBanner
import com.mflgameday.live.ui.components.LiveSpeakingRibbon
import com.mflgameday.live.ui.components.LocalClayHeadsDir
import com.mflgameday.live.ui.components.LocalClayManifest
import com.mflgameday.live.ui.components.QuickTourDialog
import com.mflgameday.live.ui.screens.FantasyMatchupsScreen
import com.mflgameday.live.ui.screens.NflGamesScreen
import com.mflgameday.live.ui.screens.PlayFeedScreen
import com.mflgameday.live.ui.screens.SettingsAndVoicesScreen
import com.mflgameday.live.ui.screens.SetupAndAuthScreen
import com.mflgameday.live.ui.screens.settings.QuickBoothBottomSheet
import com.mflgameday.live.ui.theme.LocalGameDayColors
import com.mflgameday.live.update.AppUpdateBannerKind
import com.mflgameday.live.update.AppUpdateUiState

enum class MainTab(val label: String, val icon: ImageVector) {
  FANTASY("Fantasy", Icons.Default.EmojiEvents),
  NFL_GAMES(if (com.mflgameday.live.BuildConfig.IS_PLAY_STORE_BUILD) "Live Games" else "NFL Live", Icons.Default.SportsFootball),
  PLAY_FEED("Plays", Icons.Default.Campaign),
  SETTINGS("Settings", Icons.Default.Settings)
}

/**
 * Adaptive top-level shell tailored for:
 * - Compact Phones & Pixel 11 Pro Fold Outer Cover (`< 600dp` -> Bottom Navigation Bar)
 * - Unfolded Foldables & Portrait Tablets (`600..1099dp` -> Left Navigation Rail)
 * - Landscape Tablets (Samsung Tab S7) & Desktop (`>= 1100dp` -> Navigation Rail + Live Command Center Ticker Split!)
 */
@Composable
fun GameDayAppShell(
  uiState: GameDayUiState,
  coordinator: GameDayCoordinator,
  updateUiState: AppUpdateUiState = AppUpdateUiState(),
  onApplyAppUpdate: () -> Unit = {},
  onDismissAppUpdate: () -> Unit = {},
  onRequestNotificationPermission: () -> Unit
) {
  val ext = LocalGameDayColors.current
  var selectedTab by rememberSaveable {
    mutableStateOf(
      if (uiState.settings.operatingMode == OperatingMode.NFL_LIVE_ONLY) MainTab.NFL_GAMES else MainTab.FANTASY
    )
  }
  var showAddLeagueModal by rememberSaveable { mutableStateOf(false) }
  var showManualQuickTour by rememberSaveable { mutableStateOf(false) }
  var showQuickBoothSheet by rememberSaveable { mutableStateOf(false) }

  if (uiState.settings.operatingMode == OperatingMode.UNCONFIGURED || showAddLeagueModal) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
      SetupAndAuthScreen(
        uiState = uiState,
        coordinator = coordinator,
        onFinishedSetup = {
          showAddLeagueModal = false
          if (coordinator.uiState.value.settings.operatingMode == OperatingMode.NFL_LIVE_ONLY) {
            selectedTab = MainTab.NFL_GAMES
          } else {
            selectedTab = MainTab.FANTASY
          }
        }
      )
    }
    return
  }

  CompositionLocalProvider(
    LocalClayManifest provides uiState.clayManifest,
    LocalClayHeadsDir provides coordinator.clayPackManager.headsDir
  ) {
    BoxWithConstraints(
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
    val shellWidth = maxWidth
    val useNavRail = shellWidth >= 600.dp
    val isCompactHeader = shellWidth < 680.dp
    val showPinnedLiveTicker = shellWidth >= 1150.dp &&
      uiState.settings.commandCenterSplitOnWideScreens &&
      selectedTab != MainTab.PLAY_FEED &&
      selectedTab != MainTab.SETTINGS

    val liveNflCount = uiState.nflGames.count { it.phase == GamePhase.LIVE || it.phase == GamePhase.HALFTIME }

    Column(modifier = Modifier.fillMaxSize()) {
      // Top Broadcast Control Header
      TopBroadcastBar(
        uiState = uiState,
        compact = isCompactHeader,
        ultraCompact = shellWidth < 410.dp,
        onOpenBoothSheet = { showQuickBoothSheet = true },
        onToggleMute = { coordinator.updateSettings { it.copy(muted = !it.muted) } },
        onRefresh = { coordinator.refreshNow() }
      )

      if (updateUiState.bannerKind != AppUpdateBannerKind.NONE) {
        AppUpdateBannerStrip(
          updateUiState = updateUiState,
          onPrimaryAction = onApplyAppUpdate,
          onDismiss = onDismissAppUpdate
        )
      }

      if (uiState.isBroadcastPaused) {
        Surface(
          color = ext.opponentBadge.copy(alpha = 0.16f),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(
              text = "Live announcements & background polling are paused.",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.weight(1f)
            )
            FilledTonalButton(onClick = { coordinator.resumeLiveBroadcastSession() }) {
              Text("Resume Live")
            }
          }
        }
      }

      // Live TTS Speaking Equalizer / Replay Last Call Ribbon
      LiveSpeakingRibbon(
        isSpeaking = uiState.isSpeakingNow,
        activeText = uiState.activeSpeechText,
        lastSpokenText = uiState.lastSpokenText,
        onStopSpeaking = { coordinator.voiceEngine.stopAndClearQueue() },
        onReplayLast = { coordinator.voiceEngine.replayLastCall(uiState.settings) }
      )

      // Animated Slide-Down Touchdown / Turnover / RedZone Celebration Banner
      LiveCelebrationBanner(
        entry = uiState.activeBannerAlert,
        onSpeakAgain = { entry ->
          coordinator.voiceEngine.speakImmediateSample(entry.spokenText, uiState.settings, entry.event.kind)
        },
        onDismiss = { coordinator.dismissBannerAlert() }
      )

      // Main Adaptive Body
      Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
        if (useNavRail) {
          NavigationRail(
            containerColor = MaterialTheme.colorScheme.surface
          ) {
            Spacer(Modifier.weight(0.15f))
            MainTab.entries.forEach { tab ->
              NavigationRailItem(
                selected = selectedTab == tab,
                onClick = { selectedTab = tab },
                icon = {
                  TabIconWithBadge(tab = tab, liveNflCount = liveNflCount, playCount = uiState.playLog.size)
                },
                label = { Text(tab.label) }
              )
            }
            Spacer(Modifier.weight(1f))
          }
          Box(
            modifier = Modifier
              .width(1.dp)
              .fillMaxHeight()
              .background(ext.cardBorder)
          )
        }

        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
          when (selectedTab) {
            MainTab.FANTASY -> FantasyMatchupsScreen(
              uiState = uiState,
              coordinator = coordinator,
              onNavigateToAddLeague = { showAddLeagueModal = true }
            )
            MainTab.NFL_GAMES -> NflGamesScreen(
              uiState = uiState,
              coordinator = coordinator
            )
            MainTab.PLAY_FEED -> PlayFeedScreen(
              uiState = uiState,
              coordinator = coordinator
            )
            MainTab.SETTINGS -> SettingsAndVoicesScreen(
              uiState = uiState,
              coordinator = coordinator,
              onRequestNotificationPermission = onRequestNotificationPermission,
              onOpenAddLeague = { showAddLeagueModal = true },
              onOpenQuickTour = { showManualQuickTour = true }
            )
          }
        }

        // Ultra-wide Command Center Live Ticker Pane (Samsung Tab S7 Landscape / Desktop / Wide Fold)
        if (showPinnedLiveTicker) {
          Box(
            modifier = Modifier
              .width(1.dp)
              .fillMaxHeight()
              .background(ext.cardBorder)
          )
          Box(
            modifier = Modifier
              .width(315.dp)
              .fillMaxHeight()
              .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
          ) {
            PlayFeedScreen(
              uiState = uiState,
              coordinator = coordinator,
              compactSideRailMode = true
            )
          }
        }
      }

      // Bottom Status & Countdown Bar
      HorizontalDivider(color = ext.cardBorder.copy(alpha = 0.7f))
      GameDayStatusBar(
        statusMessage = uiState.statusMessage,
        warningMessage = uiState.warningMessage,
        isNetworkAvailable = uiState.isNetworkAvailable,
        isRefreshing = uiState.isRefreshing,
        lastUpdatedMillis = uiState.lastUpdatedMillis,
        nextRefreshAtMillis = uiState.nextRefreshAtMillis,
        pollIntervalSec = uiState.currentPollIntervalSec
      )

      if (!useNavRail) {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
          MainTab.entries.forEach { tab ->
            NavigationBarItem(
              selected = selectedTab == tab,
              onClick = { selectedTab = tab },
              icon = {
                TabIconWithBadge(tab = tab, liveNflCount = liveNflCount, playCount = uiState.playLog.size)
              },
              label = { Text(tab.label, maxLines = 1) }
            )
          }
        }
      }
    }

    if (showQuickBoothSheet) {
      QuickBoothBottomSheet(
        uiState = uiState,
        coordinator = coordinator,
        onOpenFullSettings = { selectedTab = MainTab.SETTINGS },
        onDismiss = { showQuickBoothSheet = false }
      )
    }

    if (!uiState.settings.hasCompletedQuickTour || showManualQuickTour) {
      QuickTourDialog(
        selectedPersona = uiState.settings.voicePersona,
        onSelectAndSamplePersona = { persona ->
          val updated = uiState.settings.copy(voicePersona = persona, selectedSystemVoiceId = null)
          coordinator.updateSettings { updated }
          coordinator.voiceEngine.speakImmediateSample(persona.sampleLine, updated, persona.sampleKind)
        },
        onSampleVoice = { coordinator.simulateNextLivePlay() },
        onDismissAndComplete = {
          showManualQuickTour = false
          if (!uiState.settings.hasCompletedQuickTour) {
            coordinator.updateSettings { it.copy(hasCompletedQuickTour = true) }
          }
        }
      )
    }
  }
  }
}

@Composable
private fun TabIconWithBadge(tab: MainTab, liveNflCount: Int, playCount: Int) {
  when {
    tab == MainTab.NFL_GAMES && liveNflCount > 0 -> {
      BadgedBox(badge = { Badge { Text(liveNflCount.toString()) } }) {
        Icon(tab.icon, contentDescription = tab.label)
      }
    }
    tab == MainTab.PLAY_FEED && playCount > 0 -> {
      BadgedBox(badge = { Badge { Text(playCount.coerceAtMost(99).toString()) } }) {
        Icon(tab.icon, contentDescription = tab.label)
      }
    }
    else -> Icon(tab.icon, contentDescription = tab.label)
  }
}

@Composable
private fun TopBroadcastBar(
  uiState: GameDayUiState,
  compact: Boolean,
  ultraCompact: Boolean,
  onOpenBoothSheet: () -> Unit,
  onToggleMute: () -> Unit,
  onRefresh: () -> Unit
) {
  val ext = LocalGameDayColors.current
  val brandPrefix = if (com.mflgameday.live.BuildConfig.IS_PLAY_STORE_BUILD) "GameDay Live" else "MFL GameDay"
  val titleText = when (uiState.settings.operatingMode) {
    OperatingMode.NFL_LIVE_ONLY -> "$brandPrefix · Live Scores"
    OperatingMode.DEMO_SIMULATOR -> "$brandPrefix · Sunday Simulator"
    else -> uiState.activeLeagueSnapshot?.config?.leagueName ?: brandPrefix
  }
  val boothLabel = if (ultraCompact) {
    uiState.settings.voicePersona.shortName
  } else {
    "${uiState.settings.voicePersona.shortName} · ${uiState.settings.scope.shortLabel}"
  }

  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
    modifier = Modifier.fillMaxWidth()
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.horizontalGradient(
            listOf(ext.headerGradientStart, ext.headerGradientEnd)
          )
        )
        .padding(horizontal = 14.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Icon(
        imageVector = Icons.Default.SportsFootball,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp)
      )

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = titleText,
          style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.ExtraBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = "Week ${uiState.currentWeek} · ${uiState.settings.scope.shortLabel}" +
            if (uiState.leagues.size > 1) " · ${uiState.leagues.size} Leagues" else "",
          style = MaterialTheme.typography.labelSmall,
          color = ext.subtleText,
          maxLines = 1
        )
      }

      Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier
          .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
          .clip(RoundedCornerShape(999.dp))
          .clickable(onClick = onOpenBoothSheet)
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = "Open Broadcast Booth",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp)
          )
          Text(
            text = boothLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = ext.subtleText,
            modifier = Modifier.size(16.dp)
          )
        }
      }

      IconButton(
        onClick = onToggleMute,
        modifier = Modifier
          .clip(RoundedCornerShape(10.dp))
          .background(
            if (uiState.settings.muted) MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
            else ext.turfAccent.copy(alpha = 0.16f)
          )
      ) {
        Icon(
          imageVector = if (uiState.settings.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
          contentDescription = if (uiState.settings.muted) "Unmute announcer" else "Mute announcer",
          tint = if (uiState.settings.muted) MaterialTheme.colorScheme.error else ext.turfAccent
        )
      }

      IconButton(onClick = onRefresh) {
        Icon(
          imageVector = Icons.Default.Refresh,
          contentDescription = "Refresh now",
          tint = MaterialTheme.colorScheme.onSurface
        )
      }
    }
  }
}

@Composable
private fun AppUpdateBannerStrip(
  updateUiState: AppUpdateUiState,
  onPrimaryAction: () -> Unit,
  onDismiss: () -> Unit
) {
  val ext = LocalGameDayColors.current
  Surface(
    color = ext.turfAccent.copy(alpha = 0.14f),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Icon(
          imageVector = Icons.Default.SystemUpdate,
          contentDescription = null,
          tint = ext.turfAccent,
          modifier = Modifier.size(18.dp)
        )
        Text(
          text = when (updateUiState.bannerKind) {
            AppUpdateBannerKind.PLAY_STORE_DOWNLOADING -> "Downloading GameDay Live update in background…"
            AppUpdateBannerKind.PLAY_STORE_RESTART_READY -> "New update ready — restart whenever convenient."
            AppUpdateBannerKind.DIRECT_APK_AVAILABLE -> {
              val ver = updateUiState.availableVersionLabel ?: "update"
              "New Pro Edition $ver is available."
            }
            AppUpdateBannerKind.NONE -> ""
          },
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.weight(1f),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )

        if (updateUiState.bannerKind == AppUpdateBannerKind.PLAY_STORE_RESTART_READY) {
          FilledTonalButton(onClick = onPrimaryAction) {
            Text("Restart Now")
          }
        } else if (updateUiState.bannerKind == AppUpdateBannerKind.DIRECT_APK_AVAILABLE) {
          FilledTonalButton(onClick = onPrimaryAction) {
            Text("Get Update")
          }
        }

        IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Dismiss update notice",
            tint = ext.subtleText,
            modifier = Modifier.size(16.dp)
          )
        }
      }
      if (updateUiState.bannerKind == AppUpdateBannerKind.PLAY_STORE_DOWNLOADING) {
        val frac = updateUiState.downloadProgressFraction
        if (frac != null) {
          LinearProgressIndicator(
            progress = { frac },
            modifier = Modifier.fillMaxWidth()
          )
        } else {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
      }
    }
  }
}
