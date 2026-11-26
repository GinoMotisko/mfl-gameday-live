package com.mflgameday.live.diagnostics

import android.content.Context
import android.os.Build
import com.mflgameday.live.BuildConfig
import com.mflgameday.live.model.GameDaySettings
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TelemetrySnapshot(
  val schemaVersion: Int = 1,
  val appVersion: String = BuildConfig.VERSION_NAME,
  val versionCode: Int = BuildConfig.VERSION_CODE,
  val flavor: String = if (BuildConfig.IS_PLAY_STORE_BUILD) "playStore" else "direct",
  val sdkInt: Int = Build.VERSION.SDK_INT,
  val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}".take(48),
  val sessionsStarted: Long = 0L,
  val crashCount: Long = 0L,
  val espnPollsOk: Long = 0L,
  val espnPollsFailed: Long = 0L,
  val espnLastLatencyMs: Long = 0L,
  val espnAvgLatencyMs: Long = 0L,
  val mflPollsOk: Long = 0L,
  val mflPollsFailed: Long = 0L,
  val mflEtagHits: Long = 0L,
  val ttsAnnouncementsSpoken: Long = 0L,
  val foregroundServiceStarts: Long = 0L,
  val swipeCloseStops: Long = 0L,
  val networkDropRecoveries: Long = 0L,
  val lastHeartbeatEpochMillis: Long = 0L,
  val lastReportedCrashEpochMillis: Long = 0L
) {
  val totalPolls: Long get() = espnPollsOk + espnPollsFailed + mflPollsOk + mflPollsFailed
  val pollSuccessRatePct: Int
    get() {
      val total = totalPolls
      if (total <= 0L) return 100
      return (((espnPollsOk + mflPollsOk) * 100L) / total).toInt().coerceIn(0, 100)
    }
}

/**
 * Privacy-first operational telemetry collector paired with native Google Firebase Crashlytics & Analytics.
 * - Maintains local feed latency, ETag hit, and poll reliability counters on-device for the Settings Health UI
 *   and 1-tap Email Support reports.
 * - Syncs non-PII operational context keys and health snapshots to Firebase when `optInAnonymousTelemetry` is enabled
 *   (replacing the legacy evaporating Vercel runtime-log beacon).
 * - Serializes persistence through `persistMutex` with atomic temp-file replacement.
 */
object GameDayTelemetry {
  private const val MIN_AUTO_SYNC_INTERVAL_MS = 6L * 60L * 60L * 1000L // 6 hours

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val persistMutex = Mutex()
  private val syncMutex = Mutex()
  private val fileIoLock = Any()
  private var initCompleted = CompletableDeferred<Unit>()
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

  private var metricsFile: File? = null
  private var installed = false

  private val _snapshot = MutableStateFlow(TelemetrySnapshot())
  val snapshot: StateFlow<TelemetrySnapshot> = _snapshot.asStateFlow()

  private val _lastBeaconStatus = MutableStateFlow<String?>(null)
  val lastBeaconStatus: StateFlow<String?> = _lastBeaconStatus.asStateFlow()

  @Synchronized
  fun install(context: Context) {
    if (installed) return
    installed = true
    initCompleted = CompletableDeferred()
    val dir = File(context.applicationContext.filesDir, "diagnostics").apply { mkdirs() }
    val file = File(dir, "telemetry_metrics.json")
    metricsFile = file

    scope.launch {
      try {
        val loaded = runCatching {
          if (file.exists()) json.decodeFromString<TelemetrySnapshot>(file.readText()) else TelemetrySnapshot()
        }.getOrDefault(TelemetrySnapshot())

        synchronized(this@GameDayTelemetry) {
          val early = _snapshot.value
          val combinedEspnOk = loaded.espnPollsOk + early.espnPollsOk
          val weightedEspnAvg = if (combinedEspnOk > 0L) {
            ((loaded.espnAvgLatencyMs * loaded.espnPollsOk) + (early.espnAvgLatencyMs * early.espnPollsOk)) / combinedEspnOk
          } else {
            0L
          }
          _snapshot.value = loaded.copy(
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            flavor = if (BuildConfig.IS_PLAY_STORE_BUILD) "playStore" else "direct",
            sdkInt = Build.VERSION.SDK_INT,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".take(48),
            sessionsStarted = loaded.sessionsStarted + 1L,
            crashCount = loaded.crashCount + early.crashCount,
            espnPollsOk = combinedEspnOk,
            espnPollsFailed = loaded.espnPollsFailed + early.espnPollsFailed,
            espnLastLatencyMs = if (early.espnPollsOk > 0L) early.espnLastLatencyMs else loaded.espnLastLatencyMs,
            espnAvgLatencyMs = weightedEspnAvg,
            mflPollsOk = loaded.mflPollsOk + early.mflPollsOk,
            mflPollsFailed = loaded.mflPollsFailed + early.mflPollsFailed,
            mflEtagHits = loaded.mflEtagHits + early.mflEtagHits,
            ttsAnnouncementsSpoken = loaded.ttsAnnouncementsSpoken + early.ttsAnnouncementsSpoken,
            foregroundServiceStarts = loaded.foregroundServiceStarts + early.foregroundServiceStarts,
            swipeCloseStops = loaded.swipeCloseStops + early.swipeCloseStops,
            networkDropRecoveries = loaded.networkDropRecoveries + early.networkDropRecoveries
          )
        }
        persistLatestOrdered(awaitInit = false)
      } finally {
        initCompleted.complete(Unit)
      }
    }
  }

  fun recordEspnPoll(success: Boolean, latencyMs: Long) = mutate { cur ->
    if (success) {
      val count = cur.espnPollsOk + 1L
      val prevTotal = cur.espnAvgLatencyMs * cur.espnPollsOk
      val nextAvg = ((prevTotal + latencyMs.coerceAtLeast(0L)) / count).coerceAtLeast(1L)
      cur.copy(
        espnPollsOk = count,
        espnLastLatencyMs = latencyMs.coerceAtLeast(0L),
        espnAvgLatencyMs = nextAvg
      )
    } else {
      cur.copy(espnPollsFailed = cur.espnPollsFailed + 1L)
    }
  }

  fun recordMflPoll(success: Boolean, etagNotModified: Boolean = false) = mutate { cur ->
    when {
      etagNotModified -> cur.copy(mflPollsOk = cur.mflPollsOk + 1L, mflEtagHits = cur.mflEtagHits + 1L)
      success -> cur.copy(mflPollsOk = cur.mflPollsOk + 1L)
      else -> cur.copy(mflPollsFailed = cur.mflPollsFailed + 1L)
    }
  }

  fun recordTtsSpoken() = mutate { it.copy(ttsAnnouncementsSpoken = it.ttsAnnouncementsSpoken + 1L) }

  fun recordServiceStart() = mutate { it.copy(foregroundServiceStarts = it.foregroundServiceStarts + 1L) }

  fun recordSwipeCloseStop() = mutate { it.copy(swipeCloseStops = it.swipeCloseStops + 1L) }

  fun recordNetworkRecovery() = mutate { it.copy(networkDropRecoveries = it.networkDropRecoveries + 1L) }

  /**
   * Synchronous crash increment called from `UncaughtExceptionHandler` right before process exit
   * so `crashCount` is durably persisted without relying on async coroutine scheduling.
   */
  fun recordCrashSynchronously() {
    val file = metricsFile ?: return
    runCatching {
      synchronized(fileIoLock) {
        val next = synchronized(this@GameDayTelemetry) {
          val updated = _snapshot.value.copy(crashCount = _snapshot.value.crashCount + 1L)
          _snapshot.value = updated
          updated
        }
        writeSnapshotAtomicallyLocked(file, next)
      }
    }
  }

  fun resetMetrics() {
    synchronized(this) {
      _snapshot.value = TelemetrySnapshot(sessionsStarted = 1L)
    }
    scope.launch {
      if (installed) {
        initCompleted.await()
      }
      synchronized(this@GameDayTelemetry) {
        _snapshot.value = TelemetrySnapshot(sessionsStarted = 1L)
      }
      persistLatestOrdered(awaitInit = false)
    }
    _lastBeaconStatus.value = "Telemetry counters reset"
  }

  fun encodeJsonSummary(): String = runCatching {
    json.encodeToString(_snapshot.value)
  }.getOrDefault("{}")

  fun maybeSyncFirebaseHeartbeat(
    context: Context,
    settings: GameDaySettings,
    leagueCount: Int,
    lastCrash: CrashReportSummary?
  ) {
    if (!settings.optInAnonymousTelemetry) return
    val appCtx = context.applicationContext
    scope.launch {
      if (installed) {
        initCompleted.await()
      }
      syncMutex.withLock {
        val now = System.currentTimeMillis()
        val cur = _snapshot.value
        val unseenCrash = lastCrash != null && lastCrash.timestampMillis > cur.lastReportedCrashEpochMillis
        if (!unseenCrash && (now - cur.lastHeartbeatEpochMillis) < MIN_AUTO_SYNC_INTERVAL_MS) {
          GameDayFirebaseBridge.syncContext(appCtx, settings, leagueCount, cur)
          return@withLock
        }
        syncSnapshotToFirebaseLocked(
          appCtx = appCtx,
          settings = settings,
          leagueCount = leagueCount,
          lastCrash = lastCrash,
          manual = false
        )
      }
    }
  }

  fun syncManualSnapshotNow(
    context: Context,
    settings: GameDaySettings,
    leagueCount: Int,
    lastCrash: CrashReportSummary?
  ) {
    if (!settings.optInAnonymousTelemetry) {
      _lastBeaconStatus.value = "Enable Anonymous Crash & Feed Health Reports above to sync to Firebase"
      return
    }
    val appCtx = context.applicationContext
    scope.launch {
      if (installed) {
        initCompleted.await()
      }
      syncMutex.withLock {
        syncSnapshotToFirebaseLocked(
          appCtx = appCtx,
          settings = settings,
          leagueCount = leagueCount,
          lastCrash = lastCrash,
          manual = true
        )
      }
    }
  }

  private suspend fun syncSnapshotToFirebaseLocked(
    appCtx: Context,
    settings: GameDaySettings,
    leagueCount: Int,
    lastCrash: CrashReportSummary?,
    manual: Boolean
  ) {
    val now = System.currentTimeMillis()
    val snap = synchronized(this@GameDayTelemetry) {
      val cur = _snapshot.value
      val nextCrashTs = if (lastCrash != null) {
        maxOf(cur.lastReportedCrashEpochMillis, lastCrash.timestampMillis)
      } else {
        cur.lastReportedCrashEpochMillis
      }
      val updated = cur.copy(
        lastHeartbeatEpochMillis = now,
        lastReportedCrashEpochMillis = nextCrashTs
      )
      _snapshot.value = updated
      updated
    }
    persistLatestOrdered()
    GameDayFirebaseBridge.syncContext(appCtx, settings, leagueCount, snap)
    GameDayFirebaseBridge.logEvent(
      context = appCtx,
      eventName = "health_snapshot_synced",
      params = mapOf(
        "poll_success_pct" to snap.pollSuccessRatePct,
        "espn_avg_ms" to snap.espnAvgLatencyMs,
        "spoken_total" to snap.ttsAnnouncementsSpoken,
        "manual" to manual
      )
    )
    _lastBeaconStatus.value = if (manual) {
      "Health snapshot queued for Google Firebase"
    } else {
      "Active · Google Firebase Crashlytics & Analytics"
    }
  }

  private fun mutate(transform: (TelemetrySnapshot) -> TelemetrySnapshot) {
    synchronized(this) {
      _snapshot.value = transform(_snapshot.value)
    }
    scope.launch { persistLatestOrdered() }
  }

  private suspend fun persistLatestOrdered(awaitInit: Boolean = true) {
    if (installed && awaitInit) {
      initCompleted.await()
    }
    val file = metricsFile ?: return
    persistMutex.withLock {
      runCatching {
        synchronized(fileIoLock) {
          val latest = synchronized(this@GameDayTelemetry) { _snapshot.value }
          writeSnapshotAtomicallyLocked(file, latest)
        }
      }
    }
  }

  private fun writeSnapshotAtomicallyLocked(file: File, snap: TelemetrySnapshot) {
    val parentDir = file.parentFile ?: file.absoluteFile.parentFile ?: return
    val tmpPath = Files.createTempFile(parentDir.toPath(), file.name, ".tmp")
    try {
      tmpPath.toFile().writeText(json.encodeToString(snap))
      try {
        Files.move(
          tmpPath,
          file.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE
        )
      } catch (_: Exception) {
        Files.move(tmpPath, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      runCatching { Files.deleteIfExists(tmpPath) }
    }
  }
}
