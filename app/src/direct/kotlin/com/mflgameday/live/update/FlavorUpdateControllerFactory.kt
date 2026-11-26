package com.mflgameday.live.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.mflgameday.live.BuildConfig
import com.mflgameday.live.data.LenientJson
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

object FlavorUpdateControllerFactory {
  fun create(context: Context): AppUpdateController = DirectApkUpdateController(context.applicationContext)
}

/**
 * Direct Pro APK (`com.mflgameday.live`) non-blocking release notifier.
 * Compiled ONLY into the `direct` source set — zero bytes exist in the `playStore` bundle.
 */
private class DirectApkUpdateController(
  context: Context
) : AppUpdateController {

  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .build()

  private val _uiState = MutableStateFlow(AppUpdateUiState())
  override val uiState: StateFlow<AppUpdateUiState> = _uiState.asStateFlow()

  @Volatile private var checkInFlight = false

  override fun bindActivity(
    activity: ComponentActivity,
    launcher: ActivityResultLauncher<IntentSenderRequest>
  ) = Unit

  override fun unbindActivity(activity: ComponentActivity) = Unit

  override fun onActivityResumed(activity: ComponentActivity, isSpeakingNow: Boolean) {
    val now = System.currentTimeMillis()
    val cachedRemote = prefs.getString(KEY_LAST_REMOTE_VERSION, null)
    if (!cachedRemote.isNullOrBlank()) {
      refreshBannerFromRemoteVersion(cachedRemote, now)
    }

    val lastChecked = prefs.getLong(KEY_LAST_CHECK_MILLIS, 0L)
    if (checkInFlight || (now - lastChecked) in 0 until CHECK_INTERVAL_MILLIS) return

    checkInFlight = true
    scope.launch {
      try {
        val req = Request.Builder()
          .url(RELEASE_API_URL)
          .header("Accept", "application/json")
          .build()
        httpClient.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) return@use
          val raw = resp.body?.string().orEmpty()
          val remoteVer = LenientJson.parser.parseToJsonElement(raw)
            .jsonObject["version"]
            ?.jsonPrimitive
            ?.content
            ?.trim()
          if (!remoteVer.isNullOrBlank()) {
            val completedAt = System.currentTimeMillis()
            prefs.edit()
              .putLong(KEY_LAST_CHECK_MILLIS, completedAt)
              .putString(KEY_LAST_REMOTE_VERSION, remoteVer)
              .apply()
            refreshBannerFromRemoteVersion(remoteVer, completedAt)
          }
        }
      } catch (_: Throwable) {
        // Silent no-op when offline or in sandboxed test environments
      } finally {
        checkInFlight = false
      }
    }
  }

  private fun refreshBannerFromRemoteVersion(remoteVersion: String, nowMillis: Long) {
    val dismissedVer = prefs.getString(KEY_DISMISSED_VERSION, null)
    val dismissedAt = prefs.getLong(KEY_DISMISSED_AT_MILLIS, 0L)
    val shouldShow = InAppUpdatePolicy.shouldSurfaceDirectApkBanner(
      currentVersion = BuildConfig.VERSION_NAME,
      remoteVersion = remoteVersion,
      dismissedVersion = dismissedVer,
      dismissedAtMillis = dismissedAt,
      nowMillis = nowMillis
    )
    if (shouldShow) {
      _uiState.value = AppUpdateUiState(
        bannerKind = AppUpdateBannerKind.DIRECT_APK_AVAILABLE,
        availableVersionLabel = InAppUpdatePolicy.normalizeVersionLabel(remoteVersion)
      )
    } else if (_uiState.value.bannerKind == AppUpdateBannerKind.DIRECT_APK_AVAILABLE) {
      _uiState.value = AppUpdateUiState()
    }
  }

  override fun onUpdateFlowResult(resultCode: Int) = Unit

  override fun onPrimaryBannerAction(activity: ComponentActivity) {
    runCatching {
      activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHOWCASE_URL)))
    }
  }

  override fun onDismissBanner() {
    val label = _uiState.value.availableVersionLabel
    if (!label.isNullOrBlank()) {
      prefs.edit()
        .putString(KEY_DISMISSED_VERSION, label)
        .putLong(KEY_DISMISSED_AT_MILLIS, System.currentTimeMillis())
        .apply()
    }
    _uiState.value = AppUpdateUiState()
  }

  companion object {
    private const val PREFS_NAME = "mfl_gameday_update_prefs"
    private const val KEY_LAST_CHECK_MILLIS = "direct_last_check_millis"
    private const val KEY_LAST_REMOTE_VERSION = "direct_last_remote_version"
    private const val KEY_DISMISSED_VERSION = "direct_dismissed_version"
    private const val KEY_DISMISSED_AT_MILLIS = "direct_dismissed_at_millis"
    private const val CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000L
    private const val RELEASE_API_URL = "https://www.motisko.com/api/gameday/release"
    private const val SHOWCASE_URL = "https://www.motisko.com/gameday"
  }
}
