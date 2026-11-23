package com.mflgameday.live.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class LogLevel {
  INFO,
  WARN,
  ERROR
}

@Serializable
enum class LogSubsystem {
  APP,
  NETWORK,
  MFL_API,
  ESPN_API,
  TTS_AUDIO,
  SERVICE,
  CRASH
}

@Serializable
data class DiagnosticEntry(
  val timestampMillis: Long,
  val level: LogLevel,
  val subsystem: LogSubsystem,
  val message: String,
  val detail: String? = null
)

@Serializable
data class CrashReportSummary(
  val timestampMillis: Long,
  val threadName: String,
  val exceptionSummary: String,
  val stackTrace: String,
  val deviceInfo: String
)

/**
 * Production-hardened diagnostic ring-buffer & uncaught crash logger.
 * Automatically scrubs sensitive tokens (`PASSWORD`, `APIKEY`, `MFL_USER_ID`) before storing
 * or exporting logs.
 */
object DiagnosticsLogger {
  private const val MAX_ENTRIES = 350
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
  private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
  private val entriesDeque = ConcurrentLinkedDeque<DiagnosticEntry>()

  private val _entries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
  val entries: StateFlow<List<DiagnosticEntry>> = _entries.asStateFlow()

  private val _lastCrash = MutableStateFlow<CrashReportSummary?>(null)
  val lastCrash: StateFlow<CrashReportSummary?> = _lastCrash.asStateFlow()

  private var diagDir: File? = null
  private var previousHandler: Thread.UncaughtExceptionHandler? = null
  private var initialized = false

  // Redacts passwords, API keys, and MFL cookies from URLs/messages
  private val secretParamRegex = Regex(
    """(?i)(PASSWORD|APIKEY|MFL_USER_ID)=([^&\s;"']+)"""
  )

  fun install(context: Context) {
    if (initialized) return
    initialized = true
    val appCtx = context.applicationContext
    val dir = File(appCtx.filesDir, "diagnostics").apply { mkdirs() }
    diagDir = dir

    // Load prior persisted session logs & crash report (if any)
    scope.launch {
      runCatching {
        val crashFile = File(dir, "last_crash.json")
        if (crashFile.exists()) {
          _lastCrash.value = json.decodeFromString<CrashReportSummary>(crashFile.readText())
        }
      }
      runCatching {
        val logFile = File(dir, "session_ring.jsonl")
        if (logFile.exists()) {
          val lines = logFile.readLines().takeLast(MAX_ENTRIES)
          lines.asReversed().mapNotNull { line ->
            runCatching { json.decodeFromString<DiagnosticEntry>(line) }.getOrNull()
          }.forEach { entriesDeque.addFirst(it) }
          while (entriesDeque.size > MAX_ENTRIES) {
            entriesDeque.pollFirst()
          }
          _entries.value = entriesDeque.toList().reversed()
        }
      }
      info(
        LogSubsystem.APP,
        "Diagnostics initialized on ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})"
      )
    }

    // Install chained global UncaughtExceptionHandler
    previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      runCatching {
        recordCrashSynchronously(thread, throwable)
      }
      previousHandler?.uncaughtException(thread, throwable)
    }
  }

  fun info(subsystem: LogSubsystem, message: String, detail: String? = null) =
    record(LogLevel.INFO, subsystem, message, detail)

  fun warn(subsystem: LogSubsystem, message: String, detail: String? = null) =
    record(LogLevel.WARN, subsystem, message, detail)

  fun error(subsystem: LogSubsystem, message: String, throwable: Throwable? = null) {
    val trace = throwable?.let { stackTraceString(it).take(1200) }
    record(LogLevel.ERROR, subsystem, message, trace)
  }

  private fun record(level: LogLevel, subsystem: LogSubsystem, message: String, detail: String?) {
    val entry = DiagnosticEntry(
      timestampMillis = System.currentTimeMillis(),
      level = level,
      subsystem = subsystem,
      message = redactSecrets(message),
      detail = detail?.let(::redactSecrets)
    )
    entriesDeque.addLast(entry)
    while (entriesDeque.size > MAX_ENTRIES) {
      entriesDeque.pollFirst()
    }
    val snapshot = entriesDeque.toList().reversed()
    _entries.value = snapshot

    val dir = diagDir ?: return
    scope.launch {
      runCatching {
        val file = File(dir, "session_ring.jsonl")
        if (file.exists() && file.length() > 256_000L) {
          // Rotate compactly
          val recentLines = snapshot.take(180).reversed().joinToString("\n") { json.encodeToString(it) } + "\n"
          file.writeText(recentLines)
        } else {
          file.appendText(json.encodeToString(entry) + "\n")
        }
      }
    }
  }

  private fun recordCrashSynchronously(thread: Thread, throwable: Throwable) {
    val dir = diagDir ?: return
    val summary = CrashReportSummary(
      timestampMillis = System.currentTimeMillis(),
      threadName = thread.name,
      exceptionSummary = redactSecrets("${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}"),
      stackTrace = redactSecrets(stackTraceString(throwable).take(4000)),
      deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
    )
    GameDayTelemetry.recordCrashSynchronously()
    File(dir, "last_crash.json").writeText(json.encodeToString(summary))
  }

  fun clearLogs() {
    entriesDeque.clear()
    _entries.value = emptyList()
    diagDir?.let { dir ->
      scope.launch { runCatching { File(dir, "session_ring.jsonl").delete() } }
    }
    info(LogSubsystem.APP, "Diagnostic logs cleared by user")
  }

  fun dismissCrashReport() {
    _lastCrash.value = null
    diagDir?.let { dir ->
      scope.launch { runCatching { File(dir, "last_crash.json").delete() } }
    }
  }

  fun buildFormattedReport(): String {
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    val sb = StringBuilder()
    sb.appendLine("=== MFL GameDay Live Diagnostic Report ===")
    sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
    sb.appendLine("Generated: ${fmt.format(Instant.now())}")
    sb.appendLine()
    sb.appendLine("--- TELEMETRY SNAPSHOT JSON ---")
    sb.appendLine(GameDayTelemetry.encodeJsonSummary())
    _lastCrash.value?.let { crash ->
      sb.appendLine()
      sb.appendLine("--- LAST CAPTURED CRASH (${fmt.format(Instant.ofEpochMilli(crash.timestampMillis))}) ---")
      sb.appendLine("Thread: ${crash.threadName} | ${crash.exceptionSummary}")
      sb.appendLine(crash.stackTrace)
    }
    sb.appendLine()
    sb.appendLine("--- RECENT SESSION EVENTS (${_entries.value.size}) ---")
    for (e in _entries.value.take(200)) {
      val ts = fmt.format(Instant.ofEpochMilli(e.timestampMillis))
      sb.appendLine("[$ts] [${e.level}] [${e.subsystem}] ${e.message}")
      if (!e.detail.isNullOrBlank()) {
        sb.appendLine("    ${e.detail}")
      }
    }
    return sb.toString()
  }

  fun copyReportToClipboard(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("MFL GameDay Diagnostics", buildFormattedReport()))
    true
  }.getOrDefault(false)

  const val SUPPORT_EMAIL = "support.gameday@motisko.com"

  fun shareReportIntent(context: Context) {
    runCatching {
      val report = buildFormattedReport()
      val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "GameDay Live — Diagnostic Log Report")
        putExtra(Intent.EXTRA_TEXT, report)
      }
      context.startActivity(Intent.createChooser(sendIntent, "Share Diagnostic Logs"))
    }
  }

  fun emailSupportIntent(context: Context) {
    runCatching {
      val report = buildFormattedReport()
      val subject = "GameDay Live Support (${Build.MODEL})"
      val mailUri = Uri.parse(
        "mailto:$SUPPORT_EMAIL?subject=${Uri.encode(subject)}&body=${Uri.encode("Describe what happened above this line:\n\n" + report.take(6000))}"
      )
      val intent = Intent(Intent.ACTION_SENDTO, mailUri)
      context.startActivity(intent)
    }.onFailure {
      shareReportIntent(context)
    }
  }

  internal fun redactSecrets(input: String): String =
    secretParamRegex.replace(input) { match -> "${match.groupValues[1]}=<REDACTED>" }

  private fun stackTraceString(t: Throwable): String {
    val sw = StringWriter()
    t.printStackTrace(PrintWriter(sw))
    return sw.toString()
  }
}
