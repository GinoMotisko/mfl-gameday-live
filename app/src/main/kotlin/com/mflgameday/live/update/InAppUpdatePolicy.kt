package com.mflgameday.live.update

enum class UpdateFlowAction {
  NONE,
  PROMPT_FLEXIBLE,
  PROMPT_IMMEDIATE,
  RESUME_IMMEDIATE,
  SHOW_RESTART_READY
}

/**
 * Pure, deterministic update decision engine for GameDay Live.
 *
 * - Priority `0..3` (default releases): Non-blocking `PROMPT_FLEXIBLE`, respecting a 7-day snooze
 *   per `availableVersionCode` when dismissed, and deferring while `isSpeakingNow` is active so
 *   a Google Play sheet never interrupts a live spoken touchdown call.
 * - Priority `4..5` (emergency breaking MFL/ESPN hotfixes): `PROMPT_IMMEDIATE` (full-screen Play
 *   Store update), bypassing snooze.
 */
object InAppUpdatePolicy {

  const val DEFAULT_SNOOZE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000L
  const val IMMEDIATE_PRIORITY_THRESHOLD: Int = 4

  fun decidePlayStoreAction(
    isDownloaded: Boolean,
    isDeveloperTriggeredUpdateInProgress: Boolean,
    isUpdateAvailable: Boolean,
    availableVersionCode: Int,
    updatePriority: Int,
    isFlexibleAllowed: Boolean,
    isImmediateAllowed: Boolean,
    isSpeakingNow: Boolean,
    dismissedVersionCode: Int,
    dismissedAtMillis: Long,
    nowMillis: Long,
    snoozeWindowMillis: Long = DEFAULT_SNOOZE_MILLIS
  ): UpdateFlowAction {
    if (isDownloaded) {
      return UpdateFlowAction.SHOW_RESTART_READY
    }
    val clampedPriority = updatePriority.coerceIn(0, 5)
    if (isDeveloperTriggeredUpdateInProgress) {
      return if (clampedPriority >= IMMEDIATE_PRIORITY_THRESHOLD && isImmediateAllowed) {
        UpdateFlowAction.RESUME_IMMEDIATE
      } else {
        // A Flexible download/install is already in progress in the background; never escalate to Immediate!
        UpdateFlowAction.NONE
      }
    }
    if (!isUpdateAvailable || availableVersionCode <= 0) {
      return UpdateFlowAction.NONE
    }

    if (clampedPriority >= IMMEDIATE_PRIORITY_THRESHOLD && isImmediateAllowed) {
      return UpdateFlowAction.PROMPT_IMMEDIATE
    }

    if (!isFlexibleAllowed) {
      return UpdateFlowAction.NONE
    }
    if (isSpeakingNow) {
      return UpdateFlowAction.NONE
    }

    val elapsedSinceDismiss = nowMillis - dismissedAtMillis
    val isSnoozedForSameVersion =
      dismissedVersionCode == availableVersionCode &&
        dismissedAtMillis > 0L &&
        elapsedSinceDismiss in 0 until snoozeWindowMillis

    return if (isSnoozedForSameVersion) {
      UpdateFlowAction.NONE
    } else {
      UpdateFlowAction.PROMPT_FLEXIBLE
    }
  }

  fun isNewerSemver(currentVersion: String, remoteVersion: String): Boolean {
    val currentParts = parseSemverParts(currentVersion) ?: return false
    val remoteParts = parseSemverParts(remoteVersion) ?: return false
    val maxLen = maxOf(currentParts.size, remoteParts.size)
    for (i in 0 until maxLen) {
      val cur = currentParts.getOrElse(i) { 0 }
      val rem = remoteParts.getOrElse(i) { 0 }
      if (rem > cur) return true
      if (rem < cur) return false
    }
    return false
  }

  fun shouldSurfaceDirectApkBanner(
    currentVersion: String,
    remoteVersion: String,
    dismissedVersion: String?,
    dismissedAtMillis: Long,
    nowMillis: Long,
    snoozeWindowMillis: Long = DEFAULT_SNOOZE_MILLIS
  ): Boolean {
    if (!isNewerSemver(currentVersion = currentVersion, remoteVersion = remoteVersion)) {
      return false
    }
    val normalizedRemote = normalizeVersionLabel(remoteVersion)
    val normalizedDismissed = dismissedVersion?.let(::normalizeVersionLabel)
    val elapsed = nowMillis - dismissedAtMillis
    if (
      normalizedDismissed != null &&
      normalizedDismissed == normalizedRemote &&
      dismissedAtMillis > 0L &&
      elapsed in 0 until snoozeWindowMillis
    ) {
      return false
    }
    return true
  }

  fun normalizeVersionLabel(raw: String): String {
    val trimmed = raw.trim().removePrefix("v").removePrefix("V")
    return if (trimmed.isEmpty()) "" else "v$trimmed"
  }

  private fun parseSemverParts(raw: String): List<Int>? {
    val core = raw.trim()
      .removePrefix("v")
      .removePrefix("V")
      .substringBefore('-')
      .substringBefore('+')
      .trim()
    if (core.isEmpty()) return null
    val tokens = core.split('.')
    if (tokens.isEmpty()) return null
    val parsed = ArrayList<Int>(tokens.size)
    for (token in tokens) {
      val value = token.toIntOrNull() ?: return null
      if (value < 0) return null
      parsed.add(value)
    }
    return parsed
  }
}
