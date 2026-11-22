package com.mflgameday.live.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SportsFootball
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mflgameday.live.data.MflLeagueMetadata
import com.mflgameday.live.model.DiscoveredMflLeague
import com.mflgameday.live.service.GameDayCoordinator
import com.mflgameday.live.service.GameDayUiState
import com.mflgameday.live.ui.theme.LocalGameDayColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupAndAuthScreen(
  uiState: GameDayUiState,
  coordinator: GameDayCoordinator,
  onFinishedSetup: () -> Unit = {}
) {
  val ext = LocalGameDayColors.current
  var username by rememberSaveable { mutableStateOf(uiState.settings.mflUsername) }
  var password by rememberSaveable { mutableStateOf("") }
  var showPassword by rememberSaveable { mutableStateOf(false) }

  var manualLeagueId by rememberSaveable { mutableStateOf("") }
  var manualApiKey by rememberSaveable { mutableStateOf("") }
  var selectedManualFranchiseId by rememberSaveable { mutableStateOf("") }

  val selectedDiscovered = remember(uiState.discoveredLeagues) {
    mutableStateListOf<String>().apply {
      addAll(uiState.discoveredLeagues.map { it.leagueId })
    }
  }

  BoxWithConstraints(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
  ) {
    val isWide = maxWidth >= 780.dp

    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = if (isWide) 32.dp else 16.dp, vertical = 20.dp),
      verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
      // Hero Broadcast Banner
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(20.dp))
          .background(
            Brush.linearGradient(
              listOf(ext.headerGradientStart, ext.headerGradientEnd)
            )
          )
          .border(1.dp, ext.cardBorder, RoundedCornerShape(20.dp))
          .padding(if (isWide) 28.dp else 20.dp)
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Box(
              modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.SportsFootball,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
              )
            }
            Column {
              Text(
                text = "MFL GAMEDAY LIVE",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface
              )
              Text(
                text = "Real-Time Multi-League Scoring · Live NFL RedZone · Spoken Play-by-Play",
                style = MaterialTheme.typography.bodyMedium,
                color = ext.subtleText
              )
            }
          }

          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Button(
              onClick = {
                coordinator.startNflLiveOnlyMode()
                onFinishedSetup()
              }
            ) {
              Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("Launch NFL Live Only (No Account)")
            }

            FilledTonalButton(
              onClick = {
                coordinator.startDemoSimulatorMode()
                onFinishedSetup()
              }
            ) {
              Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("Interactive Sunday Simulator")
            }

            OutlinedButton(
              onClick = {
                coordinator.voiceEngine.speakImmediateSample(
                  "Touchdown, Tampa Bay Buccaneers! Baker Mayfield, 94 yard touchdown pass to Chris Godwin! Tampa Bay Buccaneers lead 21 to 17.",
                  uiState.settings
                )
              }
            ) {
              Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(4.dp))
              Text("Test Announcer Voice")
            }
          }
        }
      }

      if (uiState.authError != null) {
        Surface(
          color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
        ) {
          Text(
            text = uiState.authError,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(14.dp)
          )
        }
      }

      if (isWide) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(18.dp),
          verticalAlignment = Alignment.Top
        ) {
          MflSignInCard(
            modifier = Modifier.weight(1f),
            username = username,
            onUsernameChange = { username = it },
            password = password,
            onPasswordChange = { password = it },
            showPassword = showPassword,
            onToggleShowPassword = { showPassword = !showPassword },
            authBusy = uiState.authBusy,
            discoveredLeagues = uiState.discoveredLeagues,
            selectedIds = selectedDiscovered,
            onSignIn = { coordinator.signInWithMflCredentials(username, password) },
            onImportSelected = {
              val picked = uiState.discoveredLeagues.filter { it.leagueId in selectedDiscovered }
              coordinator.importDiscoveredLeagues(picked)
              onFinishedSetup()
            }
          )

          ManualLeagueCard(
            modifier = Modifier.weight(1f),
            leagueId = manualLeagueId,
            onLeagueIdChange = { manualLeagueId = it },
            apiKey = manualApiKey,
            onApiKeyChange = { manualApiKey = it },
            authBusy = uiState.authBusy,
            pendingMeta = uiState.pendingManualMetadata,
            selectedFranchiseId = selectedManualFranchiseId,
            onSelectFranchiseId = { selectedManualFranchiseId = it },
            onLookup = {
              selectedManualFranchiseId = ""
              coordinator.lookupManualLeague(manualLeagueId, manualApiKey)
            },
            onQuickPreset = { id ->
              manualLeagueId = id
              selectedManualFranchiseId = ""
              coordinator.lookupManualLeague(id, manualApiKey)
            },
            onConfirmAdd = { meta, fId ->
              coordinator.addConfirmedManualLeague(meta, fId)
              onFinishedSetup()
            }
          )
        }
      } else {
        MflSignInCard(
          modifier = Modifier.fillMaxWidth(),
          username = username,
          onUsernameChange = { username = it },
          password = password,
          onPasswordChange = { password = it },
          showPassword = showPassword,
          onToggleShowPassword = { showPassword = !showPassword },
          authBusy = uiState.authBusy,
          discoveredLeagues = uiState.discoveredLeagues,
          selectedIds = selectedDiscovered,
          onSignIn = { coordinator.signInWithMflCredentials(username, password) },
          onImportSelected = {
            val picked = uiState.discoveredLeagues.filter { it.leagueId in selectedDiscovered }
            coordinator.importDiscoveredLeagues(picked)
            onFinishedSetup()
          }
        )

        ManualLeagueCard(
          modifier = Modifier.fillMaxWidth(),
          leagueId = manualLeagueId,
          onLeagueIdChange = { manualLeagueId = it },
          apiKey = manualApiKey,
          onApiKeyChange = { manualApiKey = it },
          authBusy = uiState.authBusy,
          pendingMeta = uiState.pendingManualMetadata,
          selectedFranchiseId = selectedManualFranchiseId,
          onSelectFranchiseId = { selectedManualFranchiseId = it },
          onLookup = {
            selectedManualFranchiseId = ""
            coordinator.lookupManualLeague(manualLeagueId, manualApiKey)
          },
          onQuickPreset = { id ->
            manualLeagueId = id
            selectedManualFranchiseId = ""
            coordinator.lookupManualLeague(id, manualApiKey)
          },
          onConfirmAdd = { meta, fId ->
            coordinator.addConfirmedManualLeague(meta, fId)
            onFinishedSetup()
          }
        )
      }

      if (uiState.leagues.isNotEmpty()) {
        Surface(
          color = ext.cardSurface,
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ext.cardBorder, RoundedCornerShape(16.dp))
        ) {
          Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "Configured Leagues (${uiState.leagues.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
              )
              Button(
                onClick = {
                  coordinator.updateSettings {
                    it.copy(operatingMode = com.mflgameday.live.model.OperatingMode.MFL_AND_NFL_LIVE)
                  }
                  coordinator.restartPollingLoop()
                  onFinishedSetup()
                }
              ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Resume Live GameDay")
              }
            }
            uiState.leagues.forEach { cfg ->
              Text(
                text = "• ${cfg.leagueName} (#${cfg.leagueId}) — ${cfg.franchiseName}" +
                  if (cfg.isBestBall) " · [BEST BALL]" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = ext.subtleText
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MflSignInCard(
  modifier: Modifier,
  username: String,
  onUsernameChange: (String) -> Unit,
  password: String,
  onPasswordChange: (String) -> Unit,
  showPassword: Boolean,
  onToggleShowPassword: () -> Unit,
  authBusy: Boolean,
  discoveredLeagues: List<DiscoveredMflLeague>,
  selectedIds: MutableList<String>,
  onSignIn: () -> Unit,
  onImportSelected: () -> Unit
) {
  val ext = LocalGameDayColors.current
  Surface(
    color = ext.cardSurface,
    shape = RoundedCornerShape(18.dp),
    modifier = modifier.border(1.dp, ext.cardBorder, RoundedCornerShape(18.dp))
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("Sign In with MFL Account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      }
      Text(
        text = "Automatically imports all your MyFantasyLeague leagues, scoring formats, Best Ball rules, and teams.",
        style = MaterialTheme.typography.bodySmall,
        color = ext.subtleText
      )

      OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text("MFL Username") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
      )

      OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("MFL Password") },
        singleLine = true,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
          IconButton(onClick = onToggleShowPassword) {
            Icon(
              imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
              contentDescription = "Toggle password"
            )
          }
        },
        modifier = Modifier.fillMaxWidth()
      )

      Button(
        onClick = onSignIn,
        enabled = !authBusy && username.isNotBlank() && password.isNotEmpty(),
        modifier = Modifier.fillMaxWidth()
      ) {
        if (authBusy) {
          CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
          Spacer(Modifier.width(8.dp))
        }
        Text("Sign In & Pull My Leagues")
      }

      if (discoveredLeagues.isNotEmpty()) {
        HorizontalDivider(color = ext.cardBorder)
        Text(
          text = "Found ${discoveredLeagues.size} League(s) — Select to Track:",
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold
        )
        discoveredLeagues.forEach { d ->
          val checked = d.leagueId in selectedIds
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .clickable {
                if (checked) selectedIds.remove(d.leagueId) else selectedIds.add(d.leagueId)
              }
              .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Checkbox(
              checked = checked,
              onCheckedChange = { on ->
                if (on) {
                  if (d.leagueId !in selectedIds) selectedIds.add(d.leagueId)
                } else {
                  selectedIds.remove(d.leagueId)
                }
              }
            )
            Column {
              Text(d.name, fontWeight = FontWeight.SemiBold)
              Text(
                text = "ID #${d.leagueId}" + (d.franchiseName?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = ext.subtleText
              )
            }
          }
        }
        Button(
          onClick = onImportSelected,
          enabled = selectedIds.isNotEmpty() && !authBusy,
          colors = ButtonDefaults.buttonColors(containerColor = ext.turfAccent, contentColor = ColorForDarkText),
          modifier = Modifier.fillMaxWidth()
        ) {
          Text("Start GameDay with ${selectedIds.size} League(s)")
        }
      }
    }
  }
}

private val ColorForDarkText = androidx.compose.ui.graphics.Color(0xFF04210F)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualLeagueCard(
  modifier: Modifier,
  leagueId: String,
  onLeagueIdChange: (String) -> Unit,
  apiKey: String,
  onApiKeyChange: (String) -> Unit,
  authBusy: Boolean,
  pendingMeta: MflLeagueMetadata?,
  selectedFranchiseId: String,
  onSelectFranchiseId: (String) -> Unit,
  onLookup: () -> Unit,
  onQuickPreset: (String) -> Unit,
  onConfirmAdd: (MflLeagueMetadata, String) -> Unit
) {
  val ext = LocalGameDayColors.current
  Surface(
    color = ext.cardSurface,
    shape = RoundedCornerShape(18.dp),
    modifier = modifier.border(1.dp, ext.cardBorder, RoundedCornerShape(18.dp))
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Text("Add League by 5-Digit ID", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      }
      Text(
        text = "Enter any public MFL League ID (or include an API Key for private leagues). Add as many leagues as you want!",
        style = MaterialTheme.typography.bodySmall,
        color = ext.subtleText
      )

      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
          onClick = { onQuickPreset("22539") },
          label = { Text("Quick Load #22539 (Best Ball)") }
        )
        AssistChip(
          onClick = { onQuickPreset("10479") },
          label = { Text("Quick Load #10479 (WVC)") }
        )
      }

      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
          value = leagueId,
          onValueChange = onLeagueIdChange,
          label = { Text("League ID (e.g. 22539)") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
          value = apiKey,
          onValueChange = onApiKeyChange,
          label = { Text("API Key (Optional)") },
          singleLine = true,
          modifier = Modifier.weight(1f)
        )
      }

      FilledTonalButton(
        onClick = onLookup,
        enabled = !authBusy && leagueId.trim().length >= 4,
        modifier = Modifier.fillMaxWidth()
      ) {
        Text("Find League & Franchises")
      }

      if (pendingMeta != null) {
        HorizontalDivider(color = ext.cardBorder)
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = pendingMeta.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold
          )
          if (pendingMeta.isBestBall) {
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(ext.touchdownGold.copy(alpha = 0.2f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
              Text("BEST BALL", color = ext.touchdownGold, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
          }
        }
        Text(
          text = "Select your franchise (${pendingMeta.franchises.size} teams · ${pendingMeta.precision} decimal precision):",
          style = MaterialTheme.typography.labelSmall,
          color = ext.subtleText
        )

        Column(
          modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
            .verticalScroll(rememberScrollState())
            .padding(6.dp)
        ) {
          pendingMeta.franchises.forEach { f ->
            val picked = selectedFranchiseId == f.id
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onSelectFranchiseId(f.id) }
                .padding(horizontal = 6.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              RadioButton(selected = picked, onClick = { onSelectFranchiseId(f.id) })
              Text("${f.name} (#${f.id})", fontWeight = if (picked) FontWeight.Bold else FontWeight.Normal)
            }
          }
        }

        Button(
          onClick = { onConfirmAdd(pendingMeta, selectedFranchiseId) },
          enabled = selectedFranchiseId.isNotBlank(),
          modifier = Modifier.fillMaxWidth()
        ) {
          Text("Add League & Start Live Scoring")
        }
      }
    }
  }
}
