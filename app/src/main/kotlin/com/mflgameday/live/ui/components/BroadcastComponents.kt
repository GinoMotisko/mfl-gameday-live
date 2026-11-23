package com.mflgameday.live.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SportsFootball
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.mflgameday.live.BuildConfig
import com.mflgameday.live.model.ExposureRole
import com.mflgameday.live.model.FantasyPlayer
import com.mflgameday.live.model.GameEventKind
import com.mflgameday.live.model.NflTeam
import com.mflgameday.live.model.PlayLogEntry
import com.mflgameday.live.ui.theme.LocalGameDayColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * 3D Claymation Team Mascot badge inside a frosted team-color squircle with glowing
 * possession / Red Zone border and graceful offline monogram fallback.
 */
@Composable
fun TeamShieldBadge(
  team: NflTeam,
  size: Dp = 48.dp,
  hasPossession: Boolean = false,
  inRedZone: Boolean = false,
  logoUrlOverride: String? = null
) {
  val ext = LocalGameDayColors.current
  val primary = Color(team.primaryColorHex)
  val accent = Color(team.accentColorHex)
  val ringColor = when {
    inRedZone && hasPossession -> ext.redZoneAlert
    hasPossession -> ext.touchdownGold
    else -> accent.copy(alpha = 0.65f)
  }
  val mascotRes = ClayMascotResources.mascotResFor(team.code)

  Box(
    modifier = Modifier
      .size(size)
      .clip(RoundedCornerShape(12.dp))
      .background(
        Brush.linearGradient(
          listOf(primary.copy(alpha = 0.34f), primary.copy(alpha = 0.14f))
        )
      )
      .border(
        width = if (hasPossession) 2.dp else 1.2.dp,
        color = ringColor,
        shape = RoundedCornerShape(12.dp)
      )
      .padding(2.dp),
    contentAlignment = Alignment.Center
  ) {
    if (!BuildConfig.IS_PLAY_STORE_BUILD && !logoUrlOverride.isNullOrBlank()) {
      SubcomposeAsyncImage(
        model = logoUrlOverride,
        contentDescription = team.fullName,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
        loading = {
          if (mascotRes != null) {
            Image(
              painter = painterResource(mascotRes),
              contentDescription = team.fullName,
              contentScale = ContentScale.Fit,
              modifier = Modifier.fillMaxSize()
            )
          } else {
            TeamCodeMonogram(team.code, size)
          }
        },
        error = {
          if (mascotRes != null) {
            Image(
              painter = painterResource(mascotRes),
              contentDescription = team.fullName,
              contentScale = ContentScale.Fit,
              modifier = Modifier.fillMaxSize()
            )
          } else {
            TeamCodeMonogram(team.code, size)
          }
        }
      )
    } else if (mascotRes != null) {
      Image(
        painter = painterResource(mascotRes),
        contentDescription = team.fullName,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
      )
    } else {
      TeamCodeMonogram(team.code, size)
    }
  }
}

@Composable
private fun TeamCodeMonogram(code: String, size: Dp) {
  Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
    Text(
      text = code,
      color = MaterialTheme.colorScheme.onSurface,
      fontSize = (size.value * 0.28f).sp,
      fontWeight = FontWeight.ExtraBold,
      fontFamily = FontFamily.Monospace,
      maxLines = 1
    )
  }
}

enum class PlayerAvatarRingStatus {
  IDLE,
  MY_STARTER_LIVE,
  HAS_POSSESSION,
  IN_RED_ZONE,
  DEFENDING_RED_ZONE
}

/**
 * MFL Fantasy Franchise Crest badge: renders user-configured HTTPS MFL franchise logo/icon
 * with a bundled 3D Clay Golden Championship Trophy fallback when unset or unreachable.
 */
@Composable
fun FranchiseCrestBadge(
  logoUrl: String?,
  franchiseName: String,
  isMine: Boolean,
  size: Dp = 40.dp
) {
  val ext = LocalGameDayColors.current
  val safeHttpsLogo = logoUrl?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) }
  val borderColor = if (isMine) ext.mineBadge.copy(alpha = 0.75f) else ext.cardBorder

  Box(
    modifier = Modifier
      .size(size)
      .clip(RoundedCornerShape(10.dp))
      .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
      .border(1.2.dp, borderColor, RoundedCornerShape(10.dp))
      .padding(3.dp),
    contentAlignment = Alignment.Center
  ) {
    if (safeHttpsLogo != null) {
      SubcomposeAsyncImage(
        model = safeHttpsLogo,
        contentDescription = franchiseName,
        contentScale = ContentScale.Crop,
        modifier = Modifier
          .fillMaxSize()
          .clip(RoundedCornerShape(7.dp)),
        loading = { ClayTrophyFallback() },
        error = { ClayTrophyFallback() }
      )
    } else {
      ClayTrophyFallback()
    }
  }
}

@Composable
private fun ClayTrophyFallback() {
  Image(
    painter = painterResource(ClayMascotResources.BASE_TROPHY_CREST),
    contentDescription = null,
    contentScale = ContentScale.Fit,
    modifier = Modifier.fillMaxSize()
  )
}

/**
 * Decoupled 3D Claymation Player / Team-Unit / Coach / IDP Avatar:
 * - Layers cached neck-up 3D Claymation busts (`ClayPackManifest`) onto a live team-colored
 *   3D polymer-clay shoulder-pad jersey with live MFL `#jerseyNumber` so NFL trades update
 *   team colors and jersey numbers automatically with zero asset regeneration.
 * - Covers all 25 MFL position codes (`Def`/`ST`/`TMDL` Armored Fortress Shield, `Coach` Sideline
 *   Hoodie + Headset, `Off`/`TMQB` Team Mascot in Jersey, `IDP` 6-Bar Bull-Cage Visor Helmet
 *   fallback, and `QB/RB/WR/TE/PK/PN` Dark-Visor Skill Helmet fallback).
 * - Pulses a bright Crimson Red Zone ring (`IN_RED_ZONE`) whenever an offensive starter's team
 *   is driving inside the opponent's 20-yard line!
 */
@Composable
fun PlayerHeadshotAvatar(
  player: FantasyPlayer?,
  size: Dp = 52.dp,
  ringStatus: PlayerAvatarRingStatus = PlayerAvatarRingStatus.IDLE
) {
  val ext = LocalGameDayColors.current
  val clayManifest = LocalClayManifest.current
  val clayHeadsDir = LocalClayHeadsDir.current
  val posText = player?.position?.uppercase() ?: "FLX"
  val clayHeadModel = remember(clayManifest, clayHeadsDir, player?.id, player?.espnId, player?.rawMflName) {
    clayManifest.resolveHeadModel(player, clayHeadsDir)
  }

  val initials = remember(player?.displayName, posText) {
    val parts = player?.displayName?.trim()?.split("\\s+".toRegex()).orEmpty()
    when {
      parts.size >= 2 -> "${parts.first().firstOrNull() ?: ""}${parts.last().firstOrNull() ?: ""}".uppercase()
      parts.size == 1 && parts[0].length >= 2 -> parts[0].take(2).uppercase()
      else -> posText.take(3)
    }
  }

  val rawHex = player?.team?.primaryColorHex ?: 0xFF1E293B
  val accentHex = player?.team?.accentColorHex ?: 0xFF38BDF8
  val teamPrimary = Color(rawHex)
  val teamAccent = Color(accentHex)
  val jerseyTint = remember(rawHex) { ClayMascotResources.clayModulateTint(rawHex) }
  val helmetTint = remember(rawHex) { ClayMascotResources.clayHelmetTint(rawHex) }

  val isPulsing = ringStatus == PlayerAvatarRingStatus.IN_RED_ZONE ||
    ringStatus == PlayerAvatarRingStatus.DEFENDING_RED_ZONE

  val pulseProgress = if (isPulsing) {
    val infinite = rememberInfiniteTransition(label = "avatar_rz_pulse")
    val p by infinite.animateFloat(
      initialValue = 0.48f,
      targetValue = 1.0f,
      animationSpec = infiniteRepeatable(
        animation = tween(560, easing = FastOutSlowInEasing),
        repeatMode = RepeatMode.Reverse
      ),
      label = "avatar_rz_alpha"
    )
    p
  } else {
    1f
  }

  val baseRingColor = when (ringStatus) {
    PlayerAvatarRingStatus.IN_RED_ZONE -> ext.redZoneAlert
    PlayerAvatarRingStatus.DEFENDING_RED_ZONE -> ext.touchdownGold
    PlayerAvatarRingStatus.HAS_POSSESSION -> ext.touchdownGold
    PlayerAvatarRingStatus.MY_STARTER_LIVE -> ext.mineBadge
    PlayerAvatarRingStatus.IDLE -> if (accentHex == 0xFF000000L) teamPrimary.copy(alpha = 0.80f) else teamAccent.copy(alpha = 0.72f)
  }

  val ringWidth = when (ringStatus) {
    PlayerAvatarRingStatus.IN_RED_ZONE -> (2.0f + 1.4f * pulseProgress).dp
    PlayerAvatarRingStatus.DEFENDING_RED_ZONE -> (1.8f + 1.0f * pulseProgress).dp
    PlayerAvatarRingStatus.HAS_POSSESSION -> 2.2.dp
    PlayerAvatarRingStatus.MY_STARTER_LIVE -> 1.8.dp
    PlayerAvatarRingStatus.IDLE -> 1.35.dp
  }

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(3.dp)
  ) {
    Box(
      modifier = Modifier.size(size + 4.dp),
      contentAlignment = Alignment.Center
    ) {
      if (isPulsing) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .alpha(pulseProgress * 0.60f)
            .clip(CircleShape)
            .background(
              Brush.radialGradient(
                listOf(baseRingColor.copy(alpha = 0.55f), Color.Transparent)
              )
            )
        )
      }

      Box(
        modifier = Modifier
          .size(size)
          .clip(CircleShape)
          .background(
            Brush.verticalGradient(
              listOf(
                teamPrimary.copy(alpha = if (ext.isDark) 0.42f else 0.22f),
                Color(0xFF0B131C).copy(alpha = if (ext.isDark) 0.88f else 0.14f)
              )
            )
          )
          .border(ringWidth, baseRingColor.copy(alpha = pulseProgress), CircleShape),
        contentAlignment = Alignment.Center
      ) {
        when {
          // 1. Defensive Team Units (Def, ST, TMDL, TMLB, TMDB): 3D Clay Armored Fortress Shield + Mascot
          player?.isDefensiveTeamUnit == true -> {
            val mascotRes = ClayMascotResources.mascotResFor(player.team.code)
            Image(
              painter = painterResource(ClayMascotResources.BASE_DEFENSE_SHIELD),
              contentDescription = player.displayName,
              contentScale = ContentScale.Fit,
              modifier = Modifier
                .fillMaxSize()
                .padding(3.dp)
            )
            if (mascotRes != null) {
              Image(
                painter = painterResource(mascotRes),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                  .size(size * 0.44f)
                  .offset(y = (-2).dp)
              )
            }
          }

          // 2. Offensive Team Units (Off, TMQB, TMRB, TMWR, TMTE, TMPK, TMPN): Clay Mascot in Team Clay Jersey
          player?.isOffensiveTeamUnit == true -> {
            val mascotRes = ClayMascotResources.mascotResFor(player.team.code)
            val unitLabel = posText.removePrefix("TM").take(3)
            ClayJerseyTorsoLayer(
              size = size,
              jerseyTint = jerseyTint,
              chestLabel = unitLabel
            )
            if (mascotRes != null) {
              Image(
                painter = painterResource(mascotRes),
                contentDescription = player.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                  .align(Alignment.TopCenter)
                  .padding(top = 1.dp)
                  .size(size * 0.62f)
              )
            }
          }

          // 3. Head Coaches (ignores playing-day jersey numbers; renders Coach Head or Headset Silhouette)
          player?.isHeadCoach == true -> {
            if (clayHeadModel != null) {
              ClayJerseyTorsoLayer(
                size = size,
                jerseyTint = jerseyTint,
                chestLabel = null
              )
              SubcomposeAsyncImage(
                model = clayHeadModel,
                contentDescription = player.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                  .align(Alignment.TopCenter)
                  .padding(top = 1.dp)
                  .size(size * 0.68f),
                loading = { ClayCoachFallbackBust(helmetTint) },
                error = { ClayCoachFallbackBust(helmetTint) }
              )
            } else {
              ClayCoachFallbackBust(helmetTint)
            }
          }

          // 4. Individual Offensive Skill, Kickers, Punters & IDP Defenders:
          //    Decoupled Neck-Up 3D Clay Head + Live Team-Colored #Number Clay Jersey
          else -> {
            val chestNum = player?.jerseyNumber?.trim()?.takeIf { it.isNotEmpty() } ?: initials
            val isDefender = player?.isIdp == true
            ClayJerseyTorsoLayer(
              size = size,
              jerseyTint = jerseyTint,
              chestLabel = chestNum
            )
            if (clayHeadModel != null && player != null) {
              SubcomposeAsyncImage(
                model = clayHeadModel,
                contentDescription = player.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                  .align(Alignment.TopCenter)
                  .padding(top = 1.dp)
                  .size(size * 0.67f),
                loading = {
                  ClayHelmetFallbackHead(size = size, isIdp = isDefender, helmetTint = helmetTint)
                },
                error = {
                  ClayHelmetFallbackHead(size = size, isIdp = isDefender, helmetTint = helmetTint)
                }
              )
            } else {
              ClayHelmetFallbackHead(size = size, isIdp = isDefender, helmetTint = helmetTint)
            }
          }
        }
      }
    }

    // Centered position capsule cleanly below the circle so 100% of the jersey # and face stay visible
    Box(
      modifier = Modifier
        .clip(RoundedCornerShape(5.dp))
        .background(MaterialTheme.colorScheme.surface)
        .border(
          width = 0.75.dp,
          color = if (ringStatus == PlayerAvatarRingStatus.IN_RED_ZONE) ext.redZoneAlert else ext.cardBorder,
          shape = RoundedCornerShape(5.dp)
        )
        .padding(horizontal = 6.dp, vertical = 1.dp),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = posText,
        fontSize = 9.sp,
        fontWeight = FontWeight.Black,
        fontFamily = FontFamily.Monospace,
        color = if (ringStatus == PlayerAvatarRingStatus.IN_RED_ZONE) ext.redZoneAlert else MaterialTheme.colorScheme.primary,
        maxLines = 1
      )
    }
  }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ClayJerseyTorsoLayer(
  size: Dp,
  jerseyTint: Color,
  chestLabel: String?
) {
  Image(
    painter = painterResource(ClayMascotResources.BASE_JERSEY_TORSO),
    contentDescription = null,
    contentScale = ContentScale.FillBounds,
    colorFilter = ColorFilter.tint(jerseyTint, BlendMode.Modulate),
    modifier = Modifier
      .align(Alignment.BottomCenter)
      .width(size * 0.92f)
      .height(size * 0.50f)
      .offset(y = 3.dp)
  )
  if (!chestLabel.isNullOrBlank()) {
    Text(
      text = chestLabel,
      style = TextStyle(
        color = Color.White,
        fontSize = if (chestLabel.length >= 3) 8.5.sp else 10.5.sp,
        fontWeight = FontWeight.Black,
        fontFamily = FontFamily.SansSerif,
        shadow = Shadow(
          color = Color.Black.copy(alpha = 0.90f),
          offset = Offset(0f, 1.5f),
          blurRadius = 3f
        )
      ),
      maxLines = 1,
      softWrap = false,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = 2.dp)
    )
  }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ClayHelmetFallbackHead(
  size: Dp,
  isIdp: Boolean,
  helmetTint: Color
) {
  Image(
    painter = painterResource(
      if (isIdp) ClayMascotResources.BASE_HELMET_IDP else ClayMascotResources.BASE_HELMET_SKILL
    ),
    contentDescription = null,
    contentScale = ContentScale.Fit,
    colorFilter = ColorFilter.tint(helmetTint, BlendMode.Modulate),
    modifier = Modifier
      .align(Alignment.TopCenter)
      .padding(top = 1.dp)
      .size(size * 0.66f)
  )
}

@Composable
private fun ClayCoachFallbackBust(helmetTint: Color) {
  Image(
    painter = painterResource(ClayMascotResources.BASE_COACH_BUST),
    contentDescription = null,
    contentScale = ContentScale.Fit,
    colorFilter = ColorFilter.tint(helmetTint, BlendMode.Modulate),
    modifier = Modifier
      .fillMaxSize()
      .padding(top = 3.dp)
  )
}

/**
 * Animated ESPN-style digital scorebug number roll whenever points change.
 */
@Composable
fun AnimatedScoreText(
  text: String,
  style: TextStyle,
  color: Color,
  modifier: Modifier = Modifier
) {
  AnimatedContent(
    targetState = text,
    transitionSpec = {
      (slideInVertically { height -> height / 2 } + fadeIn(tween(240)))
        .togetherWith(slideOutVertically { height -> -height / 2 } + fadeOut(tween(180)))
    },
    label = "score_roll",
    modifier = modifier
  ) { value ->
    Text(
      text = value,
      style = style,
      color = color,
      maxLines = 1,
      softWrap = false
    )
  }
}

/**
 * Subtle vector stadium yard-line & radial spotlight canvas backdrop for hero scoreboards.
 */
@Composable
fun StadiumTurfCanvasBackdrop(
  accentGlow: Color,
  modifier: Modifier = Modifier
) {
  Canvas(modifier = modifier) {
    drawCircle(
      brush = Brush.radialGradient(
        colors = listOf(accentGlow.copy(alpha = 0.16f), Color.Transparent),
        center = Offset(size.width * 0.82f, size.height * 0.25f),
        radius = size.maxDimension * 0.65f
      ),
      radius = size.maxDimension * 0.65f,
      center = Offset(size.width * 0.82f, size.height * 0.25f)
    )
    // Subtle vertical 10-yard field lines
    val step = size.width / 6f
    for (i in 1..5) {
      val x = step * i
      drawLine(
        color = accentGlow.copy(alpha = 0.06f),
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = 1.5f
      )
    }
  }
}

/**
 * Breathing pulsing Red Zone alert pill.
 */
@Composable
fun RedZonePulseBadge(teamCode: String, modifier: Modifier = Modifier) {
  val ext = LocalGameDayColors.current
  val infinite = rememberInfiniteTransition(label = "rz_pulse")
  val alpha by infinite.animateFloat(
    initialValue = 0.72f,
    targetValue = 1.0f,
    animationSpec = infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    label = "rz_alpha"
  )

  Row(
    modifier = modifier
      .alpha(alpha)
      .clip(CircleShape)
      .background(ext.redZoneAlert)
      .padding(horizontal = 8.dp, vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    Icon(
      imageVector = Icons.Default.SportsFootball,
      contentDescription = "Red Zone",
      tint = Color.White,
      modifier = Modifier.size(12.dp)
    )
    Text(
      text = "$teamCode RED ZONE",
      color = Color.White,
      fontSize = 10.sp,
      fontWeight = FontWeight.ExtraBold,
      letterSpacing = 0.5.sp
    )
  }
}

/**
 * Visual 100-yard mini field-position progress bar showing how close the offense is to scoring.
 */
@Composable
fun MiniFieldPositionBar(
  progressFraction: Float,
  inRedZone: Boolean,
  downDistanceText: String,
  possessionText: String,
  modifier: Modifier = Modifier
) {
  val ext = LocalGameDayColors.current
  val fillColor = if (inRedZone) ext.redZoneAlert else ext.turfAccent

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = downDistanceText.ifBlank { "Live Drive" },
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = if (inRedZone) ext.redZoneAlert else ext.turfAccent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      if (possessionText.isNotBlank()) {
        Text(
          text = "Ball on $possessionText",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = FontFamily.Monospace,
          color = ext.subtleText,
          maxLines = 1,
          softWrap = false
        )
      }
    }

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(6.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth(progressFraction.coerceIn(0.04f, 1f))
          .fillMaxHeight()
          .clip(CircleShape)
          .background(
            Brush.horizontalGradient(
              listOf(ext.turfAccent.copy(alpha = 0.75f), fillColor)
            )
          )
      )
    }
  }
}

/**
 * Cross-league rooting badge (`MINE`, `OPP`, `CONFLICT ⚡`).
 */
@Composable
fun ExposurePill(role: ExposureRole, suffix: String = "") {
  val ext = LocalGameDayColors.current
  val (bg, label) = when (role) {
    ExposureRole.MINE -> ext.mineBadge to "MINE$suffix"
    ExposureRole.OPPONENT -> ext.opponentBadge to "OPP$suffix"
    ExposureRole.CONFLICT -> ext.conflictBadge to "CONFLICT ⚡$suffix"
  }
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(bg.copy(alpha = if (ext.isDark) 0.18f else 0.12f))
      .border(1.dp, bg.copy(alpha = 0.60f), RoundedCornerShape(6.dp))
      .padding(horizontal = 6.dp, vertical = 2.dp)
  ) {
    Text(
      text = label,
      color = bg,
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      softWrap = false
    )
  }
}

/**
 * Animated slide-down celebration banner with official NFL team logo & glowing stadium spotlight.
 */
@Composable
fun LiveCelebrationBanner(
  entry: PlayLogEntry?,
  onSpeakAgain: (PlayLogEntry) -> Unit,
  onDismiss: () -> Unit
) {
  val ext = LocalGameDayColors.current
  AnimatedVisibility(
    visible = entry != null,
    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
  ) {
    if (entry != null) {
      val (accentColor, badgeText, icon) = when (entry.event.kind) {
        GameEventKind.TOUCHDOWN, GameEventKind.TWO_POINT_GOOD ->
          Triple(ext.touchdownGold, "TOUCHDOWN!", Icons.Default.EmojiEvents)
        GameEventKind.INTERCEPTION, GameEventKind.FUMBLE_LOST ->
          Triple(ext.opponentBadge, "TURNOVER!", Icons.Default.Bolt)
        GameEventKind.SACK ->
          Triple(ext.conflictBadge, "BIG SACK!", Icons.Default.Bolt)
        GameEventKind.RED_ZONE ->
          Triple(ext.redZoneAlert, "RED ZONE ALERT", Icons.Default.Warning)
        GameEventKind.FANTASY_LEAD_CHANGE ->
          Triple(ext.turfAccent, "FANTASY LEAD FLIP!", Icons.Default.EmojiEvents)
        else ->
          Triple(ext.turfAccent, "SCORING UPDATE", Icons.Default.SportsFootball)
      }

      val eventTeam = entry.event.team?.team

      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 6.dp)
          .border(1.5.dp, accentColor, RoundedCornerShape(14.dp))
          .clickable { onSpeakAgain(entry) },
        shape = RoundedCornerShape(14.dp),
        color = ext.cardElevated,
        tonalElevation = 8.dp
      ) {
        Row(
          modifier = Modifier
            .background(
              Brush.horizontalGradient(
                listOf(accentColor.copy(alpha = 0.24f), Color.Transparent)
              )
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          if (eventTeam != null && eventTeam.code in NflTeam.catalog) {
            TeamShieldBadge(team = eventTeam, size = 52.dp, hasPossession = true)
          } else {
            Box(
              modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.22f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(icon, contentDescription = badgeText, tint = accentColor, modifier = Modifier.size(22.dp))
            }
          }

          Column(modifier = Modifier.weight(1f)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Text(
                text = badgeText,
                color = accentColor,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 0.8.sp
              )
              if (entry.isConflictPlay) {
                ExposurePill(ExposureRole.CONFLICT)
              } else if (entry.involvesMine) {
                ExposurePill(ExposureRole.MINE)
              } else if (entry.involvesOpponent) {
                ExposurePill(ExposureRole.OPPONENT)
              }
            }
            Text(
              text = entry.spokenText,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
            )
          }

          IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss alert", tint = ext.subtleText)
          }
        }
      }
    }
  }
}

/**
 * Active TTS announcer visualizer bar with instant Stop / Replay controls.
 */
@Composable
fun LiveSpeakingRibbon(
  isSpeaking: Boolean,
  activeText: String?,
  lastSpokenText: String?,
  onStopSpeaking: () -> Unit,
  onReplayLast: () -> Unit
) {
  val ext = LocalGameDayColors.current
  AnimatedVisibility(visible = isSpeaking && !activeText.isNullOrBlank()) {
    val infinite = rememberInfiniteTransition(label = "eq")
    val pulse by infinite.animateFloat(
      initialValue = 0.4f,
      targetValue = 1.0f,
      animationSpec = infiniteRepeatable(tween(380), RepeatMode.Reverse),
      label = "eq_p"
    )
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(ext.turfAccent.copy(alpha = 0.14f))
        .padding(horizontal = 14.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Icon(
        imageVector = Icons.Default.GraphicEq,
        contentDescription = "Announcing",
        tint = ext.turfAccent,
        modifier = Modifier
          .size(18.dp)
          .alpha(pulse)
      )
      Text(
        text = activeText.orEmpty(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f)
      )
      TextButton(onClick = onStopSpeaking) {
        Icon(Icons.Default.StopCircle, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("Stop", style = MaterialTheme.typography.labelSmall)
      }
    }
  }

  if (!isSpeaking && !lastSpokenText.isNullOrBlank()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
        .padding(horizontal = 14.dp, vertical = 3.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text(
        text = "Last call: $lastSpokenText",
        style = MaterialTheme.typography.labelSmall,
        color = ext.subtleText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f)
      )
      Row(
        modifier = Modifier
          .clip(RoundedCornerShape(6.dp))
          .clickable(onClick = onReplayLast)
          .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
      ) {
        Icon(Icons.Default.Replay, contentDescription = "Replay last call", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
        Text("Replay", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
      }
    }
  }
}

/**
 * Classic Windows GameDay-inspired bottom status bar with offline indicator & 1-second countdown bar.
 */
@Composable
fun GameDayStatusBar(
  statusMessage: String,
  warningMessage: String?,
  isNetworkAvailable: Boolean,
  isRefreshing: Boolean,
  lastUpdatedMillis: Long?,
  nextRefreshAtMillis: Long?,
  pollIntervalSec: Int
) {
  val ext = LocalGameDayColors.current
  var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

  LaunchedEffect(nextRefreshAtMillis) {
    while (true) {
      nowMillis = System.currentTimeMillis()
      delay(1_000L)
    }
  }

  val timeFormatter = remember { DateTimeFormatter.ofPattern("h:mm:ss a").withZone(ZoneId.systemDefault()) }

  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 3.dp,
    modifier = Modifier.fillMaxWidth()
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      if (!isNetworkAvailable) {
        Icon(
          imageVector = Icons.Default.WifiOff,
          contentDescription = "Offline",
          tint = ext.redZoneAlert,
          modifier = Modifier.size(14.dp)
        )
      } else {
        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(
              when {
                warningMessage != null -> ext.opponentBadge
                isRefreshing -> MaterialTheme.colorScheme.primary
                else -> ext.turfAccent
              }
            )
        )
      }

      Text(
        text = warningMessage ?: statusMessage,
        style = MaterialTheme.typography.labelSmall,
        color = when {
          !isNetworkAvailable -> ext.redZoneAlert
          warningMessage != null -> ext.opponentBadge
          else -> MaterialTheme.colorScheme.onSurface
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f)
      )

      if (lastUpdatedMillis != null) {
        Text(
          text = timeFormatter.format(Instant.ofEpochMilli(lastUpdatedMillis)),
          style = MaterialTheme.typography.labelSmall,
          fontFamily = FontFamily.Monospace,
          color = ext.subtleText,
          maxLines = 1,
          softWrap = false
        )
      }

      if (nextRefreshAtMillis != null && pollIntervalSec > 0 && isNetworkAvailable) {
        val remainingSec = ((nextRefreshAtMillis - nowMillis) / 1000.0).coerceAtLeast(0.0)
        val progress = (1f - (remainingSec / pollIntervalSec.toDouble()).toFloat()).coerceIn(0f, 1f)
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
              .width(64.dp)
              .height(5.dp)
              .clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
          )
          Text(
            text = "${remainingSec.roundToInt()}s",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = ext.subtleText,
            modifier = Modifier.width(28.dp),
            maxLines = 1,
            softWrap = false
          )
        }
      }
    }
  }
}
