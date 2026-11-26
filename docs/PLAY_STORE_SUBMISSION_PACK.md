# Google Play Console Submission Pack — GameDay Live (`v1.3.1` / `versionCode = 11`)

Prepared for **Motisko Ventures LLC** (Google Play Developer Account ID `7669036577809025288`, D-U-N-S `14-685-6313`).
Every field below is audited against Google Play's 2026 Impersonation/IP, Metadata, Data Safety, and Android 14–16 (`targetSdk = 36`) Foreground Service policies.

---

## 1. Upload Bundle & Graphics Inventory (`24-bit RGB` Verified)

All uploadable artifacts are staged in both `dist/play-store-kit/` and `OneDrive/Motisko Ventures LLC/Google-Play-Console-Kit/2-App-Bundle-And-Store-Graphics/`:

1. **Signed Android App Bundle (`.aab`)**: `GameDay-Live-PlayStore-v1.3.1.aab`
   - `applicationId`: `com.motiskoventures.gameday`
   - `versionName`: `1.3.1` (`versionCode = 11`)
   - Signing Key SHA-256: `4ffddda61399a4b04cf9a530ad68727f702d6dbbf6754107500265a7e2e350eb`
2. **R8 Deobfuscation Mapping File**: `mapping-playStoreRelease-v1.3.1.txt`
   - Upload alongside the `.aab` under *App bundle explorer -> Downloads / Reobfuscation file* so Android Vitals stack traces show exact Kotlin line numbers.
3. **Hi-Res App Icon (`512 × 512` PNG)**: `icon-512x512.png`
4. **Feature Graphic (`1024 × 500`, 24-bit RGB PNG, no alpha)**: `feature-graphic-1024x500.png`
5. **Phone Screenshots (`6 × 1080 × 1920`, `9:16` 24-bit RGB PNG)**: `screenshots-phone/01-*.png` .. `06-*.png`
6. **Foldable Screenshots (`4` `24-bit RGB` PNGs: `1:1` `2048 × 2048`, `4:3` `2048 × 1536`, `10:16` `1200 × 1920`)**: `screenshots-foldable/01-*.png` .. `04-*.png`
7. **7-Inch Tablet Screenshots (`4 × 1920 × 1080`, `16:9` 24-bit RGB PNG)**: `screenshots-tablet-7in/01-*.png` .. `04-*.png`
8. **10-Inch Tablet Screenshots (`4 × 2560 × 1440`, `16:9` 24-bit RGB PNG)**: `screenshots-tablet-10in/01-*.png` .. `04-*.png`

---

## 2. Store Listing Copy (ASO-Optimized & 100% Trademark-Safe)

- **App Name (`29 / 30` chars)**:
  `GameDay Live: Score Announcer`
- **Short Description (`79 / 80` chars)**:
  `Spoken pro football big-play alerts, Red Zone calls & multi-league fantasy app.`
- **Full Description (`~3,240 / 4,000` chars)**:
  ```text
  Not live game radio — GameDay Live is your hands-free Sunday big-play score announcer and multi-league fantasy football scoreboard.

  Built for grilling, driving, hosting, or tracking multiple leagues on the couch, GameDay Live watches active pro football games and speaks touchdowns, field goals, turnovers, sacks, explosive 25+ yard plays, Red Zone trips, and fantasy lead changes out loud over your music in 3 seconds — even with your phone screen off.

  HANDS-FREE BIG-PLAY ANNOUNCER — NO ACCOUNT REQUIRED
  • Launch "Live Scores Only" in one tap — zero registration, zero subscription, and zero ads.
  • 6 Distinct Commentators: Pick David (Network Lead), Red Zone Rex (Octane Hype), Callie (Studio Host), Nigel (London Booth), Macca (Aussie Sideline), or Coach Vance (Film Room) from the 1-tap Top Booth Pill or Announcer Studio.
  • Stadium Brass & Whistle Stingers precede each spoken call, while Smart Audio Ducking lowers Spotify or podcasts for 3 seconds and restores volume automatically.
  • Zero Off-Hours Spam: Runs strictly during active game windows (starting 90 minutes before kickoff) and enters silent standby automatically once games go Final.

  LIVE LINEAR FOOTBALL FIELD & 5-STATE PLAYER RINGS
  • Edge-to-Edge Stadium Field: Every live game card and Game Center displays team-colored endzones, goalposts, 10-yard markers, yellow 1st-down line, Red Zone shading, and directional ball position.
  • Glanceable 5-State Clay Avatar Rings: Championship Gold glow when your player's offense has the ball, pulsing Crimson Red inside the 20-yard Red Zone, solid team ring during live games, muted hairline before kickoff, and clean no-ring once Final.

  MULTI-LEAGUE FANTASY BRAIN (COMPATIBLE WITH MYFANTASYLEAGUE)
  • Connect an existing MyFantasyLeague (MFL) session or enter any public 5-digit League ID — credentials stay encrypted locally on your device using Android Keystore AES-256-GCM.
  • Track Standard PPR, Doubleheaders, and Best Ball (optimal lineup) leagues side-by-side with live Win Probability and remaining game-clock pace projections.
  • Cross-League Conflict Tags (CONFLICT ⚡): Instantly spots when a player starts for your team in League A while playing against you in League B, plus Best Ball BUBBLE / CHASING tags.

  NATIVE FOR PHONES, FOLDABLES & WIDE TABLETS
  • Adaptive Multi-Device Layouts: 1-hand bottom navigation and clean 5-category Settings Hub on phones, hinge-safe 2-pane split on unfolded foldables, and a 3-pane Sunday Command Center on 10-inch tablets.
  • 5 Custom Broadcast Themes (plus Auto Dark / Light) and a built-in Interactive Sunday Simulator to test live spoken calls any day of the year.

  Disclaimer: GameDay Live is an independent application developed and published by Motisko Ventures LLC. It is an unofficial companion compatible with MyFantasyLeague (MFL) and is not affiliated with, endorsed by, or sponsored by MyFantasyLeague.com, the National Football League, or any professional sports league or team.
  ```
- **App Category**: `App` -> `Sports`
- **Tags**: `Sports`, `Fantasy sports`, `Audio`
- **Contact Details**:
  - **Email**: `support.gameday@motisko.com`
  - **Phone**: `+1 570-684-4750`
  - **Website**: `https://www.motisko.com/gameday`
- **Privacy Policy URL**: `https://www.motisko.com/gameday/privacy`

---

## 3. App Content & Policy Declarations (Exact Answers)

### A) App Access (`Policy -> App content -> App access`)
- Select: **"All or some functionality in my app is restricted"** -> **Add new instructions**:
  - **Name**: `Zero-Account & Interactive Simulator Access (No Account Creation)`
  - **Username / Password**: Leave blank -> check **"No username or password required"**
  - **Any other information required to access your app**:
    ```text
    No login or account is required to use or review 100% of GameDay Live's features.
    1. Tap "Interactive Sunday Simulator" on the launch screen to load two pre-populated demo fantasy leagues (Standard PPR + Best Ball), live games, and spoken play-by-play immediately — tap "Live Games" -> "Test Live Call" to hear spoken TTS audio and trigger the live touchdown banner.
    2. Or tap "Launch Live Scores Only (No Account)" for standalone live game scores and audio.
    Note: GameDay Live does NOT support in-app account creation; the optional MFL sign-in field connects strictly to an existing third-party MyFantasyLeague session stored locally on-device.
    ```

### B) Foreground Service Permissions (`FOREGROUND_SERVICE_MEDIA_PLAYBACK` — Android 14–16 Mandatory Video!)
- **Declared Use Case**: `Media playback` -> Spoken live sports commentary / audio broadcast stream.
- **Written Justification**:
  ```text
  GameDay Live is a hands-free spoken sports play-by-play audio announcer. When the user starts a live session, the app runs a user-visible mediaPlayback Foreground Service with persistent notification controls (Mute/Unmute, Refresh, Stop) so real-time spoken scoring, turnover, and Red Zone audio calls continue uninterrupted via Android TextToSpeech when the user locks their screen or switches to another app, and stop immediately when tapped Stop or swiped closed.
  ```
- **Mandatory 35-Second Audible Demo Video Checklist** (*Google Play reviewers reject silent/ADB videos for `mediaPlayback`!*):
  1. On your Pixel Fold, open Quick Settings -> **Screen record** -> set **Record audio = Device audio** -> Start recording.
  2. Open **GameDay Live (`com.motiskoventures.gameday`)** -> tap **Interactive Sunday Simulator**.
  3. Tap **Live Games** -> tap **Test Live Call** so the touchdown call begins speaking audibly, then immediately swipe **Home** while the voice continues speaking on the Android home screen.
  4. Swipe down the **Notification Shade** showing the active `GameDay Live` notification (`Mute` / `Refresh` / `Stop`) and tap **Stop** to show the audio and notification terminating cleanly -> Stop recording.
  5. Upload as an **Unlisted YouTube video** (under `ventures@motisko.com`) or drop the `.mp4` into `motisko.com/public/gameday/` and paste that URL into Play Console's *Foreground service permissions -> Video link* field.

### C) Data Safety (`Policy -> App content -> Data safety`)
- **Does your app collect or share any of the required user data types?** -> **`Yes`**
- **Does your app allow users to create an account?** -> **`No`** *(Ensures Google does not require a web account-deletion URL)*
- **Data Types Collected**:
  - Category `App info and performance` -> Check **`Crash logs`** and **`Diagnostics`** ONLY.
- **Per-Type Answers (same for both `Crash logs` and `Diagnostics`)**:
  - Collected or Shared? -> **`Collected`** *(Never shared with third parties)*
  - Processed ephemerally? -> **`No`**
  - Required or Optional? -> **`Users can choose whether this data is collected`** *(Opt-in toggle in Settings is OFF by default)*
  - Purpose? -> **`App functionality`** and **`Analytics`**
- **Is all of the user data collected by your app encrypted in transit?** -> **`Yes`** (`HTTPS/TLS`)

### D) Remaining Quick Declarations (`App content`)
1. **Ads**: `No, my app does not contain ads`
2. **Advertising ID (`com.google.android.gms.permission.AD_ID`)**: `No` *(Stripped from `playStore` manifest via `tools:node="remove"`)*
3. **Target Audience & Content**: Target ages **`13–15`, `16–17`, `18 and over`** -> Appeal to children? **`No`**
4. **Content Rating (IARC)**: Category **`Utility, Productivity, Communication, or Other`** (or **`Sports`**) -> Answer **`No`** to violence, sexuality, profanity, controlled substances, user-generated content/chat, location sharing, digital purchases, and real-money gambling/contests -> **`Everyone (E)`**
5. **Financial Features**: Check **"My app doesn't provide any financial features"** *(Fantasy companion read-only tracker; zero wagers, entry fees, or payouts)*
6. **Government / Health / News / COVID-19**: **`No`** to all.

---

## 4. Fastest Launch Track Strategy (Share With Friends Today + Public Production)

Because **Motisko Ventures LLC** is a verified **Organization** account, you have **zero 14-day / 12-tester hold**:

1. **Track 1 — `Testing -> Internal testing` (Instant Link in ~60 Seconds!)**:
   - Upload `GameDay-Live-PlayStore-v1.3.1.aab` + `mapping-playStoreRelease-v1.3.1.txt` to **Internal testing** first.
   - Add your friends' Gmail addresses to an `Early Friends` email list and copy the **Join on Web / Android link** — up to 100 invited friends can install directly from Google Play *immediately* without waiting for Google's 2–5 day initial review!
2. **Track 2 — `Promote release -> Production` (Public Worldwide / US Launch)**:
   - Once the 35-second audible Foreground Service video URL is pasted in `App content`, click **Promote release -> Production** on that exact same `v1.3.1 (11)` bundle -> Select countries -> **Start rollout to Production**.
