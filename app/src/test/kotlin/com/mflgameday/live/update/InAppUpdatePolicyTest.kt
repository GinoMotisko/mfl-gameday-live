package com.mflgameday.live.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppUpdatePolicyTest {

  private val sevenDaysMs = 7L * 24 * 60 * 60 * 1000L

  @Test
  fun playStoreDecision_defaultsToFlexibleForPriorityZeroToThreeAndRespectsSevenDaySnooze() {
    val now = 1_800_000_000_000L

    // Fresh optional release (priority = 0) -> PROMPT_FLEXIBLE
    assertEquals(
      UpdateFlowAction.PROMPT_FLEXIBLE,
      InAppUpdatePolicy.decidePlayStoreAction(
        isDownloaded = false,
        isDeveloperTriggeredUpdateInProgress = false,
        isUpdateAvailable = true,
        availableVersionCode = 12,
        updatePriority = 2,
        isFlexibleAllowed = true,
        isImmediateAllowed = true,
        isSpeakingNow = false,
        dismissedVersionCode = -1,
        dismissedAtMillis = 0L,
        nowMillis = now
      )
    )

    // Dismissed 2 days ago for versionCode 12 -> snoozed (NONE)
    assertEquals(
      UpdateFlowAction.NONE,
      InAppUpdatePolicy.decidePlayStoreAction(
        isDownloaded = false,
        isDeveloperTriggeredUpdateInProgress = false,
        isUpdateAvailable = true,
        availableVersionCode = 12,
        updatePriority = 2,
        isFlexibleAllowed = true,
        isImmediateAllowed = true,
        isSpeakingNow = false,
        dismissedVersionCode = 12,
        dismissedAtMillis = now - (2L * 24 * 60 * 60 * 1000L),
        nowMillis = now
      )
    )

    // Newer versionCode 13 ships while 12 was snoozed -> prompts immediately!
    assertEquals(
      UpdateFlowAction.PROMPT_FLEXIBLE,
      InAppUpdatePolicy.decidePlayStoreAction(
        isDownloaded = false,
        isDeveloperTriggeredUpdateInProgress = false,
        isUpdateAvailable = true,
        availableVersionCode = 13,
        updatePriority = 0,
        isFlexibleAllowed = true,
        isImmediateAllowed = true,
        isSpeakingNow = false,
        dismissedVersionCode = 12,
        dismissedAtMillis = now - 60_000L,
        nowMillis = now
      )
    )

    // Snooze expired (> 7 days) -> prompts again
    assertEquals(
      UpdateFlowAction.PROMPT_FLEXIBLE,
      InAppUpdatePolicy.decidePlayStoreAction(
        isDownloaded = false,
        isDeveloperTriggeredUpdateInProgress = false,
        isUpdateAvailable = true,
        availableVersionCode = 12,
        updatePriority = 0,
        isFlexibleAllowed = true,
        isImmediateAllowed = true,
        isSpeakingNow = false,
        dismissedVersionCode = 12,
        dismissedAtMillis = now - sevenDaysMs - 1_000L,
        nowMillis = now
      )
    )
  }

  @Test
  fun playStoreDecision_defersFlexiblePromptDuringActiveTtsSpeech_andTriggersImmediateOnPriorityFourOrFive() {
    val now = 1_800_000_000_000L

    // Active TTS touchdown call -> defer optional prompt until speech finishes
    assertEquals(
      UpdateFlowAction.NONE,
      InAppUpdatePolicy.decidePlayStoreAction(
        isDownloaded = false,
        isDeveloperTriggeredUpdateInProgress = false,
        isUpdateAvailable = true,
        availableVersionCode = 12,
        updatePriority = 1,
        isFlexibleAllowed = true,
        isImmediateAllowed = true,
        isSpeakingNow = true,
        dismissedVersionCode = -1,
        dismissedAtMillis = 0L,
        nowMillis = now
      )
    )

    // Priority 4 or 5 emergency breaking hotfix -> PROMPT_IMMEDIATE even if previously snoozed
    assertEquals(
      UpdateFlowAction.PROMPT_IMMEDIATE,
      InAppUpdatePolicy.decidePlayStoreAction(
        isDownloaded = false,
        isDeveloperTriggeredUpdateInProgress = false,
        isUpdateAvailable = true,
        availableVersionCode = 12,
        updatePriority = 5,
        isFlexibleAllowed = true,
        isImmediateAllowed = true,
        isSpeakingNow = false,
        dismissedVersionCode = 12,
        dismissedAtMillis = now - 10_000L,
        nowMillis = now
      )
    )

    // Stalled IMMEDIATE flow (priority >= 4) resumed in MainActivity.onResume -> RESUME_IMMEDIATE
    assertEquals(
      UpdateFlowAction.RESUME_IMMEDIATE,
      InAppUpdatePolicy.decidePlayStoreAction(
        isDownloaded = false,
        isDeveloperTriggeredUpdateInProgress = true,
        isUpdateAvailable = false,
        availableVersionCode = 12,
        updatePriority = 5,
        isFlexibleAllowed = false,
        isImmediateAllowed = true,
        isSpeakingNow = false,
        dismissedVersionCode = -1,
        dismissedAtMillis = 0L,
        nowMillis = now
      )
    )

    // In-progress FLEXIBLE background download (priority 0..3) must NEVER hijack into RESUME_IMMEDIATE!
    assertEquals(
      UpdateFlowAction.NONE,
      InAppUpdatePolicy.decidePlayStoreAction(
        isDownloaded = false,
        isDeveloperTriggeredUpdateInProgress = true,
        isUpdateAvailable = false,
        availableVersionCode = 12,
        updatePriority = 0,
        isFlexibleAllowed = true,
        isImmediateAllowed = true,
        isSpeakingNow = false,
        dismissedVersionCode = -1,
        dismissedAtMillis = 0L,
        nowMillis = now
      )
    )

    // Downloaded flexible update waiting for user restart -> SHOW_RESTART_READY
    assertEquals(
      UpdateFlowAction.SHOW_RESTART_READY,
      InAppUpdatePolicy.decidePlayStoreAction(
        isDownloaded = true,
        isDeveloperTriggeredUpdateInProgress = false,
        isUpdateAvailable = false,
        availableVersionCode = 12,
        updatePriority = 0,
        isFlexibleAllowed = true,
        isImmediateAllowed = true,
        isSpeakingNow = true,
        dismissedVersionCode = -1,
        dismissedAtMillis = 0L,
        nowMillis = now
      )
    )
  }

  @Test
  fun semverComparison_andDirectApkSnoozeHandleMultiDigitVersionsSafely() {
    assertTrue(InAppUpdatePolicy.isNewerSemver(currentVersion = "1.3.1", remoteVersion = "v1.3.2"))
    assertTrue(InAppUpdatePolicy.isNewerSemver(currentVersion = "v1.3.1", remoteVersion = "v1.10.0"))
    assertFalse(InAppUpdatePolicy.isNewerSemver(currentVersion = "1.3.1", remoteVersion = "v1.3.1"))
    assertFalse(InAppUpdatePolicy.isNewerSemver(currentVersion = "1.3.1", remoteVersion = "v1.2.5"))
    assertFalse(InAppUpdatePolicy.isNewerSemver(currentVersion = "1.3.1", remoteVersion = ""))
    assertFalse(InAppUpdatePolicy.isNewerSemver(currentVersion = "1.3.1", remoteVersion = "not-a-version"))

    val now = 1_800_000_000_000L
    assertTrue(
      InAppUpdatePolicy.shouldSurfaceDirectApkBanner(
        currentVersion = "1.3.1",
        remoteVersion = "v1.4.0",
        dismissedVersion = null,
        dismissedAtMillis = 0L,
        nowMillis = now
      )
    )
    assertFalse(
      InAppUpdatePolicy.shouldSurfaceDirectApkBanner(
        currentVersion = "1.3.1",
        remoteVersion = "v1.4.0",
        dismissedVersion = "v1.4.0",
        dismissedAtMillis = now - 3_600_000L,
        nowMillis = now
      )
    )
  }
}
