# GameDay Live for Android

**Live Spoken NFL & MyFantasyLeague Broadcast Engine for Phones, Foldables & Tablets**

[![Download APK](https://img.shields.io/badge/Download_APK-v1.0.0_(Signed_Release)-d4ff78?style=for-the-badge&logo=android&logoColor=090f10)](https://github.com/GinoMotisko/mfl-gameday-live/releases/latest)
[![Product Showcase](https://img.shields.io/badge/Showcase_&_QR_Install-motisko.com%2Fgameday-91e3db?style=for-the-badge)](https://www.motisko.com/gameday)

Built by **[Gino Motisko](https://www.motisko.com)** (`Applied AI & Practice Leadership`). Inspired by MyFantasyLeague's classic 15-year Windows `GameDay` desktop announcer and [`darcuri/gameannouncer`](https://github.com/darcuri/gameannouncer), re-architected from the ground up in **Kotlin + Jetpack Compose** for modern Android 12L–17 devices (**Samsung Galaxy Tab S10+ / S7**, **Google Pixel Fold**, compact 8" tablets, and Android phones).

---

## No Fantasy League Required — Built for Sunday Man Caves, Patios & Garages

You **do not** need a MyFantasyLeague team or any login to use GameDay Live:

- Tap **NFL Live Only (No Account)** on first launch, prop a tablet on the bar or connect your phone to a Bluetooth speaker, and let it run all Sunday.
- GameDay Live polls every active NFL game and **speaks real-time scoring plays, turnovers, sacks, big plays, and Red Zone trips out loud** (*"Touchdown, Tampa Bay Buccaneers! Baker Mayfield, 94 yard touchdown pass to Chris Godwin!"*) preceded by synthesized 16-bit PCM stadium brass, whistle, and radar stingers.
- Automatically **ducks background music or podcasts** (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) for 3 seconds while calling a play, then restores volume seamlessly — so you never have to stare at NFL RedZone just to know what happened across the slate.

---

## Install on Any Android Phone or Tablet (30 Seconds)

1. **Download the Signed Release APK**: Grab [`MFL-GameDay-Live-v1.0.0.apk`](https://github.com/GinoMotisko/mfl-gameday-live/releases/latest/download/MFL-GameDay-Live-v1.0.0.apk) directly on your Android device (or scan the QR code at **[motisko.com/gameday](https://www.motisko.com/gameday)**).
2. **Allow Install Source (One-Time)**: Open the downloaded `.apk`. If Android prompts for *Install unknown apps*, toggle **Allow from this source** for your browser or Files app.
3. **Play Protect Prompt**: Because this open-source build is distributed directly via GitHub Releases outside the Play Store, if Google Play Protect displays *Unrecognized app developer*, tap **More details → Install anyway**. Every release is signed with the same permanent RSA-2048 release certificate so future updates install cleanly over the top without losing your settings.

---

## Key Capabilities

- **4 Launch Modes**:
  1. **NFL Live Only (Zero Account)**: Instant Sunday scoreboard + spoken play-by-play across all 32 NFL teams.
  2. **MFL Account Sign-In**: Encrypted Android Keystore (`AES-256-GCM`) credential vault; discovers and polls multiple leagues simultaneously.
  3. **5-Digit League ID Lookup**: Track any public MyFantasyLeague league in seconds.
  4. **Interactive Sunday Simulator**: Built-in dual-league showcase (Standard PPR + Best Ball) with one-tap live play simulation.
- **Voice Studio & Live Auditioner**: 4 classic broadcast personas (*Booth Play-by-Play (David)*, *Excited RedZone (Shouty)*, *Smooth Studio Host (Callie)*, and *Custom Rate & Pitch*) across all installed Google / Samsung neural TTS voices.
- **Multi-League, Best Ball & Conflict Intelligence**:
  - Automatically detects `bestLineup = Yes` Best Ball leagues, marking **`BUBBLE`** starters and **`CHASING`** bench players.
  - Flags **Cross-League Conflicts (`CONFLICT ⚡`)** when a player starts *for* you in one league and *against* you in another.
  - Real-time logistic **Win Probability** and remaining-clock **Pace Projections**, plus standard-league **Bench Swing Alerts**.
- **Adaptive Tablet, Foldable & Phone UI**:
  - 3-pane Command Center on wide tablets (**Galaxy Tab S10+**, **Tab S7**), 2-pane split on unfolded foldables (**Pixel Pro Fold**), and 1-hand bottom-nav layout on phones.
  - **Auto Dark / Light (`SYSTEM_AUTO`)** + 5 broadcast themes (*Stadium PrimeTime*, *Sunday Daylight*, *Cyber Gridiron OLED*, *Classic GameDay Gold*, *RedZone Octane*) with 64MB disk-cached official NFL logos, player headshots, and animated MFL franchise GIFs.
- **Hardened Android 12L–17 Runtime**:
  - `mediaPlayback` Foreground Service (`GameDayLiveService`) + `PARTIAL_WAKE_LOCK` + `FLAG_KEEP_SCREEN_ON` stadium mode.
  - `ConnectivityManager.NetworkCallback` auto-recovery, HTTP 429 rate-limit backoff, self-healing TTS rebinding, and a built-in **Diagnostic & Crash Console** (`DiagnosticsLogger`) with automatic `<REDACTED>` credential scrubbing.

---

## Build & Verify From Source

Requires JDK 17+ (JDK 21 recommended) and Android SDK 36:

```cmd
scripts\verify.cmd
```

Runs pure engine unit tests, `MockWebServer` MFL + ESPN pipeline integration tests, secret-redaction & logo contract tests, Android Lint, and builds both `app-debug.apk` and R8-shrunk `app-release.apk`.

Architecture and repository layout documentation live in [`docs/architecture_map.md`](docs/architecture_map.md) and [`docs/project_structure.md`](docs/project_structure.md).
