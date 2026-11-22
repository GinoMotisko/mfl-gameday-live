package com.mflgameday.live.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.mflgameday.live.audio.GameDayVoiceEngine
import com.mflgameday.live.data.EspnApiClient
import com.mflgameday.live.data.GameDayStorage
import com.mflgameday.live.data.MflApiClient
import com.mflgameday.live.data.MflLeagueMetadata
import com.mflgameday.live.diagnostics.CrashReportSummary
import com.mflgameday.live.diagnostics.DiagnosticEntry
import com.mflgameday.live.diagnostics.DiagnosticsLogger
import com.mflgameday.live.diagnostics.LogSubsystem
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
import com.mflgameday.live.model.NflGameState
import com.mflgameday.live.model.OperatingMode
import com.mflgameday.live.model.PlayLogEntry
import com.mflgameday.live.model.PlayerCrossLeagueExposure
import com.mflgameday.live.model.SpokenCategory
import com.mflgameday.live.model.VoiceOption
import java.time.Year
import java.util.UUID
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
  // Network & Diagnostics telemetry
  val isNetworkAvailable: Boolean = true,
  val diagnosticEntries: List<DiagnosticEntry> = emptyList(),
  val lastCrashReport: CrashReportSummary? = null
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
  val voiceEngine = GameDayVoiceEngine(appContext)

  private val differ = EventDiffer()
  private var nameExpander: PlayerNameExpander? = null
  private val cachedProjectionsByLeague = HashMap<String, Map<String, Double>>()
  private val cachedFranchisesByLeague = HashMap<String, Map<String, FranchiseInfo>>()
  private var mflScheduleEtag: String? = null
  private var pollLoopJob: Job? = null
  private var sleepJob: Job? = null
  private var bannerDismissJob: Job? = null
  private var demoSimStep: Int = 0

  private val _uiState = MutableStateFlow(
    GameDayUiState(
      settings = storage.loadSettings(),
      leagues = storage.loadLeagues(),
      isSignedInMfl = !storage.loadMflUserCookie().isNullOrBlank()
    )
  )
  val uiState: StateFlow<GameDayUiState> = _uiState.asStateFlow()

  init {
    scope.launch {
      val savedLog = storage.loadPlayLog()
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
        _uiState.update { it.copy(isSpeakingNow = speaking) }
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
      }
    }
    registerConnectivityWatcher()
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
          if (wasOffline) {
            DiagnosticsLogger.info(LogSubsystem.NETWORK, "Network restored — triggering immediate score refresh")
            if (_uiState.value.settings.operatingMode != OperatingMode.UNCONFIGURED) {
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
    val updated = transform(_uiState.value.settings)
    storage.saveSettings(updated)
    _uiState.update { it.copy(settings = updated) }
    if (updated.muted) {
      voiceEngine.stopAndClearQueue()
    }
    syncForegroundServiceState()
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
        lastUpdatedMillis = now,
        nextRefreshAtMillis = now + 30_000L,
        currentPollIntervalSec = 30
      )
    }
    syncForegroundServiceState()
  }

  fun exitToSetup() {
    pollLoopJob?.cancel()
    sleepJob?.cancel()
    pollLoopJob = null
    voiceEngine.stopAndClearQueue()
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
            currentWeek = status?.liveScoringWeek ?: 1,
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
            currentWeek = status?.liveScoringWeek ?: it.currentWeek,
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

  // -------------------- Live Polling, Schedule Refresh & Simulator --------------------

  fun startLiveEngine() {
    val mode = _uiState.value.settings.operatingMode
    if (mode == OperatingMode.DEMO_SIMULATOR) {
      if (_uiState.value.nflGames.isEmpty()) {
        startDemoSimulatorMode()
      }
      return
    }
    if (pollLoopJob?.isActive == true) return
    restartPollingLoop()
  }

  fun restartPollingLoop() {
    pollLoopJob?.cancel()
    sleepJob?.cancel()
    if (_uiState.value.settings.operatingMode == OperatingMode.DEMO_SIMULATOR) {
      startDemoSimulatorMode()
      return
    }
    syncForegroundServiceState()
    pollLoopJob = scope.launch {
      initializeSeasonAndPlayers(forcePlayerRefresh = false)
      while (isActive) {
        performPollCycle(forceScheduleReload = false)
        val state = _uiState.value
        val anyLive = state.nflGames.any { it.phase == GamePhase.LIVE || it.phase == GamePhase.HALFTIME }
        val waitSec = if (anyLive) {
          state.settings.pollIntervalSeconds.coerceIn(15, 120)
        } else {
          computeQuietWaitSeconds(state.nflGames)
        }
        val nextAt = System.currentTimeMillis() + (waitSec * 1000L)
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
      if (_uiState.value.settings.operatingMode == OperatingMode.DEMO_SIMULATOR) {
        val now = System.currentTimeMillis()
        _uiState.update {
          it.copy(
            nflGames = DemoSimulatorData.initialGames(now),
            statusMessage = "Demo NFL Schedule & Starters Refreshed",
            lastUpdatedMillis = now
          )
        }
        return@launch
      }
      mflScheduleEtag = null
      _uiState.update { it.copy(isRefreshing = true, statusMessage = "Refreshing NFL schedule & rosters…") }
      initializeSeasonAndPlayers(forcePlayerRefresh = true)
      performPollCycle(forceScheduleReload = true)
      sleepJob?.cancel()
    }
  }

  /**
   * Fires an exciting live play (starting with Baker Mayfield -> Chris Godwin 94-yd TD!)
   * so users can test live animations, fantasy swings, and audible voice announcements anytime.
   */
  fun simulateNextLivePlay() {
    val now = System.currentTimeMillis()
    val state = _uiState.value
    val baseGames = state.nflGames.ifEmpty { DemoSimulatorData.initialGames(now) }
    val step = demoSimStep++
    val (updatedGames, burstEvent) = DemoSimulatorData.buildSimulatedBurstPlay(step, baseGames, now)

    var newSnapshots = state.leagueSnapshots
    val extraEvents = ArrayList<GameEvent>()
    if (state.settings.operatingMode == OperatingMode.DEMO_SIMULATOR && step % 5 == 0) {
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

    val players = state.playersDb.ifEmpty { DemoSimulatorData.playersDb }
    if (nameExpander == null) nameExpander = PlayerNameExpander(players.values)
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
    val status = runCatching { mflClient.fetchStatus() }.getOrNull()
    val year = status?.year ?: _uiState.value.currentSeasonYear
    val week = status?.liveScoringWeek ?: _uiState.value.currentWeek
    _uiState.update { it.copy(currentSeasonYear = year, currentWeek = week) }

    val cachedJson = if (forcePlayerRefresh) null else storage.loadCachedPlayersJson(year)
    val playersMap: Map<String, FantasyPlayer> = if (cachedJson != null) {
      runCatching { mflClient.parsePlayersJson(cachedJson) }.getOrDefault(emptyMap())
    } else {
      _uiState.update { it.copy(statusMessage = "Syncing $year NFL & IDP player database…") }
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

  private suspend fun performPollCycle(forceScheduleReload: Boolean) {
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
    val warnings = ArrayList<String>()
    val detectedEvents = ArrayList<GameEvent>()
    var week = _uiState.value.currentWeek
    val year = _uiState.value.currentSeasonYear

    // 1. Poll ESPN live scoreboard + parallel game summaries for play-by-play
    var espnSucceeded = false
    try {
      val board = espnClient.fetchScoreboard()
      if (board.weekNumber != null && _uiState.value.settings.operatingMode == OperatingMode.NFL_LIVE_ONLY) {
        week = board.weekNumber
      }
      espnSucceeded = board.games.isNotEmpty()
      if (espnSucceeded) {
        _uiState.update { it.copy(nflGames = board.games, currentWeek = week) }
        detectedEvents.addAll(differ.ingestScoreboard(board.games, nowMillis = now, emitFallbackScoreEvents = false))

        val summaries = coroutineScope {
          board.liveEventIds.map { eventId ->
            async { runCatching { espnClient.fetchGameSummary(eventId) }.getOrNull() }
          }.awaitAll().filterNotNull()
        }
        for (summary in summaries) {
          detectedEvents.addAll(differ.ingestPlays(summary.plays, summary.eventId, nowMillis = now))
        }
      }
    } catch (e: Exception) {
      warnings.add("ESPN feed: ${e.message?.take(60)}")
    }

    // 2. Also sync MFL nfl_sched when explicitly refreshing schedule or if ESPN was unreachable
    if (forceScheduleReload || !espnSucceeded) {
      runCatching {
        val cond = mflClient.fetchMflNflSchedule(
          year = year,
          week = week,
          etag = if (forceScheduleReload) null else mflScheduleEtag
        )
        if (!cond.notModified && cond.games.isNotEmpty()) {
          mflScheduleEtag = cond.etag
          if (!espnSucceeded) {
            _uiState.update { it.copy(nflGames = cond.games) }
            detectedEvents.addAll(
              differ.ingestScoreboard(cond.games, nowMillis = now, emitFallbackScoreEvents = true)
            )
          }
        }
      }.onFailure { e ->
        if (!espnSucceeded) warnings.add("MFL Schedule: ${e.message?.take(60)}")
      }
    }

    // 3. Poll every configured MFL league (dynamic starters, Best Ball optimal slots, breakdowns, pace)
    val currentState = _uiState.value
    if (currentState.settings.operatingMode == OperatingMode.MFL_AND_NFL_LIVE && currentState.leagues.isNotEmpty()) {
      val cookie = storage.loadMflUserCookie()
      val updatedSnapshots = currentState.leagueSnapshots.toMutableMap()

      for (leagueCfg in currentState.leagues) {
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

          var projections = cachedProjectionsByLeague[leagueCfg.leagueId]
          if (projections == null || forceScheduleReload) {
            projections = runCatching {
              mflClient.fetchProjectedScores(leagueCfg, week, apiKey = apiKey, mflUserIdCookie = cookie)
            }.getOrDefault(emptyMap())
            cachedProjectionsByLeague[leagueCfg.leagueId] = projections
          }

          val rawMatchups = mflClient.fetchLiveScoring(
            config = leagueCfg,
            week = week,
            apiKey = apiKey,
            mflUserIdCookie = cookie
          )

          val breakdowns = runCatching {
            mflClient.fetchScoringBreakdowns(
              config = leagueCfg,
              week = week,
              apiKey = apiKey,
              mflUserIdCookie = cookie
            )
          }.getOrDefault(emptyMap())

          val prevSnap = updatedSnapshots[leagueCfg.leagueId]
          val mergedBreakdowns = if (breakdowns.isNotEmpty()) {
            breakdowns
          } else {
            // Preserve previous breakdowns if HTML fetch was skipped/throttled
            prevSnap?.matchups
              ?.flatMap { it.franchises }
              ?.flatMap { it.starters + it.bench }
              ?.associate { it.playerId to it.breakdowns }
              .orEmpty()
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
        } catch (e: Exception) {
          warnings.add("${leagueCfg.leagueName}: ${e.message?.take(50)}")
        }
      }

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

    processDetectedEvents(detectedEvents, forceSpeak = false)

    val liveCount = _uiState.value.nflGames.count { it.phase == GamePhase.LIVE || it.phase == GamePhase.HALFTIME }
    if (warnings.isNotEmpty()) {
      DiagnosticsLogger.warn(LogSubsystem.NETWORK, "Partial poll completed", warnings.joinToString(" | "))
    } else {
      DiagnosticsLogger.info(
        LogSubsystem.APP,
        "Poll OK: $liveCount live NFL games, ${_uiState.value.leagueSnapshots.size} MFL league(s), ${detectedEvents.size} new event(s)"
      )
    }
    _uiState.update {
      it.copy(
        isRefreshing = false,
        lastUpdatedMillis = System.currentTimeMillis(),
        warningMessage = warnings.takeIf { w -> w.isNotEmpty() }?.joinToString(" · "),
        statusMessage = when {
          warnings.isNotEmpty() -> "Partial Update"
          liveCount > 0 -> "Live · $liveCount NFL Games Active"
          else -> "Monitoring · Standing By"
        }
      )
    }
    GameDayLiveService.notifySummaryUpdate(appContext)
  }

  private fun processDetectedEvents(events: List<GameEvent>, forceSpeak: Boolean) {
    if (events.isEmpty()) return
    val state = _uiState.value
    val settings = state.settings
    val exposures = state.crossLeagueExposures
    val newEntries = ArrayList<PlayLogEntry>(events.size)

    for (event in events) {
      val involved = BestBallAndPaceCalculator.findInvolvedExposures(event, exposures)
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

      if (shouldSpeak) {
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

    scope.launch {
      storage.savePlayLog(combinedLog)
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
    val involved = BestBallAndPaceCalculator.findInvolvedExposures(event, exposures)
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

  private fun computeQuietWaitSeconds(games: List<NflGameState>): Int {
    val now = System.currentTimeMillis()
    val nextKickoff = games
      .filter { it.phase == GamePhase.SCHEDULED }
      .mapNotNull { it.kickoffEpochMillis }
      .filter { it > now }
      .minOrNull()
      ?: return 180
    val untilKickoffSec = ((nextKickoff - now) / 1000L) - 30L
    return untilKickoffSec.coerceIn(30L, 240L).toInt()
  }

  private fun syncForegroundServiceState() {
    val s = _uiState.value.settings
    if (s.operatingMode != OperatingMode.UNCONFIGURED && s.runInBackgroundService) {
      GameDayLiveService.start(appContext)
    } else {
      GameDayLiveService.stop(appContext)
    }
  }
}
