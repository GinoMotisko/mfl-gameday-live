package com.mflgameday.live.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.SportsFootball
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mflgameday.live.diagnostics.DiagnosticsLogger
import com.mflgameday.live.ui.theme.LocalGameDayColors

private data class TourSlide(
  val stepLabel: String,
  val title: String,
  val bullets: List<Pair<String, String>>,
  val icon: ImageVector
)

private val TOUR_SLIDES = listOf(
  TourSlide(
    stepLabel = "STEP 1 OF 4 · SPOKEN CALL SCOPE",
    title = "Control Which Games Are Announced Out Loud",
    icon = Icons.AutoMirrored.Filled.VolumeUp,
    bullets = listOf(
      "All NFL" to "Speaks scores, turnovers, sacks & Red Zone trips across every live game — ideal for Sunday man caves or NFL Live Only mode.",
      "My Games" to "Limits spoken calls to NFL games involving starters in your active fantasy matchup.",
      "My Players" to "Speaks only when a player in your lineup or facing you directly makes the play."
    )
  ),
  TourSlide(
    stepLabel = "STEP 2 OF 4 · MATCHUPS, BEST BALL & CONFLICTS",
    title = "Read Live Pace, Win % & Cross-League Rooting",
    icon = Icons.Default.SportsFootball,
    bullets = listOf(
      "Win % & Pace Bar" to "Combines live points with remaining game-clock projections so you see projected final scores in real time.",
      "Best Ball (BB)" to "Automatically marks your lowest counting starter as BUBBLE and active bench threats as CHASING.",
      "MINE · OPP · CONFLICT ⚡" to "Tracking 2+ leagues? CONFLICT ⚡ flags players starting FOR you in one league and AGAINST you in another."
    )
  ),
  TourSlide(
    stepLabel = "STEP 3 OF 4 · VOICES, STINGERS & SCHEDULE",
    title = "Pick Your Announcer Voice & Event Ticks",
    icon = Icons.Default.Tune,
    bullets = listOf(
      "Voice Audition Studio" to "Open Settings to sample David, Shouty, Callie, or any installed Google / Samsung neural voice.",
      "Granular Event Ticks" to "Toggle Touchdowns, Field Goals, Turnovers, Sacks, 25+ Yard Big Plays, Red Zone trips, and Lead Flips individually.",
      "Schedule & Player Sync" to "Tap 'Refresh NFL Schedule' anytime to pull updated flex times and rosters."
    )
  ),
  TourSlide(
    stepLabel = "STEP 4 OF 4 · TABLET COUCH MODE & SUPPORT",
    title = "All-Day Reliability & One-Tap Support",
    icon = Icons.Default.ScreenLockPortrait,
    bullets = listOf(
      "Stadium Mode & Music Ducking" to "Keeps your tablet awake on the counter, runs with your phone screen off, and smoothly ducks Spotify for 3 seconds during play calls.",
      "Replay Anytime" to "Missed a call? Tap any card in Plays / Live Ticker (or 'Replay' at the top) to hear it again.",
      "Easy Support" to "Need help? Tap 'Email Support' in Settings to send a secret-redacted diagnostic log straight to support.gameday@motisko.com."
    )
  )
)

@Composable
fun QuickTourDialog(
  onSampleVoice: () -> Unit,
  onDismissAndComplete: () -> Unit
) {
  val ext = LocalGameDayColors.current
  val context = LocalContext.current
  var slideIndex by rememberSaveable { mutableIntStateOf(0) }
  val slide = TOUR_SLIDES[slideIndex]

  AlertDialog(
    onDismissRequest = onDismissAndComplete,
    title = {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = slide.stepLabel,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ext.turfAccent
          )
          Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            TOUR_SLIDES.indices.forEach { i ->
              Box(
                modifier = Modifier
                  .size(if (i == slideIndex) 18.dp else 7.dp, 7.dp)
                  .clip(CircleShape)
                  .background(
                    if (i == slideIndex) MaterialTheme.colorScheme.primary
                    else ext.cardBorder
                  )
              )
            }
          }
        }
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Box(
            modifier = Modifier
              .size(36.dp)
              .clip(RoundedCornerShape(10.dp))
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(slide.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
          }
          Text(
            text = slide.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold
          )
        }
      }
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        slide.bullets.forEach { (heading, desc) ->
          Surface(
            color = ext.cardElevated,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .fillMaxWidth()
              .border(1.dp, ext.cardBorder, RoundedCornerShape(12.dp))
          ) {
            Column(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
              verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
              Text(
                text = heading,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
              )
              Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          if (slideIndex == 0 || slideIndex == 2) {
            FilledTonalButton(onClick = onSampleVoice) {
              Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text("Test Spoken Call")
            }
          }
          if (slideIndex == TOUR_SLIDES.lastIndex) {
            OutlinedButton(onClick = { DiagnosticsLogger.emailSupportIntent(context) }) {
              Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text("Contact Support")
            }
          }
        }
      }
    },
    dismissButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (slideIndex > 0) {
          TextButton(onClick = { slideIndex -= 1 }) {
            Text("Back")
          }
        }
        TextButton(onClick = onDismissAndComplete) {
          Text(if (slideIndex == TOUR_SLIDES.lastIndex) "Close" else "Skip Tour")
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (slideIndex < TOUR_SLIDES.lastIndex) {
            slideIndex += 1
          } else {
            onDismissAndComplete()
          }
        }
      ) {
        Text(if (slideIndex < TOUR_SLIDES.lastIndex) "Next →" else "Got It — Let's Roll!")
      }
    }
  )
}
