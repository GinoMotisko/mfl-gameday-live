# GameDay Live for Android

**Hands-Free Spoken Big-Play Score Announcer & Multi-League MyFantasyLeague Scoreboard for Phones, Foldables & Tablets**

[![Google Play](https://img.shields.io/badge/Google_Play-GameDay_Live_(v1.3.1)-34a853?style=for-the-badge&logo=google-play&logoColor=white)](https://play.google.com/store/apps/details?id=com.motiskoventures.gameday)
[![Download Direct Pro APK](https://img.shields.io/badge/Direct_Pro_APK-v1.3.1_(Signed_Release)-d4ff78?style=for-the-badge&logo=android&logoColor=090f10)](https://github.com/GinoMotisko/mfl-gameday-live/releases/latest)
[![Product Showcase](https://img.shields.io/badge/Showcase_&_QR_Install-motisko.com%2Fgameday-91e3db?style=for-the-badge)](https://www.motisko.com/gameday)

Built by **[Gino Motisko](https://www.motisko.com)** (`Motisko Ventures LLC` · `Applied AI & Practice Leadership`). Inspired by MyFantasyLeague's classic 15-year Windows `GameDay` desktop announcer and [`darcuri/gameannouncer`](https://github.com/darcuri/gameannouncer), re-architected from the ground up in **Kotlin + Jetpack Compose** for modern Android 12–16+ devices (**Samsung Galaxy Tab S10+ / S7**, **Google Pixel Fold**, compact 8" tablets, and Android phones).

---

## What GameDay Live Does (Zero-BS Elevator Pitch)

**Not live game radio** — GameDay Live is built for Sunday multitasking when you're grilling, hosting, driving, or tracking multiple fantasy leagues at once:

- **No Fantasy League Required**: Tap **NFL Live Only (No Account)** on first launch, connect to a Bluetooth speaker or prop a tablet on the counter, and let it watch the slate for you.
- **Spoken Big-Play Calls + Brass Stingers**: Monitors every active NFL game and **speaks real-time touchdowns, field goals, turnovers, sacks, explosive 25+ yard plays, Red Zone trips, and fantasy lead flips out loud** (*"Touchdown, Tampa Bay Buccaneers! Baker Mayfield, 94 yard touchdown pass to Chris Godwin!"*) preceded by synthesized 16-bit PCM stadium stingers.
- **3-Second Smart Music Ducking & Auto-Standby**: Smoothly ducks Spotify or podcasts (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) for 3 seconds while calling a play, restores volume automatically, and runs strictly during active NFL game windows (90 minutes before kickoff until games go `FINAL`) so you never get off-hours notification spam.

---

## Install on Any Android Phone, Foldable, or Tablet

- **Google Play Store (Recommended — Automatic Updates)**: Install **[GameDay Live: Score Announcer](https://play.google.com/store/apps/details?id=com.motiskoventures.gameday)** (`com.motiskoventures.gameday`) featuring original 3D claymation team mascots, 644+ OTA clay player busts, edge-to-edge Google-style linear football fields, and 5-state player status rings.
- **Direct Pro Edition APK (Optional Side-by-Side Power-User Build)**: Grab [`MFL-GameDay-Live.apk`](https://github.com/GinoMotisko/mfl-gameday-live/releases/latest/download/MFL-GameDay-Live.apk) (`com.mflgameday.live`, or scan the Pro APK QR code at **[motisko.com/gameday](https://www.motisko.com/gameday)**) for full 32-team shield logos and player headshots. Because the two editions use separate `applicationId` values, they can be installed side-by-side.

---

## Key Capabilities

- **4 Launch Modes**:
  1. **NFL Live Only (Zero Account)**: Instant Sunday scoreboard + spoken big-play calls across all 32 NFL teams.
  2. **MFL Account Sign-In**: Encrypted Android Keystore (`AES-256-GCM`) credential vault; discovers and polls multiple leagues simultaneously.
  3. **5-Digit League ID Lookup**: Track any public MyFantasyLeague league in seconds.
  4. **Interactive Sunday Simulator**: Built-in dual-league showcase (Standard PPR + Best Ball) with one-tap live play simulation.
- **6-Commentator Announcer Studio & Top Booth Pill**:
  - Audition and cast 6 distinct human commentators (**David**, **Red Zone Rex**, **Callie**, **Nigel 🇬🇧**, **Macca 🇦🇺**, and **Coach Vance**) mapped across distinct installed neural TTS voices (`VoiceCastingResolver`), plus a 1-tap Top Header **Booth Pill** (`🎙️ David · My Games ▾`) on every screen.
- **Edge-to-Edge Linear Football Field & 5-State Avatar Rings**:
  - Full-width **Google Pinned Pro** linear stadium field (`GooglePinnedLinearField`) on live games showing team-colored endzones, yellow goalposts, 10-yard lines, 1st-down marker, Red Zone shading, and directional ball puck — plus a compact micro-drive strip exclusively under active ball-carriers on Fantasy Matchups.
  - Clean 5-state clay/headshot ring system: **No Ring** when `FINAL`, muted hairline when `SCHEDULED`, solid team primary when `LIVE`, glowing **Championship Gold** with possession, and pulsing **Crimson Red** inside the Red Zone.
- **Multi-League, Best Ball & Conflict Intelligence**:
  - Automatically detects `bestLineup = Yes` Best Ball leagues, marking **`BUBBLE`** starters and **`CHASING`** bench players.
  - Flags **Cross-League Conflicts (`CONFLICT ⚡`)** when a player starts *for* you in one league and *against* you in another.
  - Real-time logistic **Win Probability** and remaining-clock **Pace Projections**, plus standard-league **Bench Swing Alerts**.
- **Adaptive Tablet, Foldable & Phone UI**:
  - 3-pane Command Center on wide tablets (**Galaxy Tab S10+**, **Tab S7**), hinge-safe 2-pane split on unfolded foldables (**Pixel Pro Fold**), and native 5-category Hub + Drill-In Settings on phones.
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
