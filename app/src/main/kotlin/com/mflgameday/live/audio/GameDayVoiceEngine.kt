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
import com.mflgameday.live.model.VoicePersona
import java.util.Locale
import java.util.UUID
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Priority-queued Android TextToSpeech broadcast announcer with transient audio ducking,
 * multi-voice discovery, curated GameDay voice personas, and optional stadium sound effects.
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
    val playChime: Boolean
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
    .setWillPauseWhenDucked(false)
    .setOnAudioFocusChangeListener { /* transient ducking handled by OS mixer */ }
    .build()

  init {
    initializeTts()
    startWorkerLoop()
  }

  private fun initializeTts() {
    tts = TextToSpeech(appContext) { status ->
      if (status == TextToSpeech.SUCCESS) {
        val engine = tts ?: return@TextToSpeech
        engine.setAudioAttributes(speechAudioAttributes)
        engine.language = Locale.US
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) {
            _isSpeaking.value = true
          }

          override fun onDone(utteranceId: String?) {
            currentUtteranceDone?.complete(Unit)
          }

          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) {
            currentUtteranceDone?.complete(Unit)
          }

          override fun onError(utteranceId: String?, errorCode: Int) {
            currentUtteranceDone?.complete(Unit)
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
    val rawVoices = runCatching { engine.voices?.toList().orEmpty() }.getOrDefault(emptyList())
      .filter { v ->
        v.locale.language.equals("en", ignoreCase = true) &&
          !v.features.orEmpty().contains("notInstalled")
      }

    voiceMapById = rawVoices.associateBy { it.name }

    val groupedCounters = HashMap<String, Int>()
    val options = rawVoices
      .sortedWith(
        compareByDescending<Voice> { v -> if (v.locale.country.equals("US", ignoreCase = true)) 1 else 0 }
          .thenByDescending { it.quality }
          .thenBy { it.isNetworkConnectionRequired }
          .thenBy { it.name }
      )
      .map { v ->
        val regionLabel = when (v.locale.country.uppercase()) {
          "US" -> "US Broadcast"
          "GB" -> "UK Broadcast"
          "AU" -> "AU Broadcast"
          "CA" -> "Canada Broadcast"
          "IN" -> "India English"
          else -> "English (${v.locale.country.ifEmpty { "Global" }})"
        }
        val modeTag = if (v.isNetworkConnectionRequired) "Neural Cloud" else "On-Device"
        val key = "$regionLabel-$modeTag"
        val idx = (groupedCounters[key] ?: 0) + 1
        groupedCounters[key] = idx
        val qualityBadge = if (v.quality >= Voice.QUALITY_HIGH) "HD" else "Std"
        VoiceOption(
          id = v.name,
          displayName = "$regionLabel #$idx · $modeTag ($qualityBadge)",
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
      // Synchronously mark speaking BEFORE the worker coroutine wakes so GameDayLiveService & WakeLock
      // never tear down during the microsecond gap after the final game of a slate flips to FINAL!
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
    tts?.stop()
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
          val item = speechQueue.poll() ?: break
          if (item.settingsSnapshot.muted) continue
          speakSingleItem(item)
        }
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
      return
    }

    val selectedVoiceId = item.settingsSnapshot.selectedSystemVoiceId
    if (!selectedVoiceId.isNullOrBlank()) {
      voiceMapById[selectedVoiceId]?.let { v ->
        runCatching { engine.voice = v }
      }
    } else {
      runCatching { engine.language = Locale.US }
    }

    val persona = item.settingsSnapshot.voicePersona
    val rate = if (persona == VoicePersona.CUSTOM_TUNED) {
      item.settingsSnapshot.customSpeechRate
    } else {
      persona.speechRate
    }
    val pitch = if (persona == VoicePersona.CUSTOM_TUNED) {
      item.settingsSnapshot.customPitch
    } else {
      persona.pitch
    }
    engine.setSpeechRate(rate.coerceIn(0.6f, 1.8f))
    engine.setPitch(pitch.coerceIn(0.6f, 1.6f))

    val doneDeferred = CompletableDeferred<Unit>()
    currentUtteranceDone = doneDeferred
    val utteranceId = "mfl_gd_${UUID.randomUUID()}"
    val params = Bundle().apply {
      putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
    }

    val result = engine.speak(item.text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    if (result == TextToSpeech.ERROR) {
      com.mflgameday.live.diagnostics.DiagnosticsLogger.warn(
        com.mflgameday.live.diagnostics.LogSubsystem.TTS_AUDIO,
        "TTS speak returned ERROR; rebinding engine automatically"
      )
      doneDeferred.complete(Unit)
      runCatching { engine.shutdown() }
      _ttsReady.value = false
      initializeTts()
    } else {
      com.mflgameday.live.diagnostics.DiagnosticsLogger.info(
        com.mflgameday.live.diagnostics.LogSubsystem.TTS_AUDIO,
        "Spoke: ${item.text.take(120)}"
      )
    }
    doneDeferred.await()
  }
}
