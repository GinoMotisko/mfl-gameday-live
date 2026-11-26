package com.mflgameday.live.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.mflgameday.live.MainActivity
import com.mflgameday.live.MflGameDayApp
import com.mflgameday.live.diagnostics.DiagnosticsLogger
import com.mflgameday.live.diagnostics.LogSubsystem
import com.mflgameday.live.model.GamePhase

/**
 * In-process BroadcastReceiver handling notification shade actions (`Mute/Unmute`, `Refresh`, `Stop`)
 * and user swipe-dismissal (`setDeleteIntent`) directly on `GameDayCoordinator` without re-triggering
 * `startForegroundService()` or redundant `ServiceCompat.startForeground()` promotions on Android 12–16.
 */
class NotificationDismissReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val app = context.applicationContext as? MflGameDayApp ?: return
    when (intent?.action) {
      GameDayLiveService.ACTION_TOGGLE_MUTE -> {
        app.coordinator.updateSettings { it.copy(muted = !it.muted) }
      }
      GameDayLiveService.ACTION_REFRESH_NOW -> {
        app.coordinator.refreshNow()
      }
      GameDayLiveService.ACTION_STOP_SERVICE -> {
        app.coordinator.pauseLiveBroadcastSession("Broadcast stopped from notification")
      }
      GameDayLiveService.ACTION_NOTIFICATION_DISMISSED -> {
        if (!GameDayLiveService.isRunningInForeground && !GameDayLiveService.isStartPending) return
        app.coordinator.pauseLiveBroadcastSession("Broadcast stopped when notification was dismissed")
      }
    }
  }
}

/**
 * Android 14 / 15 / 16 / 17 compliant `mediaPlayback` Foreground Service.
 * Runs strictly during active NFL game windows (or Demo Simulator), keeping live-score polling
 * and audible TTS play-by-play uninterrupted while the screen is off or in background apps,
 * and tearing down automatically once games conclude or when dismissed by the user.
 */
class GameDayLiveService : LifecycleService() {

  private var wakeLock: PowerManager.WakeLock? = null

  companion object {
    private const val CHANNEL_ID = "mfl_gameday_live_broadcast"
    private const val NOTIFICATION_ID = 2026
    internal const val ACTION_TOGGLE_MUTE = "com.mflgameday.live.ACTION_TOGGLE_MUTE"
    internal const val ACTION_REFRESH_NOW = "com.mflgameday.live.ACTION_REFRESH_NOW"
    internal const val ACTION_STOP_SERVICE = "com.mflgameday.live.ACTION_STOP_SERVICE"
    internal const val ACTION_NOTIFICATION_DISMISSED = "com.mflgameday.live.ACTION_NOTIFICATION_DISMISSED"

    @Volatile
    internal var isRunningInForeground: Boolean = false

    @Volatile
    internal var isStartPending: Boolean = false

    @Volatile
    private var pendingStopRequested: Boolean = false

    @Volatile
    private var lastRenderedSignature: String? = null

    internal fun markStopped() {
      isRunningInForeground = false
      isStartPending = false
      pendingStopRequested = false
      lastRenderedSignature = null
    }

    fun start(context: Context) {
      pendingStopRequested = false
      if (isRunningInForeground) {
        notifySummaryUpdate(context)
        return
      }
      if (isStartPending) {
        return
      }
      isStartPending = true
      runCatching {
        val intent = Intent(context, GameDayLiveService::class.java)
        context.startForegroundService(intent)
      }.onFailure { err ->
        isStartPending = false
        DiagnosticsLogger.warn(
          LogSubsystem.APP,
          "Foreground service start deferred by OS (${err.javaClass.simpleName})"
        )
      }
    }

    fun stop(context: Context) {
      if (isStartPending && !isRunningInForeground) {
        // Android 12–16 FGS timeout invariant: never call stopService() while startForegroundService()
        // is already in-flight in ActivityManagerService; let onStartCommand() promote then immediately stop!
        pendingStopRequested = true
        return
      }
      markStopped()
      runCatching {
        context.stopService(Intent(context, GameDayLiveService::class.java))
      }
      runCatching {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIFICATION_ID)
      }
    }

    fun notifySummaryUpdate(context: Context) {
      if (!isRunningInForeground) return
      val app = context.applicationContext as? MflGameDayApp ?: return
      val coordinator = app.coordinator
      val state = coordinator.uiState.value
      val now = System.currentTimeMillis()
      val isSpeaking = state.isSpeakingNow || coordinator.voiceEngine.hasActiveOrPendingSpeech()
      val shouldStayForeground = ServiceLifecyclePolicy.shouldRunForegroundService(
        settings = state.settings,
        isBroadcastPaused = state.isBroadcastPaused,
        nflGames = state.nflGames,
        nowMillis = now,
        isSpeakingNow = isSpeaking
      )
      if (!shouldStayForeground) {
        stop(context)
        return
      }

      val summary = computeSummary(state, now)
      if (summary.signature == lastRenderedSignature) {
        // Identical title/body/mute state: do not call nm.notify() so Android never resets timestamp or resurfaces shade!
        return
      }
      lastRenderedSignature = summary.signature
      val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
      runCatching {
        nm.notify(NOTIFICATION_ID, buildNotificationFromSummary(context, summary))
      }
    }

    private fun computeSummary(
      state: GameDayUiState,
      nowMillis: Long = System.currentTimeMillis()
    ): ServiceLifecyclePolicy.NotificationSummary {
      val liveGames = state.nflGames.count { it.phase == GamePhase.LIVE || it.phase == GamePhase.HALFTIME }
      val activeSnap = state.activeLeagueSnapshot
      val myMatchup = activeSnap?.matchups?.firstOrNull { it.involvesMyFranchise }
      val fmt = "%.${activeSnap?.config?.precision ?: 2}f"
      val appBrand = if (com.mflgameday.live.BuildConfig.IS_PLAY_STORE_BUILD) "GameDay Live" else "MFL GameDay Live"

      val matchupHeadline = if (myMatchup != null) {
        val me = myMatchup.mySide(activeSnap.config.franchiseId)
        val opp = myMatchup.opponentSide(activeSnap.config.franchiseId)
        if (me != null && opp != null) {
          "${activeSnap.config.leagueName}: ${fmt.format(me.score)} – ${fmt.format(opp.score)} vs ${opp.franchiseName}"
        } else null
      } else null

      val latestRealOrDemoEntry = state.playLog.firstOrNull { entry ->
        state.settings.operatingMode == com.mflgameday.live.model.OperatingMode.DEMO_SIMULATOR ||
          !ServiceLifecyclePolicy.isSimulatedPlayId(entry.event.id)
      }

      return ServiceLifecyclePolicy.buildNotificationSummary(
        appBrand = appBrand,
        operatingMode = state.settings.operatingMode,
        currentWeek = state.currentWeek,
        muted = state.settings.muted,
        scope = state.settings.scope,
        liveGamesCount = liveGames,
        matchupHeadline = matchupHeadline,
        latestPlaySpokenText = latestRealOrDemoEntry?.spokenText,
        latestPlayEventId = latestRealOrDemoEntry?.event?.id,
        latestPlayTimestampMillis = latestRealOrDemoEntry?.timestampMillis,
        nowMillis = nowMillis
      )
    }

    internal fun buildNotification(context: Context, coordinator: GameDayCoordinator): Notification {
      val summary = computeSummary(coordinator.uiState.value, System.currentTimeMillis())
      lastRenderedSignature = summary.signature
      return buildNotificationFromSummary(context, summary)
    }

    private fun buildNotificationFromSummary(
      context: Context,
      summary: ServiceLifecyclePolicy.NotificationSummary
    ): Notification {
      val openIntent = PendingIntent.getActivity(
        context,
        10,
        Intent(context, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )

      val muteIntent = PendingIntent.getBroadcast(
        context,
        11,
        Intent(context, NotificationDismissReceiver::class.java).setAction(ACTION_TOGGLE_MUTE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )

      val refreshIntent = PendingIntent.getBroadcast(
        context,
        12,
        Intent(context, NotificationDismissReceiver::class.java).setAction(ACTION_REFRESH_NOW),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )

      val stopIntent = PendingIntent.getBroadcast(
        context,
        13,
        Intent(context, NotificationDismissReceiver::class.java).setAction(ACTION_STOP_SERVICE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )

      val dismissIntent = PendingIntent.getBroadcast(
        context,
        14,
        Intent(context, NotificationDismissReceiver::class.java).setAction(ACTION_NOTIFICATION_DISMISSED),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )

      return NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
        .setContentTitle(summary.title)
        .setContentText(summary.body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(summary.body))
        .setContentIntent(openIntent)
        .setDeleteIntent(dismissIntent)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setShowWhen(false)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        .addAction(0, summary.muteActionLabel, muteIntent)
        .addAction(0, "Refresh", refreshIntent)
        .addAction(0, "Stop", stopIntent)
        .build()
    }
  }

  override fun onCreate() {
    super.onCreate()
    lastRenderedSignature = null
    ensureChannel()
    acquirePartialWakeLock()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)
    val coordinator = (application as MflGameDayApp).coordinator

    // Android 12–16 strictly requires calling startForeground() within the FGS timeout window
    // whenever startForegroundService() was queued — even if pendingStopRequested is true!
    val wasAlreadyForeground = isRunningInForeground
    val promoted = if (wasAlreadyForeground && !pendingStopRequested) {
      true
    } else {
      val notification = buildNotification(this, coordinator)
      val fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
      runCatching {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
      }.isSuccess
    }
    isStartPending = false

    if (!promoted || pendingStopRequested) {
      markStopped()
      if (promoted) {
        stopForeground(STOP_FOREGROUND_REMOVE)
      }
      stopSelf()
      return START_NOT_STICKY
    }

    val state = coordinator.uiState.value
    val isSpeaking = state.isSpeakingNow || coordinator.voiceEngine.hasActiveOrPendingSpeech()
    val shouldRun = ServiceLifecyclePolicy.shouldRunForegroundService(
      settings = state.settings,
      isBroadcastPaused = state.isBroadcastPaused,
      nflGames = state.nflGames,
      nowMillis = System.currentTimeMillis(),
      isSpeakingNow = isSpeaking
    )
    if (!shouldRun) {
      markStopped()
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    isRunningInForeground = true
    if (!wasAlreadyForeground) {
      com.mflgameday.live.diagnostics.GameDayTelemetry.recordServiceStart()
    }

    coordinator.ensureEngineStartedFromService()
    return if (state.settings.stopWhenSwipedClosed) {
      START_NOT_STICKY
    } else {
      START_STICKY
    }
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    val coordinator = (application as? MflGameDayApp)?.coordinator
    if (coordinator != null && ServiceLifecyclePolicy.shouldStopOnTaskRemoved(coordinator.uiState.value.settings)) {
      markStopped()
      com.mflgameday.live.diagnostics.GameDayTelemetry.recordSwipeCloseStop()
      coordinator.pauseLiveBroadcastSession("Stopped when swiped closed from Recent Apps")
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    }
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    markStopped()
    releaseWakeLock()
    super.onDestroy()
  }

  override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

  private fun ensureChannel() {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel(CHANNEL_ID) == null) {
      val channelTitle = if (com.mflgameday.live.BuildConfig.IS_PLAY_STORE_BUILD) {
        "GameDay Live Announcer"
      } else {
        "MFL GameDay Live Announcer"
      }
      val channel = NotificationChannel(
        CHANNEL_ID,
        channelTitle,
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "Keeps live football & fantasy score polling and spoken play-by-play active during game windows."
        setShowBadge(false)
      }
      nm.createNotificationChannel(channel)
    }
  }

  private fun acquirePartialWakeLock() {
    runCatching {
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MFLGameDay::LiveAnnouncerWakeLock").apply {
        setReferenceCounted(false)
        // Bounded to 12 hours max (covers full Sunday 1pm -> midnight slate safely; released immediately on stop)
        acquire(12 * 60 * 60 * 1000L)
      }
    }
  }

  private fun releaseWakeLock() {
    runCatching {
      if (wakeLock?.isHeld == true) {
        wakeLock?.release()
      }
      wakeLock = null
    }
  }
}
