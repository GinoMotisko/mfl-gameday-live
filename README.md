# GameDay Live for Android

**Live Spoken NFL & MyFantasyLeague Broadcast Engine for Phones, Foldables & Tablets**

[![Google Play](https://img.shields.io/badge/Google_Play-GameDay_Live_(v1.2.3)-34a853?style=for-the-badge&logo=google-play&logoColor=white)](https://play.google.com/store/apps/details?id=com.motiskoventures.gameday)
[![Download Direct Pro APK](https://img.shields.io/badge/Direct_Pro_APK-v1.2.3_(Signed_Release)-d4ff78?style=for-the-badge&logo=android&logoColor=090f10)](https://github.com/GinoMotisko/mfl-gameday-live/releases/latest)
[![Product Showcase](https://img.shields.io/badge/Showcase_&_QR_Install-motisko.com%2Fgameday-91e3db?style=for-the-badge)](https://www.motisko.com/gameday)

Built by **[Gino Motisko](https://www.motisko.com)** (`Motisko Ventures LLC` · `Applied AI & Practice Leadership`). Inspired by MyFantasyLeague's classic 15-year Windows `GameDay` desktop announcer and [`darcuri/gameannouncer`](https://github.com/darcuri/gameannouncer), re-architected from the ground up in **Kotlin + Jetpack Compose** for modern Android 12–16+ devices (**Samsung Galaxy Tab S10+ / S7**, **Google Pixel Fold**, compact 8" tablets, and Android phones).

---

## No Fantasy League Required — Built for Sunday Man Caves, Patios & Garages

You **do not** need a MyFantasyLeague team or any login to use GameDay Live:

- Tap **NFL Live Only (No Account)** on first launch, prop a tablet on the bar or connect your phone to a Bluetooth speaker, and let it run all Sunday.
- GameDay Live polls every active NFL game and **speaks real-time scoring plays, turnovers, sacks, big plays, and Red Zone trips out loud** (*"Touchdown, Tampa Bay Buccaneers! Baker Mayfield, 94 yard touchdown pass to Chris Godwin!"*) preceded by synthesized 16-bit PCM stadium brass, whistle, and radar stingers.
- Automatically **ducks background music or podcasts** (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) for 3 seconds while calling a play, then restores volume seamlessly — so you never have to stare at NFL RedZone just to know what happened across the slate.

---

## Install on Any Android Phone, Foldable, or Tablet

- **Google Play Store (Recommended — Automatic Updates)**: Install **[GameDay Live: Football Audio](https://play.google.com/store/apps/details?id=com.motiskoventures.gameday)** (`com.motiskoventures.gameday`) featuring original 3D claymation team mascots, 644+ OTA clay player busts, and pulsing Red Zone rings.
- **Direct Pro Edition APK (Optional Side-by-Side Power-User Build)**: Grab [`MFL-GameDay-Live.apk`](https://github.com/GinoMotisko/mfl-gameday-live/releases/latest/download/MFL-GameDay-Live.apk) (`com.mflgameday.live`, or scan the Pro APK QR code at **[motisko.com/gameday](https://www.motisko.com/gameday)**) for full 32-team shield logos and player headshots. Because the two editions use separate `applicationId` values, they can be installed side-by-side.

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

Runs pure engine unit tests, `MockWebServer` MFL + ESPN + OTA Claymation delta manifest integration tests, Play API pre-flight unit tests, secret-redaction & logo contract tests, Android Lint (`directDebug` + `playStoreRelease`), and builds both signed `app-direct-release.apk` and R8-shrunk `app-playStore-release.aab`.

Architecture and repository layout documentation live in [`docs/architecture_map.md`](docs/architecture_map.md) and [`docs/project_structure.md`](docs/project_structure.md).
