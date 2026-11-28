package com.mflgameday.live.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.mflgameday.live.model.GameDaySettings
import com.mflgameday.live.model.LeagueConfig
import com.mflgameday.live.model.PlayLogEntry
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * Hardware-backed Android Keystore AES-256-GCM vault for MFL session cookies (`MFL_USER_ID`)
 * and private league `APIKEY` tokens, paired with fast JSON file persistence for multi-league
 * configurations, settings, weekly play logs, and the 24-hour NFL/IDP player cache.
 */
class GameDayStorage(context: Context) {
  private val appContext = context.applicationContext
  private val prefs = appContext.getSharedPreferences("mfl_gameday_store", Context.MODE_PRIVATE)
  private val cacheDir = File(appContext.filesDir, "gameday_cache").apply { mkdirs() }

  companion object {
    private const val KEYSTORE_ALIAS = "mfl_gameday_aes_master_key"
    private const val KEY_SETTINGS_JSON = "settings_v1"
    private const val KEY_LEAGUES_JSON = "leagues_v1"
    private const val KEY_MFL_COOKIE_ENC = "mfl_cookie_enc"
    private const val KEY_API_KEY_PREFIX = "league_apikey_enc_"
    private const val KEY_PLAY_LOG_YEAR = "play_log_season_year"
    private const val KEY_PLAY_LOG_WEEK = "play_log_week"
  }

  fun loadPlayLogSeasonAndWeek(): Pair<Int, Int>? {
    val y = prefs.getInt(KEY_PLAY_LOG_YEAR, -1)
    val w = prefs.getInt(KEY_PLAY_LOG_WEEK, -1)
    return if (y > 0 && w > 0) y to w else null
  }

  fun savePlayLogSeasonAndWeek(year: Int, week: Int) {
    if (year > 0 && week > 0) {
      prefs.edit()
        .putInt(KEY_PLAY_LOG_YEAR, year)
        .putInt(KEY_PLAY_LOG_WEEK, week)
        .apply()
    }
  }

  fun loadSettings(): GameDaySettings {
    val raw = prefs.getString(KEY_SETTINGS_JSON, null) ?: return GameDaySettings()
    return runCatching { LenientJson.parser.decodeFromString<GameDaySettings>(raw) }
      .getOrDefault(GameDaySettings())
  }

  fun saveSettings(settings: GameDaySettings) {
    val encoded = LenientJson.parser.encodeToString(settings)
    prefs.edit().putString(KEY_SETTINGS_JSON, encoded).apply()
  }

  fun loadLeagues(): List<LeagueConfig> {
    val raw = prefs.getString(KEY_LEAGUES_JSON, null) ?: return emptyList()
    return runCatching { LenientJson.parser.decodeFromString<List<LeagueConfig>>(raw) }
      .getOrDefault(emptyList())
  }

  fun saveLeagues(leagues: List<LeagueConfig>) {
    val encoded = LenientJson.parser.encodeToString(leagues)
    prefs.edit().putString(KEY_LEAGUES_JSON, encoded).apply()
  }

  fun saveMflUserCookie(cookie: String?) {
    if (cookie.isNullOrBlank()) {
      prefs.edit().remove(KEY_MFL_COOKIE_ENC).apply()
    } else {
      encrypt(cookie)?.let { prefs.edit().putString(KEY_MFL_COOKIE_ENC, it).apply() }
    }
  }

  fun loadMflUserCookie(): String? =
    prefs.getString(KEY_MFL_COOKIE_ENC, null)?.let(::decrypt)

  fun saveLeagueApiKey(leagueId: String, apiKey: String?) {
    val k = KEY_API_KEY_PREFIX + leagueId
    if (apiKey.isNullOrBlank()) {
      prefs.edit().remove(k).apply()
    } else {
      encrypt(apiKey)?.let { prefs.edit().putString(k, it).apply() }
    }
  }

  fun loadLeagueApiKey(leagueId: String): String? =
    prefs.getString(KEY_API_KEY_PREFIX + leagueId, null)?.let(::decrypt)

  suspend fun loadCachedPlayersJson(year: Int, maxAgeMillis: Long = 86_400_000L): String? =
    withContext(Dispatchers.IO) {
      val file = File(cacheDir, "players_$year.json")
      if (!file.exists()) return@withContext null
      val age = System.currentTimeMillis() - file.lastModified()
      if (age > maxAgeMillis) return@withContext null
      runCatching { file.readText() }.getOrNull()
    }

  suspend fun saveCachedPlayersJson(year: Int, rawJson: String) = withContext(Dispatchers.IO) {
    runCatching {
      File(cacheDir, "players_$year.json").writeText(rawJson)
    }
  }

  suspend fun loadPlayLog(): List<PlayLogEntry> = withContext(Dispatchers.IO) {
    val file = File(cacheDir, "play_log_current.json")
    if (!file.exists()) return@withContext emptyList()
    runCatching {
      LenientJson.parser.decodeFromString<List<PlayLogEntry>>(file.readText())
    }.onFailure {
      com.mflgameday.live.diagnostics.DiagnosticsLogger.warn(
        com.mflgameday.live.diagnostics.LogSubsystem.APP,
        "Play log cache was corrupted; resetting cleanly."
      )
      runCatching { file.delete() }
    }.getOrDefault(emptyList())
  }

  suspend fun savePlayLog(entries: List<PlayLogEntry>) = withContext(Dispatchers.IO) {
    runCatching {
      val trimmed = entries.take(250)
      File(cacheDir, "play_log_current.json").writeText(
        LenientJson.parser.encodeToString(trimmed)
      )
    }
  }

  private fun getOrCreateSecretKey(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val existing = ks.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
    if (existing != null) return existing.secretKey

    val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    keyGen.init(
      KeyGenParameterSpec.Builder(
        KEYSTORE_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    )
    return keyGen.generateKey()
  }

  private fun encrypt(plainText: String): String? = runCatching {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
    val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    val ct = Base64.encodeToString(cipher.doFinal(plainText.encodeToByteArray()), Base64.NO_WRAP)
    "$iv:$ct"
  }.getOrNull()

  private fun decrypt(payload: String): String? = runCatching {
    val parts = payload.split(':', limit = 2)
    if (parts.size != 2) return null
    val iv = Base64.decode(parts[0], Base64.NO_WRAP)
    val ct = Base64.decode(parts[1], Base64.NO_WRAP)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
    cipher.doFinal(ct).decodeToString()
  }.getOrNull()
}
