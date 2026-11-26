package com.mflgameday.live.audio

import com.mflgameday.live.model.GameDaySettings
import com.mflgameday.live.model.VoicePersona

data class InstalledVoiceDescriptor(
  val id: String,
  val localeTag: String,
  val country: String,
  val quality: Int,
  val isNetwork: Boolean,
  val features: Set<String> = emptySet()
)

data class ResolvedVoicePlan(
  val voiceId: String?,
  val fallbackCountry: String,
  val speechRate: Float,
  val pitch: Float,
  val isStudioHd: Boolean,
  val worksOffline: Boolean
)

/**
 * Pure, deterministic commentator voice-casting engine.
 * Guarantees that all 6 named commentators resolve to distinct installed Android TTS voices
 * whenever >=6 English voices exist on-device (Google Speech Services & Samsung SMT), and
 * gracefully separates rate/pitch acoustic envelopes when fewer voices are installed.
 */
object VoiceCastingResolver {

  private val personaSignatureTokens: Map<VoicePersona, List<String>> = mapOf(
    VoicePersona.BOOTH_PLAY_BY_PLAY to listOf("iom-local", "iom", "en_us_m01", "en-us-x-iom", "male"),
    VoicePersona.REDZONE_SHOUTY to listOf("tpd-local", "tpd", "iol-local", "iol", "en_us_m02", "en_us_m03"),
    VoicePersona.STUDIO_HOST to listOf("iob-local", "sfg-local", "iob", "sfg", "tpc-local", "en_us_f", "female"),
    VoicePersona.LONDON_CALLING to listOf("gbd-local", "rjs-local", "gbb-local", "gbd", "rjs", "en_gb_m", "en-gb"),
    VoicePersona.AUSSIE_SIDELINE to listOf("aud-local", "aub-local", "aua-local", "aud", "aub", "en_au", "en-au"),
    VoicePersona.FILM_ROOM to listOf("tpf-local", "iog-local", "tpf", "iog", "en_us_m04", "en_us_m00")
  )

  private val knownFemaleTokens = listOf(
    "iob", "sfg", "tpc", "tpg", "sfp", "gba", "gbc", "aua", "auc", "_f0", "_f1", "female"
  )
  private val knownMaleTokens = listOf(
    "iom", "tpd", "tpf", "iog", "iol", "gbd", "gbb", "rjs", "aud", "aub", "_m0", "_m1", "male"
  )

  fun resolveAll(installedVoices: List<InstalledVoiceDescriptor>): Map<VoicePersona, ResolvedVoicePlan> {
    val canonicalPool = installedVoices.sortedWith(
      compareByDescending<InstalledVoiceDescriptor> { !it.isNetwork }
        .thenByDescending { it.quality }
        .thenBy { it.id }
    )

    val result = LinkedHashMap<VoicePersona, ResolvedVoicePlan>()
    if (canonicalPool.isEmpty()) {
      for (p in VoicePersona.entries) {
        result[p] = ResolvedVoicePlan(
          voiceId = null,
          fallbackCountry = p.preferredCountry,
          speechRate = p.speechRate,
          pitch = p.pitch,
          isStudioHd = false,
          worksOffline = true
        )
      }
      return result
    }

    // Cast in specificity order so UK, AU, Female, and signature US voices claim their exact match first
    val castingOrder = listOf(
      VoicePersona.LONDON_CALLING,
      VoicePersona.AUSSIE_SIDELINE,
      VoicePersona.STUDIO_HOST,
      VoicePersona.BOOTH_PLAY_BY_PLAY,
      VoicePersona.REDZONE_SHOUTY,
      VoicePersona.FILM_ROOM
    )

    val unclaimed = canonicalPool.toMutableList()
    val claimedByVoiceId = HashMap<String, Int>()

    for (persona in castingOrder) {
      if (unclaimed.isNotEmpty()) {
        val chosen = unclaimed.maxWithOrNull(
          compareBy<InstalledVoiceDescriptor> { scoreVoiceForPersona(persona, it) }
            .thenByDescending { !it.isNetwork }
            .thenByDescending { it.quality }
            .thenBy { it.id }
        )!!
        unclaimed.remove(chosen)
        claimedByVoiceId[chosen.id] = 1
        result[persona] = ResolvedVoicePlan(
          voiceId = chosen.id,
          fallbackCountry = persona.preferredCountry,
          speechRate = persona.speechRate,
          pitch = persona.pitch,
          isStudioHd = chosen.quality >= 400,
          worksOffline = !chosen.isNetwork
        )
      } else {
        // Fewer installed voices than commentators (e.g. 1-voice AOSP emulator):
        // pick best country/gender match and apply deterministic acoustic separation!
        val fallbackVoice = canonicalPool.maxByOrNull { scoreVoiceForPersona(persona, it) } ?: canonicalPool.first()
        val reuseCount = (claimedByVoiceId[fallbackVoice.id] ?: 0) + 1
        claimedByVoiceId[fallbackVoice.id] = reuseCount
        val (sepRate, sepPitch) = separatedEnvelopeForReuse(persona)
        result[persona] = ResolvedVoicePlan(
          voiceId = fallbackVoice.id,
          fallbackCountry = persona.preferredCountry,
          speechRate = sepRate,
          pitch = sepPitch,
          isStudioHd = fallbackVoice.quality >= 400,
          worksOffline = !fallbackVoice.isNetwork
        )
      }
    }

    val defaultPlan = result[VoicePersona.BOOTH_PLAY_BY_PLAY]!!
    result[VoicePersona.CUSTOM_TUNED] = defaultPlan.copy(
      speechRate = VoicePersona.CUSTOM_TUNED.speechRate,
      pitch = VoicePersona.CUSTOM_TUNED.pitch
    )
    return result
  }

  fun resolveForSettings(
    settings: GameDaySettings,
    installedVoices: List<InstalledVoiceDescriptor>
  ): ResolvedVoicePlan {
    val allPlans = resolveAll(installedVoices)
    val basePlan = allPlans[settings.voicePersona] ?: ResolvedVoicePlan(
      voiceId = null,
      fallbackCountry = settings.voicePersona.preferredCountry,
      speechRate = settings.voicePersona.speechRate,
      pitch = settings.voicePersona.pitch,
      isStudioHd = false,
      worksOffline = true
    )

    val rate = if (settings.voicePersona == VoicePersona.CUSTOM_TUNED) {
      settings.customSpeechRate.coerceIn(0.6f, 1.8f)
    } else {
      basePlan.speechRate.coerceIn(0.6f, 1.8f)
    }
    val pitch = if (settings.voicePersona == VoicePersona.CUSTOM_TUNED) {
      settings.customPitch.coerceIn(0.6f, 1.6f)
    } else {
      basePlan.pitch.coerceIn(0.6f, 1.6f)
    }

    val manualId = settings.selectedSystemVoiceId?.takeIf { it.isNotBlank() }
    if (manualId != null) {
      val matchedDesc = installedVoices.firstOrNull { it.id == manualId }
      return basePlan.copy(
        voiceId = manualId,
        fallbackCountry = matchedDesc?.country?.takeIf { it.isNotBlank() } ?: basePlan.fallbackCountry,
        speechRate = rate,
        pitch = pitch,
        isStudioHd = matchedDesc?.let { it.quality >= 400 } ?: basePlan.isStudioHd,
        worksOffline = matchedDesc?.let { !it.isNetwork } ?: basePlan.worksOffline
      )
    }

    return basePlan.copy(speechRate = rate, pitch = pitch)
  }

  /**
   * Preserves the active commentator's resolved neural voice ID when the user nudges
   * speed or pitch sliders (entering `CUSTOM_TUNED`), so tuning Nigel or Callie never
   * unexpectedly reverts their accent or speaker back to David.
   */
  fun applyCustomTuning(
    current: GameDaySettings,
    newRate: Float,
    newPitch: Float,
    resolvedPlans: Map<VoicePersona, ResolvedVoicePlan>
  ): GameDaySettings {
    val preservedVoiceId = current.selectedSystemVoiceId?.takeIf { it.isNotBlank() }
      ?: resolvedPlans[current.voicePersona]?.voiceId
    return current.copy(
      voicePersona = VoicePersona.CUSTOM_TUNED,
      selectedSystemVoiceId = preservedVoiceId,
      customSpeechRate = newRate.coerceIn(0.70f, 1.50f),
      customPitch = newPitch.coerceIn(0.70f, 1.40f)
    )
  }

  private fun scoreVoiceForPersona(persona: VoicePersona, voice: InstalledVoiceDescriptor): Int {
    val idLower = voice.id.lowercase()
    val featLower = voice.features.joinToString(" ").lowercase()
    val combined = "$idLower $featLower"
    var score = 0

    // 1. Country / accent match
    if (voice.country.equals(persona.preferredCountry, ignoreCase = true)) {
      score += 500
    } else if (voice.country.equals("US", ignoreCase = true) && persona.preferredCountry != "US") {
      score += 40
    }

    // 2. Signature neural voice token match (ordered by preference)
    val sigTokens = personaSignatureTokens[persona].orEmpty()
    val matchedIdx = sigTokens.indexOfFirst { tok -> combined.contains(tok) }
    if (matchedIdx >= 0) {
      score += 400 - (matchedIdx * 25)
    }

    // 3. Gender timbre alignment
    val looksFemale = knownFemaleTokens.any { combined.contains(it) }
    val looksMale = knownMaleTokens.any { combined.contains(it) }
    if (persona.prefersFemale) {
      if (looksFemale) score += 260
      if (looksMale) score -= 220
    } else {
      if (looksMale) score += 180
      if (looksFemale) score -= 220
    }

    // 4. Prefer low-latency local HD models over network-dependent models
    if (!voice.isNetwork) score += 90
    if (voice.quality >= 400) score += 70

    return score
  }

  private fun separatedEnvelopeForReuse(persona: VoicePersona): Pair<Float, Float> = when (persona) {
    VoicePersona.BOOTH_PLAY_BY_PLAY -> 1.02f to 0.90f
    VoicePersona.REDZONE_SHOUTY -> 1.20f to 1.10f
    VoicePersona.STUDIO_HOST -> 0.98f to 1.18f
    VoicePersona.LONDON_CALLING -> 1.05f to 0.98f
    VoicePersona.AUSSIE_SIDELINE -> 1.11f to 1.05f
    VoicePersona.FILM_ROOM -> 0.88f to 0.80f
    VoicePersona.CUSTOM_TUNED -> 1.00f to 1.00f
  }
}
