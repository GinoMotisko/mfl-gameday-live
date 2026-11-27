package com.mflgameday.live.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.mflgameday.live.model.GameEventKind
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Pure Kotlin 16-bit PCM stadium audio stinger synthesizer.
 * Plays short, punchy broadcast chimes before spoken play-by-play announcements
 * with smooth attack/decay envelopes, non-blocking coroutine suspension, and guaranteed
 * native `AudioTrack` cleanup on cancellation.
 */
object StadiumSoundSynthesizer {
  private const val SAMPLE_RATE = 24_000

  private data class Note(val freqHz: Double, val durationMs: Int, val gain: Double = 0.35)

  suspend fun playStingerFor(kind: GameEventKind) = withContext(Dispatchers.Default) {
    val sequence: List<Note> = when (kind) {
      GameEventKind.TOUCHDOWN,
      GameEventKind.TWO_POINT_GOOD -> listOf(
        Note(523.25, 95, 0.38),  // C5
        Note(659.25, 95, 0.38),  // E5
        Note(783.99, 110, 0.40), // G5
        Note(1046.50, 210, 0.44) // C6
      )
      GameEventKind.RED_ZONE -> listOf(
        Note(440.0, 90, 0.34),
        Note(659.25, 130, 0.38),
        Note(440.0, 90, 0.34),
        Note(659.25, 150, 0.40)
      )
      GameEventKind.INTERCEPTION,
      GameEventKind.FUMBLE_LOST,
      GameEventKind.SACK -> listOf(
        Note(783.99, 95, 0.36),
        Note(587.33, 95, 0.36),
        Note(493.88, 160, 0.38)
      )
      GameEventKind.FIELD_GOAL,
      GameEventKind.SAFETY,
      GameEventKind.FANTASY_LEAD_CHANGE -> listOf(
        Note(587.33, 110, 0.34),
        Note(880.00, 190, 0.38)
      )
      else -> listOf(
        Note(659.25, 110, 0.28)
      )
    }
    playNotes(sequence)
  }

  private suspend fun playNotes(notes: List<Note>) {
    var audioTrack: AudioTrack? = null
    try {
      val totalSamples = notes.sumOf { (SAMPLE_RATE * it.durationMs) / 1000 }
      if (totalSamples <= 0) return
      val pcm = ShortArray(totalSamples)
      var offset = 0

      for (note in notes) {
        val count = (SAMPLE_RATE * note.durationMs) / 1000
        val attackSamples = (count * 0.12).toInt().coerceAtLeast(1)
        val releaseSamples = (count * 0.28).toInt().coerceAtLeast(1)
        for (i in 0 until count) {
          val env = when {
            i < attackSamples -> i.toDouble() / attackSamples
            i > count - releaseSamples -> (count - i).toDouble() / releaseSamples
            else -> 1.0
          }
          val t = i.toDouble() / SAMPLE_RATE
          val wave = 0.78 * sin(2.0 * PI * note.freqHz * t) +
            0.22 * sin(2.0 * PI * (note.freqHz * 2.0) * t)
          val sample = (wave * note.gain * env * Short.MAX_VALUE).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
          if (offset + i < pcm.size) {
            pcm[offset + i] = sample.toShort()
          }
        }
        offset += count
      }

      val track = AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        )
        .setBufferSizeInBytes(pcm.size * 2)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
      audioTrack = track

      track.write(pcm, 0, pcm.size)
      track.play()
      val totalDurationMs = notes.sumOf { it.durationMs }.toLong()
      delay(totalDurationMs + 45L)
    } catch (ce: kotlinx.coroutines.CancellationException) {
      throw ce
    } catch (_: Throwable) {
      // Audio device unavailable or transient mixer error — proceed to spoken TTS immediately
    } finally {
      audioTrack?.let { t ->
        runCatching { t.stop() }
        runCatching { t.release() }
      }
    }
  }
}
