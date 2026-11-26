package com.mflgameday.live.update

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.Lifecycle
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.mflgameday.live.diagnostics.DiagnosticsLogger
import com.mflgameday.live.diagnostics.LogSubsystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object FlavorUpdateControllerFactory {
  fun create(context: Context): AppUpdateController = PlayStoreUpdateController(context.applicationContext)
}

private class PlayStoreUpdateController(
  context: Context
) : AppUpdateController {

  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(context)

  private val _uiState = MutableStateFlow(AppUpdateUiState())
  override val uiState: StateFlow<AppUpdateUiState> = _uiState.asStateFlow()

  private var boundActivityHash: Int? = null
  private var boundLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
  private var listenerRegistered = false

  private var updateFlowInFlight = false
  private var pendingFlowType: Int = -1
  private var pendingVersionCode: Int = -1
  private var promptedFlexibleVersionThisProcess: Int = -1
  private var abortedImmediateVersionThisProcess: Int = -1
  private var dismissedRestartReadyThisSession: Boolean = false

  private val installListener = InstallStateUpdatedListener { state ->
    when (state.installStatus()) {
      InstallStatus.DOWNLOADING -> {
        val total = state.totalBytesToDownload()
        val fraction = if (total > 0L) {
          (state.bytesDownloaded().toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else {
          null
        }
        _uiState.value = AppUpdateUiState(
          bannerKind = AppUpdateBannerKind.PLAY_STORE_DOWNLOADING,
          downloadProgressFraction = fraction
        )
      }
      InstallStatus.DOWNLOADED -> {
        if (!dismissedRestartReadyThisSession) {
          _uiState.value = AppUpdateUiState(
            bannerKind = AppUpdateBannerKind.PLAY_STORE_RESTART_READY
          )
        }
      }
      InstallStatus.INSTALLED,
      InstallStatus.CANCELED,
      InstallStatus.FAILED -> {
        _uiState.value = AppUpdateUiState()
      }
      else -> Unit
    }
  }

  override fun bindActivity(
    activity: ComponentActivity,
    launcher: ActivityResultLauncher<IntentSenderRequest>
  ) {
    boundActivityHash = System.identityHashCode(activity)
    boundLauncher = launcher
    if (!listenerRegistered) {
      runCatching {
        appUpdateManager.registerListener(installListener)
        listenerRegistered = true
      }
    }
  }

  override fun unbindActivity(activity: ComponentActivity) {
    if (boundActivityHash == System.identityHashCode(activity)) {
      boundActivityHash = null
      boundLauncher = null
      if (listenerRegistered) {
        runCatching { appUpdateManager.unregisterListener(installListener) }
        listenerRegistered = false
      }
    }
  }

  override fun onActivityResumed(activity: ComponentActivity, isSpeakingNow: Boolean) {
    if (updateFlowInFlight) return
    runCatching {
      appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
        if (updateFlowInFlight) return@addOnSuccessListener
        if (boundActivityHash != System.identityHashCode(activity)) return@addOnSuccessListener
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@addOnSuccessListener
        val launcher = boundLauncher ?: return@addOnSuccessListener
        handleUpdateInfo(info = info, launcher = launcher, isSpeakingNow = isSpeakingNow)
      }
    }.onFailure { err ->
      DiagnosticsLogger.info(LogSubsystem.APP, "Play In-App Update check skipped: ${err.message}")
    }
  }

  private fun handleUpdateInfo(
    info: AppUpdateInfo,
    launcher: ActivityResultLauncher<IntentSenderRequest>,
    isSpeakingNow: Boolean
  ) {
    if (info.installStatus() == InstallStatus.DOWNLOADING) {
      val total = info.totalBytesToDownload()
      val fraction = if (total > 0L) {
        (info.bytesDownloaded().toFloat() / total.toFloat()).coerceIn(0f, 1f)
      } else {
        null
      }
      _uiState.value = AppUpdateUiState(
        bannerKind = AppUpdateBannerKind.PLAY_STORE_DOWNLOADING,
        downloadProgressFraction = fraction
      )
      return
    }

    val availableCode = info.availableVersionCode()
    val dismissedCode = prefs.getInt(KEY_DISMISSED_VERSION_CODE, -1)
    val dismissedAt = prefs.getLong(KEY_DISMISSED_AT_MILLIS, 0L)

    val action = InAppUpdatePolicy.decidePlayStoreAction(
      isDownloaded = info.installStatus() == InstallStatus.DOWNLOADED,
      isDeveloperTriggeredUpdateInProgress =
        info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS,
      isUpdateAvailable = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE,
      availableVersionCode = availableCode,
      updatePriority = info.updatePriority(),
      isFlexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
      isImmediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
      isSpeakingNow = isSpeakingNow,
      dismissedVersionCode = dismissedCode,
      dismissedAtMillis = dismissedAt,
      nowMillis = System.currentTimeMillis()
    )

    when (action) {
      UpdateFlowAction.SHOW_RESTART_READY -> {
        if (!dismissedRestartReadyThisSession) {
          _uiState.value = AppUpdateUiState(bannerKind = AppUpdateBannerKind.PLAY_STORE_RESTART_READY)
        }
      }
      UpdateFlowAction.PROMPT_IMMEDIATE,
      UpdateFlowAction.RESUME_IMMEDIATE -> {
        if (abortedImmediateVersionThisProcess == availableCode && availableCode > 0) return
        runCatching {
          val started = appUpdateManager.startUpdateFlowForResult(
            info,
            launcher,
            AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
          )
          if (started) {
            updateFlowInFlight = true
            pendingFlowType = AppUpdateType.IMMEDIATE
            pendingVersionCode = availableCode
          }
        }.onFailure { err ->
          DiagnosticsLogger.warn(LogSubsystem.APP, "Immediate Play update launch failed: ${err.message}")
        }
      }
      UpdateFlowAction.PROMPT_FLEXIBLE -> {
        if (promptedFlexibleVersionThisProcess == availableCode) return
        runCatching {
          val started = appUpdateManager.startUpdateFlowForResult(
            info,
            launcher,
            AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
          )
          if (started) {
            updateFlowInFlight = true
            pendingFlowType = AppUpdateType.FLEXIBLE
            pendingVersionCode = availableCode
            promptedFlexibleVersionThisProcess = availableCode
          }
        }.onFailure { err ->
          DiagnosticsLogger.info(LogSubsystem.APP, "Flexible Play update prompt skipped: ${err.message}")
        }
      }
      UpdateFlowAction.NONE -> Unit
    }
  }

  override fun onUpdateFlowResult(resultCode: Int) {
    val flowType = pendingFlowType
    val code = pendingVersionCode
    updateFlowInFlight = false
    pendingFlowType = -1
    pendingVersionCode = -1

    if (resultCode != Activity.RESULT_OK && code > 0) {
      if (flowType == AppUpdateType.FLEXIBLE && resultCode == Activity.RESULT_CANCELED) {
        prefs.edit()
          .putInt(KEY_DISMISSED_VERSION_CODE, code)
          .putLong(KEY_DISMISSED_AT_MILLIS, System.currentTimeMillis())
          .apply()
      } else if (flowType == AppUpdateType.IMMEDIATE) {
        // Prevent an infinite MainActivity.onResume() flicker loop if Play fails offline or user backs out
        abortedImmediateVersionThisProcess = code
      }
    }
  }

  override fun onPrimaryBannerAction(activity: ComponentActivity) {
    if (_uiState.value.bannerKind == AppUpdateBannerKind.PLAY_STORE_RESTART_READY) {
      _uiState.value = AppUpdateUiState()
      runCatching { appUpdateManager.completeUpdate() }
    }
  }

  override fun onDismissBanner() {
    if (_uiState.value.bannerKind == AppUpdateBannerKind.PLAY_STORE_RESTART_READY) {
      dismissedRestartReadyThisSession = true
    }
    _uiState.value = AppUpdateUiState()
  }

  companion object {
    private const val PREFS_NAME = "mfl_gameday_update_prefs"
    private const val KEY_DISMISSED_VERSION_CODE = "dismissed_play_version_code"
    private const val KEY_DISMISSED_AT_MILLIS = "dismissed_play_at_millis"
  }
}
