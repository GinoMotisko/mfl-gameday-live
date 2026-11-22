package com.mflgameday.live.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.mflgameday.live.MainActivity
import com.mflgameday.live.MflGameDayApp
import com.mflgameday.live.model.GamePhase

/**
 * Android 14 / 15 / 16 / 17 compliant `mediaPlayback` Foreground Service.
 * Ensures uninterrupted background live-score polling and audible TTS play-by-play
 * announcements even when the screen is off or the user switches apps on game day.
 */
class GameDayLiveService : LifecycleService() {

  private var wakeLock: PowerManager.WakeLock? = null

  companion object {
    private const val CHANNEL_ID = "mfl_gameday_live_broadcast"
    private const val NOTIFICATION_ID = 2026
    private const val ACTION_TOGGLE_MUTE = "com.mflgameday.live.ACTION_TOGGLE_MUTE"
    private const val ACTION_REFRESH_NOW = "com.mflgameday.live.ACTION_REFRESH_NOW"
    private const val ACTION_STOP_SERVICE = "com.mflgameday.live.ACTION_STOP_SERVICE"

    fun start(context: Context) {
      runCatching {
        val intent = Intent(context, GameDayLiveService::class.java)
        context.startForegroundService(intent)
      }
    }

    fun stop(context: Context) {
      runCatching {
        context.stopService(Intent(context, GameDayLiveService::class.java))
      }
    }

    fun notifySummaryUpdate(context: Context) {
      val app = context.applicationContext as? MflGameDayApp ?: return
      if (!app.coordinator.uiState.value.settings.runInBackgroundService) return
      val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      runCatching {
        nm.notify(NOTIFICATION_ID, buildNotification(context, app.coordinator))
      }
    }

    internal fun buildNotification(context: Context, coordinator: GameDayCoordinator): Notification {
      val state = coordinator.uiState.value
      val liveGames = state.nflGames.count { it.phase == GamePhase.LIVE || it.phase == GamePhase.HALFTIME }
      val activeSnap = state.activeLeagueSnapshot
      val myMatchup = activeSnap?.matchups?.firstOrNull { it.involvesMyFranchise }
      val fmt = "%.${activeSnap?.config?.precision ?: 2}f"

      val title = if (myMatchup != null && activeSnap != null) {
        val me = myMatchup.mySide(activeSnap.config.franchiseId)
        val opp = myMatchup.opponentSide(activeSnap.config.franchiseId)
        if (me != null && opp != null) {
          "${activeSnap.config.leagueName}: ${fmt.format(me.score)} – ${fmt.format(opp.score)} vs ${opp.franchiseName}"
        } else {
          "MFL GameDay Live · Week ${state.currentWeek}"
        }
      } else {
        "MFL GameDay Live · Week ${state.currentWeek}"
      }

      val lastCall = state.playLog.firstOrNull()?.spokenText
      val body = when {
        state.settings.muted -> "Announcements Muted · $liveGames live NFL games"
        !lastCall.isNullOrBlank() -> lastCall
        else -> "${state.statusMessage} · Scope: ${state.settings.scope.shortLabel}"
      }

      val openIntent = PendingIntent.getActivity(
        context,
        10,
        Intent(context, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )

      val muteIntent = PendingIntent.getService(
        context,
        11,
        Intent(context, GameDayLiveService::class.java).setAction(ACTION_TOGGLE_MUTE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )

      val refreshIntent = PendingIntent.getService(
        context,
        12,
        Intent(context, GameDayLiveService::class.java).setAction(ACTION_REFRESH_NOW),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )

      val stopIntent = PendingIntent.getService(
        context,
        13,
        Intent(context, GameDayLiveService::class.java).setAction(ACTION_STOP_SERVICE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )

      val muteLabel = if (state.settings.muted) "Unmute" else "Mute"

      return NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setContentIntent(openIntent)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        .addAction(0, muteLabel, muteIntent)
        .addAction(0, "Refresh", refreshIntent)
        .addAction(0, "Stop", stopIntent)
        .build()
    }
  }

  override fun onCreate() {
    super.onCreate()
    ensureChannel()
    acquirePartialWakeLock()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)
    val coordinator = (application as MflGameDayApp).coordinator

    when (intent?.action) {
      ACTION_TOGGLE_MUTE -> {
        coordinator.updateSettings { it.copy(muted = !it.muted) }
      }
      ACTION_REFRESH_NOW -> {
        coordinator.refreshNow()
      }
      ACTION_STOP_SERVICE -> {
        coordinator.updateSettings { it.copy(runInBackgroundService = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
      }
    }

    val notification = buildNotification(this, coordinator)
    val fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    runCatching {
      ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
    }

    coordinator.startLiveEngine()
    return START_STICKY
  }

  override fun onDestroy() {
    releaseWakeLock()
    super.onDestroy()
  }

  override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

  private fun ensureChannel() {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel(CHANNEL_ID) == null) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "MFL GameDay Live Announcer",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "Keeps live NFL & fantasy score polling and spoken play-by-play active in the background."
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
        // Renewed/bounded to 12 hours max (covers full Sunday 1pm -> midnight slate safely)
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
