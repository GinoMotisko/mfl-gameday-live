package com.mflgameday.live.diagnostics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.mflgameday.live.BuildConfig
import com.mflgameday.live.model.GameDaySettings

/**
 * Stack-frame-preserving redacted wrapper exception so handled non-fatal Crashlytics reports
 * keep exact Kotlin file/line deobfuscation frames while stripping any URL query tokens from messages.
 */
class RedactedDiagnosticException(
  redactedMessage: String,
  originalFrames: Array<StackTraceElement>
) : RuntimeException(redactedMessage, null) {
  init {
    stackTrace = originalFrames
  }
}

/**
 * Privacy-hardened, exception-safe bridge to Google Firebase Crashlytics and Google Analytics for Firebase.
 * - Never collects Advertising ID (`AD_ID` stripped + manifest meta-data disabled).
 * - Never sends MFL usernames, session cookies, API keys, or 5-digit League IDs.
 * - Wrapped in `runCatching` on every entrypoint so JVM unit tests and AOSP devices without Play Services never fail.
 */
object GameDayFirebaseBridge {
  @Volatile
  private var collectionEnabled: Boolean = false

  private val leagueQueryParamRegex = Regex("""(?i)([?&]L=)([A-Za-z0-9_-]+)""")
  private val leagueProseIdRegex = Regex("""(?i)(league\s*#?\s*)\d{4,7}""")

  fun install(context: Context, enabled: Boolean) {
    setCollectionEnabled(context, enabled)
  }

  fun setCollectionEnabled(context: Context, enabled: Boolean) {
    collectionEnabled = enabled
    val appCtx = context.applicationContext
    runCatching {
      val crashlytics = FirebaseCrashlytics.getInstance()
      crashlytics.isCrashlyticsCollectionEnabled = enabled
      if (!enabled) {
        crashlytics.deleteUnsentReports()
      }
    }
    runCatching {
      val analytics = FirebaseAnalytics.getInstance(appCtx)
      analytics.setAnalyticsCollectionEnabled(enabled)
      if (!enabled) {
        analytics.resetAnalyticsData()
      }
    }
  }

  fun syncContext(
    context: Context,
    settings: GameDaySettings,
    leagueCount: Int,
    snapshot: TelemetrySnapshot
  ) {
    if (!collectionEnabled) return
    val edition = if (BuildConfig.IS_PLAY_STORE_BUILD) "playStore" else "direct"
    val safeLeagueCount = leagueCount.coerceIn(0, 99)
    runCatching {
      val crashlytics = FirebaseCrashlytics.getInstance()
      crashlytics.setCustomKey("edition", edition)
      crashlytics.setCustomKey("operating_mode", settings.operatingMode.name)
      crashlytics.setCustomKey("voice_persona", settings.voicePersona.name)
      crashlytics.setCustomKey("announcement_scope", settings.scope.name)
      crashlytics.setCustomKey("theme_preset", settings.themePreset.name)
      crashlytics.setCustomKey("league_count", safeLeagueCount)
      crashlytics.setCustomKey("poll_success_pct", snapshot.pollSuccessRatePct)
      crashlytics.setCustomKey("tts_spoken_count", snapshot.ttsAnnouncementsSpoken)
    }
    runCatching {
      val analytics = FirebaseAnalytics.getInstance(context.applicationContext)
      analytics.setUserProperty("edition", edition)
      analytics.setUserProperty("operating_mode", settings.operatingMode.name.take(36))
      analytics.setUserProperty("commentator", settings.voicePersona.shortName.take(36))
      analytics.setUserProperty("league_count", safeLeagueCount.toString())
    }
  }

  fun logBreadcrumb(level: LogLevel, subsystem: LogSubsystem, redactedMessage: String) {
    if (!collectionEnabled) return
    runCatching {
      FirebaseCrashlytics.getInstance().log(
        "[${level.name}] [${subsystem.name}] ${redactTelemetryPii(redactedMessage).take(240)}"
      )
    }
  }

  fun recordHandledError(subsystem: LogSubsystem, redactedMessage: String, throwable: Throwable?) {
    if (!collectionEnabled || throwable == null) return
    runCatching {
      val crashlytics = FirebaseCrashlytics.getInstance()
      crashlytics.setCustomKey("last_error_subsystem", subsystem.name)
      val safeThrowable = buildRedactedException(redactedMessage, throwable)
      crashlytics.recordException(safeThrowable)
    }
  }

  fun logEvent(context: Context, eventName: String, params: Map<String, Any> = emptyMap()) {
    if (!collectionEnabled) return
    runCatching {
      val bundle = Bundle()
      for ((rawKey, value) in params) {
        val key = rawKey.take(40)
        when (value) {
          is String -> bundle.putString(key, redactTelemetryPii(value).take(100))
          is Int -> bundle.putLong(key, value.toLong())
          is Long -> bundle.putLong(key, value)
          is Double -> bundle.putDouble(key, value)
          is Float -> bundle.putDouble(key, value.toDouble())
          is Boolean -> bundle.putString(key, value.toString())
        }
      }
      FirebaseAnalytics.getInstance(context.applicationContext).logEvent(eventName.take(40), bundle)
    }
  }

  internal fun redactTelemetryPii(input: String): String {
    val secretsScrubbed = DiagnosticsLogger.redactSecrets(input)
    val queryScrubbed = leagueQueryParamRegex.replace(secretsScrubbed) { m -> "${m.groupValues[1]}<LEAGUE_ID>" }
    return leagueProseIdRegex.replace(queryScrubbed) { m -> "${m.groupValues[1]}<LEAGUE_ID>" }
  }

  internal fun buildRedactedException(redactedContext: String, throwable: Throwable): RedactedDiagnosticException {
    val rawDetail = "${throwable.javaClass.name}: ${throwable.message.orEmpty()}"
    val scrubbedDetail = redactTelemetryPii(rawDetail).take(300)
    return RedactedDiagnosticException(
      redactedMessage = "${redactTelemetryPii(redactedContext).take(160)} | $scrubbedDetail",
      originalFrames = throwable.stackTrace
    )
  }
}
