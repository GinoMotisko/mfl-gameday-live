package com.mflgameday.live.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.mflgameday.live.model.GameDaySettings
import com.mflgameday.live.model.GameEventKind
import com.mflgameday.live.model.VoiceOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Priority-queued Android TextToSpeech broadcast announcer hardened for Pixel / Android 14–16
 * background survival: handles `UtteranceProgressListener.onStop`, guards against wedged Binder
 * callbacks with bounded timeouts, and preserves queued utterances across background TTS rebinds.
 */
class GameDayVoiceEngine(context: Context) {
  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  private data class QueuedSpeech(
    val priority: Int,
    val sequence: Long,
    val text: String,
    val kind: GameEventKind?,
    val settingsSnapshot: GameDaySettings,
    val playChime: Boolean,
    val retryCount: Int = 0
  ) : Comparable<QueuedSpeech> {
    override fun compareTo(other: QueuedSpeech): Int {
      val pCmp = other.priority.compareTo(this.priority)
      return if (pCmp != 0) pCmp else this.sequence.compareTo(other.sequence)
    }
  }

  private val seqCounter = AtomicLong(0L)
  private val speechQueue = PriorityBlockingQueue<QueuedSpeech>()
  private val queueSignal = Channel<Unit>(Channel.CONFLATED)

  private var tts: TextToSpeech? = null
  private var voiceMapById: Map<String, Voice> = emptyMap()
  private var defaultEngineVoice: Voice? = null
  @Volatile
  var installedDescriptors: List<InstalledVoiceDescriptor> = emptyList()
    private set

  @Volatile
  private var activeUtteranceId: String? = null

  @Volatile
  private var currentUtteranceDone: CompletableDeferred<Unit>? = null

  private val _ttsReady = MutableStateFlow(false)
  val ttsReady: StateFlow<Boolean> = _ttsReady.asStateFlow()

  private val _availableVoices = MutableStateFlow<List<VoiceOption>>(emptyList())
  val availableVoices: StateFlow<List<VoiceOption>> = _availableVoices.asStateFlow()

  private val _isSpeaking = MutableStateFlow(false)
  val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

  private val _activeSpeechText = MutableStateFlow<String?>(null)
  val activeSpeechText: StateFlow<String?> = _activeSpeechText.asStateFlow()

  private val _lastSpokenText = MutableStateFlow<String?>(null)
  val lastSpokenText: StateFlow<String?> = _lastSpokenText.asStateFlow()

  private val speechAudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

  private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
    .setAudioAttributes(speechAudioAttributes)
    .setAcceptsDelayedFocusGain(true)
    .setWillPauseWhenDucked(false)
    .setOnAudioFocusChangeListener { /* transient ducking handled by OS mixer */ }
    .build()

  init {
    initializeTts()
    startWorkerLoop()
  }

  private fun completeIfCurrentUtterance(utteranceId: String?) {
    if (utteranceId == null || utteranceId == activeUtteranceId) {
      currentUtteranceDone?.complete(Unit)
    }
  }

  private fun initializeTts() {
    tts = TextToSpeech(appContext) { status ->
      if (status == TextToSpeech.SUCCESS) {
        val engine = tts ?: return@TextToSpeech
        engine.setAudioAttributes(speechAudioAttributes)
        engine.language = Locale.US
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) {
            if (utteranceId == null || utteranceId == activeUtteranceId) {
              _isSpeaking.value = true
            }
          }

          override fun onDone(utteranceId: String?) {
            completeIfCurrentUtterance(utteranceId)
          }

          override fun onStop(utteranceId: String?, interrupted: Boolean) {
            // Critical on Pixel / Android 14–16: audio route changes, assistant/call steals, or
            // TTS service restarts invoke onStop() instead of onDone()/onError()!
            completeIfCurrentUtterance(utteranceId)
          }

          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) {
            completeIfCurrentUtterance(utteranceId)
          }

          override fun onError(utteranceId: String?, errorCode: Int) {
            completeIfCurrentUtterance(utteranceId)
          }
        })
        refreshAvailableVoices()
        _ttsReady.value = true
        com.mflgameday.live.diagnostics.DiagnosticsLogger.info(
          com.mflgameday.live.diagnostics.LogSubsystem.TTS_AUDIO,
          "TextToSpeech bound (${_availableVoices.value.size} English voices available)"
        )
        queueSignal.trySend(Unit)
      } else {
        _ttsReady.value = false
        com.mflgameday.live.diagnostics.DiagnosticsLogger.warn(
          com.mflgameday.live.diagnostics.LogSubsystem.TTS_AUDIO,
          "TextToSpeech initialization returned status=$status"
        )
      }
    }
  }

  fun refreshAvailableVoices() {
    val engine = tts ?: return
    defaultEngineVoice = runCatching { engine.defaultVoice }.getOrNull()
    val rawVoices = runCatching { engine.voices?.toList().orEmpty() }.getOrDefault(emptyList())
      .filter { v ->
        v.locale.language.equals("en", ignoreCase = true) &&
          !v.features.orEmpty().contains("notInstalled")
      }

    voiceMapById = rawVoices.associateBy { it.name }
    installedDescriptors = rawVoices.map { v ->
      InstalledVoiceDescriptor(
        id = v.name,
        localeTag = v.locale.toLanguageTag(),
        country = v.locale.country.uppercase(),
        quality = v.quality,
        isNetwork = v.isNetworkConnectionRequired,
        features = v.features.orEmpty()
      )
    }

    val groupedCounters = HashMap<String, Int>()
    val options = rawVoices
      .sortedWith(
        compareByDescending<Voice> { !it.isNetworkConnectionRequired }
          .thenByDescending { v -> if (v.locale.country.equals("US", ignoreCase = true)) 1 else 0 }
          .thenByDescending { it.quality }
          .thenBy { it.name }
      )
      .map { v ->
        val regionLabel = when (v.locale.country.uppercase()) {
          "US" -> "US Voice"
          "GB" -> "UK Voice"
          "AU" -> "Aussie Voice"
          "CA" -> "Canada Voice"
          "IN" -> "India Voice"
          else -> "English Voice"
        }
        val idx = (groupedCounters[regionLabel] ?: 0) + 1
        groupedCounters[regionLabel] = idx
        val letter = ('A' + ((idx - 1) % 26)).toString() + if (idx > 26) "${idx / 26}" else ""
        val modeLabel = when {
          v.quality >= Voice.QUALITY_HIGH && !v.isNetworkConnectionRequired -> "Studio HD · Offline"
          v.quality >= Voice.QUALITY_HIGH -> "Studio HD"
          !v.isNetworkConnectionRequired -> "Works Offline"
          else -> "Standard"
        }
        VoiceOption(
          id = v.name,
          displayName = "$regionLabel $letter · $modeLabel",
          localeTag = v.locale.toLanguageTag(),
          isNetworkEnhanced = v.isNetworkConnectionRequired,
          qualityRank = v.quality
        )
      }
      .take(24)

    _availableVoices.value = options
  }

  fun hasActiveOrPendingSpeech(): Boolean = _isSpeaking.value || speechQueue.isNotEmpty()

  fun enqueueAnnouncement(
    text: String,
    kind: GameEventKind?,
    settings: GameDaySettings,
    highPriority: Boolean = false
  ) {
    if (text.isBlank()) return
    val basePriority = when (kind) {
      GameEventKind.TOUCHDOWN,
      GameEventKind.TWO_POINT_GOOD -> 100
      GameEventKind.INTERCEPTION,
      GameEventKind.FUMBLE_LOST,
      GameEventKind.FANTASY_LEAD_CHANGE -> 85
      GameEventKind.FIELD_GOAL,
      GameEventKind.SAFETY,
      GameEventKind.SACK -> 70
      GameEventKind.RED_ZONE,
      GameEventKind.BIG_PLAY -> 55
      else -> 40
    }
    val priority = if (highPriority) basePriority + 25 else basePriority
    if (!settings.muted) {
      _isSpeaking.value = true
    }
    speechQueue.offer(
      QueuedSpeech(
        priority = priority,
        sequence = seqCounter.incrementAndGet(),
        text = text,
        kind = kind,
        settingsSnapshot = settings,
        playChime = settings.playStadiumSoundEffects && kind != null
      )
    )
    queueSignal.trySend(Unit)
  }

  /**
   * Immediately auditions a voice sample or replays a specific log entry on user tap,
   * even if background announcements are currently muted.
   */
  fun speakImmediateSample(
    sampleText: String,
    settings: GameDaySettings,
    sampleKind: GameEventKind? = GameEventKind.TOUCHDOWN
  ) {
    speechQueue.clear()
    activeUtteranceId = null
    runCatching { tts?.stop() }
    currentUtteranceDone?.complete(Unit)
    speechQueue.offer(
      QueuedSpeech(
        priority = 1_000,
        sequence = seqCounter.incrementAndGet(),
        text = sampleText,
        kind = sampleKind,
        settingsSnapshot = settings.copy(muted = false),
        playChime = settings.playStadiumSoundEffects && sampleKind != null
      )
    )
    queueSignal.trySend(Unit)
  }

  fun replayLastCall(settings: GameDaySettings) {
    val last = _lastSpokenText.value ?: return
    speakImmediateSample(last, settings, sampleKind = null)
  }

  fun stopAndClearQueue() {
    speechQueue.clear()
    activeUtteranceId = null
    runCatching { tts?.stop() }
    currentUtteranceDone?.complete(Unit)
    _isSpeaking.value = false
    _activeSpeechText.value = null
    runCatching { audioManager.abandonAudioFocusRequest(audioFocusRequest) }
  }

  fun shutdown() {
    stopAndClearQueue()
    runCatching { tts?.shutdown() }
    tts = null
  }

  private fun startWorkerLoop() {
    scope.launch {
      for (signal in queueSignal) {
        while (speechQueue.isNotEmpty()) {
          // Wait up to 5s if Google TTS is still binding on cold start or rebinding after background unbind
          if (!_ttsReady.value) {
            if (tts == null) initializeTts()
            val boundInTime = withTimeoutOrNull(5_000L) {
              _ttsReady.first { it }
            } != null
            if (!boundInTime) {
              com.mflgameday.live.diagnostics.DiagnosticsLogger.warn(
                com.mflgameday.live.diagnostics.LogSubsystem.TTS_AUDIO,
                "Timed out waiting 5s for Android TextToSpeech bind; clearing stalled batch"
              )
              speechQueue.clear()
              break
            }
          }

          val item = speechQueue.poll() ?: break
          if (item.settingsSnapshot.muted) continue
          speakSingleItem(item)
        }
        activeUtteranceId = null
        _isSpeaking.value = false
        _activeSpeechText.value = null
        runCatching { audioManager.abandonAudioFocusRequest(audioFocusRequest) }
      }
    }
  }

  private suspend fun speakSingleItem(item: QueuedSpeech) {
    _lastSpokenText.value = item.text
    _activeSpeechText.value = item.text
    _isSpeaking.value = true

    runCatching { audioManager.requestAudioFocus(audioFocusRequest) }

    if (item.playChime && item.kind != null) {
      StadiumSoundSynthesizer.playStingerFor(item.kind)
    }

    val engine = tts
    if (engine == null || !_ttsReady.value) {
      if (item.retryCount < 1) {
        speechQueue.offer(item.copy(retryCount = item.retryCount + 1, playChime = false))
      }
      return
    }

    val plan = VoiceCastingResolver.resolveForSettings(
      settings = item.settingsSnapshot,
      installedVoices = installedDescriptors
    )
    val targetVoice = plan.voiceId?.let { voiceMapById[it] } ?: defaultEngineVoice
    val fallbackLocale = when (plan.fallbackCountry.uppercase()) {
      "GB" -> Locale.UK
      "AU" -> Locale.forLanguageTag("en-AU")
      else -> Locale.US
    }
    if (targetVoice != null) {
      runCatching { engine.voice = targetVoice }.onFailure {
        runCatching { engine.language = fallbackLocale }
      }
    } else {
      runCatching { engine.language = fallbackLocale }
    }

    val effectiveRate = plan.speechRate.coerceIn(0.6f, 1.8f)
    engine.setSpeechRate(effectiveRate)
    engine.setPitch(plan.pitch.coerceIn(0.6f, 1.6f))

    val doneDeferred = CompletableDeferred<Unit>()
    val utteranceId = "mfl_gd_${UUID.randomUUID()}"
    activeUtteranceId = utteranceId
    currentUtteranceDone = doneDeferred
    val params = Bundle().apply {
      putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
    }

    val result = engine.speak(item.text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    if (result == TextToSpeech.ERROR) {
      com.mflgameday.live.diagnostics.DiagnosticsLogger.warn(
        com.mflgameday.live.diagnostics.LogSubsystem.TTS_AUDIO,
        "TTS speak returned ERROR in background; rebinding engine & retrying utterance once"
      )
      activeUtteranceId = null
      doneDeferred.complete(Unit)
      if (item.retryCount < 1) {
        speechQueue.offer(item.copy(retryCount = item.retryCount + 1, playChime = false))
      }
      runCatching { engine.shutdown() }
      tts = null
      _ttsReady.value = false
      initializeTts()
      return
    }

    val estimatedDurationMs = ((item.text.length.coerceAtLeast(1) * 110L) / effectiveRate).toLong()
    val watchdogTimeoutMs = (estimatedDurationMs + 20_000L).coerceIn(30_000L, 120_000L)
    val completedCleanly = withTimeoutOrNull(watchdogTimeoutMs) {
      doneDeferred.await()
      true
    } ?: false

    if (completedCleanly) {
      com.mflgameday.live.diagnostics.DiagnosticsLogger.info(
        com.mflgameday.live.diagnostics.LogSubsystem.TTS_AUDIO,
        "Spoke: ${item.text.take(120)}"
      )
    } else {
      activeUtteranceId = null
      currentUtteranceDone = null
      runCatching { engine.stop() }
      com.mflgameday.live.diagnostics.DiagnosticsLogger.warn(
        com.mflgameday.live.diagnostics.LogSubsystem.TTS_AUDIO,
        "TTS utterance watchdog timed out after ${watchdogTimeoutMs}ms; stopped hung synthesis"
      )
    }
  }
}
