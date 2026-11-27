# AGENTS.md — MFL GameDay Live (Android)

Authoritative engineering, release, and operational guide for AI agents (`Antigravity`, `Claude Code`, `Codex`) working in this repository.

---

## 1. Product & Distribution Architecture

GameDay Live builds **two distinct signed Android product flavors** from a single Kotlin + Jetpack Compose codebase (`minSdk = 31` Android 12, `targetSdk = 36` / `compileSdk = 36` Android 16):

| Flavor | `applicationId` | Target Channel | Brand / Visual Policy |
| :--- | :--- | :--- | :--- |
| **`direct`** | `com.mflgameday.live` | GitHub Releases & `https://www.motisko.com/gameday` (`/gameday/apk`) | Full 32-team ESPN NFL shield logos, player headshots, and optional `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` helper. |
| **`playStore`** | `com.motiskoventures.gameday` | Google Play Store (`Motisko Ventures LLC`, Account ID `7669036577809025288`) | `IS_PLAY_STORE_BUILD = true`: stylized team-color vector monograms & player initials, Play-compliant manifest (`app/src/playStore/AndroidManifest.xml`), scoped `network_security_config.xml`. |

> **Important Upgrade Note**: Because `direct` and `playStore` use separate `applicationId` values, they install side-by-side on Android devices and do **not** overwrite each other in-place. Users can clone their tracked leagues, commentator voice, and preferences across devices or editions in 2 taps via `Settings -> My Leagues -> Export / Share Setup` (`ProfileSyncCodec`, zero credentials exported). Never promise an automatic OS-level in-place upgrade between GitHub APK and Google Play installs.

---

## 2. Single Verification & Build Command

Always wrap commands in `cmd /c` on Windows and run the unified verification script before merging or cutting any release:

```bat
cmd /c "scripts\verify.cmd"
```

This executes:
1. `testDirectDebugUnitTest` (unit tests + MockWebServer MFL/ESPN pipeline tests + `ServiceLifecyclePolicyTest` + `DiagnosticsAndLogoTest`)
2. `lintDirectDebug` & `lintVitalPlayStoreRelease`
3. `assembleDirectRelease` -> `app/build/outputs/apk/direct/release/app-direct-release.apk`
4. `bundlePlayStoreRelease` -> `app/build/outputs/bundle/playStoreRelease/app-playStore-release.aab` (+ R8 mapping at `app/build/outputs/mapping/playStoreRelease/mapping.txt`)

---

## 3. Signing Keystore & 1Password Vaulting (NEVER COMMIT SECRETS)

- Local git-ignored signing files:
  - `keystore/mfl-gameday-release.jks` (RSA-2048, V1+V2+V3 signing, Certificate SHA-256: `4ffddda61399a4b04cf9a530ad68727f702d6dbbf6754107500265a7e2e350eb`)
  - `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`)
- **1Password Backup**: Vaulted via `1Password CLI` (`op`) under vault `Motisko Ventures` (items `GameDay Live — Android Release Keystore (.jks)` and `GameDay Live — Keystore Credentials`). See `C:/Users/motis/OneDrive/Motisko Ventures LLC/MOTISKO_VENTURES_MASTER_RUNBOOK.md` for full corporate & 1Password recovery commands.

---

## 4. Release Lockstep Discipline (GitHub <-> `motisko.com/gameday`)

Whenever publishing a new GitHub Release (`vX.Y.Z` marked `--latest`):
1. Upload `MFL-GameDay-Live.apk`, `MFL-GameDay-Live-vX.Y.Z.apk`, and `MFL-GameDay-Live-vX.Y.Z.apk.sha256` (exact format: `<64-hex-sha256>  MFL-GameDay-Live.apk`).
2. `https://www.motisko.com/gameday/apk` redirects (`HTTP 307`) to `releases/latest/download/MFL-GameDay-Live.apk`, and `https://www.motisko.com/api/gameday/release` reads the latest release version & `.sha256` asset dynamically, while `GameDayShowcase.tsx` holds the current fallback version + SHA-256. Update `GameDayShowcase.tsx` fallback constants when bumping major/minor releases so SSR HTML matches immediately.
3. Bump `docs/PLAY_STORE_SUBMISSION_PACK.md` (`vX.Y.Z` / `versionCode = <N>` / artifact filenames & smoke command) and stage the matching `GameDay-Live-PlayStore-vX.Y.Z.aab`, `mapping-playStoreRelease-vX.Y.Z.txt`, and `PLAY_STORE_SUBMISSION_PACK.md` into `C:/Users/motis/OneDrive/Motisko Ventures LLC/Google-Play-Console-Kit/2-App-Bundle-And-Store-Graphics/`.
4. **Standing Post-Release Store & Showcase Smoke Regression (Mandatory After Every Store Release)**:
   - Immediately after every Google Play & GitHub Release push, execute `node scripts/play-publish.mjs smoke --version=vX.Y.Z --version-code=<N>` verifying:
     1) `node scripts/play-publish.mjs status` confirms the latest `versionCode` (`vX.Y.Z`) is active on `production` (`status: "completed"` or staged `inProgress`) and `publicStoreRetailLive === true` (`HTTP 200`).
     2) `https://www.motisko.com/api/gameday/release` returns `"version": "vX.Y.Z"`, matching `sha256`, and `"playStoreLive": true`.
     3) `https://www.motisko.com/gameday/apk` returns `HTTP 307` redirecting to `releases/latest/download/MFL-GameDay-Live.apk`, and `https://www.motisko.com/gameday` renders the updated version & Google Play CTA cleanly.
     4) Verify `https://play.google.com/store/apps/details?id=com.motiskoventures.gameday` displays the new `vX.Y.Z` release notes/version once Google's post-submission scan propagates (if still propagating, note exact Play API `tracks.production` state and recommend `/schedule` to confirm retail propagation).

---

## 5. Critical Engineering Guardrails

1. **Foreground Service & Swipe-Close Lifecycle (`GameDayLiveService` + `ServiceLifecyclePolicy`)**:
   - Pressing Home / switching apps / turning screen off keeps `mediaPlayback` Foreground Service polling & speaking.
   - Swiping closed from Android Recent Apps (`onTaskRemoved`) stops TTS speech & cancels polling cleanly (`isBroadcastPaused = true`, `broadcastGeneration++`) **without** mutating `settings.runInBackgroundService`, and `MainActivity.onStart()` auto-resumes on next open.
   - Never call `syncForegroundServiceState()` from inside `GameDayLiveService.onStartCommand` (use `ensureEngineStartedFromService()` and `isRunningInForeground` to prevent re-entrant `startForegroundService` loops).
   - Always use `nonCancellingCatching` (rethrowing `CancellationException`) and `canEmitForCycle(cycleGen)` around suspend network calls so cancelled polls can never speak after swipe-close.
2. **Privacy, Backup & Telemetry (`AndroidManifest.xml`, `data_extraction_rules.xml`, `GameDayTelemetry`)**:
   - `android:allowBackup="false"` and explicit `<exclude>` rules in `backup_rules.xml` & `data_extraction_rules.xml` prevent encrypted MFL cookies, API keys, and logs from ever leaving the device via cloud backup or device transfer.
   - `GameDayTelemetry` tracks strictly non-PII feed latency/poll health locally, while `GameDayFirebaseBridge` reports anonymous crash stack traces and reliability metrics to Google Firebase Crashlytics & Analytics (`AD_ID` stripped + `adid`/`ssaid`/ad-personalization disabled; user opt-out toggle in `Settings -> Help & Sync`). `app/google-services.json` stays git-ignored locally & vaulted in 1Password (`Motisko Ventures`), while `scripts/verify.sh` injects a temporary stub in keyless CI.
3. **Living Docs**: Keep `docs/architecture_map.md` and `docs/project_structure.md` updated whenever modules or flows change.
4. **Bounded Pre-PR Subagent Review & Codex Sync**:
   - Before cutting a release, run an independent read-only `research` subagent review focused strictly on P0/P1 runtime, security, and Google Play policy defects (max 2 iterations — never loop endlessly on style nitpicks).
   - After opening a PR, check `gh api repos/GinoMotisko/mfl-gameday-live/pulls/<N>/comments` so automated Codex reviews complete with `has_any_findings: false` before merging.
5. **CLI & Connectors First (Browser as Last Resort)**:
   - Always prefer direct CLIs and MCP connectors (`gh`, `vercel`, `railway`, `op` 1Password CLI, `ExchangeOnlineManagement` PowerShell, Google Drive/Docs plugins, local `G:\` Google Drive mount) over web-browser automation unless an interactive human-only UI has no CLI/API.


---

## 6. Google Play Packaging, Graphics & Official API Publishing Rules

1. **20-Asset `24-bit RGB` Store Matrix (`scripts/build-play-store-assets.mjs`)**:
   - Export all Play Store graphics as **24-bit RGB PNG (`channels === 3`, `hasAlpha === false`)** using a two-pass `sharp(svg).composite(...).toBuffer() -> sharp(buf).flatten(...).removeAlpha()` pipeline (calling `.composite()` after `.flatten()` in one chain re-introduces an alpha channel).
   - Always generate all 5 visual slots (20 files): App Icon (`512×512`), Feature Graphic (`1024×500`), Phone (`6 × 1080×1920`), **Foldable (`4`: `1:1` `2048×2048`, `4:3` `2048×1536`, `10:16` `1200×1920`)**, 7" Tablet (`4 × 1920×1080`), and 10" Tablet (`4 × 2560×1440`). Leave the optional Store Listing YouTube trailer blank.
2. **`.aab` R8 Mapping & Native Symbols**:
   - AGP 8+ automatically embeds `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map` inside `app-playStore-release.aab` (verify via `tar -tf`).
3. **First-Party Google Play API Only (`scripts/play-publish.mjs` — Zero Unvetted Third-Party Publishing MCPs)**:
   - Never pass Google Play Service Account credentials to unvetted community MCP servers or indie CLIs, and never write Service Account JSON files to disk or global Windows env vars.
   - Use **`node scripts/play-publish.mjs`** (`status`, `smoke`, `vitals`, `reviews`, `validate-bundle`, `upload-release`, `promote --rollout=...`, `set-rollout`, `halt-release`, `sync-graphics`, `sync-listing`):
     - Zero third-party npm packages (`node:crypto` RS256 JWT + native `fetch` against `androidpublisher.googleapis.com` & `playdeveloperreporting.googleapis.com`).
     - Authenticates via git-ignored Windows DPAPI blob `keystore/gameday-op-sa.dpapi` -> 1Password Service Account `GameDay-Agent-Publisher` (**read-only `GameDay:read_items`**, 90-day expiry, zero access to `Motisko Ventures` `.jks` signing vault or any other vault) -> GCP Service Account `gameday-play-publisher@motisko-gameday-play-api.iam.gserviceaccount.com` (zero GCP IAM roles; Play Console app-scoped strictly to `com.motiskoventures.gameday` with zero financial/account permissions).
     - Enforces **Initial Review Queue Lockout** (aborts live mutations while `play.google.com` returns `HTTP 404` unless `--dry-run` or `--override-initial-review-lock`), `.aab` SHA-256 cert + embedded `proguard.map` verification, `24-bit RGB` PNG `IHDR` verification, and server-side `:validate` before `:commit`.
     - **Cloud CI Security Invariant**: Keep `.github/workflows/*` strictly **keyless / secretless** (`permissions: contents: read`, 40-char commit SHA-pinned Actions only). Never upload `.jks` keystores, `keystore.properties`, `GAMEDAY_OP_SERVICE_ACCOUNT_TOKEN`, or Google Play Service Account keys to GitHub Actions Secrets, and keep local NTFS ACLs on `keystore/` and `keystore.properties` restricted strictly to the owner user SID.
4. **Active Engineering Backlog**: Tracked in GitHub Issues (`gh issue list --repo GinoMotisko/mfl-gameday-live` — Issues `#5`, `#7`–`#11`).