package com.mflgameday.live.diagnostics

import android.content.Context
import android.os.Build
import com.mflgameday.live.BuildConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

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
  val lastHeartbeatEpochMillis: Long = 0L
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
 * Never records usernames, MFL cookies, league IDs, franchise names, or personal identifiers.
 * Network reporting is strictly opt-in (`optInAnonymousTelemetry`) or manual one-tap send.
 */
object GameDayTelemetry {
  private const val ENDPOINT_URL = "https://www.motisko.com/api/gameday/telemetry"
  private const val MIN_AUTO_BEACON_INTERVAL_MS = 6L * 60L * 60L * 1000L // 6 hours

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
    val dir = File(context.applicationContext.filesDir, "diagnostics").apply { mkdirs() }
    val file = File(dir, "telemetry_metrics.json")
    metricsFile = file

    scope.launch {
      val loaded = runCatching {
        if (file.exists()) json.decodeFromString<TelemetrySnapshot>(file.readText()) else TelemetrySnapshot()
      }.getOrDefault(TelemetrySnapshot())

      val updated = loaded.copy(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        flavor = if (BuildConfig.IS_PLAY_STORE_BUILD) "playStore" else "direct",
        sdkInt = Build.VERSION.SDK_INT,
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".take(48),
        sessionsStarted = loaded.sessionsStarted + 1L
      )
      _snapshot.value = updated
      persist(updated)
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

  fun recordCrashDetected() = mutate { it.copy(crashCount = it.crashCount + 1L) }

  fun resetMetrics() {
    val fresh = TelemetrySnapshot(sessionsStarted = 1L)
    _snapshot.value = fresh
    scope.launch { persist(fresh) }
    _lastBeaconStatus.value = "Telemetry counters reset"
  }

  fun encodeJsonSummary(): String = runCatching {
    json.encodeToString(_snapshot.value)
  }.getOrDefault("{}")

  fun maybeSendAutoHeartbeat(operatingMode: String, optInEnabled: Boolean, lastCrash: CrashReportSummary?) {
    if (!optInEnabled) return
    val now = System.currentTimeMillis()
    val cur = _snapshot.value
    if (lastCrash == null && (now - cur.lastHeartbeatEpochMillis) < MIN_AUTO_BEACON_INTERVAL_MS) return
    scope.launch {
      sendSnapshotSuspend(operatingMode = operatingMode, lastCrash = lastCrash, manual = false)
    }
  }

  fun sendManualSnapshotNow(operatingMode: String, lastCrash: CrashReportSummary?) {
    scope.launch {
      sendSnapshotSuspend(operatingMode = operatingMode, lastCrash = lastCrash, manual = true)
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
          mutate { it.copy(lastHeartbeatEpochMillis = now) }
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
    val next = synchronized(this) {
      val updated = transform(_snapshot.value)
      _snapshot.value = updated
      updated
    }
    scope.launch { persist(next) }
  }

  private fun persist(snap: TelemetrySnapshot) {
    val file = metricsFile ?: return
    runCatching { file.writeText(json.encodeToString(snap)) }
  }
}
