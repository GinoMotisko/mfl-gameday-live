package com.mflgameday.live.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.mflgameday.live.model.VoicePersona
import com.mflgameday.live.ui.theme.LocalGameDayColors

private data class TourSlide(
  val stepLabel: String,
  val title: String,
  val bullets: List<Pair<String, String>>,
  val icon: ImageVector
)

private val FEATURED_TOUR_PERSONAS = listOf(
  VoicePersona.BOOTH_PLAY_BY_PLAY,
  VoicePersona.REDZONE_SHOUTY,
  VoicePersona.STUDIO_HOST,
  VoicePersona.LONDON_CALLING
)

private val TOUR_SLIDES = listOf(
  TourSlide(
    stepLabel = "STEP 1 OF 4 · WHAT GAMEDAY LIVE DOES",
    title = "Your Hands-Free Big-Play & Fantasy Announcer",
    icon = Icons.AutoMirrored.Filled.VolumeUp,
    bullets = listOf(
      "Not Live Radio — Built for Multitasking" to "Watches active games and speaks touchdowns, turnovers, Red Zone trips & fantasy lead flips over your music in 3 seconds, even with your screen off.",
      "Scope Filter (Top Booth Pill)" to "Tap the Booth Pill in the top bar anytime to switch between All Games, My Games, or My Players."
    )
  ),
  TourSlide(
    stepLabel = "STEP 2 OF 4 · MATCHUPS, FIELD & CONFLICTS",
    title = "Live Field Drive Strip, Win % & Rooting Rings",
    icon = Icons.Default.SportsFootball,
    bullets = listOf(
      "Google-Style Linear Field & 5-State Rings" to "Live games render endzones, goalposts & ball position edge-to-edge. Player rings glow Gold with the ball, pulse Red inside the 20, and clear once Final.",
      "Win %, Best Ball & CONFLICT ⚡" to "Tracks live remaining-clock projections, Best Ball BUBBLE / CHASING status, and flags players starting FOR you in League A and AGAINST you in League B."
    )
  ),
  TourSlide(
    stepLabel = "STEP 3 OF 4 · 6 COMMENTATORS & EVENT TICKS",
    title = "Pick Your Commentator & What Gets Called",
    icon = Icons.Default.Tune,
    bullets = listOf(
      "6 Distinct Commentators" to "Swap between David, Red Zone Rex, Callie, Nigel (UK), Macca (AU), and Coach Vance from the top Booth Pill or Settings -> Announcer & Voice.",
      "One-Tap Broadcast Presets" to "Choose Sunday RedZone Party, Just My Matchup, or Big Scores Only — or toggle individual play types."
    )
  ),
  TourSlide(
    stepLabel = "STEP 4 OF 4 · COUCH MODE & AUTO-STANDBY",
    title = "Smart Game-Window Standby & Support",
    icon = Icons.Default.ScreenLockPortrait,
    bullets = listOf(
      "Zero Off-Hours Notification Spam" to "Runs only during active game windows (starting 90m before kickoff) and sleeps automatically once games go Final — or swipe away the shade card anytime.",
      "Music Ducking & Easy Support" to "Smoothly ducks Spotify for 3 seconds during spoken calls, and includes one-tap secret-redacted support logs in Settings."
    )
  )
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickTourDialog(
  selectedPersona: VoicePersona,
  onSelectAndSamplePersona: (VoicePersona) -> Unit,
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

        if (slideIndex == 0) {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
              text = "TAP TO AUDITION YOUR COMMENTATOR NOW",
              fontSize = 10.sp,
              fontWeight = FontWeight.ExtraBold,
              color = ext.subtleText
            )
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              FEATURED_TOUR_PERSONAS.forEach { persona ->
                val isSelected = selectedPersona == persona
                Surface(
                  color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else ext.cardElevated,
                  shape = RoundedCornerShape(10.dp),
                  modifier = Modifier
                    .border(
                      width = if (isSelected) 1.6.dp else 1.dp,
                      color = if (isSelected) MaterialTheme.colorScheme.primary else ext.cardBorder,
                      shape = RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelectAndSamplePersona(persona) }
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                  ) {
                    Text(persona.flagEmoji, fontSize = 13.sp)
                    Text(
                      text = persona.shortName,
                      style = MaterialTheme.typography.labelMedium,
                      fontWeight = FontWeight.Bold
                    )
                  }
                }
              }
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
              Text("Simulate Live Play")
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
