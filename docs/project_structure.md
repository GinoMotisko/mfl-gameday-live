# Project Structure — MFL GameDay Live (Android)

## Repository Layout

```text
MFL-GameDay-Android-app/
├── .github/
│   └── workflows/
│       └── ci.yml                       # Keyless GitHub Actions CI (unit, MockWebServer, lintVital, R8 & Play gates)
├── app/
│   ├── build.gradle.kts                 # Android app config (minSdk 31, targetSdk 36, Compose, OkHttp, Serialization, Coil)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml      # Permissions (FGS_MEDIA_PLAYBACK, WAKE_LOCK, POST_NOTIFICATIONS), TTS <queries>
│       │   ├── kotlin/com/mflgameday/live/
│       │   │   ├── MainActivity.kt      # Edge-to-edge Compose host, FLAG_KEEP_SCREEN_ON, notification permission flow
│       │   │   ├── MflGameDayApp.kt     # Application entrypoint & GIF-capable Coil ImageLoader
│       │   │   ├── audio/
│       │   │   │   ├── GameDayVoiceEngine.kt         # Multi-voice Android TTS queue, personas, default-voice reset & audio ducking
│       │   │   │   ├── StadiumSoundSynthesizer.kt    # Pure 16-bit PCM stadium brass/radar/whistle stingers
│       │   │   │   └── VoiceCastingResolver.kt       # Deterministic bipartite neural voice casting for 6 distinct commentators
│       │   │   ├── data/
│       │   │   │   ├── ClayPackManager.kt            # ETag conditional delta manifest sync & O(1) head URL resolver
│       │   │   │   ├── EspnApiClient.kt              # Real-time NFL scoreboard, team logos, situation & play-by-play
│       │   │   │   ├── GameDayStorage.kt             # Android Keystore AES-GCM credential vault & self-healing cache
│       │   │   │   ├── LenientJson.kt                # Polymorphic OneOrMany & lenient MFL wire decoders
│       │   │   │   ├── MflApiClient.kt               # Throttled/retry-hardened MFL login, myleagues, league, liveScoring
│       │   │   │   └── ScoringBreakdownParser.kt     # HTML parser for MFL live_scoring_summary category points
│       │   │   ├── diagnostics/
│       │   │   │   ├── DiagnosticsLogger.kt          # Uncaught crash logger, secret-redacted ring buffer & share/copy export
│       │   │   │   └── GameDayTelemetry.kt           # Non-PII feed latency, poll health & opt-in anonymous telemetry reporter
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
│       │   │   │   ├── GameDayCoordinator.kt         # Application-scoped reactive polling, game-window auto-standby & state orchestrator
│       │   │   │   ├── GameDayLiveService.kt         # Game-window Foreground Service, NotificationDismissReceiver & deduplicated shade controls
│       │   │   │   └── ServiceLifecyclePolicy.kt     # Pure lifecycle policy (90m game-window gating, shade dedupe, Recents swipe-close & week rollover)
│       │   │   └── ui/
│       │   │       ├── GameDayAppShell.kt            # Responsive NavBar / NavRail / 3-Pane shell + Top Header Booth Pill
│       │   │       ├── components/
│       │   │       │   ├── BroadcastComponents.kt    # 3D clay avatars, 5-state rings, GooglePinnedLinearField & celebration banner
│       │   │       │   ├── ClayMascotResources.kt    # R8-safe 32 clay mascot & 6 clay base-layer drawable registry
│       │   │       │   └── QuickTourDialog.kt        # 4-step interactive first-open wizard with live commentator audition
│       │   │       ├── screens/
│       │   │       │   ├── FantasyMatchupsScreen.kt  # Multi-league split scoreboard, Best Ball, 5-state rings & micro-drive strip
│       │   │       │   ├── NflGamesScreen.kt         # Adaptive 1/2/3-col NFL grid, GooglePinnedLinearField & Game Center modal
│       │   │       │   ├── PlayFeedScreen.kt         # Filterable play-by-play log + one-tap speak & simulator
│       │   │       │   ├── SettingsAndVoicesScreen.kt# Multi-device adaptive 5-category Settings Hub (Phone / Foldable / Tablet)
│       │   │       │   ├── SetupAndAuthScreen.kt     # Zero-BS elevator pitch hero, MFL Login, League ID lookup & Demo Simulator
│       │   │       │   └── settings/
│       │   │       │       ├── SettingsLeaguesLookAndHelpPanes.kt # Leagues, Look/Couch Mode & Help/Diagnostics panes
│       │   │       │       ├── SettingsSharedComponents.kt        # Category cards, CommentatorStudioCard & QuickBoothBottomSheet
│       │   │       │       └── SettingsVoiceAndPlaysPanes.kt      # 6-Commentator Announcer Studio & Broadcast Presets panes
│       │   │       └── theme/
│       │   │           ├── Color.kt
│       │   │           ├── Theme.kt                  # 5 animated broadcast color palettes
│       │   │           └── Type.kt
│       │   └── res/                                  # Adaptive vector football/soundwave icon, strings, XML rules
│       ├── playStore/                                # Google Play flavor overrides (AndroidManifest.xml, network_security_config.xml)
│       ├── test/kotlin/com/mflgameday/live/
│       │   ├── data/ClayPackAndPositionsTest.kt          # 25-position MFL taxonomy & MockWebServer ETag delta manifest tests
│       │   ├── data/MflAndEspnPipelineIntegrationTest.kt # MockWebServer MFL + ESPN + HTML breakdown pipeline tests
│       │   ├── diagnostics/DiagnosticsAndLogoTest.kt     # Secret redaction & 32-team NFL logo / headshot tests
│       │   ├── engine/EventDifferAndSpeechTest.kt        # Pure unit tests for EventDiffer, Speech, Best Ball & Conflicts
│       │   ├── service/ServiceLifecyclePolicyTest.kt     # Home-background vs Recents swipe-close & projection pace tests
│       │   └── ui/VoiceAndFieldVisualPolicyTest.kt       # Unit tests for 6-commentator casting, 5-state rings & linear field geometry
│       └── androidTest/kotlin/com/mflgameday/live/
│           └── ui/GameDayUiSmokeTest.kt              # Instrumented Compose smoke test
├── docs/
│   ├── architecture_map.md              # Living architecture reference
│   ├── project_structure.md             # Living folder & command reference
│   ├── PLAY_STORE_SUBMISSION_PACK.md    # Google Play ASO copy, Data Safety & FGS submission guide
│   └── plans/
│       └── 2026-11-22-mfl-gameday-android.md
├── gradle/
│   └── libs.versions.toml               # Version catalog
├── scripts/
│   ├── verify.cmd                       # Single Windows verification entrypoint (+ Play API gate tests)
│   ├── verify.sh                        # Single Linux/macOS/CI verification entrypoint (parity with verify.cmd)
│   ├── play-publish.mjs                 # Zero-dependency Google Play API v3 + Reporting CLI (1Password GameDay vault + DPAPI)
│   ├── test-play-publish.mjs            # Automated unit tests for 24-bit RGB, AAB SHA-256, review-queue lockout & AppEdit gates
│   ├── build-play-store-assets.mjs      # Repeatable 24-bit RGB Play Store graphic & screenshot compositor
│   └── sync-claymation-pack.mjs         # Self-healing MFL starter discovery + 4x4 Gemini clay head batch generator
├── AGENTS.md                            # Engineering, dual-flavor, signing & release lockstep guide
└── README.md
```
