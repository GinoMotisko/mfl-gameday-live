package com.mflgameday.live.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.mflgameday.live.MainActivity
import org.junit.Rule
import org.junit.Test

class GameDayUiSmokeTest {
  @get:Rule
  val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun launchesAppShellSuccessfully() {
    composeTestRule.onNodeWithText("MFL", substring = true, ignoreCase = true).assertIsDisplayed()
  }
}
