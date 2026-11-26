package com.mflgameday.live.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.mflgameday.live.MainActivity
import com.mflgameday.live.MflGameDayApp
import com.mflgameday.live.diagnostics.DiagnosticsLogger
import com.mflgameday.live.diagnostics.LogSubsystem
import com.mflgameday.live.model.GamePhase

/**
 * In-process BroadcastReceiver handling media/notification controls (`Mute/Unmute`, `Refresh`, `Stop`).
 * Importantly, on Android 14–16 (`targetSdk = 36`) tapping "Clear all" in the Pixel notification shade
 * dismisses ongoing notifications (`ACTION_NOTIFICATION_DISMISSED`). Just like Spotify / YouTube Music,
 * clearing the shade MUST NOT pause or kill the live audio broadcast — only explicit `Stop`, swipe-close
 * from Recent Apps (`onTaskRemoved`), or games finishing (`FINAL`) stops the announcer.
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
        app.coordinator.pauseLiveBroadcastSession(
          reason = "Broadcast stopped from notification",
          explicitUserPause = true
        )
      }
      GameDayLiveService.ACTION_NOTIFICATION_DISMISSED -> {
        // User cleared notifications in the Android 14–16 shade: keep broadcast alive and allow next
        // score/play change to re-post the live scoreboard card without killing audio!
        GameDayLiveService.onShadeCardDismissedBySystemUi()
      }
    }
  }
}

/**
 * Local zero-server Sunday morning wake receiver (`AlarmManager.setWindow`) that wakes
 * `GameDayCoordinator` automatically when the 90-minute pregame broadcast window opens.
 */
class PregameWakeReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != GameDayLiveService.ACTION_PREGAME_WAKE) return
    val app = context.applicationContext as? MflGameDayApp ?: return
    app.coordinator.onPregameWakeAlarmFired()
  }
}

/**
 * Android 14 / 15 / 16 / 17 compliant `mediaPlayback` Foreground Service with native `MediaSession`,
 * `PARTIAL_WAKE_LOCK`, and low-latency `WifiLock` so Pixel/Samsung devices never stall sockets or
 * drop announcements when the screen is off.
 */
class GameDayLiveService : LifecycleService() {

  private var wakeLock: PowerManager.WakeLock? = null
  private var wifiLock: WifiManager.WifiLock? = null
  private var mediaSession: MediaSession? = null

  companion object {
    private const val CHANNEL_ID = "mfl_gameday_live_broadcast"
    private const val NOTIFICATION_ID = 2026
    private const val PREGAME_ALARM_REQ_CODE = 2090
    internal const val ACTION_TOGGLE_MUTE = "com.mflgameday.live.ACTION_TOGGLE_MUTE"
    internal const val ACTION_REFRESH_NOW = "com.mflgameday.live.ACTION_REFRESH_NOW"
    internal const val ACTION_STOP_SERVICE = "com.mflgameday.live.ACTION_STOP_SERVICE"
    internal const val ACTION_NOTIFICATION_DISMISSED = "com.mflgameday.live.ACTION_NOTIFICATION_DISMISSED"
    internal const val ACTION_PREGAME_WAKE = "com.mflgameday.live.ACTION_PREGAME_WAKE"

    @Volatile
    internal var isRunningInForeground: Boolean = false

    @Volatile
    internal var isStartPending: Boolean = false

    @Volatile
    private var pendingStopRequested: Boolean = false

    @Volatile
    private var lastRenderedSignature: String? = null

    @Volatile
    private var activeMediaSessionToken: MediaSession.Token? = null

    @Volatile
    private var activeInstance: GameDayLiveService? = null

    internal fun onShadeCardDismissedBySystemUi() {
      lastRenderedSignature = null
    }

    internal fun markStopped() {
      isRunningInForeground = false
      isStartPending = false
      pendingStopRequested = false
      lastRenderedSignature = null
    }

    fun schedulePregameWakeAlarm(context: Context, triggerAtEpochMillis: Long) {
      runCatching {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(
          context.applicationContext,
          PREGAME_ALARM_REQ_CODE,
          Intent(context.applicationContext, PregameWakeReceiver::class.java).setAction(ACTION_PREGAME_WAKE),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Inexact 5-minute window requires ZERO exact-alarm permissions on Android 12–16!
        am.setWindow(AlarmManager.RTC_WAKEUP, triggerAtEpochMillis, 5L * 60_000L, pi)
        DiagnosticsLogger.info(
          LogSubsystem.APP,
          "Scheduled local pregame broadcast wakeup for ${java.time.Instant.ofEpochMilli(triggerAtEpochMillis)}"
        )
      }
    }

    fun cancelPregameWakeAlarm(context: Context) {
      runCatching {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(
          context.applicationContext,
          PREGAME_ALARM_REQ_CODE,
          Intent(context.applicationContext, PregameWakeReceiver::class.java).setAction(ACTION_PREGAME_WAKE),
          PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
          am.cancel(pi)
          pi.cancel()
        }
      }
    }

    fun start(context: Context) {
      cancelPregameWakeAlarm(context)
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
        isSpeakingNow = isSpeaking,
        preserveWhileSlateLoading = true
      )
      if (!shouldStayForeground) {
        stop(context)
        return
      }

      val summary = computeSummary(state, now)
      activeInstance?.syncMediaSessionMetadataAndState(summary, muted = state.settings.muted)
      if (summary.signature == lastRenderedSignature) {
        return
      }
      lastRenderedSignature = summary.signature
      val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
      runCatching {
        nm.notify(NOTIFICATION_ID, buildNotificationFromSummary(context, summary, activeMediaSessionToken))
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

    internal fun buildNotification(
      context: Context,
      coordinator: GameDayCoordinator,
      sessionToken: MediaSession.Token? = activeMediaSessionToken
    ): Notification {
      val state = coordinator.uiState.value
      val summary = computeSummary(state, System.currentTimeMillis())
      activeInstance?.syncMediaSessionMetadataAndState(summary, muted = state.settings.muted)
      lastRenderedSignature = summary.signature
      return buildNotificationFromSummary(context, summary, sessionToken)
    }

    private fun buildNotificationFromSummary(
      context: Context,
      summary: ServiceLifecyclePolicy.NotificationSummary,
      sessionToken: MediaSession.Token?
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

      val muteIcon = if (summary.muteActionLabel == "Unmute") {
        android.R.drawable.ic_lock_silent_mode
      } else {
        android.R.drawable.ic_lock_silent_mode_off
      }
      val muteAction = Notification.Action.Builder(
        Icon.createWithResource(context, muteIcon),
        summary.muteActionLabel,
        muteIntent
      ).build()
      val refreshAction = Notification.Action.Builder(
        Icon.createWithResource(context, android.R.drawable.stat_notify_sync),
        "Refresh",
        refreshIntent
      ).build()
      val stopAction = Notification.Action.Builder(
        Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
        "Stop",
        stopIntent
      ).build()

      val builder = Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
        .setContentTitle(summary.title)
        .setContentText(summary.body)
        .setContentIntent(openIntent)
        .setDeleteIntent(dismissIntent)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setCategory(Notification.CATEGORY_TRANSPORT)
        .addAction(muteAction)
        .addAction(refreshAction)
        .addAction(stopAction)

      if (sessionToken != null) {
        builder.setStyle(
          Notification.MediaStyle()
            .setMediaSession(sessionToken)
            .setShowActionsInCompactView(0, 1, 2)
        )
      } else {
        builder.setStyle(Notification.BigTextStyle().bigText(summary.body))
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
      }

      return builder.build()
    }
  }

  override fun onCreate() {
    super.onCreate()
    activeInstance = this
    lastRenderedSignature = null
    ensureChannel()
    initMediaSession()
    acquirePartialWakeLock()
    acquireWifiLock()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)
    val coordinator = (application as MflGameDayApp).coordinator

    val wasAlreadyForeground = isRunningInForeground
    val promoted = if (wasAlreadyForeground && !pendingStopRequested) {
      true
    } else {
      val notification = buildNotification(this, coordinator, mediaSession?.sessionToken)
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
      isSpeakingNow = isSpeaking,
      preserveWhileSlateLoading = true
    )
    if (!shouldRun) {
      markStopped()
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    isRunningInForeground = true
    mediaSession?.isActive = true
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
      coordinator.pauseLiveBroadcastSession(
        reason = "Stopped when swiped closed from Recent Apps",
        explicitUserPause = false
      )
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    }
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    markStopped()
    activeInstance = null
    activeMediaSessionToken = null
    releaseMediaSession()
    releaseWifiLock()
    releaseWakeLock()
    super.onDestroy()
  }

  override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

  private fun initMediaSession() {
    runCatching {
      val coordinator = (application as MflGameDayApp).coordinator
      val session = MediaSession(this, "GameDayLiveBroadcast").apply {
        setCallback(object : MediaSession.Callback() {
          override fun onPlay() {
            coordinator.updateSettings { it.copy(muted = false) }
          }

          override fun onPause() {
            coordinator.updateSettings { it.copy(muted = true) }
          }

          override fun onSkipToNext() {
            coordinator.refreshNow()
          }

          override fun onStop() {
            coordinator.pauseLiveBroadcastSession(
              reason = "Broadcast stopped from media player",
              explicitUserPause = true
            )
          }
        })
        isActive = true
      }
      mediaSession = session
      activeMediaSessionToken = session.sessionToken
    }
  }

  internal fun syncMediaSessionMetadataAndState(
    summary: ServiceLifecyclePolicy.NotificationSummary,
    muted: Boolean
  ) {
    val session = mediaSession ?: return
    runCatching {
      val pbState = PlaybackState.Builder()
        .setActions(
          PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT
        )
        .setState(
          if (muted) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING,
          PlaybackState.PLAYBACK_POSITION_UNKNOWN,
          1.0f
        )
        .build()
      session.setPlaybackState(pbState)

      val meta = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, summary.title)
        .putString(MediaMetadata.METADATA_KEY_ARTIST, summary.body)
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, summary.body)
        .build()
      session.setMetadata(meta)
    }
  }

  private fun releaseMediaSession() {
    runCatching {
      mediaSession?.isActive = false
      mediaSession?.release()
      mediaSession = null
    }
  }

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

  private fun acquireWifiLock() {
    runCatching {
      val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
      @Suppress("DEPRECATION")
      val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
      } else {
        WifiManager.WIFI_MODE_FULL_HIGH_PERF
      }
      wifiLock = wm.createWifiLock(mode, "MFLGameDay::LiveAnnouncerWifiLock").apply {
        setReferenceCounted(false)
        acquire()
      }
    }
  }

  private fun releaseWifiLock() {
    runCatching {
      if (wifiLock?.isHeld == true) {
        wifiLock?.release()
      }
      wifiLock = null
    }
  }
}
