package com.mflgameday.live

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.mflgameday.live.diagnostics.DiagnosticsLogger
import com.mflgameday.live.service.GameDayCoordinator

class MflGameDayApp : Application(), ImageLoaderFactory {
  lateinit var coordinator: GameDayCoordinator
    private set

  override fun onCreate() {
    super.onCreate()
    DiagnosticsLogger.install(this)
    coordinator = GameDayCoordinator(this)
  }

  override fun newImageLoader(): ImageLoader {
    return ImageLoader.Builder(this)
      .components {
        add(ImageDecoderDecoder.Factory()) // Native animated GIF support for MFL helmet/team logos
      }
      .memoryCache {
        MemoryCache.Builder(this)
          .maxSizePercent(0.20)
          .build()
      }
      .diskCache {
        DiskCache.Builder()
          .directory(cacheDir.resolve("team_and_player_images"))
          .maxSizeBytes(64L * 1024L * 1024L)
          .build()
      }
      .respectCacheHeaders(false)
      .crossfade(true)
      .build()
  }
}
