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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.mflgameday.live.BuildConfig
import com.mflgameday.live.model.ExposureRole
import com.mflgameday.live.model.FantasyPlayer
import com.mflgameday.live.model.GameEventKind
import com.mflgameday.live.model.GamePhase
import com.mflgameday.live.model.LiveFieldSituation
import com.mflgameday.live.model.NflGameState
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
  FINAL_DONE,
  SCHEDULED_UPCOMING,
  GAME_LIVE,
  HAS_POSSESSION,
  IN_RED_ZONE,
  DEFENDING_RED_ZONE
}

/**
 * Pure 5-state (+ Goal-Line Defense) ring policy so every player avatar immediately communicates
 * whether their game is done (`FINAL_DONE` -> zero ring), yet to kick off (`SCHEDULED_UPCOMING`),
 * underway (`GAME_LIVE` -> solid team primary, avoiding yellow accent collisions), on offense
 * (`HAS_POSSESSION` -> bright Championship Gold), or inside the 20 (`IN_RED_ZONE` -> pulsing Crimson).
 */
object PlayerAvatarRingPolicy {
  fun resolve(
    player: FantasyPlayer?,
    game: NflGameState?,
    gameSecondsRemaining: Int
  ): PlayerAvatarRingStatus {
    if (game != null) {
      return when (game.phase) {
        GamePhase.FINAL -> PlayerAvatarRingStatus.FINAL_DONE
        GamePhase.SCHEDULED -> PlayerAvatarRingStatus.SCHEDULED_UPCOMING
        GamePhase.HALFTIME -> PlayerAvatarRingStatus.GAME_LIVE
        GamePhase.LIVE -> {
          if (game.fieldSituation == LiveFieldSituation.KICKOFF_PENDING) {
            return PlayerAvatarRingStatus.GAME_LIVE
          }
          val isDefense = player?.isDefensiveSide == true
          val myTeam = player?.team
          val possTeam = game.possessionTeam
          when {
            !isDefense && myTeam != null && possTeam == myTeam && game.effectiveInRedZone ->
              PlayerAvatarRingStatus.IN_RED_ZONE
            isDefense && myTeam != null && possTeam != null && possTeam != myTeam && game.effectiveInRedZone ->
              PlayerAvatarRingStatus.DEFENDING_RED_ZONE
            !isDefense && myTeam != null && possTeam == myTeam ->
              PlayerAvatarRingStatus.HAS_POSSESSION
            else -> PlayerAvatarRingStatus.GAME_LIVE
          }
        }
      }
    }
    return when {
      gameSecondsRemaining <= 0 -> PlayerAvatarRingStatus.FINAL_DONE
      gameSecondsRemaining < 3600 -> PlayerAvatarRingStatus.GAME_LIVE
      else -> PlayerAvatarRingStatus.SCHEDULED_UPCOMING
    }
  }
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
  ringStatus: PlayerAvatarRingStatus = PlayerAvatarRingStatus.SCHEDULED_UPCOMING
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
  val teamPrimary = Color(rawHex)
  // Ensure dark team primaries (e.g. PIT 0xFF101820, LVR 0xFF111827) stay readable against dark cards without using yellow accents!
  val liveTeamRingColor = when (rawHex) {
    0xFF101820L, 0xFF111827L, 0xFF0B162AL, 0xFF03202FL -> Color(0xFF64748B)
    else -> teamPrimary
  }
  val championshipGold = Color(0xFFFFD600)
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
    PlayerAvatarRingStatus.DEFENDING_RED_ZONE -> ext.opponentBadge
    PlayerAvatarRingStatus.HAS_POSSESSION -> championshipGold
    PlayerAvatarRingStatus.GAME_LIVE -> liveTeamRingColor
    PlayerAvatarRingStatus.SCHEDULED_UPCOMING -> ext.cardBorder.copy(alpha = 0.42f)
    PlayerAvatarRingStatus.FINAL_DONE -> Color.Transparent
  }

  val ringWidth = when (ringStatus) {
    PlayerAvatarRingStatus.IN_RED_ZONE -> (2.2f + 1.4f * pulseProgress).dp
    PlayerAvatarRingStatus.DEFENDING_RED_ZONE -> (2.0f + 1.1f * pulseProgress).dp
    PlayerAvatarRingStatus.HAS_POSSESSION -> 2.6.dp
    PlayerAvatarRingStatus.GAME_LIVE -> 1.9.dp
    PlayerAvatarRingStatus.SCHEDULED_UPCOMING -> 1.0.dp
    PlayerAvatarRingStatus.FINAL_DONE -> 0.dp
  }

  val avatarAlpha = if (ringStatus == PlayerAvatarRingStatus.FINAL_DONE) 0.76f else 1.0f

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(3.dp),
    modifier = Modifier.alpha(avatarAlpha)
  ) {
    Box(
      modifier = Modifier.size(size + 4.dp),
      contentAlignment = Alignment.Center
    ) {
      if (isPulsing || ringStatus == PlayerAvatarRingStatus.HAS_POSSESSION) {
        val glowAlpha = if (isPulsing) pulseProgress * 0.62f else 0.34f
        Box(
          modifier = Modifier
            .fillMaxSize()
            .alpha(glowAlpha)
            .clip(CircleShape)
            .background(
              Brush.radialGradient(
                listOf(baseRingColor.copy(alpha = 0.60f), Color.Transparent)
              )
            )
        )
      }

      val circleModifier = Modifier
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
        .let { base ->
          if (ringWidth > 0.dp) {
            base.border(ringWidth, baseRingColor.copy(alpha = pulseProgress), CircleShape)
          } else {
            base
          }
        }

      Box(
        modifier = circleModifier,
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

    val posAccent = when (ringStatus) {
      PlayerAvatarRingStatus.IN_RED_ZONE -> ext.redZoneAlert
      PlayerAvatarRingStatus.HAS_POSSESSION -> championshipGold
      PlayerAvatarRingStatus.FINAL_DONE -> ext.subtleText
      else -> MaterialTheme.colorScheme.primary
    }
    val posBorder = when (ringStatus) {
      PlayerAvatarRingStatus.IN_RED_ZONE -> ext.redZoneAlert
      PlayerAvatarRingStatus.HAS_POSSESSION -> championshipGold.copy(alpha = 0.85f)
      else -> ext.cardBorder
    }

    // Centered position capsule cleanly below the circle so 100% of the jersey # and face stay visible
    Box(
      modifier = Modifier
        .clip(RoundedCornerShape(5.dp))
        .background(MaterialTheme.colorScheme.surface)
        .border(
          width = 0.75.dp,
          color = posBorder,
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
        color = posAccent,
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
 * Post-score Special Teams pill (`AWAITING KICKOFF` / `PAT · 2-PT TRY`) shown across TV
 * commercial breaks & official timeouts so scoring teams never stay stuck in a false Red Zone badge.
 */
@Composable
fun SpecialTeamsStatusBadge(
  situation: LiveFieldSituation,
  teamCode: String,
  modifier: Modifier = Modifier
) {
  val ext = LocalGameDayColors.current
  val isPat = situation == LiveFieldSituation.PAT_PENDING
  val badgeColor = if (isPat) ext.touchdownGold else ext.turfAccent
  val prefix = if (teamCode.isNotBlank()) "$teamCode · " else ""
  val text = if (isPat) "${prefix}PAT / 2-PT" else "${prefix}AWAITING KICKOFF"

  Row(
    modifier = modifier
      .clip(CircleShape)
      .background(badgeColor.copy(alpha = if (ext.isDark) 0.22f else 0.14f))
      .border(1.dp, badgeColor.copy(alpha = 0.72f), CircleShape)
      .padding(horizontal = 8.dp, vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    Icon(
      imageVector = Icons.Default.SportsFootball,
      contentDescription = text,
      tint = badgeColor,
      modifier = Modifier.size(11.dp)
    )
    Text(
      text = text,
      color = badgeColor,
      fontSize = 10.sp,
      fontWeight = FontWeight.ExtraBold,
      letterSpacing = 0.4.sp
    )
  }
}

data class FieldDriveVisualState(
  val awayCode: String,
  val homeCode: String,
  val awayColorHex: Long,
  val homeColorHex: Long,
  val drivesLeftToRight: Boolean,
  val ballFractionFromLeft: Float,
  val firstDownFractionFromLeft: Float?,
  val targetEndzoneOnRight: Boolean,
  val inRedZone: Boolean,
  val fieldSituation: LiveFieldSituation,
  val downDistanceText: String,
  val possessionText: String
)

object FieldDriveGeometry {
  private val distanceRegex = Regex("""&\s*(\d+)""")

  fun fromGame(game: NflGameState): FieldDriveVisualState? {
    if (game.phase != GamePhase.LIVE || game.possessionEspnId == null) return null
    val progress = game.fieldProgressFraction ?: return null
    val awayHasBall = game.possessionEspnId == game.away.espnId
    val ballFromLeft = if (awayHasBall) {
      progress.coerceIn(0.01f, 0.99f)
    } else {
      (1f - progress).coerceIn(0.01f, 0.99f)
    }

    val downTxt = game.downDistanceText.trim()
    val firstDownFromLeft: Float? = if (game.fieldSituation != LiveFieldSituation.SCRIMMAGE_DRIVE) {
      null
    } else {
      val isGoalToGo = downTxt.contains("goal", ignoreCase = true)
      val parsedToGain = distanceRegex.find(downTxt)?.groupValues?.getOrNull(1)?.toIntOrNull()
      when {
        isGoalToGo -> if (awayHasBall) 1.0f else 0.0f
        parsedToGain != null && parsedToGain in 1..99 -> {
          val gainFrac = parsedToGain / 100f
          if (awayHasBall) {
            (ballFromLeft + gainFrac).coerceIn(0.0f, 1.0f)
          } else {
            (ballFromLeft - gainFrac).coerceIn(0.0f, 1.0f)
          }
        }
        else -> null
      }
    }

    val kickingTeamCode = if (awayHasBall) game.away.team.code else game.home.team.code
    val receivingTeamCode = if (awayHasBall) game.home.team.code else game.away.team.code
    val resolvedSpotLabel = when (game.fieldSituation) {
      LiveFieldSituation.KICKOFF_PENDING -> {
        val teeYd = (progress * 100f).roundToInt().coerceIn(20, 50)
        "Tee at $kickingTeamCode $teeYd"
      }
      LiveFieldSituation.PAT_PENDING -> "Try at $receivingTeamCode 2"
      LiveFieldSituation.SCRIMMAGE_DRIVE -> game.possessionText.trim()
    }

    val resolvedHeaderLeft = when (game.fieldSituation) {
      LiveFieldSituation.KICKOFF_PENDING -> "Awaiting Kickoff"
      LiveFieldSituation.PAT_PENDING -> "PAT / 2-Pt Try Pending"
      LiveFieldSituation.SCRIMMAGE_DRIVE -> downTxt.ifBlank { "Live Drive" }
    }

    return FieldDriveVisualState(
      awayCode = game.away.team.code,
      homeCode = game.home.team.code,
      awayColorHex = game.away.team.primaryColorHex,
      homeColorHex = game.home.team.primaryColorHex,
      drivesLeftToRight = awayHasBall,
      ballFractionFromLeft = ballFromLeft,
      firstDownFractionFromLeft = firstDownFromLeft,
      targetEndzoneOnRight = awayHasBall,
      inRedZone = game.effectiveInRedZone,
      fieldSituation = game.fieldSituation,
      downDistanceText = resolvedHeaderLeft,
      possessionText = resolvedSpotLabel
    )
  }
}

/**
 * Edge-to-Edge "Google Pinned Pro" Linear Football Field (`Modifier.fillMaxWidth()`):
 * Renders Away (left) and Home (right) team-colored endzones, yellow goalposts, white goal lines,
 * 10-yard lines + hash marks, yard numbers (`10..50..10` on wide containers, `20·50·20` on compact),
 * glowing yellow 1st-Down line, crimson Red Zone 20-yard tint, directional live football puck,
 * and post-score `KICKOFF_PENDING` tee + flight-arc visual across commercial breaks.
 */
@Composable
fun GooglePinnedLinearField(
  game: NflGameState,
  fieldHeight: Dp = 40.dp,
  showHeaderLabels: Boolean = true,
  modifier: Modifier = Modifier
) {
  val drive = remember(
    game.id,
    game.phase,
    game.possessionEspnId,
    game.inRedZone,
    game.fieldSituation,
    game.downDistanceText,
    game.possessionText,
    game.yardLine
  ) {
    FieldDriveGeometry.fromGame(game)
  } ?: return

  val ext = LocalGameDayColors.current
  val textMeasurer = rememberTextMeasurer()
  val gold = Color(0xFFFFD600)
  val turfBase = Color(0xFF0E3121)
  val isKickoff = drive.fieldSituation == LiveFieldSituation.KICKOFF_PENDING
  val isPat = drive.fieldSituation == LiveFieldSituation.PAT_PENDING
  val borderCol = when {
    drive.inRedZone -> ext.redZoneAlert
    isPat -> gold.copy(alpha = 0.82f)
    isKickoff -> ext.turfAccent.copy(alpha = 0.78f)
    else -> ext.cardBorder.copy(alpha = 0.78f)
  }
  val directionCaption = if (drive.drivesLeftToRight) {
    "${drive.awayCode} ➔ ${drive.homeCode}"
  } else {
    "${drive.awayCode} ⬅ ${drive.homeCode}"
  }

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (showHeaderLabels) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = drive.downDistanceText.ifBlank { "Live Drive" },
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
          color = when {
            drive.inRedZone -> ext.redZoneAlert
            isPat -> gold
            else -> ext.turfAccent
          },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
        val rightCaption = when {
          isKickoff || isPat -> "${drive.possessionText} · $directionCaption"
          drive.possessionText.isNotBlank() -> "Ball on ${drive.possessionText} · $directionCaption"
          else -> directionCaption
        }
        Text(
          text = rightCaption,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          color = ext.subtleText,
          maxLines = 1,
          softWrap = false
        )
      }
    }

    val ezTextStyle = TextStyle(
      color = Color.White.copy(alpha = 0.94f),
      fontSize = 8.5.sp,
      fontWeight = FontWeight.Black,
      fontFamily = FontFamily.Monospace
    )
    val yardNumStyle = TextStyle(
      color = Color.White.copy(alpha = 0.62f),
      fontSize = 7.5.sp,
      fontWeight = FontWeight.Bold,
      fontFamily = FontFamily.Monospace
    )

    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(fieldHeight)
        .clip(RoundedCornerShape(8.dp))
        .border(1.dp, borderCol, RoundedCornerShape(8.dp))
    ) {
      val w = size.width
      val h = size.height
      val ezW = (w * 0.095f).coerceAtLeast(30.dp.toPx())
      val playW = (w - ezW * 2f).coerceAtLeast(40f)

      // 1. Turf base + Away (left) & Home (right) Endzones stretched 100% edge-to-edge
      drawRect(color = turfBase, size = Size(w, h))
      drawRect(color = Color(drive.awayColorHex).copy(alpha = 0.88f), topLeft = Offset.Zero, size = Size(ezW, h))
      drawRect(color = Color(drive.homeColorHex).copy(alpha = 0.88f), topLeft = Offset(w - ezW, 0f), size = Size(ezW, h))

      val ballX = ezW + drive.ballFractionFromLeft * playW

      // 2. Defending Red Zone 20-yard crimson tint + active drive progress wash (suppressed on Kickoffs!)
      if (!isKickoff) {
        val rzStartX = if (drive.targetEndzoneOnRight) ezW + playW * 0.80f else ezW
        drawRect(
          color = ext.redZoneAlert.copy(alpha = if (drive.inRedZone) 0.30f else 0.09f),
          topLeft = Offset(rzStartX, 0f),
          size = Size(playW * 0.20f, h)
        )

        if (!isPat) {
          val driveFillColor = if (drive.inRedZone) ext.redZoneAlert.copy(alpha = 0.16f) else ext.turfAccent.copy(alpha = 0.12f)
          if (drive.drivesLeftToRight) {
            drawRect(
              color = driveFillColor,
              topLeft = Offset(ezW, 0f),
              size = Size((ballX - ezW).coerceAtLeast(2f), h)
            )
          } else {
            drawRect(
              color = driveFillColor,
              topLeft = Offset(ballX, 0f),
              size = Size(((w - ezW) - ballX).coerceAtLeast(2f), h)
            )
          }
        }
      } else {
        // Receiving Dynamic Kickoff Landing Zone highlight (20-yard line to Goal Line)
        val lzStartX = if (drive.targetEndzoneOnRight) ezW + playW * 0.80f else ezW
        drawRect(
          color = ext.turfAccent.copy(alpha = 0.14f),
          topLeft = Offset(lzStartX, 0f),
          size = Size(playW * 0.20f, h)
        )
      }

      // 3. Crisp white Goal Lines
      drawLine(Color.White, Offset(ezW, 0f), Offset(ezW, h), strokeWidth = 1.8.dp.toPx())
      drawLine(Color.White, Offset(w - ezW, 0f), Offset(w - ezW, h), strokeWidth = 1.8.dp.toPx())

      // 4. Every 10-yard marker, hash ticks & responsive yard numbers
      val showAllTens = w >= 380.dp.toPx()
      for (i in 1..9) {
        val lx = ezW + (i * 0.10f) * playW
        val isMidfield = i == 5
        drawLine(
          color = Color.White.copy(alpha = if (isMidfield) 0.68f else 0.22f),
          start = Offset(lx, 0f),
          end = Offset(lx, h),
          strokeWidth = if (isMidfield) 1.5.dp.toPx() else 0.9.dp.toPx()
        )
        drawLine(
          color = Color.White.copy(alpha = 0.42f),
          start = Offset(lx, h * 0.32f),
          end = Offset(lx, h * 0.40f),
          strokeWidth = 1.dp.toPx()
        )
        drawLine(
          color = Color.White.copy(alpha = 0.42f),
          start = Offset(lx, h * 0.60f),
          end = Offset(lx, h * 0.68f),
          strokeWidth = 1.dp.toPx()
        )
        if (showAllTens || i == 2 || i == 5 || i == 8) {
          val numTxt = (if (i <= 5) i * 10 else (10 - i) * 10).toString()
          val layout = textMeasurer.measure(numTxt, style = yardNumStyle)
          drawText(
            textLayoutResult = layout,
            topLeft = Offset(lx - layout.size.width / 2f, h - layout.size.height - 1.5.dp.toPx())
          )
        }
      }

      // 5. Endzone Team Codes & Yellow Goalposts on both outer edges
      val gpStroke = 1.7.dp.toPx()
      val gpInset = 4.dp.toPx()
      val gpCross = 9.5.dp.toPx()
      // Left goalpost
      drawLine(gold, Offset(gpInset, h / 2f), Offset(gpCross, h / 2f), gpStroke, cap = StrokeCap.Round)
      drawLine(gold, Offset(gpCross, h * 0.25f), Offset(gpCross, h * 0.75f), gpStroke, cap = StrokeCap.Round)
      drawLine(gold, Offset(gpInset + 1.dp.toPx(), h * 0.25f), Offset(gpCross, h * 0.25f), gpStroke, cap = StrokeCap.Round)
      drawLine(gold, Offset(gpInset + 1.dp.toPx(), h * 0.75f), Offset(gpCross, h * 0.75f), gpStroke, cap = StrokeCap.Round)
      // Right goalpost
      drawLine(gold, Offset(w - gpInset, h / 2f), Offset(w - gpCross, h / 2f), gpStroke, cap = StrokeCap.Round)
      drawLine(gold, Offset(w - gpCross, h * 0.25f), Offset(w - gpCross, h * 0.75f), gpStroke, cap = StrokeCap.Round)
      drawLine(gold, Offset(w - gpInset - 1.dp.toPx(), h * 0.25f), Offset(w - gpCross, h * 0.25f), gpStroke, cap = StrokeCap.Round)
      drawLine(gold, Offset(w - gpInset - 1.dp.toPx(), h * 0.75f), Offset(w - gpCross, h * 0.75f), gpStroke, cap = StrokeCap.Round)

      val awayLayout = textMeasurer.measure(drive.awayCode, style = ezTextStyle)
      drawText(
        textLayoutResult = awayLayout,
        topLeft = Offset(ezW * 0.58f - awayLayout.size.width / 2f, (h - awayLayout.size.height) / 2f)
      )
      val homeLayout = textMeasurer.measure(drive.homeCode, style = ezTextStyle)
      drawText(
        textLayoutResult = homeLayout,
        topLeft = Offset(w - ezW * 0.58f - homeLayout.size.width / 2f, (h - homeLayout.size.height) / 2f)
      )

      // 6. Glowing Yellow 1st-Down Line (only during active scrimmage drives)
      drive.firstDownFractionFromLeft?.let { fdFrac ->
        val fdX = ezW + fdFrac * playW
        drawLine(
          color = gold,
          start = Offset(fdX, 0f),
          end = Offset(fdX, h),
          strokeWidth = 2.dp.toPx(),
          pathEffect = if (drive.inRedZone) PathEffect.dashPathEffect(floatArrayOf(6f, 5f)) else null
        )
      }

      // 7. Live Football Puck / Kickoff Tee & Flight Arc
      val centerY = h * 0.45f
      val ballRx = 6.dp.toPx()
      val ballRy = 4.dp.toPx()

      if (isKickoff) {
        // Dashed Kickoff Flight Arc from 35-yard Tee to Receiving 12-yard Landing Zone
        val targetFrac = if (drive.drivesLeftToRight) 0.88f else 0.12f
        val targetX = ezW + targetFrac * playW
        val midX = (ballX + targetX) / 2f
        val arcPath = Path().apply {
          moveTo(ballX, centerY)
          quadraticTo(midX, h * 0.06f, targetX, centerY)
        }
        drawPath(
          path = arcPath,
          color = gold.copy(alpha = 0.88f),
          style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 1.8.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
            cap = StrokeCap.Round
          )
        )
        // Receiving landing target ring + directional chevron
        drawCircle(
          color = ext.turfAccent.copy(alpha = 0.35f),
          radius = 6.dp.toPx(),
          center = Offset(targetX, centerY)
        )
        drawCircle(
          color = gold,
          radius = 2.6.dp.toPx(),
          center = Offset(targetX, centerY)
        )
        // Orange/Gold Kickoff Tee Base under the ball
        val teeHalfW = 5.dp.toPx()
        val teeTopY = centerY + ballRy - 0.5.dp.toPx()
        val teeBotY = centerY + ballRy + 3.2.dp.toPx()
        val teePath = Path().apply {
          moveTo(ballX - teeHalfW * 0.55f, teeTopY)
          lineTo(ballX + teeHalfW * 0.55f, teeTopY)
          lineTo(ballX + teeHalfW, teeBotY)
          lineTo(ballX - teeHalfW, teeBotY)
          close()
        }
        drawPath(teePath, color = Color(0xFFF97316))
        drawCircle(
          color = ext.turfAccent.copy(alpha = 0.30f),
          radius = 10.dp.toPx(),
          center = Offset(ballX, centerY)
        )
      } else {
        drawCircle(
          color = (if (drive.inRedZone) ext.redZoneAlert else gold).copy(alpha = 0.34f),
          radius = 10.dp.toPx(),
          center = Offset(ballX, centerY)
        )
        val arrowPath = Path().apply {
          if (drive.drivesLeftToRight) {
            moveTo(ballX + ballRx + 2.dp.toPx(), centerY - 3.dp.toPx())
            lineTo(ballX + ballRx + 7.dp.toPx(), centerY)
            lineTo(ballX + ballRx + 2.dp.toPx(), centerY + 3.dp.toPx())
          } else {
            moveTo(ballX - ballRx - 2.dp.toPx(), centerY - 3.dp.toPx())
            lineTo(ballX - ballRx - 7.dp.toPx(), centerY)
            lineTo(ballX - ballRx - 2.dp.toPx(), centerY + 3.dp.toPx())
          }
          close()
        }
        drawPath(arrowPath, color = gold)
      }

      drawRoundRect(
        color = when {
          drive.inRedZone -> ext.redZoneAlert
          isPat -> gold
          else -> Color(0xFFB45309)
        },
        topLeft = Offset(ballX - ballRx, centerY - ballRy),
        size = Size(ballRx * 2f, ballRy * 2f),
        cornerRadius = CornerRadius(ballRx, ballRy)
      )
      drawLine(
        color = if (isPat) Color(0xFF1E293B) else Color.White,
        start = Offset(ballX - 2.2.dp.toPx(), centerY),
        end = Offset(ballX + 2.2.dp.toPx(), centerY),
        strokeWidth = 1.dp.toPx()
      )
    }
  }
}

/**
 * Compact 14.dp Edge-to-Edge Micro-Field Strip (`Modifier.fillMaxWidth()`) shown directly beneath
 * active ball-carrying starters (`hasBall == true`) in Fantasy Matchup rows across all resolutions.
 */
@Composable
fun PlayerMicroDriveStrip(
  game: NflGameState,
  modifier: Modifier = Modifier
) {
  if (game.fieldSituation == LiveFieldSituation.KICKOFF_PENDING) return
  val drive = remember(
    game.id,
    game.phase,
    game.possessionEspnId,
    game.inRedZone,
    game.fieldSituation,
    game.downDistanceText,
    game.possessionText,
    game.yardLine
  ) {
    FieldDriveGeometry.fromGame(game)
  } ?: return

  val ext = LocalGameDayColors.current
  val gold = Color(0xFFFFD600)
  val borderCol = if (drive.inRedZone) ext.redZoneAlert else ext.cardBorder.copy(alpha = 0.72f)

  Canvas(
    modifier = modifier
      .fillMaxWidth()
      .height(14.dp)
      .clip(RoundedCornerShape(4.dp))
      .border(0.9.dp, borderCol, RoundedCornerShape(4.dp))
  ) {
    val w = size.width
    val h = size.height
    val ezW = (w * 0.08f).coerceAtLeast(14.dp.toPx())
    val playW = (w - ezW * 2f).coerceAtLeast(24f)

    drawRect(color = Color(0xFF0E3121), size = Size(w, h))
    drawRect(color = Color(drive.awayColorHex).copy(alpha = 0.85f), topLeft = Offset.Zero, size = Size(ezW, h))
    drawRect(color = Color(drive.homeColorHex).copy(alpha = 0.85f), topLeft = Offset(w - ezW, 0f), size = Size(ezW, h))

    val rzStartX = if (drive.targetEndzoneOnRight) ezW + playW * 0.80f else ezW
    drawRect(
      color = ext.redZoneAlert.copy(alpha = if (drive.inRedZone) 0.32f else 0.12f),
      topLeft = Offset(rzStartX, 0f),
      size = Size(playW * 0.20f, h)
    )

    drawLine(Color.White.copy(alpha = 0.85f), Offset(ezW, 0f), Offset(ezW, h), strokeWidth = 1.2.dp.toPx())
    drawLine(Color.White.copy(alpha = 0.85f), Offset(w - ezW, 0f), Offset(w - ezW, h), strokeWidth = 1.2.dp.toPx())

    for (i in 1..9) {
      val lx = ezW + (i * 0.10f) * playW
      drawLine(
        color = Color.White.copy(alpha = if (i == 5) 0.62f else 0.18f),
        start = Offset(lx, 0f),
        end = Offset(lx, h),
        strokeWidth = if (i == 5) 1.3.dp.toPx() else 0.8.dp.toPx()
      )
    }

    drive.firstDownFractionFromLeft?.let { fd ->
      val fdX = ezW + fd * playW
      drawLine(gold, Offset(fdX, 0f), Offset(fdX, h), strokeWidth = 1.5.dp.toPx())
    }

    val ballX = ezW + drive.ballFractionFromLeft * playW
    drawCircle(
      color = Color.White,
      radius = 4.5.dp.toPx(),
      center = Offset(ballX, h / 2f)
    )
    drawCircle(
      color = if (drive.inRedZone) ext.redZoneAlert else gold,
      radius = 3.4.dp.toPx(),
      center = Offset(ballX, h / 2f)
    )
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
