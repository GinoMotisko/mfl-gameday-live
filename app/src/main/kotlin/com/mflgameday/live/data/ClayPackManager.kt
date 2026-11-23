package com.mflgameday.live.data

import com.mflgameday.live.diagnostics.DiagnosticsLogger
import com.mflgameday.live.diagnostics.LogSubsystem
import com.mflgameday.live.model.FantasyPlayer
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Versioned over-the-air Claymation Head Pack manifest (`https://www.motisko.com/assets/gameday/clay/manifest.json`).
 *
 * Maps individual players/coaches (`byMflId`, `byEspnId`, or canonical `bySlug`) to immutable
 * versioned neck-up `.webp` asset keys (e.g., `"lamar_jackson_v1"` -> `.../heads/lamar_jackson_v1.webp`).
 * Because neck-up busts contain zero team colors or jersey numbers, mid-season NFL trades and
 * new seasons reuse cached heads with zero downloads; only newly added rookies/waiver breakouts
 * or explicit haircut revisions (`_v2`) trigger a ~5 KB fetch.
 */
@Serializable
data class ClayPackManifest(
  val manifestVersion: Int = 0,
  val updatedAt: String = "",
  val killSwitch: Boolean = false,
  val baseUrl: String = ClayPackManager.DEFAULT_HEADS_BASE_URL,
  val byMflId: Map<String, String> = emptyMap(),
  val byEspnId: Map<String, String> = emptyMap(),
  val bySlug: Map<String, String> = emptyMap()
) {
  fun resolveAssetKey(player: FantasyPlayer?): String? {
    if (player == null || killSwitch || player.isTeamUnit) return null
    val key = byMflId[player.id]
      ?: player.espnId?.takeIf { it.isNotBlank() }?.let { byEspnId[it] }
      ?: player.claySlug.takeIf { it.isNotBlank() }?.let { bySlug[it] }
      ?: return null
    return key.takeIf { it.isNotBlank() && SAFE_ASSET_KEY.matches(it) }
  }

  fun resolveHeadUrl(player: FantasyPlayer?): String? {
    val key = resolveAssetKey(player) ?: return null
    val normalizedBase = baseUrl.trim().trimEnd('/')
    val parsedBase = normalizedBase.toHttpUrlOrNull() ?: return null
    val isAllowedOrigin = parsedBase.scheme == "https" ||
      (parsedBase.scheme == "http" && (parsedBase.host == "127.0.0.1" || parsedBase.host == "localhost"))
    if (!isAllowedOrigin || parsedBase.encodedQuery != null || parsedBase.fragment != null) {
      return null
    }
    return "$normalizedBase/$key.webp"
  }

  fun resolveHeadModel(player: FantasyPlayer?, localHeadsDir: File? = null): Any? {
    val key = resolveAssetKey(player) ?: return null
    if (localHeadsDir != null) {
      val localFile = File(localHeadsDir, "$key.webp")
      if (localFile.isFile && localFile.length() > 0L) {
        return localFile
      }
    }
    return resolveHeadUrl(player)
  }

  companion object {
    private val SAFE_ASSET_KEY = Regex("^[a-zA-Z0-9_-]{1,80}$")
  }
}

enum class ManifestSyncOutcome {
  UPDATED,
  NOT_MODIFIED,
  FAILED
}

class ClayPackManager(
  private val cacheDir: File,
  private val manifestUrl: String = DEFAULT_MANIFEST_URL,
  private val httpClient: OkHttpClient = defaultHttpClient()
) {
  companion object {
    const val DEFAULT_MANIFEST_URL = "https://www.motisko.com/assets/gameday/clay/manifest.json"
    const val DEFAULT_HEADS_BASE_URL = "https://www.motisko.com/assets/gameday/clay/heads"
    const val MANIFEST_FILE = "clay_pack_manifest_v1.json"
    const val ETAG_FILE = "clay_pack_manifest_v1.etag"
    private const val MAX_UNMAPPED_IDS = 64

    private fun defaultHttpClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
  }

  val headsDir: File = File(cacheDir, "clay_heads")
  private val manifestFile = File(cacheDir, MANIFEST_FILE)
  private val etagFile = File(cacheDir, ETAG_FILE)
  private val syncMutex = Mutex()
  private val unmappedStarterMflIds = Collections.synchronizedSet(LinkedHashSet<String>())

  private val _manifest = MutableStateFlow(loadPersistedManifest())
  val manifest: StateFlow<ClayPackManifest> = _manifest.asStateFlow()

  private fun loadPersistedManifest(): ClayPackManifest {
    if (!manifestFile.exists()) return ClayPackManifest()
    return runCatching {
      LenientJson.parser.decodeFromString<ClayPackManifest>(manifestFile.readText())
    }.getOrDefault(ClayPackManifest())
  }

  private fun loadPersistedEtag(): String? {
    if (!etagFile.exists()) return null
    return runCatching { etagFile.readText().trim().takeIf { it.isNotEmpty() } }.getOrNull()
  }

  /**
   * Reloads persisted manifest from disk (used when local staged packs are updated).
   */
  fun reloadFromDisk(): ClayPackManifest {
    val loaded = loadPersistedManifest()
    _manifest.value = loaded
    return loaded
  }

  /**
   * Conditional `If-None-Match` manifest check (`0 bytes` when `HTTP 304 Not Modified`),
   * serialized via [syncMutex] with atomic `.tmp` writes and corrupted-manifest self-healing.
   */
  suspend fun syncManifestIfUpdated(): ManifestSyncOutcome = syncMutex.withLock {
    withContext(Dispatchers.IO) {
      try {
        cacheDir.mkdirs()
        val reqBuilder = Request.Builder()
          .url(manifestUrl)
          .header("Accept", "application/json")
        val savedEtag = loadPersistedEtag()
        if (savedEtag != null && _manifest.value.manifestVersion > 0 && manifestFile.isFile) {
          reqBuilder.header("If-None-Match", savedEtag)
        }

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
          if (response.code == 304) {
            return@withContext ManifestSyncOutcome.NOT_MODIFIED
          }
          if (!response.isSuccessful) {
            return@withContext ManifestSyncOutcome.FAILED
          }
          val body = response.body?.string().orEmpty()
          if (body.isBlank()) return@withContext ManifestSyncOutcome.FAILED

          val parsed = LenientJson.parser.decodeFromString<ClayPackManifest>(body)
          val tmpManifest = File(cacheDir, "$MANIFEST_FILE.tmp")
          tmpManifest.writeText(LenientJson.parser.encodeToString(parsed))
          tmpManifest.copyTo(manifestFile, overwrite = true)
          tmpManifest.delete()

          val responseEtag = response.header("ETag")?.trim()?.takeIf { it.isNotEmpty() }
          if (responseEtag != null) {
            val tmpEtag = File(cacheDir, "$ETAG_FILE.tmp")
            tmpEtag.writeText(responseEtag)
            tmpEtag.copyTo(etagFile, overwrite = true)
            tmpEtag.delete()
          } else if (etagFile.exists()) {
            etagFile.delete()
          }

          _manifest.value = parsed
          DiagnosticsLogger.info(
            LogSubsystem.NETWORK,
            "ClayPack manifest synced (v${parsed.manifestVersion}, mflIds=${parsed.byMflId.size}, slugs=${parsed.bySlug.size})"
          )
          ManifestSyncOutcome.UPDATED
        }
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        DiagnosticsLogger.warn(LogSubsystem.NETWORK, "ClayPack manifest sync skipped (${t.message})")
        ManifestSyncOutcome.FAILED
      }
    }
  }

  /**
   * Records public numeric MFL player IDs of active starters currently using the Visor-Helmet fallback
   * so weekly pack maintenance / non-PII diagnostics can prioritize newly emerged starters.
   */
  fun recordUnmappedStarterIfNumeric(player: FantasyPlayer?) {
    if (player == null || player.isTeamUnit) return
    val id = player.id.trim()
    if (id.length in 3..7 && id.all(Char::isDigit) && unmappedStarterMflIds.size < MAX_UNMAPPED_IDS) {
      unmappedStarterMflIds.add(id)
    }
  }

  fun snapshotUnmappedStarterIds(): List<String> = synchronized(unmappedStarterMflIds) {
    unmappedStarterMflIds.toList()
  }
}
