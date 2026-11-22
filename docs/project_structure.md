# Project Structure — MFL GameDay Live (Android)

## Repository Layout

```text
MFL-GameDay-Android-app/
├── app/
│   ├── build.gradle.kts                 # Android app config (minSdk 31, targetSdk 36, Compose, OkHttp, Serialization, Coil)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml      # Permissions (FGS_MEDIA_PLAYBACK, WAKE_LOCK, POST_NOTIFICATIONS), TTS <queries>
│       │   ├── kotlin/com/mflgameday/live/
│       │   │   ├── MainActivity.kt      # Edge-to-edge Compose host, FLAG_KEEP_SCREEN_ON, notification permission flow
│       │   │   ├── MflGameDayApp.kt     # Application entrypoint & GIF-capable Coil ImageLoader
│       │   │   ├── audio/
│       │   │   │   ├── GameDayVoiceEngine.kt         # Multi-voice Android TTS queue, personas & audio ducking
│       │   │   │   └── StadiumSoundSynthesizer.kt    # Pure 16-bit PCM stadium brass/radar/whistle stingers
│       │   │   ├── data/
│       │   │   │   ├── EspnApiClient.kt              # Real-time NFL scoreboard, team logos, situation & play-by-play
│       │   │   │   ├── GameDayStorage.kt             # Android Keystore AES-GCM credential vault & self-healing cache
│       │   │   │   ├── LenientJson.kt                # Polymorphic OneOrMany & lenient MFL wire decoders
│       │   │   │   ├── MflApiClient.kt               # Throttled/retry-hardened MFL login, myleagues, league, liveScoring
│       │   │   │   └── ScoringBreakdownParser.kt     # HTML parser for MFL live_scoring_summary category points
│       │   │   ├── diagnostics/
│       │   │   │   └── DiagnosticsLogger.kt          # Uncaught crash logger, secret-redacted ring buffer & share/copy export
│       │   │   ├── engine/
│       │   │   │   ├── BestBallAndPaceCalculator.kt  # Win probability, pace projections, Best Ball & Cross-League Exposure
│       │   │   │   ├── DemoSimulatorData.kt          # Dual-league showcase dataset + live burst play generator
│       │   │   │   ├── EventDiffer.kt                # Deterministic NFL & Fantasy event state machine
│       │   │   │   ├── PlayerNameExpander.kt         # Expands abbreviated ESPN names via MFL + IDP player database
│       │   │   │   └── PlaySpeechFormatter.kt        # Natural broadcast play-by-play speech generator
│       │   │   ├── model/
│       │   │   │   ├── FantasyModels.kt              # LeagueConfig, FantasyMatchup, PlayerLiveScore, ExposureRole
│       │   │   │   ├── NflModels.kt                  # NflTeam (32 teams + colors), NflGameState, NflPlay, GameEvent
│       │   │   │   └── SettingsModels.kt             # AppThemePreset (Auto Dark/Light + 5 themes), VoicePersona, GameDaySettings
│       │   │   ├── service/
│       │   │   │   ├── GameDayCoordinator.kt         # Application-scoped reactive polling & state orchestrator
│       │   │   │   ├── GameDayLiveService.kt         # Uninterrupted background Foreground Service + swipe-close shutdown
│       │   │   │   └── ServiceLifecyclePolicy.kt     # Pure lifecycle policy (Home background vs Recents swipe-close)
│       │   │   └── ui/
│       │   │       ├── GameDayAppShell.kt            # Responsive NavBar / NavRail / 3-Pane Command Center shell
│       │   │       ├── components/
│       │   │       │   ├── BroadcastComponents.kt    # 52dp unobstructed headshots, 48dp team shields, celebration banner
│       │   │       │   └── QuickTourDialog.kt        # 4-step interactive first-open wizard & anytime ? help guide
│       │   │       ├── screens/
│       │   │       │   ├── FantasyMatchupsScreen.kt  # Multi-league split scoreboard, Best Ball & starter breakdowns
│       │   │       │   ├── NflGamesScreen.kt         # Adaptive 1/2/3-col NFL grid, RedZone pulse, Game Center modal
│       │   │       │   ├── PlayFeedScreen.kt         # Filterable play-by-play log + one-tap speak & simulator
│       │   │       │   ├── SettingsAndVoicesScreen.kt# Voice auditioner, 5 themes, event ticks, schedule refresh, OS readiness
│       │   │       │   └── SetupAndAuthScreen.kt     # MFL Login, League ID lookup, NFL-Only mode & Demo Simulator
│       │   │       └── theme/
│       │   │           ├── Color.kt
│       │   │           ├── Theme.kt                  # 5 animated broadcast color palettes
│       │   │           └── Type.kt
│       │   └── res/                                  # Adaptive vector football/soundwave icon, strings, XML rules
│       ├── test/kotlin/com/mflgameday/live/
│       │   ├── data/MflAndEspnPipelineIntegrationTest.kt # MockWebServer MFL + ESPN + HTML breakdown pipeline tests
│       │   ├── diagnostics/DiagnosticsAndLogoTest.kt     # Secret redaction & 32-team NFL logo / headshot tests
│       │   └── engine/EventDifferAndSpeechTest.kt        # Pure unit tests for EventDiffer, Speech, Best Ball & Conflicts
│       └── androidTest/kotlin/com/mflgameday/live/
│           └── ui/GameDayUiSmokeTest.kt              # Instrumented Compose smoke test
├── docs/
│   ├── architecture_map.md              # Living architecture reference
│   ├── project_structure.md             # Living folder & command reference
│   └── plans/
│       └── 2026-11-22-mfl-gameday-android.md
├── gradle/
│   └── libs.versions.toml               # Version catalog
├── scripts/
│   └── verify.cmd                       # Single local/CI verification entrypoint
└── README.md
