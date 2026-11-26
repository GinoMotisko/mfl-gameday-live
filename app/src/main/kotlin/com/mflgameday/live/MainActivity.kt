package com.mflgameday.live

import android.Manifest
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mflgameday.live.model.OperatingMode
import com.mflgameday.live.ui.GameDayAppShell
import com.mflgameday.live.ui.theme.LocalGameDayColors
import com.mflgameday.live.ui.theme.MFLGameDayTheme

class MainActivity : ComponentActivity() {
  private val updateFlowLauncher = registerForActivityResult(
    ActivityResultContracts.StartIntentSenderForResult()
  ) { result ->
    (application as MflGameDayApp).updateController.onUpdateFlowResult(result.resultCode)
  }

  override fun onStart() {
    super.onStart()
    val app = application as MflGameDayApp
    app.updateController.bindActivity(this, updateFlowLauncher)
    app.coordinator.onAppForegrounded()
  }

  override fun onResume() {
    super.onResume()
    val app = application as MflGameDayApp
    val state = app.coordinator.uiState.value
    if (state.settings.operatingMode != OperatingMode.UNCONFIGURED) {
      app.updateController.onActivityResumed(
        activity = this,
        isSpeakingProvider = {
          app.coordinator.uiState.value.isSpeakingNow || app.coordinator.voiceEngine.hasActiveOrPendingSpeech()
        }
      )
    }
  }

  override fun onStop() {
    super.onStop()
    val app = application as MflGameDayApp
    app.updateController.unbindActivity(this)
    if (!isChangingConfigurations) {
      app.coordinator.onAppBackgrounded()
    }
  }

  override fun onDestroy() {
    val app = application as MflGameDayApp
    app.updateController.unbindActivity(this)
    app.coordinator.onMainActivityDestroyed(
      isChangingConfigurations = isChangingConfigurations,
      isFinishing = isFinishing
    )
    super.onDestroy()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val app = application as MflGameDayApp
    val coordinator = app.coordinator
    val updateController = app.updateController

    setContent {
      val uiState by coordinator.uiState.collectAsStateWithLifecycle()
      val updateUiState by updateController.uiState.collectAsStateWithLifecycle()

      val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
      ) { /* state automatically reflected on resume/recomposition */ }

      // Keep tablet / phone screen awake when enabled in GameDay Stadium Mode
      DisposableEffect(uiState.settings.keepScreenAwake, uiState.settings.operatingMode) {
        val shouldKeepOn = uiState.settings.keepScreenAwake &&
          uiState.settings.operatingMode != OperatingMode.UNCONFIGURED
        if (shouldKeepOn) {
          window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
          window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {}
      }

      LaunchedEffect(uiState.settings.operatingMode) {
        if (
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
          uiState.settings.operatingMode != OperatingMode.UNCONFIGURED
        ) {
          notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
      }

      MFLGameDayTheme(preset = uiState.settings.themePreset) {
        val isDark = LocalGameDayColors.current.isDark
        SideEffect {
          val barStyle = if (isDark) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
          } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
          }
          enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        }

        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          GameDayAppShell(
            uiState = uiState,
            coordinator = coordinator,
            updateUiState = updateUiState,
            onApplyAppUpdate = { updateController.onPrimaryBannerAction(this@MainActivity) },
            onDismissAppUpdate = { updateController.onDismissBanner() },
            onRequestNotificationPermission = {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
              }
            }
          )
        }
      }
    }
  }
}
