package com.mflgameday.live

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mflgameday.live.model.OperatingMode
import com.mflgameday.live.ui.GameDayAppShell
import com.mflgameday.live.ui.theme.LocalGameDayColors
import com.mflgameday.live.ui.theme.MFLGameDayTheme

class MainActivity : ComponentActivity() {
  override fun onStart() {
    super.onStart()
    (application as MflGameDayApp).coordinator.onAppForegrounded()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val coordinator = (application as MflGameDayApp).coordinator

    setContent {
      val uiState by coordinator.uiState.collectAsStateWithLifecycle()

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
        val view = LocalView.current
        val isDark = LocalGameDayColors.current.isDark
        SideEffect {
          val insetsController = WindowCompat.getInsetsController(window, view)
          insetsController.isAppearanceLightStatusBars = !isDark
          insetsController.isAppearanceLightNavigationBars = !isDark
        }

        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          GameDayAppShell(
            uiState = uiState,
            coordinator = coordinator,
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
