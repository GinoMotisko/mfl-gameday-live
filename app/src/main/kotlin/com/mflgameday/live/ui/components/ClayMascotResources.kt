package com.mflgameday.live.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.mflgameday.live.R
import com.mflgameday.live.data.ClayPackManifest
import java.io.File

/**
 * Provides the reactive over-the-air Claymation Head Pack manifest and optional local heads cache dir down the Compose tree.
 */
val LocalClayManifest = compositionLocalOf { ClayPackManifest() }
val LocalClayHeadsDir = compositionLocalOf<File?> { null }

/**
 * Explicit R8-safe resource registry for all 32 original 3D Claymation team mascots
 * and 6 bundled 3D polymer-clay base/fallback layers (`isShrinkResources = true` safe).
 */
object ClayMascotResources {

  @DrawableRes val BASE_JERSEY_TORSO: Int = R.drawable.clay_base_jersey_torso
  @DrawableRes val BASE_HELMET_SKILL: Int = R.drawable.clay_base_helmet_skill
  @DrawableRes val BASE_HELMET_IDP: Int = R.drawable.clay_base_helmet_idp
  @DrawableRes val BASE_DEFENSE_SHIELD: Int = R.drawable.clay_base_defense_shield
  @DrawableRes val BASE_COACH_BUST: Int = R.drawable.clay_base_coach_bust
  @DrawableRes val BASE_TROPHY_CREST: Int = R.drawable.clay_base_trophy_crest

  @DrawableRes
  fun mascotResFor(teamCode: String?): Int? = when (teamCode?.trim()?.uppercase()) {
    "ARI" -> R.drawable.clay_mascot_ari
    "ATL" -> R.drawable.clay_mascot_atl
    "BAL" -> R.drawable.clay_mascot_bal
    "BUF" -> R.drawable.clay_mascot_buf
    "CAR" -> R.drawable.clay_mascot_car
    "CHI" -> R.drawable.clay_mascot_chi
    "CIN" -> R.drawable.clay_mascot_cin
    "CLE" -> R.drawable.clay_mascot_cle
    "DAL" -> R.drawable.clay_mascot_dal
    "DEN" -> R.drawable.clay_mascot_den
    "DET" -> R.drawable.clay_mascot_det
    "GBP", "GB" -> R.drawable.clay_mascot_gbp
    "HOU" -> R.drawable.clay_mascot_hou
    "IND" -> R.drawable.clay_mascot_ind
    "JAC", "JAX" -> R.drawable.clay_mascot_jac
    "KCC", "KC" -> R.drawable.clay_mascot_kcc
    "LAC" -> R.drawable.clay_mascot_lac
    "LAR", "LA" -> R.drawable.clay_mascot_lar
    "LVR", "LV" -> R.drawable.clay_mascot_lvr
    "MIA" -> R.drawable.clay_mascot_mia
    "MIN" -> R.drawable.clay_mascot_min
    "NEP", "NE" -> R.drawable.clay_mascot_nep
    "NOS", "NO" -> R.drawable.clay_mascot_nos
    "NYG" -> R.drawable.clay_mascot_nyg
    "NYJ" -> R.drawable.clay_mascot_nyj
    "PHI" -> R.drawable.clay_mascot_phi
    "PIT" -> R.drawable.clay_mascot_pit
    "SEA" -> R.drawable.clay_mascot_sea
    "SFO", "SF" -> R.drawable.clay_mascot_sfo
    "TBB", "TB" -> R.drawable.clay_mascot_tbb
    "TEN" -> R.drawable.clay_mascot_ten
    "WAS", "WSH" -> R.drawable.clay_mascot_was
    else -> null
  }

  /**
   * Lifts near-black team colors slightly before `BlendMode.Modulate` tinting on white clay sprites
   * so sculpted 3D polymer-clay shadows, folds, and specular highlights remain crisp.
   */
  fun clayModulateTint(rawHex: Long): Color {
    val base = Color(rawHex)
    val minChannel = 0.20f
    return Color(
      red = base.red.coerceAtLeast(minChannel),
      green = base.green.coerceAtLeast(minChannel),
      blue = base.blue.coerceAtLeast(minChannel),
      alpha = 1f
    )
  }

  /**
   * Lighter tint for white 3D clay helmets/hoodies so dark visors & silver facemasks stay prominent.
   */
  fun clayHelmetTint(rawHex: Long): Color {
    val base = Color(rawHex)
    return Color(
      red = (0.38f + base.red * 0.62f).coerceIn(0f, 1f),
      green = (0.38f + base.green * 0.62f).coerceIn(0f, 1f),
      blue = (0.38f + base.blue * 0.62f).coerceIn(0f, 1f),
      alpha = 1f
    )
  }
}
