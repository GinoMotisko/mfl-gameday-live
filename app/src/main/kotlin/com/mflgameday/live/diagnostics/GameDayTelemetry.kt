package com.mflgameday.live.diagnostics

import android.content.Context
import android.os.Build
import com.mflgameday.live.BuildConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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

@Serializable
data class TelemetryUploadPayload(
  val telemetry: TelemetrySnapshot,
  val operatingMode: String,
  val lastCrash: CrashReportSummary? = null
)

/**
 * Privacy-first operational telemetry collector & optional anonymous health reporter.
 * - Awaits initial disk hydration (`initCompleted`) before evaluating auto-heartbeat intervals.
 * - Weights latency averages accurately when merging early cold-start polls with persisted history.
 * - Deduplicates crash reporting via `lastReportedCrashEpochMillis` so a stored crash is never re-sent on every launch.
 * - Serializes persistence through `persistMutex` with atomic temp-file replacement.
 */
object GameDayTelemetry {
  private const val ENDPOINT_URL = "https://www.motisko.com/api/gameday/telemetry"
  private const val MIN_AUTO_BEACON_INTERVAL_MS = 6L * 60L * 60L * 1000L // 6 hours

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val persistMutex = Mutex()
  private val beaconMutex = Mutex()
  private val fileIoLock = Any()
  private var initCompleted = CompletableDeferred<Unit>()
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .writeTimeout(8, TimeUnit.SECONDS)
    .build()

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
        persistLatestOrdered()
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
    scope.launch { persistLatestOrdered() }
    _lastBeaconStatus.value = "Telemetry counters reset"
  }

  fun encodeJsonSummary(): String = runCatching {
    json.encodeToString(_snapshot.value)
  }.getOrDefault("{}")

  fun maybeSendAutoHeartbeat(operatingMode: String, optInEnabled: Boolean, lastCrash: CrashReportSummary?) {
    if (!optInEnabled) return
    scope.launch {
      if (installed) {
        initCompleted.await()
      }
      beaconMutex.withLock {
        val now = System.currentTimeMillis()
        val cur = _snapshot.value
        val unseenCrash = lastCrash != null && lastCrash.timestampMillis > cur.lastReportedCrashEpochMillis
        if (!unseenCrash && (now - cur.lastHeartbeatEpochMillis) < MIN_AUTO_BEACON_INTERVAL_MS) {
          return@withLock
        }
        sendSnapshotSuspend(
          operatingMode = operatingMode,
          lastCrash = if (unseenCrash) lastCrash else null,
          manual = false
        )
      }
    }
  }

  fun sendManualSnapshotNow(operatingMode: String, lastCrash: CrashReportSummary?) {
    scope.launch {
      if (installed) {
        initCompleted.await()
      }
      beaconMutex.withLock {
        sendSnapshotSuspend(operatingMode = operatingMode, lastCrash = lastCrash, manual = true)
      }
    }
  }

  private suspend fun sendSnapshotSuspend(
    operatingMode: String,
    lastCrash: CrashReportSummary?,
    manual: Boolean
  ): Boolean = withContext(Dispatchers.IO) {
    if (manual) _lastBeaconStatus.value = "Sending anonymous snapshot…"
    val now = System.currentTimeMillis()
    val payload = TelemetryUploadPayload(
      telemetry = _snapshot.value.copy(lastHeartbeatEpochMillis = now),
      operatingMode = operatingMode,
      lastCrash = lastCrash
    )
    val bodyStr = json.encodeToString(payload)
    val req = Request.Builder()
      .url(ENDPOINT_URL)
      .header("User-Agent", "GameDayLive/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.SDK_INT})")
      .post(bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType()))
      .build()

    runCatching {
      httpClient.newCall(req).execute().use { resp ->
        if (resp.isSuccessful) {
          synchronized(this@GameDayTelemetry) {
            val cur = _snapshot.value
            val nextCrashTs = if (lastCrash != null) {
              maxOf(cur.lastReportedCrashEpochMillis, lastCrash.timestampMillis)
            } else {
              cur.lastReportedCrashEpochMillis
            }
            _snapshot.value = cur.copy(
              lastHeartbeatEpochMillis = now,
              lastReportedCrashEpochMillis = nextCrashTs
            )
          }
          persistLatestOrdered()
          _lastBeaconStatus.value = "Telemetry snapshot delivered (HTTP ${resp.code})"
          DiagnosticsLogger.info(LogSubsystem.NETWORK, "Anonymous telemetry snapshot delivered")
          true
        } else {
          if (manual) _lastBeaconStatus.value = "Server returned HTTP ${resp.code}"
          false
        }
      }
    }.getOrElse { err ->
      if (manual) _lastBeaconStatus.value = "Offline / unreachable (${err.javaClass.simpleName})"
      false
    }
  }

  private fun mutate(transform: (TelemetrySnapshot) -> TelemetrySnapshot) {
    synchronized(this) {
      _snapshot.value = transform(_snapshot.value)
    }
    scope.launch { persistLatestOrdered() }
  }

  private suspend fun persistLatestOrdered() {
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
