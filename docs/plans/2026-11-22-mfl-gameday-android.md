# MFL GameDay Live (Android) Implementation Plan

> **For Antigravity:** Autonomous multi-layer implementation plan for porting and extending Windows MFL GameDay & GameAnnouncer to a Play Store-caliber Android app.

**Goal:** Build a rock-solid, colorful, responsive Android APK (`minSdk = 31`, `targetSdk = 36`) for Phones, Pixel 11 Pro Fold (folded & unfolded), Samsung Galaxy Tab S7 Tablets, and Desktop/Freeform windows that delivers real-time MFL multi-league scoring, live NFL play-by-play, Best Ball & dynamic starter intelligence, and multi-voice spoken play-by-play announcements uninterrupted in foreground or background.

**Architecture:** Clean reactive Kotlin + Jetpack Compose (Material 3 Adaptive / WindowSizeClass) architecture backed by a singleton `GameDayEngine` state machine and a `mediaPlayback` Foreground Service (`GameDayLiveService`) with transient audio-ducking `TextToSpeech` + synthesized PCM stadium chimes. Data sources combine MyFantasyLeague's official Export/Login APIs (`login`, `myleagues`, `league`, `liveScoring`, `projectedScores`, `players`, `nfl_sched`, `live_scoring_summary`) with ESPN's real-time NFL scoreboard & play-by-play feed (`scoreboard`, `summary`), unified through a pure, deterministic `EventDiffer` and `PlaySpeechFormatter`.

**Tech Stack:** Kotlin 2.3, AGP 9.0, Jetpack Compose (BOM 2026.03), Material 3 + WindowSizeClass, Coroutines & StateFlow, `kotlinx.serialization`, OkHttp 4.12, Coil (with GIF support for MFL helmet/team logos), Android `TextToSpeech` + `AudioTrack` PCM Chime Synthesizer, Android Keystore AES-GCM credential encryption, JUnit 4 + `kotlinx-coroutines-test` + `MockWebServer` + Compose UI / Android CLI Journey verification.

---

## Architectural Modules & File Map

1. **Core Domain & Models (`com.mflgameday.live.model`)**
   - `NflModels.kt`: `NflTeam` (32 NFL teams with official MFL<->ESPN code mapping, primary/secondary broadcast hex colors, full location + nickname), `GamePhase`, `GameSituation` (`downDistanceText`, `possessionText`, `yardLine`, `isRedZone`, `isLiveDown`), `NflGameState`, `PlayKind`, `NflPlay`, `GameEvent`, `SpokenCategory`.
   - `FantasyModels.kt`: `LeagueConfig` (id, name, year, baseURL, franchiseId, apiKey, isBestBall, precision, starterCount, positionLimits, enabledForAudio), `FantasyPlayer`, `PlayerDatabase`, `PlayerNameExpander`, `PlayerLiveScore` (starter/nonstarter, optimal/bubble flag, score, projectedRemaining, gameSecondsRemaining, categoryBreakdowns), `FranchiseMatchupSide`, `FantasyMatchup`, `LeagueLiveSnapshot`, `CrossLeagueExposure` (`MINE`, `OPP`, `CONFLICT`).
   - `SettingsModels.kt`: `AppThemePreset` (`STADIUM_PRIMETIME`, `SUNDAY_DAYLIGHT`, `CYBER_GRIDIRON_OLED`, `RETRO_GAMEDAY_GOLD`, `REDZONE_BLITZ`), `AnnouncementScope` (`ALL_NFL`, `MY_GAMES`, `MY_PLAYERS`), `VoicePersona` (`PLAY_BY_PLAY`, `EXCITED_SHOUTY`, `CALM_STUDIO`, `CUSTOM`), `GameDaySettings` (all Windows `Gameday.ini` ticks + modern Android enhancements).

2. **API Clients, Parsing & Security (`com.mflgameday.live.data`)**
   - `LenientJson.kt`: `OneOrManySerializer<T>`, lenient String/Int/Double decoders for MFL's polymorphic XML-to-JSON wire quirks.
   - `MflApiClient.kt`: Rate-limited (1.1s spacing + HTTP 429 60s backoff), host-aware (`wwwNN.myfantasyleague.com`) client supporting:
     - `login(username, password)` -> HTTPS POST `/{year}/login` (`XML=1`), extracting `MFL_USER_ID` from both XML `<status MFL_USER_ID="..."/>` and `Set-Cookie`.
     - `fetchMyLeagues(mflUserId)` -> `export?TYPE=myleagues&FRANCHISE_NAMES=1&JSON=1`.
     - `fetchStatus()` -> `fflnetdynamic{year}/mfl_status.json`.
     - `fetchLeague(leagueId, apiKey, cookie)` -> `export?TYPE=league&L=...&JSON=1` (detects `bestLineup`, `precision`, `starters`, `franchises`, `baseURL`).
     - `fetchLiveScoring(leagueConfig, week)` -> `export?TYPE=liveScoring&L=...&W=...&DETAILS=1&JSON=1`.
     - `fetchProjectedScores(leagueConfig, week)` -> `export?TYPE=projectedScores&L=...&W=...&JSON=1`.
     - `fetchScoringBreakdowns(leagueConfig, week)` -> HTML parser for `{baseURL}/{year}/live_scoring_summary?L=...&W=...`.
     - `fetchPlayers(year)` -> `export?TYPE=players&DETAILS=1&JSON=1` (24-hour disk cache + manual refresh).
     - `fetchMflNflSchedule(year, week, etag)` -> `fflnetdynamic{year}/nfl_sched_{week}.json` (ETag conditional GET + fallback when ESPN unavailable + explicit "Refresh NFL Schedule" action).
   - `EspnApiClient.kt`: `fetchScoreboard()` and parallel `fetchSummary(eventId)` with rich `situation` (`downDistanceText`, `yardLine`, `possessionText`, `lastPlay`), team records, and deduplicated/sorted drive plays.
   - `CredentialVault.kt` & `PreferencesStore.kt`: Hardware-backed Android Keystore `AES/GCM/NoPadding` encryption for `MFL_USER_ID` cookie and per-league `APIKEY`s; JSON state persistence for multi-league roster, settings, cached players, and weekly play log.

3. **Pure Event Engine, Best-Ball Intelligence & Speech Formatter (`com.mflgameday.live.engine`)**
   - `EventDiffer.kt`: Pure deterministic state machine detecting Kickoff, Halftime, Overtime, Final, Touchdowns (with pending PAT/2pt resolution), Field Goals, Safeties, Sacks, Interceptions, Fumbles Lost (handling `REVERSED.` replay calls), First Downs / Fourth Down Stops / Big Plays (20+ yds), Red Zone entries (ignoring PAT/kickoff non-downs and possession blinks), Score Corrections, and Fantasy Matchup Lead Changes!
   - `PlaySpeechFormatter.kt`: Natural broadcast play-by-play generator expanding abbreviated names (`B.Mayfield` -> `Baker Mayfield`), handling offensive TDs, Pick-Sixes, Fumble Returns, Kick/Punt/Blocked FG Returns, Sacks with yardage & split tacklers, Turnovers, Red Zone calls, and optional Cross-League Fantasy Lead/Impact callouts.
   - `BestBallAndPaceCalculator.kt`:
     - Computes Live Projected Final Score & Win Probability (`0%..100%`) using live score + remaining game clock fraction * pre-game projection.
     - Best Ball (`bestLineup == true`) optimal lineup highlighter, "Bubble Starter" indicator, and Bench Contender alerts; Standard league "Bench Outscoring Starter" detector; Cross-League Player Exposure (`MINE` / `OPP` / `CONFLICT`) mapper.
   - `DemoSimulatorData.kt`: Rich multi-league showcase dataset (1 Standard PPR league + 1 Best Ball league + 10 NFL games) AND an interactive **Live Play Injector** that fires realistic plays (including *"Touchdown Tampa Bay Buccaneers! Chris Godwin 94 yard pass from Baker Mayfield!"*, strip-sacks, pick-sixes, red-zone trips, and Best Ball lead changes) on demand!

4. **Android Audio, Voices & Background Service (`com.mflgameday.live.audio` & `.service`)**
   - `StadiumSoundSynthesizer.kt`: Pure Kotlin 16-bit PCM `AudioTrack` synthesizer generating crisp broadcast stingers (Touchdown Brass Chord, RedZone Pulse Radar, Turnover Whistle, Lead-Change Chime) with zero external audio copyright dependencies.
   - `GameDayAnnouncer.kt`: Wraps `TextToSpeech` with `AudioFocusRequest` (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`), discovers all English system/neural voices on device, applies `VoicePersona` pitch/rate profiles, queues priority utterances, exposes live `isSpeaking` state + `lastSpokenText` ("Replay Last Call"), and allows speaking any play or voice sample immediately.
   - `GameDayLiveService.kt`: Android 14–17 compliant `LifecycleService` (`foregroundServiceType="mediaPlayback"`) with ongoing live-score notification, Mute/Unmute & Refresh & Stop notification actions, and adaptive polling scheduler (30s live / smart pre-kickoff sleep).

5. **Responsive Multi-Theme Compose UI (`com.mflgameday.live.ui`)**
   - `Theme.kt`: 5 full broadcast color schemes (`Stadium PrimeTime`, `Sunday Daylight`, `Cyber Gridiron OLED`, `Classic MFL Retro Gold`, `RedZone Blitz`) with smooth animated transitions.
   - Adaptive Shell (`GameDayAppShell.kt`):
     - **Compact (`< 600dp` Phone / Fold Cover)**: Top Broadcast Bar + League Switcher + Bottom Navigation Bar.
     - **Medium (`600..839dp` Pixel 11 Pro Fold Unfolded Portrait / Small Tablet)**: Navigation Rail + 2-column grids & split matchup cards.
     - **Expanded / Ultra-Wide (`>= 840dp` & `>= 1200dp` Pixel Fold Landscape, Samsung Tab S7, Desktop)**: Navigation Rail + Master-Detail Split Scoreboard + optional **"Command Center" 3-Pane Broadcast View** (Matchups + Live NFL RedZone Grid + Live Spoken Play Feed side-by-side!).
   - Screens:
     - `OnboardingAndAuthScreen.kt`: MFL Account Login (`USERNAME`/`PASSWORD` -> `myleagues` multi-select), Add by League ID (with quick-fill support), **NFL Live Only Mode (No Account Needed)**, and **Interactive Sunday Simulator**.
     - `FantasyMatchupsScreen.kt`: Multi-league switcher, Win Probability bar, Playing / Yet-to-Play / Final counters, Game-Clock progress ring, Best Ball Optimal vs Contenders view, Scoring Category Breakdowns, Bench Swing alerts.
     - `NflGamesScreen.kt`: Live/Upcoming/Final filter, Red Zone pulsing cards, Possession footballs, `2nd & Goal at MIA 4` + visual 100-yard field-position bar, Cross-League Rooting badges (`MINE`, `OPP`, `CONFLICT ⚡`), tap-to-open Game Center modal (recent plays & scoring summary), and one-tap **Refresh NFL Schedule**.
     - `PlayFeedScreen.kt`: Filterable live log (All Plays, Scores, My Leagues, Red Zone, Turnovers/Sacks), spoken badge, tap-any-play to speak out loud, and Live Simulator Play Trigger bar when in Demo/Test mode!
     - `SettingsAndVoicesScreen.kt`: Theme gallery (5 themes), Voice Persona + System Voice picker with multi-sample audition buttons (Baker Mayfield 94-yd TD, Strip-Sack, Red Zone), granular Windows `Gameday.ini` event tick checkboxes, Polling & Refresh NFL Schedule controls, Keep Screen On toggle, Background Service toggle, and Android Permissions Readiness panel.
