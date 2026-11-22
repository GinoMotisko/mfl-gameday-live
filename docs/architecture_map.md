# Architecture Map — MFL GameDay Live (Android)

## Overview

MFL GameDay Live is a native Kotlin + Jetpack Compose Android application (`minSdk = 31`, `targetSdk = 36`, `compileSdk = 36`) that ports and extends MyFantasyLeague's classic Windows `Gameday.exe` desktop client and `darcuri/gameannouncer` into a multi-league, multi-form-factor mobile, foldable, tablet, and desktop broadcast client.

## Runtime Entrypoints

1. **`MflGameDayApp` (`Application`)**: Initializes the singleton `GameDayCoordinator` and configures `Coil` with `ImageDecoderDecoder` for native animated GIF rendering (supporting MFL commissioner/franchise helmet GIFs).
2. **`MainActivity` (`ComponentActivity`)**: Edge-to-edge Compose host observing `GameDayCoordinator.uiState`, managing `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` for uninterrupted tablet/couch viewing, and requesting `POST_NOTIFICATIONS` on Android 13+.
3. **`GameDayLiveService` (`LifecycleService`, `foregroundServiceType="mediaPlayback"`) + `ServiceLifecyclePolicy`**: Android 14–17 compliant Foreground Service with `PARTIAL_WAKE_LOCK` and persistent notification controls (`Mute/Unmute`, `Refresh`, `Stop`) so live polling and spoken TTS announcements run uninterrupted while the screen is off or in background apps, while cleanly stopping speech & polling when the user swipes the app closed from Recent Apps (`onTaskRemoved`) and automatically resuming on next launch (`MainActivity.onStart`).

## Component & Data Flow

```mermaid
flowchart TD
    subgraph Providers["External Live Feeds"]
        MFL["MyFantasyLeague API\nlogin · myleagues · league · liveScoring\nprojectedScores · players · nfl_sched\nlive_scoring_summary"]
        ESPN["ESPN Site API\nscoreboard · summary (drives & scoringPlays)"]
    end

    subgraph DataLayer["Data & Security Layer (com.mflgameday.live.data)"]
        MflClient["MflApiClient\n1.1s rate spacing · HTTP 429 60s backoff\nETag conditional GET · LenientJson"]
        EspnClient["EspnApiClient\nSituation & Field Progress · Play Dedupe"]
        BreakdownParser["ScoringBreakdownParser\nHTML entity + category parser"]
        Storage["GameDayStorage\nAndroid Keystore AES-256-GCM Vault\n24h Player Cache + Play Log JSON"]
    end

    subgraph EngineLayer["Pure Domain Engine (com.mflgameday.live.engine)"]
        Differ["EventDiffer\nScoreboard + Play + Pending-Try\n+ Fantasy Lead Flip State Machine"]
        Pace["BestBallAndPaceCalculator\nLive Win Prob · Pace Projections\nBest Ball Bubble/Chasers · Conflict ⚡"]
        Formatter["PlaySpeechFormatter +\nPlayerNameExpander"]
    end

    subgraph Runtime["Service, Audio & UI"]
        Coord["GameDayCoordinator\nApplication-Scoped StateFlow +\nConnectivity Auto-Recovery"]
        Diag["DiagnosticsLogger\nChained Crash Catcher +\nRedacted Ring-Buffer Console"]
        Voice["GameDayVoiceEngine + StadiumSoundSynthesizer\nAndroid TextToSpeech · Self-Healing Rebind\n16-bit PCM Broadcast Stingers"]
        FGS["GameDayLiveService\nmediaPlayback Foreground Service"]
        UI["Jetpack Compose Adaptive Shell\nTab S10+ · All Tablets · Pixel Fold · Phone\nAuto Dark/Light + 5 Broadcast Themes\nOfficial NFL Logos & Player Headshots"]
    end

    MFL --> MflClient
    MFL --> BreakdownParser
    ESPN --> EspnClient
    MflClient --> Coord
    BreakdownParser --> Coord
    EspnClient --> Coord
    Storage <--> Coord
    Coord --> Differ
    Coord --> Pace
    Differ --> Formatter
    Formatter --> Voice
    Coord <--> FGS
    Coord --> UI
```

## Key Technical Decisions

- **`mediaPlayback` Foreground Service + Transient Audio Ducking**: Android 15–17 background audio hardening suppresses background audio unless backed by a While-In-Use `mediaPlayback` foreground service. `GameDayVoiceEngine` requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` only while speaking an utterance so background music/podcasts duck cleanly and resume immediately.
- **Manifest `<queries>` for `TTS_SERVICE`**: Required on Android 11+ package visibility rules so `TextToSpeech` binds to Google Speech Services and Samsung TTS engines reliably.
- **Best Ball (`bestLineup = Yes`) & Multi-League Conflict Intelligence**: Automatically detects Best Ball leagues from MFL `league` metadata, tracks optimal bubble starters vs live bench chasers, respects per-league decimal `precision`, and flags players started on your team in League A while facing you in League B (`CONFLICT ⚡`).
- **Dual Distribution Flavors (`direct` vs `playStore`)**: `direct` (`com.mflgameday.live`) builds the full-featured GitHub Release APK; `playStore` (`com.motiskoventures.gameday`) produces the Google Play `.aab` bundle with IP-safe vector monograms, scoped `network_security_config.xml`, and Play-Store-compliant manifest permissions.
