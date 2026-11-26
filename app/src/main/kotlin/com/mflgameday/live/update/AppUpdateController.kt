package com.mflgameday.live.update

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.flow.StateFlow

enum class AppUpdateBannerKind {
  NONE,
  PLAY_STORE_DOWNLOADING,
  PLAY_STORE_RESTART_READY,
  DIRECT_APK_AVAILABLE
}

data class AppUpdateUiState(
  val bannerKind: AppUpdateBannerKind = AppUpdateBannerKind.NONE,
  val availableVersionLabel: String? = null,
  val downloadProgressFraction: Float? = null
)

interface AppUpdateController {
  val uiState: StateFlow<AppUpdateUiState>

  fun bindActivity(
    activity: ComponentActivity,
    launcher: ActivityResultLauncher<IntentSenderRequest>
  )

  fun unbindActivity(activity: ComponentActivity)

  fun onActivityResumed(activity: ComponentActivity, isSpeakingNow: Boolean)

  fun onUpdateFlowResult(resultCode: Int)

  fun onPrimaryBannerAction(activity: ComponentActivity)

  fun onDismissBanner()
}
