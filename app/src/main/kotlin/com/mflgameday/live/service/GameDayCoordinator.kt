package com.mflgameday.live.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.mflgameday.live.audio.GameDayVoiceEngine
import com.mflgameday.live.data.ClayPackManager
import com.mflgameday.live.data.ClayPackManifest
import com.mflgameday.live.data.EspnApiClient
import com.mflgameday.live.data.GameDayStorage
import com.mflgameday.live.data.ManifestSyncOutcome
import com.mflgameday.live.data.MflApiClient
import com.mflgameday.live.data.MflLeagueMetadata
import com.mflgameday.live.data.MflStatusInfo
import com.mflgameday.live.data.ProfileSyncCodec
import com.mflgameday.live.diagnostics.CrashReportSummary
import com.mflgameday.live.diagnostics.DiagnosticEntry
import com.mflgameday.live.diagnostics.DiagnosticsLogger
import com.mflgameday.live.diagnostics.GameDayFirebaseBridge
import com.mflgameday.live.diagnostics.GameDayTelemetry
import com.mflgameday.live.diagnostics.LogSubsystem
import com.mflgameday.live.diagnostics.TelemetrySnapshot
import com.mflgameday.live.engine.BestBallAndPaceCalculator
import com.mflgameday.live.engine.DemoSimulatorData
import com.mflgameday.live.engine.EventDiffer
import com.mflgameday.live.engine.PlaySpeechFormatter
import com.mflgameday.live.engine.PlayerNameExpander
import com.mflgameday.live.model.DiscoveredMflLeague
import com.mflgameday.live.model.ExposureRole
import com.mflgameday.live.model.FantasyPlayer
import com.mflgameday.live.model.FranchiseInfo
import com.mflgameday.live.model.GameDaySettings
import com.mflgameday.live.model.GameEvent
import com.mflgameday.live.model.GameEventKind
import com.mflgameday.live.model.GamePhase
import com.mflgameday.live.model.LeagueConfig
import com.mflgameday.live.model.LeagueLiveSnapshot
import com.mflgameday.live.model.LiveFieldSituationResolver
import com.mflgameday.live.model.NflGameState
import com.mflgameday.live.model.OperatingMode
import com.mflgameday.live.model.PlayLogEntry
import com.mflgameday.live.model.PlayerCrossLeagueExposure
import com.mflgameday.live.model.SpokenCategory
import com.mflgameday.live.model.VoiceOption
import java.io.File
import java.time.Year
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GameDayUiState(
  val settings: GameDaySettings = GameDaySettings(),
  val leagues: List<LeagueConfig> = emptyList(),
  val leagueSnapshots: Map<String, LeagueLiveSnapshot> = emptyMap(),
  val nflGames: List<NflGameState> = emptyList(),
  val playersDb: Map<String, FantasyPlayer> = emptyMap(),
  val crossLeagueExposures: Map<String, PlayerCrossLeagueExposure> = emptyMap(),
  val playLog: List<PlayLogEntry> = emptyList(),
  val activeBannerAlert: PlayLogEntry? = null,
  val currentSeasonYear: Int = Year.now().value,
  val currentWeek: Int = 1,
  val statusMessage: String = "Ready",
  val warningMessage: String? = null,
  val isRefreshing: Boolean = false,
  val isBroadcastPaused: Boolean = false,
  val lastUpdatedMillis: Long? = null,
  val nextRefreshAtMillis: Long? = null,
  val currentPollIntervalSec: Int = 30,
  // Auth / League Setup helper state
  val isSignedInMfl: Boolean = false,
  val authBusy: Boolean = false,
  val authError: String? = null,
  val discoveredLeagues: List<DiscoveredMflLeague> = emptyList(),
  val pendingManualMetadata: MflLeagueMetadata? = null,
  // Voice Engine live status
  val availableVoices: List<VoiceOption> = emptyList(),
  val isSpeakingNow: Boolean = false,
  val activeSpeechText: String? = null,
  val lastSpokenText: String? = null,
  // Network, Telemetry & Diagnostics
  val isNetworkAvailable: Boolean = true,
  val diagnosticEntries: List<DiagnosticEntry> = emptyList(),
  val lastCrashReport: CrashReportSummary? = null,
  val telemetrySnapshot: TelemetrySnapshot = TelemetrySnapshot(),
  val telemetryBeaconStatus: String? = null,
  val clayManifest: ClayPackManifest = ClayPackManifest()
) {
  val activeLeagueSnapshot: LeagueLiveSnapshot?
    get() {
      val targetId = settings.activeLeagueId ?: leagues.firstOrNull()?.leagueId
      return if (targetId != null) leagueSnapshots[targetId] else null
    }
}

/**
 * Application-wide reactive orchestrator for MFL multi-league live scoring,
 * ESPN real-time NFL scoreboard & play-by-play, Best Ball & Win Probability analytics,
 * and spoken announcements. Survives Activity recreation, folding/unfolding, and backgrounding.
 */
class GameDayCoordinator(
  context: Context,
  private val mflClient: MflApiClient = MflApiClient(),
  private val espnClient: EspnApiClient = EspnApiClient()
) {
  private val appContext = context.applicationContext
  private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
    DiagnosticsLogger.error(LogSubsystem.APP, "Unhandled coroutine error caught safely", throwable)
  }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler)
  private val storage = GameDayStorage(appContext)
  val clayPackManager = ClayPackManager(File(appContext.filesDir, "gameday_cache"))
  val voiceEngine = GameDayVoiceEngine(appContext)

  private val differ = EventDiffer()
  private var nameExpander: PlayerNameExpander? = null
  private val cachedProjectionsByLeague = HashMap<String, Map<String, Double>>()
  private val cachedFranchisesByLeague = HashMap<String, Map<String, FranchiseInfo>>()
  private val pendingBreakdownRetryByLeague = HashSet<String>()
  private var mflScheduleEtag: String? = null
  private var lastStatusSyncMillis: Long = 0L
  private var lastKnownMflStatus: MflStatusInfo? = null
  private var lastClaySyncMillis: Long = 0L
  private var pollLoopJob: Job? = null
  private var sleepJob: Job? = null
  private var bannerDismissJob: Job? = null
  private var demoSimStep: Int = 0
  @Volatile
  private var broadcastGeneration: Long = 0L
  @Volatile
  private var isAppInForeground: Boolean = false
  @Volatile
  private var pausedExplicitlyByUser: Boolean = false

  private val _uiState = MutableStateFlow(
    GameDayUiState(
      settings = storage.loadSettings(),
      leagues = storage.loadLeagues(),
      isSignedInMfl = !storage.loadMflUserCookie().isNullOrBlank(),
      clayManifest = clayPackManager.manifest.value
    )
  )
  val uiState: StateFlow<GameDayUiState> = _uiState.asStateFlow()

  init {
    GameDayFirebaseBridge.install(appContext, _uiState.value.settings.optInAnonymousTelemetry)
    scope.launch {
      clayPackManager.manifest.collect { manifest ->
        _uiState.update { it.copy(clayManifest = manifest) }
      }
    }
    scope.launch {
      maybeSyncClayManifest(force = true)
    }
    scope.launch {
      val rawSavedLog = storage.loadPlayLog()
      val savedLog = rawSavedLog.filterNot { ServiceLifecyclePolicy.isSimulatedPlayId(it.event.id) }
      if (savedLog.size != rawSavedLog.size) {
        storage.savePlayLog(savedLog)
      }
      if (savedLog.isNotEmpty()) {
        differ.seedSeenFromLog(savedLog)
        _uiState.update { it.copy(playLog = savedLog) }
      }
      val currentMode = _uiState.value.settings.operatingMode
      if (currentMode != OperatingMode.UNCONFIGURED) {
        startLiveEngine()
      }
    }

    scope.launch {
      voiceEngine.availableVoices.collect { voices ->
        _uiState.update { it.copy(availableVoices = voices) }
      }
    }
    scope.launch {
      voiceEngine.isSpeaking.collect { speaking ->
        val wasSpeaking = _uiState.value.isSpeakingNow
        _uiState.update { it.copy(isSpeakingNow = speaking) }
        if (wasSpeaking && !speaking) {
          onSpeechQueueIdle()
        }
      }
    }
    scope.launch {
      voiceEngine.activeSpeechText.collect { txt ->
        _uiState.update { it.copy(activeSpeechText = txt) }
      }
    }
    scope.launch {
      voiceEngine.lastSpokenText.collect { txt ->
        _uiState.update { it.copy(lastSpokenText = txt) }
      }
    }
    scope.launch {
      DiagnosticsLogger.entries.collect { logs ->
        _uiState.update { it.copy(diagnosticEntries = logs) }
      }
    }
    scope.launch {
      DiagnosticsLogger.lastCrash.collect { crash ->
        _uiState.update { it.copy(lastCrashReport = crash) }
        if (crash != null) {
          GameDayTelemetry.maybeSyncFirebaseHeartbeat(
            context = appContext,
            settings = _uiState.value.settings,
            leagueCount = _uiState.value.leagues.size,
            lastCrash = crash
          )
        }
      }
    }
    scope.launch {
      GameDayTelemetry.snapshot.collect { snap ->
        _uiState.update { it.copy(telemetrySnapshot = snap) }
      }
    }
    scope.launch {
      GameDayTelemetry.lastBeaconStatus.collect { status ->
        _uiState.update { it.copy(telemetryBeaconStatus = status) }
      }
    }
    registerConnectivityWatcher()
    GameDayTelemetry.maybeSyncFirebaseHeartbeat(
      context = appContext,
      settings = _uiState.value.settings,
      leagueCount = _uiState.value.leagues.size,
      lastCrash = _uiState.value.lastCrashReport
    )
  }

  private fun registerConnectivityWatcher() {
    runCatching {
      val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
      val hasInitialNet = cm.activeNetwork?.let { net ->
        cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      } ?: true
      _uiState.update { it.copy(isNetworkAvailable = hasInitialNet) }

      cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          val wasOffline = !_uiState.value.isNetworkAvailable
          _uiState.update { it.copy(isNetworkAvailable = true) }
          if (wasOffline || _uiState.value.clayManifest.manifestVersion == 0) {
            scope.launch { maybeSyncClayManifest(force = false) }
          }
          if (wasOffline) {
            GameDayTelemetry.recordNetworkRecovery()
            if (pollLoopJob?.isActive == true && !_uiState.value.isBroadcastPaused) {
              DiagnosticsLogger.info(LogSubsystem.NETWORK, "Network restored — triggering immediate score refresh")
              sleepJob?.cancel()
            }
          }
        }

        override fun onLost(network: Network) {
          val stillActive = cm.activeNetwork != null
          if (!stillActive) {
            _uiState.update {
              it.copy(
                isNetworkAvailable = false,
                statusMessage = "Offline — Waiting for Wi-Fi / Mobile Data…"
              )
            }
            DiagnosticsLogger.warn(LogSubsystem.NETWORK, "Device network lost; standing by for reconnect")
          }
        }
      })
    }
  }

  // -------------------- Settings & Mode Controls --------------------

  fun updateSettings(transform: (GameDaySettings) -> GameDaySettings) {
    val prev = _uiState.value.settings
    val updated = transform(prev)
    storage.saveSettings(updated)
    _uiState.update { it.copy(settings = updated) }
    if (prev.optInAnonymousTelemetry != updated.optInAnonymousTelemetry) {
      GameDayFirebaseBridge.setCollectionEnabled(appContext, updated.optInAnonymousTelemetry)
      if (updated.optInAnonymousTelemetry) {
        GameDayTelemetry.maybeSyncFirebaseHeartbeat(
          context = appContext,
          settings = updated,
          leagueCount = _uiState.value.leagues.size,
          lastCrash = _uiState.value.lastCrashReport
        )
      }
    }
    if (prev.voicePersona != updated.voicePersona || prev.scope != updated.scope) {
      GameDayFirebaseBridge.logEvent(
        context = appContext,
        eventName = "commentator_selected",
        params = mapOf(
          "persona" to updated.voicePersona.name,
          "scope" to updated.scope.name
        )
      )
    }
    GameDayFirebaseBridge.syncContext(
      context = appContext,
      settings = updated,
      leagueCount = _uiState.value.leagues.size,
      snapshot = _uiState.value.telemetrySnapshot
    )
    if (updated.muted) {
      voiceEngine.stopAndClearQueue()
    }
    syncForegroundServiceState()
  }

  fun syncTelemetrySnapshotNow() {
    val state = _uiState.value
    GameDayTelemetry.syncManualSnapshotNow(
      context = appContext,
      settings = state.settings,
      leagueCount = state.leagues.size,
      lastCrash = state.lastCrashReport
    )
  }

  fun selectActiveLeague(leagueId: String) {
    updateSettings { it.copy(activeLeagueId = leagueId) }
  }

  fun startNflLiveOnlyMode() {
    differ.reset()
    updateSettings {
      it.copy(
        operatingMode = OperatingMode.NFL_LIVE_ONLY,
        scope = com.mflgameday.live.model.AnnouncementScope.ALL_NFL
      )
    }
    GameDayFirebaseBridge.logEvent(
      context = appContext,
      eventName = "mode_activated",
      params = mapOf("mode" to OperatingMode.NFL_LIVE_ONLY.name, "league_count" to _uiState.value.leagues.size)
    )
    restartPollingLoop()
  }

  fun startDemoSimulatorMode() {
    pollLoopJob?.cancel()
    sleepJob?.cancel()
    differ.reset()
    demoSimStep = 0
    val now = System.currentTimeMillis()
    val demoLeagues = listOf(DemoSimulatorData.standardLeagueConfig, DemoSimulatorData.bestBallLeagueConfig)
    val snapshots = DemoSimulatorData.buildDemoSnapshots(now, godwinTdScored = false)
    val games = DemoSimulatorData.initialGames(now)
    nameExpander = PlayerNameExpander(DemoSimulatorData.playersDb.values)

    val exposures = BestBallAndPaceCalculator.buildCrossLeagueExposure(
      snapshots = snapshots,
      playersDb = DemoSimulatorData.playersDb
    )

    val seedEntries = DemoSimulatorData.initialSeedEvents(games, now).reversed().map { ev ->
      buildLogEntry(ev, exposures, DemoSimulatorData.playersDb, wasSpoken = true)
    }

    val updatedSettings = _uiState.value.settings.copy(
      operatingMode = OperatingMode.DEMO_SIMULATOR,
      activeLeagueId = demoLeagues.first().leagueId
    )
    storage.saveSettings(updatedSettings)

    _uiState.update {
      it.copy(
        settings = updatedSettings,
        leagues = demoLeagues,
        leagueSnapshots = snapshots.associateBy { s -> s.config.leagueId },
        nflGames = games,
        playersDb = DemoSimulatorData.playersDb,
        crossLeagueExposures = exposures,
        playLog = seedEntries,
        currentSeasonYear = 2026,
        currentWeek = 11,
        statusMessage = "Live Demo Simulator Ready",
        warningMessage = null,
        isRefreshing = false,
        isBroadcastPaused = false,
        lastUpdatedMillis = now,
        nextRefreshAtMillis = now + 30_000L,
        currentPollIntervalSec = 30
      )
    }
    GameDayFirebaseBridge.logEvent(
      context = appContext,
      eventName = "mode_activated",
      params = mapOf("mode" to OperatingMode.DEMO_SIMULATOR.name, "league_count" to demoLeagues.size)
    )
    syncForegroundServiceState()
  }

  fun exitToSetup() {
    pausedExplicitlyByUser = false
    pollLoopJob?.cancel()
    sleepJob?.cancel()
    pollLoopJob = null
    voiceEngine.stopAndClearQueue()
    GameDayLiveService.cancelPregameWakeAlarm(appContext)
    GameDayLiveService.dismissPregameReminderNotification(appContext)
    val realLeagues = storage.loadLeagues()
    updateSettings { it.copy(operatingMode = OperatingMode.UNCONFIGURED) }
    _uiState.update { it.copy(leagues = realLeagues, statusMessage = "Setup") }
    GameDayLiveService.stop(appContext)
  }

  // -------------------- MFL Login & League Management --------------------

  fun signInWithMflCredentials(username: String, password: String) {
    scope.launch {
      _uiState.update { it.copy(authBusy = true, authError = null) }
      try {
        val status = runCatching { mflClient.fetchStatus() }.getOrNull()
        val year = status?.year ?: Year.now().value
        val cookie = mflClient.login(username = username, password = password, year = year)
        storage.saveMflUserCookie(cookie)
        val discovered = mflClient.fetchMyLeagues(mflUserIdCookie = cookie, year = year)
        updateSettings { it.copy(mflUsername = username.trim()) }
        _uiState.update {
          it.copy(
            authBusy = false,
            isSignedInMfl = true,
            discoveredLeagues = discovered,
            currentSeasonYear = year,
            currentWeek = status?.effectiveActiveWeek ?: 1,
            authError = if (discovered.isEmpty()) "Signed in, but no $year leagues found on this MFL account." else null
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            authBusy = false,
            authError = e.message ?: "MFL sign-in failed."
          )
        }
      }
    }
  }

  fun signOutMfl() {
    storage.saveMflUserCookie(null)
    _uiState.update {
      it.copy(
        isSignedInMfl = false,
        discoveredLeagues = emptyList()
      )
    }
  }

  fun lookupManualLeague(leagueId: String, apiKey: String?) {
    val cleanId = leagueId.trim()
    if (cleanId.isEmpty()) return
    scope.launch {
      _uiState.update { it.copy(authBusy = true, authError = null, pendingManualMetadata = null) }
      try {
        val status = runCatching { mflClient.fetchStatus() }.getOrNull()
        val year = status?.year ?: Year.now().value
        val cookie = storage.loadMflUserCookie()
        val meta = mflClient.fetchLeagueMetadata(
          leagueId = cleanId,
          year = year,
          apiKey = apiKey?.trim()?.takeIf { it.isNotEmpty() },
          mflUserIdCookie = cookie
        )
        if (!apiKey.isNullOrBlank()) {
          storage.saveLeagueApiKey(cleanId, apiKey.trim())
        }
        _uiState.update {
          it.copy(
            authBusy = false,
            currentSeasonYear = year,
            currentWeek = status?.effectiveActiveWeek ?: it.currentWeek,
            pendingManualMetadata = meta
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            authBusy = false,
            authError = "Couldn't load League $cleanId: ${e.message}"
          )
        }
      }
    }
  }

  fun clearPendingManualLeague() {
    _uiState.update { it.copy(pendingManualMetadata = null, authError = null) }
  }

  fun addConfirmedManualLeague(meta: MflLeagueMetadata, franchiseId: String) {
    val franchise = meta.franchises.firstOrNull { it.id == franchiseId }
    val year = _uiState.value.currentSeasonYear
    val config = LeagueConfig(
      leagueId = meta.leagueId,
      leagueName = meta.name,
      year = year,
      franchiseId = franchiseId,
      franchiseName = franchise?.name ?: "Team $franchiseId",
      baseUrl = meta.baseUrl,
      hasCustomApiKey = !storage.loadLeagueApiKey(meta.leagueId).isNullOrBlank(),
      isBestBall = meta.isBestBall,
      precision = meta.precision,
      starterCount = meta.starterCount,
      positionLimits = meta.positionLimits,
      includeInAnnouncements = true,
      bestBallTrackFullRoster = true
    )
    cachedFranchisesByLeague[meta.leagueId] = meta.franchises.associateBy { it.id }
    saveAndActivateLeague(config)
    _uiState.update { it.copy(pendingManualMetadata = null) }
  }

  fun importDiscoveredLeagues(selected: List<DiscoveredMflLeague>) {
    if (selected.isEmpty()) return
    scope.launch {
      _uiState.update { it.copy(authBusy = true, authError = null) }
      val cookie = storage.loadMflUserCookie()
      val existing = storage.loadLeagues().associateBy { it.leagueId }.toMutableMap()

      for (d in selected) {
        runCatching {
          val meta = mflClient.fetchLeagueMetadata(
            leagueId = d.leagueId,
            year = d.year,
            mflUserIdCookie = cookie
          )
          val fId = d.franchiseId ?: meta.franchises.firstOrNull()?.id ?: "0001"
          val fName = meta.franchises.firstOrNull { it.id == fId }?.name
            ?: d.franchiseName
            ?: "Team $fId"
          cachedFranchisesByLeague[meta.leagueId] = meta.franchises.associateBy { it.id }
          existing[meta.leagueId] = LeagueConfig(
            leagueId = meta.leagueId,
            leagueName = meta.name,
            year = d.year,
            franchiseId = fId,
            franchiseName = fName,
            baseUrl = meta.baseUrl,
            isBestBall = meta.isBestBall,
            precision = meta.precision,
            starterCount = meta.starterCount,
            positionLimits = meta.positionLimits,
            includeInAnnouncements = true,
            bestBallTrackFullRoster = true
          )
        }
      }

      val merged = existing.values.toList()
      storage.saveLeagues(merged)
      val newSettings = _uiState.value.settings.copy(
        operatingMode = if (merged.isNotEmpty()) OperatingMode.MFL_AND_NFL_LIVE else _uiState.value.settings.operatingMode,
        activeLeagueId = merged.firstOrNull()?.leagueId
      )
      storage.saveSettings(newSettings)
      _uiState.update {
        it.copy(
          authBusy = false,
          leagues = merged,
          settings = newSettings
        )
      }
      if (merged.isNotEmpty()) {
        restartPollingLoop()
      }
    }
  }

  private fun saveAndActivateLeague(newLeague: LeagueConfig) {
    val current = storage.loadLeagues().filterNot { it.leagueId == newLeague.leagueId } + newLeague
    storage.saveLeagues(current)
    val updatedSettings = _uiState.value.settings.copy(
      operatingMode = OperatingMode.MFL_AND_NFL_LIVE,
      activeLeagueId = newLeague.leagueId
    )
    storage.saveSettings(updatedSettings)
    _uiState.update {
      it.copy(
        leagues = current,
        settings = updatedSettings
      )
    }
    restartPollingLoop()
  }

  fun removeLeague(leagueId: String) {
    val remaining = storage.loadLeagues().filterNot { it.leagueId == leagueId }
    storage.saveLeagues(remaining)
    storage.saveLeagueApiKey(leagueId, null)
    val nextActive = remaining.firstOrNull()?.leagueId
    val nextMode = if (remaining.isEmpty() && _uiState.value.settings.operatingMode == OperatingMode.MFL_AND_NFL_LIVE) {
      OperatingMode.NFL_LIVE_ONLY
    } else {
      _uiState.value.settings.operatingMode
    }
    val newSettings = _uiState.value.settings.copy(
      activeLeagueId = nextActive,
      operatingMode = nextMode
    )
    storage.saveSettings(newSettings)
    _uiState.update {
      it.copy(
        leagues = remaining,
        leagueSnapshots = it.leagueSnapshots - leagueId,
        settings = newSettings
      )
    }
    restartPollingLoop()
  }

  fun toggleLeagueAnnouncements(leagueId: String, enabled: Boolean) {
    val updated = _uiState.value.leagues.map {
      if (it.leagueId == leagueId) it.copy(includeInAnnouncements = enabled) else it
    }
    if (_uiState.value.settings.operatingMode != OperatingMode.DEMO_SIMULATOR) {
      storage.saveLeagues(updated)
    }
    _uiState.update { state ->
      val exposures = BestBallAndPaceCalculator.buildCrossLeagueExposure(
        snapshots = state.leagueSnapshots.values.map { snap ->
          if (snap.config.leagueId == leagueId) snap.copy(config = snap.config.copy(includeInAnnouncements = enabled))
          else snap
        },
        playersDb = state.playersDb
      )
      state.copy(leagues = updated, crossLeagueExposures = exposures)
    }
  }

  /**
   * Exports a secret-free JSON snapshot of the user's tracked MFL leagues, voice commentator,
   * broadcast scope/categories, and theme so setups clone across Phone <-> Tablet or Direct <-> Play Store in 2 taps.
   */
  fun exportSetupProfileJson(): String {
    val state = _uiState.value
    val realLeagues = if (state.settings.operatingMode == OperatingMode.DEMO_SIMULATOR) {
      storage.loadLeagues()
    } else {
      state.leagues
    }
    val encoded = ProfileSyncCodec.encodeProfile(
      settings = state.settings,
      leagues = realLeagues
    )
    GameDayFirebaseBridge.logEvent(
      context = appContext,
      eventName = "profile_sync_action",
      params = mapOf("direction" to "export", "league_count" to realLeagues.size)
    )
    DiagnosticsLogger.info(LogSubsystem.APP, "Exported secret-free setup profile (${realLeagues.size} league(s))")
    return encoded
  }

  /**
   * Validates and imports a secret-free GameDay setup profile JSON, preserving any local
   * Android Keystore credentials already saved on this device.
   */
  fun importSetupProfileJson(rawJson: String): Result<Int> {
    val decoded = ProfileSyncCodec.decodeOrNull(rawJson)
      ?: return Result.failure(IllegalArgumentException("Not a valid GameDay Live profile JSON."))
    val wasDemo = _uiState.value.settings.operatingMode == OperatingMode.DEMO_SIMULATOR
    val existingLeagues = storage.loadLeagues()
    val applied = ProfileSyncCodec.applyDecodedProfile(
      decoded = decoded,
      existingSettings = _uiState.value.settings,
      existingLeagues = existingLeagues
    )
    val mergedWithKeyFlags = applied.leagues.map { cfg ->
      cfg.copy(hasCustomApiKey = !storage.loadLeagueApiKey(cfg.leagueId).isNullOrBlank())
    }
    val allowedIds = mergedWithKeyFlags.map { it.leagueId }.toSet()
    storage.saveLeagues(mergedWithKeyFlags)
    storage.saveSettings(applied.settings)
    if (wasDemo) {
      nameExpander = null
    }
    differ.reset()
    _uiState.update { state ->
      val keptSnapshots = if (wasDemo) {
        emptyMap()
      } else {
        state.leagueSnapshots.filterKeys { id -> id in allowedIds }
      }
      val cleanedPlayers = if (wasDemo) emptyMap() else state.playersDb
      val cleanedGames = if (wasDemo) emptyList() else state.nflGames
      val cleanedPlayLog = state.playLog.filterNot { entry ->
        ServiceLifecyclePolicy.isSimulatedPlayId(entry.event.id)
      }
      val recomputedExposures = if (keptSnapshots.isEmpty()) {
        emptyMap()
      } else {
        BestBallAndPaceCalculator.buildCrossLeagueExposure(keptSnapshots.values, cleanedPlayers)
      }
      state.copy(
        settings = applied.settings,
        leagues = mergedWithKeyFlags,
        leagueSnapshots = keptSnapshots,
        nflGames = cleanedGames,
        playersDb = cleanedPlayers,
        crossLeagueExposures = recomputedExposures,
        playLog = cleanedPlayLog,
        activeBannerAlert = null,
        statusMessage = "Setup profile imported (${mergedWithKeyFlags.size} league(s))"
      )
    }
    GameDayFirebaseBridge.syncContext(
      context = appContext,
      settings = applied.settings,
      leagueCount = mergedWithKeyFlags.size,
      snapshot = _uiState.value.telemetrySnapshot
    )
    GameDayFirebaseBridge.logEvent(
      context = appContext,
      eventName = "profile_sync_action",
      params = mapOf("direction" to "import", "league_count" to mergedWithKeyFlags.size)
    )
    DiagnosticsLogger.info(LogSubsystem.APP, "Imported setup profile (${mergedWithKeyFlags.size} league(s))")
    if (applied.settings.operatingMode != OperatingMode.UNCONFIGURED) {
      restartPollingLoop()
    }
    return Result.success(mergedWithKeyFlags.size)
  }

  // -------------------- Live Polling, Schedule Refresh & Simulator --------------------

  /**
   * Invoked exclusively by `GameDayLiveService.onStartCommand` — starts polling if needed
   * WITHOUT calling `syncForegroundServiceState()` back into `GameDayLiveService.start()`,
   * eliminating re-entrant `startForegroundService` loops.
   */
  internal fun ensureEngineStartedFromService() {
    val state = _uiState.value
    val mode = state.settings.operatingMode
    if (mode == OperatingMode.UNCONFIGURED || state.isBroadcastPaused) return
    if (mode == OperatingMode.DEMO_SIMULATOR) {
      if (state.nflGames.isEmpty()) startDemoSimulatorMode()
      return
    }
    if (pollLoopJob?.isActive == true) return
    restartPollingLoop(syncService = false)
  }

  fun startLiveEngine() {
    val mode = _uiState.value.settings.operatingMode
    if (mode == OperatingMode.UNCONFIGURED) return
    pausedExplicitlyByUser = false
    _uiState.update { it.copy(isBroadcastPaused = false) }
    if (mode == OperatingMode.DEMO_SIMULATOR) {
      if (_uiState.value.nflGames.isEmpty()) {
        startDemoSimulatorMode()
      } else {
        syncForegroundServiceState()
      }
      return
    }
    if (pollLoopJob?.isActive == true) {
      syncForegroundServiceState()
      return
    }
    restartPollingLoop(syncService = true)
  }

  fun pauseLiveBroadcastSession(
    reason: String = "Broadcast paused",
    explicitUserPause: Boolean = true
  ) {
    pausedExplicitlyByUser = explicitUserPause
    broadcastGeneration += 1L
    pollLoopJob?.cancel()
    sleepJob?.cancel()
    pollLoopJob = null
    sleepJob = null
    voiceEngine.stopAndClearQueue()
    GameDayLiveService.cancelPregameWakeAlarm(appContext)
    GameDayLiveService.dismissPregameReminderNotification(appContext)
    _uiState.update {
      it.copy(
        isBroadcastPaused = true,
        isRefreshing = false,
        nextRefreshAtMillis = null,
        statusMessage = "$reason · Reopen or tap Resume"
      )
    }
    DiagnosticsLogger.info(LogSubsystem.APP, reason)
    GameDayLiveService.stop(appContext)
  }

  fun resumeLiveBroadcastSession() {
    if (_uiState.value.settings.operatingMode == OperatingMode.UNCONFIGURED) return
    pausedExplicitlyByUser = false
    _uiState.update { it.copy(isBroadcastPaused = false) }
    if (_uiState.value.settings.operatingMode == OperatingMode.DEMO_SIMULATOR) {
      _uiState.update { it.copy(statusMessage = "Live Demo Simulator Ready") }
      syncForegroundServiceState()
    } else {
      restartPollingLoop(syncService = true)
    }
  }

  private suspend fun maybeSyncClayManifest(force: Boolean) {
    clayPackManager.reloadFromDisk()
    val now = System.currentTimeMillis()
    val needsInitialLoad = _uiState.value.clayManifest.manifestVersion == 0
    if (!force && !needsInitialLoad && (now - lastClaySyncMillis <= 1_800_000L)) {
      return
    }
    val outcome = clayPackManager.syncManifestIfUpdated()
    if (outcome != ManifestSyncOutcome.FAILED) {
      lastClaySyncMillis = System.currentTimeMillis()
    }
  }

  fun onAppForegrounded() {
    isAppInForeground = true
    GameDayLiveService.dismissPregameReminderNotification(appContext)
    scope.launch { maybeSyncClayManifest(force = false) }
    val state = _uiState.value
    val isLoopActive = pollLoopJob?.isActive == true
    if (
      ServiceLifecyclePolicy.shouldAutoResumeOnAppForeground(
        settings = state.settings,
        isBroadcastPaused = state.isBroadcastPaused,
        isPollLoopActive = isLoopActive
      )
    ) {
      DiagnosticsLogger.info(LogSubsystem.APP, "App reopened in foreground — resuming live broadcast")
      resumeLiveBroadcastSession()
      return
    }
    val allGamesFinal = state.nflGames.isNotEmpty() && state.nflGames.all { it.phase == GamePhase.FINAL }
    if (
      ServiceLifecyclePolicy.shouldWakeActivePollOnAppForeground(
        settings = state.settings,
        isBroadcastPaused = state.isBroadcastPaused,
        isPollLoopActive = isLoopActive,
        isRefreshing = state.isRefreshing,
        lastUpdatedMillis = state.lastUpdatedMillis,
        nowMillis = System.currentTimeMillis(),
        allGamesFinal = allGamesFinal
      )
    ) {
      DiagnosticsLogger.info(LogSubsystem.APP, "App returned to foreground — checking week rollover & refreshing slate")
      lastStatusSyncMillis = 0L
      sleepJob?.cancel()
    }
  }

  fun onAppBackgrounded() {
    isAppInForeground = false
    val state = _uiState.value
    val now = System.currentTimeMillis()
    val isSpeaking = state.isSpeakingNow || voiceEngine.hasActiveOrPendingSpeech()
    syncForegroundServiceState(nowMillis = now)
    if (
      !ServiceLifecyclePolicy.shouldKeepPollingInBackground(
        settings = state.settings,
        isBroadcastPaused = state.isBroadcastPaused,
        isAppInForeground = false,
        nflGames = state.nflGames,
        nowMillis = now,
        isSpeakingNow = isSpeaking
      ) && state.settings.operatingMode != OperatingMode.DEMO_SIMULATOR
    ) {
      // Allow an in-flight foreground refresh to finish so cold launches or stale post-MNF resumes
      // can load live games within ~250ms; post-cycle guard enters standby immediately if none are active.
      val allowInFlightScoreboardFetch = state.settings.runInBackgroundService &&
        (state.nflGames.isEmpty() || state.isRefreshing)
      if (!allowInFlightScoreboardFetch) {
        enterOffWindowStandby("Outside active game window — background polling & notification asleep")
      }
    }
  }

  fun onAppWindowFocused() {
    val state = _uiState.value
    val isLoopActive = pollLoopJob?.isActive == true
    if (
      ServiceLifecyclePolicy.shouldAutoResumeOnWindowFocus(
        settings = state.settings,
        isBroadcastPaused = state.isBroadcastPaused,
        pausedExplicitlyByUser = pausedExplicitlyByUser,
        isPollLoopActive = isLoopActive
      )
    ) {
      DiagnosticsLogger.info(LogSubsystem.APP, "App window regained focus — resuming live broadcast")
      resumeLiveBroadcastSession()
    }
  }

  fun onPregameWakeAlarmFired(canStartFgsFromBackground: Boolean) {
    val state = _uiState.value
    val mode = state.settings.operatingMode
    if (mode != OperatingMode.MFL_AND_NFL_LIVE && mode != OperatingMode.NFL_LIVE_ONLY) return
    if (!state.settings.runInBackgroundService || state.isBroadcastPaused || pausedExplicitlyByUser) return
    if (pollLoopJob?.isActive == true) return
    if (isAppInForeground || canStartFgsFromBackground) {
      DiagnosticsLogger.info(LogSubsystem.APP, "Pregame wake alarm fired (FGS-exempt) — starting game-window broadcast standby")
      restartPollingLoop(syncService = true)
    } else {
      // Android 12+ blocks startForegroundService() from inexact alarms when battery optimizations are active.
      // Post a standard 1-tap reminder notification instead so tapping launches MainActivity with full FGS rights!
      DiagnosticsLogger.info(LogSubsystem.APP, "Pregame wake alarm fired (battery optimized) — posting 1-tap pregame reminder")
      GameDayLiveService.postPregameReadyReminderNotification(appContext)
      _uiState.update { it.copy(statusMessage = "Game Window Open · Tap to Go Live") }
    }
  }

  fun onMainActivityDestroyed(isChangingConfigurations: Boolean, isFinishing: Boolean) {
    val state = _uiState.value
    val serviceActive = GameDayLiveService.isRunningInForeground || GameDayLiveService.isStartPending
    if (
      ServiceLifecyclePolicy.shouldStopOnMainActivityDestroyed(
        settings = state.settings,
        isBroadcastPaused = state.isBroadcastPaused,
        isChangingConfigurations = isChangingConfigurations,
        isFinishing = isFinishing,
        isServiceActiveOrPending = serviceActive
      )
    ) {
      GameDayTelemetry.recordSwipeCloseStop()
      pauseLiveBroadcastSession(
        reason = "Stopped when closed before live service started",
        explicitUserPause = false
      )
    }
  }

  private fun onSpeechQueueIdle() {
    val state = _uiState.value
    if (state.isRefreshing) {
      // Defer speech-idle teardown while an active poll cycle is still collecting MFL/ESPN events
      return
    }
    val now = System.currentTimeMillis()
    val stillSpeaking = state.isSpeakingNow || voiceEngine.hasActiveOrPendingSpeech()
    syncForegroundServiceState(nowMillis = now)
    if (
      !ServiceLifecyclePolicy.shouldKeepPollingInBackground(
        settings = state.settings,
        isBroadcastPaused = state.isBroadcastPaused,
        isAppInForeground = isAppInForeground,
        nflGames = state.nflGames,
        nowMillis = now,
        isSpeakingNow = stillSpeaking
      ) && state.settings.operatingMode != OperatingMode.DEMO_SIMULATOR
    ) {
      enterOffWindowStandby("Final call finished — entering off-game standby")
    }
  }

  private fun enterOffWindowStandby(logReason: String) {
    val hadActiveLoop = pollLoopJob?.isActive == true
    broadcastGeneration += 1L
    pollLoopJob?.cancel()
    sleepJob?.cancel()
    pollLoopJob = null
    sleepJob = null
    if (GameDayLiveService.isRunningInForeground || GameDayLiveService.isStartPending) {
      GameDayLiveService.stop(appContext)
    }
    val snapState = _uiState.value
    val nextWakeAt = ServiceLifecyclePolicy.computeNextPregameWakeEpochMillis(
      settings = snapState.settings,
      isBroadcastPaused = snapState.isBroadcastPaused,
      nflGames = snapState.nflGames,
      nowMillis = System.currentTimeMillis()
    )
    if (nextWakeAt != null) {
      GameDayLiveService.schedulePregameWakeAlarm(appContext, nextWakeAt)
    } else {
      GameDayLiveService.cancelPregameWakeAlarm(appContext)
    }
    if (hadActiveLoop) {
      DiagnosticsLogger.info(LogSubsystem.APP, logReason)
    }
    val canAutoStartFgs = nextWakeAt != null && GameDayLiveService.canAutoStartForegroundFromAlarm(appContext)
    _uiState.update {
      it.copy(
        isRefreshing = false,
        nextRefreshAtMillis = null,
        statusMessage = when {
          canAutoStartFgs -> "Standing By · Auto-wakes 90m pregame"
          nextWakeAt != null -> "Standing By · Pregame reminder 90m out"
          else -> "Standing By · Auto-wakes on open"
        }
      )
    }
  }

  fun restartPollingLoop(syncService: Boolean = true) {
    pausedExplicitlyByUser = false
    broadcastGeneration += 1L
    pollLoopJob?.cancel()
    sleepJob?.cancel()
    _uiState.update { it.copy(isBroadcastPaused = false) }
    if (_uiState.value.settings.operatingMode == OperatingMode.DEMO_SIMULATOR) {
      startDemoSimulatorMode()
      return
    }
    if (syncService) {
      syncForegroundServiceState()
    }
    val loopGen = broadcastGeneration
    pollLoopJob = scope.launch {
      initializeSeasonAndPlayers(forcePlayerRefresh = false)
      while (isActive && loopGen == broadcastGeneration && !_uiState.value.isBroadcastPaused) {
        performPollCycle(forceScheduleReload = false, cycleGen = loopGen)
        if (!isActive || loopGen != broadcastGeneration || _uiState.value.isBroadcastPaused) break
        val state = _uiState.value
        val now = System.currentTimeMillis()
        val isSpeaking = state.isSpeakingNow || voiceEngine.hasActiveOrPendingSpeech()
        if (
          !ServiceLifecyclePolicy.shouldKeepPollingInBackground(
            settings = state.settings,
            isBroadcastPaused = state.isBroadcastPaused,
            isAppInForeground = isAppInForeground,
            nflGames = state.nflGames,
            nowMillis = now,
            isSpeakingNow = isSpeaking
          )
        ) {
          enterOffWindowStandby("No active NFL games within 90m — sleeping background poller")
          break
        }
        val waitSec = ServiceLifecyclePolicy.computePollWaitSeconds(
          pollIntervalSeconds = state.settings.pollIntervalSeconds,
          nflGames = state.nflGames,
          nowMillis = now
        )
        val nextAt = now + (waitSec * 1000L)
        _uiState.update {
          it.copy(
            currentPollIntervalSec = waitSec,
            nextRefreshAtMillis = nextAt
          )
        }
        val pause = launch { delay(waitSec * 1000L) }
        sleepJob = pause
        pause.join()
      }
    }
  }

  fun refreshNow() {
    if (_uiState.value.settings.operatingMode == OperatingMode.DEMO_SIMULATOR) {
      simulateNextLivePlay()
      return
    }
    lastStatusSyncMillis = 0L
    if (pollLoopJob?.isActive == true) {
      sleepJob?.cancel()
    } else {
      restartPollingLoop()
    }
  }

  /**
   * Explicit user action to force-refresh the NFL Schedule, Season Week, and NFL/IDP Player Database
   * (requested specifically for Configuration/Settings & NFL view).
   */
  fun refreshNflScheduleAndPlayers() {
    scope.launch {
      launch { maybeSyncClayManifest(force = true) }
      if (_uiState.value.settings.operatingMode == OperatingMode.DEMO_SIMULATOR) {
        val now = System.currentTimeMillis()
        _uiState.update {
          it.copy(
            nflGames = DemoSimulatorData.initialGames(now),
            statusMessage = if (com.mflgameday.live.BuildConfig.IS_PLAY_STORE_BUILD) "Demo Schedule & Starters Refreshed" else "Demo NFL Schedule & Starters Refreshed",
            lastUpdatedMillis = now
          )
        }
        return@launch
      }
      mflScheduleEtag = null
      _uiState.update {
        it.copy(
          isRefreshing = true,
          statusMessage = if (com.mflgameday.live.BuildConfig.IS_PLAY_STORE_BUILD) "Refreshing game schedule & rosters…" else "Refreshing NFL schedule & rosters…"
        )
      }
      val gen = broadcastGeneration
      initializeSeasonAndPlayers(forcePlayerRefresh = true)
      performPollCycle(forceScheduleReload = true, cycleGen = gen)
      sleepJob?.cancel()
    }
  }

  /**
   * Fires an exciting sample play (starting with Baker Mayfield -> Chris Godwin 94-yd TD!)
   * so users can test live animations and audible voice announcements anytime.
   * Outside `DEMO_SIMULATOR`, auditions run ephemerally without mutating real NFL games,
   * real MFL snapshots, or persisted `play_log_current.json`.
   */
  fun simulateNextLivePlay() {
    val now = System.currentTimeMillis()
    val state = _uiState.value
    val step = demoSimStep++
    val players = state.playersDb.ifEmpty { DemoSimulatorData.playersDb }
    if (nameExpander == null) nameExpander = PlayerNameExpander(players.values)

    if (state.settings.operatingMode != OperatingMode.DEMO_SIMULATOR) {
      val (_, burstEvent) = DemoSimulatorData.buildSimulatedBurstPlay(
        step = step,
        currentGames = DemoSimulatorData.initialGames(now),
        nowMillis = now
      )
      val sampleEntry = buildLogEntry(
        event = burstEvent,
        exposures = state.crossLeagueExposures,
        playersDb = players,
        wasSpoken = true,
        includeFantasyContextInSpeech = state.settings.announceFantasyContextOnPlays
      )
      GameDayTelemetry.recordTtsSpoken()
      GameDayFirebaseBridge.logEvent(
        context = appContext,
        eventName = "spoken_play_called",
        params = mapOf(
          "event_kind" to burstEvent.kind.name,
          "persona" to state.settings.voicePersona.name,
          "is_demo" to true
        )
      )
      voiceEngine.enqueueAnnouncement(
        text = sampleEntry.spokenText,
        kind = burstEvent.kind,
        settings = state.settings,
        highPriority = true
      )
      if (state.settings.celebrationBannerAnimations) {
        _uiState.update { it.copy(activeBannerAlert = sampleEntry) }
        bannerDismissJob?.cancel()
        bannerDismissJob = scope.launch {
          delay(6_500L)
          _uiState.update { cur ->
            if (cur.activeBannerAlert?.id == sampleEntry.id) cur.copy(activeBannerAlert = null) else cur
          }
        }
      }
      return
    }

    val baseGames = state.nflGames.ifEmpty { DemoSimulatorData.initialGames(now) }
    val (updatedGames, burstEvent) = DemoSimulatorData.buildSimulatedBurstPlay(step, baseGames, now)

    var newSnapshots = state.leagueSnapshots
    val extraEvents = ArrayList<GameEvent>()
    if (step % 5 == 0) {
      val updatedDemoSnaps = DemoSimulatorData.buildDemoSnapshots(now, godwinTdScored = true)
      newSnapshots = updatedDemoSnaps.associateBy { it.config.leagueId }
      for (snap in updatedDemoSnaps) {
        extraEvents.addAll(
          differ.ingestFantasyMatchups(
            leagueName = snap.config.leagueName,
            myFranchiseId = snap.config.franchiseId,
            matchups = snap.matchups,
            precision = snap.config.precision,
            nowMillis = now
          )
        )
      }
    }

    val exposures = BestBallAndPaceCalculator.buildCrossLeagueExposure(newSnapshots.values, players)

    _uiState.update {
      it.copy(
        nflGames = updatedGames,
        leagueSnapshots = newSnapshots,
        crossLeagueExposures = exposures,
        lastUpdatedMillis = now,
        statusMessage = "Live Broadcast Active"
      )
    }

    processDetectedEvents(listOf(burstEvent) + extraEvents, forceSpeak = true)
  }

  fun dismissBannerAlert() {
    bannerDismissJob?.cancel()
    _uiState.update { it.copy(activeBannerAlert = null) }
  }

  private suspend fun initializeSeasonAndPlayers(forcePlayerRefresh: Boolean) {
    _uiState.update { it.copy(isRefreshing = true, statusMessage = "Connecting to MyFantasyLeague…") }
    val status = nonCancellingCatching { mflClient.fetchStatus() }.getOrNull()
    if (status != null) {
      lastKnownMflStatus = status
      lastStatusSyncMillis = System.currentTimeMillis()
    }
    val year = status?.year ?: _uiState.value.currentSeasonYear
    val rawWeek = status?.effectiveActiveWeek ?: _uiState.value.currentWeek
    // Never regress an already-advanced week within the same season while upcoming scheduled games are loaded
    val hasUpcomingGames = _uiState.value.nflGames.any { it.phase == GamePhase.SCHEDULED || it.phase == GamePhase.LIVE || it.phase == GamePhase.HALFTIME }
    val week = if (year == _uiState.value.currentSeasonYear && hasUpcomingGames && _uiState.value.currentWeek > rawWeek) {
      _uiState.value.currentWeek
    } else {
      rawWeek
    }
    applySeasonAndWeek(year = year, week = week)

    val cachedJson = if (forcePlayerRefresh) null else storage.loadCachedPlayersJson(year)
    val playersMap: Map<String, FantasyPlayer> = if (cachedJson != null) {
      runCatching { mflClient.parsePlayersJson(cachedJson) }.getOrDefault(emptyMap())
    } else {
      _uiState.update {
        it.copy(
          statusMessage = if (com.mflgameday.live.BuildConfig.IS_PLAY_STORE_BUILD) "Syncing $year player & IDP database…" else "Syncing $year NFL & IDP player database…"
        )
      }
      runCatching {
        val raw = mflClient.fetchPlayersRawJson(year)
        storage.saveCachedPlayersJson(year, raw)
        mflClient.parsePlayersJson(raw)
      }.getOrElse { DemoSimulatorData.playersDb }
    }

    if (playersMap.isNotEmpty()) {
      nameExpander = PlayerNameExpander(playersMap.values)
      _uiState.update { it.copy(playersDb = playersMap) }
    }
  }

  private suspend fun performPollCycle(forceScheduleReload: Boolean, cycleGen: Long = broadcastGeneration) {
    if (!canEmitForCycle(cycleGen)) return
    if (!_uiState.value.isNetworkAvailable && _uiState.value.settings.operatingMode != OperatingMode.DEMO_SIMULATOR) {
      _uiState.update {
        it.copy(
          isRefreshing = false,
          statusMessage = "Offline — Auto-resuming when network reconnects"
        )
      }
      return
    }
    val now = System.currentTimeMillis()
    _uiState.update { it.copy(isRefreshing = true, statusMessage = "Updating live scores…") }

    val previousGamesAllFinal =
      _uiState.value.nflGames.isNotEmpty() && _uiState.value.nflGames.all { it.phase == GamePhase.FINAL }
    val statusSyncIntervalMs = if (previousGamesAllFinal) 60_000L else 15L * 60L * 1000L

    // Periodic & post-MNF week-rollover check (and immediate check when woken on foreground / Refresh)
    // Note: Do NOT applySeasonAndWeek before ESPN is polled so an ahead ESPN slate never ping-pongs back!
    if (_uiState.value.settings.operatingMode == OperatingMode.MFL_AND_NFL_LIVE &&
      (now - lastStatusSyncMillis) >= statusSyncIntervalMs
    ) {
      val periodicStatus = nonCancellingCatching { mflClient.fetchStatus() }.getOrNull()
      if (periodicStatus != null && canEmitForCycle(cycleGen)) {
        lastKnownMflStatus = periodicStatus
        lastStatusSyncMillis = now
      }
    }

    val warnings = ArrayList<String>()
    val detectedEvents = ArrayList<GameEvent>()
    val previousSeasonYear = _uiState.value.currentSeasonYear
    var week = _uiState.value.currentWeek
    var year = lastKnownMflStatus?.year?.takeIf { _uiState.value.settings.operatingMode == OperatingMode.MFL_AND_NFL_LIVE }
      ?: previousSeasonYear

    // 1. Poll ESPN live scoreboard + parallel game summaries for play-by-play
    var espnSucceeded = false
    val espnStartMs = System.currentTimeMillis()
    try {
      var board = espnClient.fetchScoreboard()
      if (!canEmitForCycle(cycleGen)) return
      val resolvedYear = board.seasonYear
        ?.takeIf {
          _uiState.value.settings.operatingMode == OperatingMode.NFL_LIVE_ONLY &&
            (board.seasonType == 2 || board.seasonType == 3)
        }
        ?: year
      val resolvedWeek = ServiceLifecyclePolicy.resolveEffectiveWeek(
        operatingMode = _uiState.value.settings.operatingMode,
        currentUiWeek = week,
        mflStatus = lastKnownMflStatus,
        espnSeasonType = board.seasonType,
        espnWeekNumber = board.weekNumber,
        previousGamesAllFinal = previousGamesAllFinal,
        sameSeasonAsUi = resolvedYear == previousSeasonYear
      )
      if (resolvedWeek != week || resolvedYear != year) {
        applySeasonAndWeek(year = resolvedYear, week = resolvedWeek)
        year = resolvedYear
        week = resolvedWeek
      }
      // Prevent NFL/MFL week split-brain across Preseason, Regular Season (1..18), and Postseason (19..22).
      // Propagate failure if the targeted ESPN fetch fails so MFL's nfl_sched fallback supplies the matching week!
      if (
        _uiState.value.settings.operatingMode == OperatingMode.MFL_AND_NFL_LIVE &&
        (board.seasonType != null || board.weekNumber != null)
      ) {
        val (targetSeasonType, targetEspnWeek) = ServiceLifecyclePolicy.toEspnTargetSeasonTypeAndWeek(week)
        val seasonTypeMismatch = board.seasonType != null && board.seasonType != targetSeasonType
        val weekMismatch = board.weekNumber != null && board.weekNumber != targetEspnWeek &&
          !(week == 22 && board.seasonType == 3 && board.weekNumber in 4..5)
        if (seasonTypeMismatch || weekMismatch) {
          board = espnClient.fetchScoreboard(week = targetEspnWeek, seasonType = targetSeasonType)
          if (!canEmitForCycle(cycleGen)) return
        }
      }
      espnSucceeded = board.games.isNotEmpty()
      GameDayTelemetry.recordEspnPoll(success = espnSucceeded, latencyMs = System.currentTimeMillis() - espnStartMs)
      if (espnSucceeded) {
        val summaries = coroutineScope {
          board.liveEventIds.map { eventId ->
            async { nonCancellingCatching { espnClient.fetchGameSummary(eventId) }.getOrNull() }
          }.awaitAll().filterNotNull()
        }
        if (!canEmitForCycle(cycleGen)) return
        val summaryByEventId = summaries.associateBy { it.eventId }
        val refinedBoardGames = board.games.map { game ->
          val plays = summaryByEventId[game.id]?.plays
          if (!plays.isNullOrEmpty()) LiveFieldSituationResolver.refineWithPlayHistory(game, plays) else game
        }
        detectedEvents.addAll(
          differ.ingestScoreboard(refinedBoardGames, nowMillis = now, emitFallbackScoreEvents = false)
        )
        for (summary in summaries) {
          differ.refineGameWithPlays(summary.eventId, summary.plays)
          detectedEvents.addAll(differ.ingestPlays(summary.plays, summary.eventId, nowMillis = now))
        }
        _uiState.update {
          it.copy(
            nflGames = differ.stabilizedGames(refinedBoardGames),
            currentSeasonYear = year,
            currentWeek = week
          )
        }
        // Promote FGS early on live scoreboard arrival (~300ms), but defer any teardown (`allowStop = false`)
        // until AFTER `differ` + `processDetectedEvents()` have queued any Final-Score TTS calls!
        syncForegroundServiceState(nowMillis = System.currentTimeMillis(), allowStop = false)
      }
    } catch (ce: CancellationException) {
      throw ce
    } catch (e: Exception) {
      GameDayTelemetry.recordEspnPoll(success = false, latencyMs = System.currentTimeMillis() - espnStartMs)
      warnings.add("ESPN feed: ${e.message?.take(60)}")
      // Ensure MFL-driven rollover still applies even when ESPN is temporarily unreachable
      val fallbackWeek = ServiceLifecyclePolicy.resolveEffectiveWeek(
        operatingMode = _uiState.value.settings.operatingMode,
        currentUiWeek = week,
        mflStatus = lastKnownMflStatus,
        espnSeasonType = null,
        espnWeekNumber = null,
        previousGamesAllFinal = previousGamesAllFinal,
        sameSeasonAsUi = year == previousSeasonYear
      )
      if (fallbackWeek != week || year != _uiState.value.currentSeasonYear) {
        applySeasonAndWeek(year = year, week = fallbackWeek)
        week = fallbackWeek
      }
    }

    // 2. Also sync MFL nfl_sched when explicitly refreshing schedule or if ESPN was unreachable
    if (forceScheduleReload || !espnSucceeded) {
      nonCancellingCatching {
        val cond = mflClient.fetchMflNflSchedule(
          year = year,
          week = week,
          etag = if (forceScheduleReload) null else mflScheduleEtag
        )
        GameDayTelemetry.recordMflPoll(success = true, etagNotModified = cond.notModified)
        if (!cond.notModified && cond.games.isNotEmpty() && canEmitForCycle(cycleGen)) {
          mflScheduleEtag = cond.etag
          if (!espnSucceeded) {
            _uiState.update { it.copy(nflGames = cond.games) }
            syncForegroundServiceState(nowMillis = System.currentTimeMillis(), allowStop = false)
            detectedEvents.addAll(
              differ.ingestScoreboard(cond.games, nowMillis = now, emitFallbackScoreEvents = true)
            )
          }
        }
      }.onFailure { e ->
        GameDayTelemetry.recordMflPoll(success = false)
        if (!espnSucceeded) warnings.add("MFL Schedule: ${e.message?.take(60)}")
      }
    }

    if (!canEmitForCycle(cycleGen)) return

    var totalEmittedEvents = 0
    val preMflState = _uiState.value
    val hasReadyExposuresForImmediateVoice =
      preMflState.settings.operatingMode != OperatingMode.MFL_AND_NFL_LIVE ||
        preMflState.leagues.isEmpty() ||
        preMflState.leagueSnapshots.isNotEmpty()
    if (hasReadyExposuresForImmediateVoice && detectedEvents.isNotEmpty()) {
      // Speak ESPN NFL plays immediately (~300ms) instead of blocking 8–18s behind sequential MFL HTML scraping!
      val immediateNflBatch = detectedEvents.toList()
      detectedEvents.clear()
      totalEmittedEvents += immediateNflBatch.size
      processDetectedEvents(immediateNflBatch, forceSpeak = false)
    }

    // 3. Poll every configured MFL league (dynamic starters, Best Ball optimal slots, breakdowns, pace)
    val currentState = _uiState.value
    if (currentState.settings.operatingMode == OperatingMode.MFL_AND_NFL_LIVE && currentState.leagues.isNotEmpty()) {
      val cookie = storage.loadMflUserCookie()
      val updatedSnapshots = currentState.leagueSnapshots.toMutableMap()

      for (leagueCfg in currentState.leagues) {
        if (!canEmitForCycle(cycleGen)) return
        val apiKey = storage.loadLeagueApiKey(leagueCfg.leagueId)
        try {
          // Cache franchise metadata & weekly projections once per session (or on force reload)
          var franchisesMap = cachedFranchisesByLeague[leagueCfg.leagueId]
          if (franchisesMap == null || forceScheduleReload) {
            val meta = mflClient.fetchLeagueMetadata(
              leagueId = leagueCfg.leagueId,
              year = leagueCfg.year,
              hostOverride = leagueCfg.baseUrl,
              apiKey = apiKey,
              mflUserIdCookie = cookie
            )
            franchisesMap = meta.franchises.associateBy { it.id }
            cachedFranchisesByLeague[leagueCfg.leagueId] = franchisesMap
          }

          val projKey = "${leagueCfg.leagueId}_w$week"
          var projections = cachedProjectionsByLeague[projKey]
          if (projections == null || forceScheduleReload) {
            projections = nonCancellingCatching {
              mflClient.fetchProjectedScores(leagueCfg, week, apiKey = apiKey, mflUserIdCookie = cookie)
            }.getOrDefault(emptyMap())
            if (projections.isNotEmpty()) {
              cachedProjectionsByLeague[projKey] = projections
            }
          }

          val rawMatchups = mflClient.fetchLiveScoring(
            config = leagueCfg,
            week = week,
            apiKey = apiKey,
            mflUserIdCookie = cookie
          )

          val prevSnap = updatedSnapshots[leagueCfg.leagueId]
          val needBreakdowns = forceScheduleReload ||
            prevSnap == null ||
            prevSnap.week != week ||
            leagueCfg.leagueId in pendingBreakdownRetryByLeague ||
            hasAnyScoreChanged(prevSnap, rawMatchups)

          val breakdownResult = if (needBreakdowns) {
            nonCancellingCatching {
              mflClient.fetchScoringBreakdowns(
                config = leagueCfg,
                week = week,
                apiKey = apiKey,
                mflUserIdCookie = cookie
              )
            }
          } else {
            null
          }

          if (!canEmitForCycle(cycleGen)) return
          GameDayTelemetry.recordMflPoll(success = true)

          val previousSameWeekBreakdowns = prevSnap
            ?.takeIf { it.week == week }
            ?.matchups
            ?.flatMap { it.franchises }
            ?.flatMap { it.starters + it.bench }
            ?.associate { it.playerId to it.breakdowns }
            .orEmpty()

          val hasNonZeroScores = rawMatchups
            .flatMap { it.franchises }
            .any { side ->
              kotlin.math.abs(side.score) > 0.001 ||
                side.players.any { kotlin.math.abs(it.score) > 0.001 }
            }

          val mergedBreakdowns = when {
            breakdownResult == null -> previousSameWeekBreakdowns
            breakdownResult.isSuccess -> {
              val fetched = breakdownResult.getOrThrow()
              if (fetched.isEmpty() && hasNonZeroScores) {
                pendingBreakdownRetryByLeague.add(leagueCfg.leagueId)
                previousSameWeekBreakdowns
              } else {
                pendingBreakdownRetryByLeague.remove(leagueCfg.leagueId)
                fetched
              }
            }
            else -> {
              pendingBreakdownRetryByLeague.add(leagueCfg.leagueId)
              previousSameWeekBreakdowns
            }
          }

          val snapshot = BestBallAndPaceCalculator.buildLeagueSnapshot(
            config = leagueCfg,
            week = week,
            franchisesMap = franchisesMap,
            rawMatchups = rawMatchups,
            playersDb = _uiState.value.playersDb,
            projections = projections,
            breakdowns = mergedBreakdowns,
            previousSnapshot = prevSnap,
            nowMillis = now
          )
          updatedSnapshots[leagueCfg.leagueId] = snapshot

          if (leagueCfg.includeInAnnouncements) {
            detectedEvents.addAll(
              differ.ingestFantasyMatchups(
                leagueName = leagueCfg.leagueName,
                myFranchiseId = leagueCfg.franchiseId,
                matchups = snapshot.matchups,
                precision = leagueCfg.precision,
                nowMillis = now
              )
            )
          }
        } catch (ce: CancellationException) {
          throw ce
        } catch (e: Exception) {
          GameDayTelemetry.recordMflPoll(success = false)
          warnings.add("${leagueCfg.leagueName}: ${e.message?.take(50)}")
        }
      }

      if (!canEmitForCycle(cycleGen)) return
      val exposures = BestBallAndPaceCalculator.buildCrossLeagueExposure(
        snapshots = updatedSnapshots.values,
        playersDb = _uiState.value.playersDb
      )
      _uiState.update {
        it.copy(
          leagueSnapshots = updatedSnapshots,
          crossLeagueExposures = exposures
        )
      }
    }

    if (!canEmitForCycle(cycleGen)) return
    if (detectedEvents.isNotEmpty()) {
      totalEmittedEvents += detectedEvents.size
      processDetectedEvents(detectedEvents, forceSpeak = false)
    }

    val liveCount = _uiState.value.nflGames.count { it.phase == GamePhase.LIVE || it.phase == GamePhase.HALFTIME }
    if (warnings.isNotEmpty()) {
      DiagnosticsLogger.warn(LogSubsystem.NETWORK, "Partial poll completed", warnings.joinToString(" | "))
    } else {
      DiagnosticsLogger.info(
        LogSubsystem.APP,
        "Poll OK: $liveCount live NFL games, ${_uiState.value.leagueSnapshots.size} MFL league(s), $totalEmittedEvents new event(s)"
      )
    }
    _uiState.update {
      it.copy(
        isRefreshing = false,
        lastUpdatedMillis = System.currentTimeMillis(),
        warningMessage = warnings.takeIf { w -> w.isNotEmpty() }?.joinToString(" · "),
        statusMessage = when {
          warnings.isNotEmpty() -> "Partial Update"
          liveCount > 0 -> if (com.mflgameday.live.BuildConfig.IS_PLAY_STORE_BUILD) "Live · $liveCount Games Active" else "Live · $liveCount NFL Games Active"
          else -> "Monitoring · Standing By"
        }
      )
    }
    syncForegroundServiceState()
  }

  private fun processDetectedEvents(events: List<GameEvent>, forceSpeak: Boolean) {
    if (events.isEmpty()) return
    val state = _uiState.value
    if (!forceSpeak && state.isBroadcastPaused) return
    val settings = state.settings
    val exposures = state.crossLeagueExposures
    val newEntries = ArrayList<PlayLogEntry>(events.size)

    for (event in events) {
      val involved = BestBallAndPaceCalculator.findInvolvedExposures(event, exposures, state.playersDb)
      val category = SpokenCategory.forEvent(event.kind)
      val relevant = BestBallAndPaceCalculator.isEventRelevantForScope(
        event = event,
        scope = settings.scope,
        exposures = exposures,
        involved = involved
      )
      val shouldSpeak = forceSpeak || (
        !settings.muted &&
          category in settings.enabledCategories &&
          relevant
        )

      val entry = buildLogEntry(
        event = event,
        exposures = exposures,
        playersDb = state.playersDb,
        wasSpoken = shouldSpeak,
        includeFantasyContextInSpeech = settings.announceFantasyContextOnPlays
      )
      newEntries.add(entry)

      if (shouldSpeak && (forceSpeak || !_uiState.value.isBroadcastPaused)) {
        GameDayTelemetry.recordTtsSpoken()
        GameDayFirebaseBridge.logEvent(
          context = appContext,
          eventName = "spoken_play_called",
          params = mapOf(
            "event_kind" to event.kind.name,
            "persona" to settings.voicePersona.name,
            "is_demo" to (settings.operatingMode == OperatingMode.DEMO_SIMULATOR)
          )
        )
        voiceEngine.enqueueAnnouncement(
          text = entry.spokenText,
          kind = event.kind,
          settings = settings,
          highPriority = entry.involvesMine || forceSpeak
        )
      }
    }

    val combinedLog = (newEntries.reversed() + state.playLog).take(250)
    val bannerCandidate = newEntries.lastOrNull {
      it.event.kind == GameEventKind.TOUCHDOWN ||
        it.event.kind == GameEventKind.INTERCEPTION ||
        it.event.kind == GameEventKind.FUMBLE_LOST ||
        it.event.kind == GameEventKind.SACK ||
        it.event.kind == GameEventKind.FIELD_GOAL ||
        it.event.kind == GameEventKind.RED_ZONE ||
        it.event.kind == GameEventKind.FANTASY_LEAD_CHANGE
    }

    _uiState.update {
      it.copy(
        playLog = combinedLog,
        activeBannerAlert = if (settings.celebrationBannerAnimations && bannerCandidate != null) {
          bannerCandidate
        } else {
          it.activeBannerAlert
        }
      )
    }

    if (settings.celebrationBannerAnimations && bannerCandidate != null) {
      bannerDismissJob?.cancel()
      bannerDismissJob = scope.launch {
        delay(6_500L)
        _uiState.update { cur ->
          if (cur.activeBannerAlert?.id == bannerCandidate.id) cur.copy(activeBannerAlert = null) else cur
        }
      }
    }

    if (settings.operatingMode != OperatingMode.DEMO_SIMULATOR) {
      val realEntriesOnly = combinedLog.filterNot { ServiceLifecyclePolicy.isSimulatedPlayId(it.event.id) }
      scope.launch {
        storage.savePlayLog(realEntriesOnly)
      }
    }
  }

  private fun buildLogEntry(
    event: GameEvent,
    exposures: Map<String, PlayerCrossLeagueExposure>,
    playersDb: Map<String, FantasyPlayer>,
    wasSpoken: Boolean,
    includeFantasyContextInSpeech: Boolean = true
  ): PlayLogEntry {
    if (nameExpander == null && playersDb.isNotEmpty()) {
      nameExpander = PlayerNameExpander(playersDb.values)
    }
    val involved = BestBallAndPaceCalculator.findInvolvedExposures(event, exposures, playersDb)
    val involvesMine = involved.any { it.role == ExposureRole.MINE || it.role == ExposureRole.CONFLICT }
    val involvesOpp = involved.any { it.role == ExposureRole.OPPONENT || it.role == ExposureRole.CONFLICT }
    val isConflict = involved.any { it.role == ExposureRole.CONFLICT }

    val myLeagues = involved.flatMap { it.myLeagueNames }.distinct()
    val oppLeagues = involved.flatMap { it.opponentLeagueNames }.distinct()
    val fantasySuffix = if (includeFantasyContextInSpeech && involved.isNotEmpty() && event.kind != GameEventKind.FANTASY_LEAD_CHANGE) {
      when {
        isConflict -> "Conflict play: starting for you in ${myLeagues.first()}, and against you in ${oppLeagues.first()}."
        myLeagues.isNotEmpty() -> "For your lineup in ${myLeagues.joinToString(" and ")}."
        oppLeagues.isNotEmpty() -> "Against you in ${oppLeagues.joinToString(" and ")}."
        else -> null
      }
    } else null

    val spoken = PlaySpeechFormatter.format(
      event = event,
      nameExpander = nameExpander,
      fantasyTagSuffix = fantasySuffix
    )

    return PlayLogEntry(
      id = "${event.id}_${UUID.randomUUID()}",
      timestampMillis = event.observedAtMillis,
      event = event,
      spokenText = spoken,
      leagueTags = (myLeagues + oppLeagues).distinct(),
      involvesMine = involvesMine,
      involvesOpponent = involvesOpp,
      isConflictPlay = isConflict,
      wasSpoken = wasSpoken
    )
  }

  private fun applySeasonAndWeek(year: Int, week: Int) {
    val prevYear = _uiState.value.currentSeasonYear
    val prevWeek = _uiState.value.currentWeek
    val hadLoadedData = _uiState.value.lastUpdatedMillis != null ||
      _uiState.value.leagueSnapshots.isNotEmpty() ||
      _uiState.value.nflGames.isNotEmpty()
    if (hadLoadedData && (year != prevYear || week != prevWeek)) {
      DiagnosticsLogger.info(LogSubsystem.APP, "Season/Week rolled over ($prevYear W$prevWeek -> $year W$week) — resetting weekly play ticker")
      differ.reset()
      mflScheduleEtag = null
      pendingBreakdownRetryByLeague.clear()
      _uiState.update {
        it.copy(
          currentSeasonYear = year,
          currentWeek = week,
          nflGames = emptyList(),
          leagueSnapshots = emptyMap(),
          crossLeagueExposures = emptyMap(),
          playLog = emptyList(),
          activeBannerAlert = null
        )
      }
      scope.launch {
        storage.savePlayLog(emptyList())
        // Also refresh the MFL player database in background on week rollover if cache is stale (>24h)
        if (storage.loadCachedPlayersJson(year) == null) {
          nonCancellingCatching {
            val raw = mflClient.fetchPlayersRawJson(year)
            storage.saveCachedPlayersJson(year, raw)
            val parsed = mflClient.parsePlayersJson(raw)
            if (parsed.isNotEmpty()) {
              nameExpander = PlayerNameExpander(parsed.values)
              _uiState.update { it.copy(playersDb = parsed) }
            }
          }
        }
      }
    } else {
      _uiState.update { it.copy(currentSeasonYear = year, currentWeek = week) }
    }
  }

  private fun hasAnyScoreChanged(
    prevSnap: LeagueLiveSnapshot,
    rawMatchups: List<com.mflgameday.live.data.RawMflMatchup>
  ): Boolean {
    val prevByFranchise = prevSnap.matchups.flatMap { it.franchises }.associateBy { it.franchiseId }
    for (m in rawMatchups) {
      for (f in m.franchises) {
        val prevF = prevByFranchise[f.franchiseId] ?: return true
        if (kotlin.math.abs(prevF.score - f.score) > 0.001) return true
        val prevPlayers = (prevF.starters + prevF.bench).associate { it.playerId to it.score }
        for (p in f.players) {
          val prevP = prevPlayers[p.playerId] ?: return true
          if (kotlin.math.abs(prevP - p.score) > 0.001) return true
        }
      }
    }
    return false
  }

  private suspend fun canEmitForCycle(cycleGen: Long): Boolean =
    ServiceLifecyclePolicy.canEmitPollResults(
      isBroadcastPaused = _uiState.value.isBroadcastPaused,
      cycleGeneration = cycleGen,
      activeGeneration = broadcastGeneration,
      isCoroutineActive = currentCoroutineContext().isActive
    )

  private inline fun <T> nonCancellingCatching(block: () -> T): Result<T> =
    try {
      Result.success(block())
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      Result.failure(t)
    }

  private fun syncForegroundServiceState(
    nowMillis: Long = System.currentTimeMillis(),
    allowStop: Boolean = true
  ) {
    val state = _uiState.value
    val s = state.settings
    val isSpeaking = state.isSpeakingNow || voiceEngine.hasActiveOrPendingSpeech()
    val alreadyForegroundOrPending = GameDayLiveService.isRunningInForeground || GameDayLiveService.isStartPending
    val shouldRun = ServiceLifecyclePolicy.shouldRunForegroundService(
      settings = s,
      isBroadcastPaused = state.isBroadcastPaused,
      nflGames = state.nflGames,
      nowMillis = nowMillis,
      isSpeakingNow = isSpeaking,
      preserveWhileSlateLoading = alreadyForegroundOrPending
    )
    if (
      ServiceLifecyclePolicy.shouldDispatchStartForegroundService(
        settings = s,
        isBroadcastPaused = state.isBroadcastPaused,
        isServiceAlreadyForeground = alreadyForegroundOrPending,
        nflGames = state.nflGames,
        nowMillis = nowMillis,
        isSpeakingNow = isSpeaking
      )
    ) {
      GameDayLiveService.start(appContext)
    } else if (!shouldRun) {
      if (allowStop && (GameDayLiveService.isRunningInForeground || GameDayLiveService.isStartPending)) {
        GameDayLiveService.stop(appContext)
      }
    } else {
      GameDayLiveService.notifySummaryUpdate(appContext)
    }
  }
}
