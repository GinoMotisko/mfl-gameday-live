# AGENTS.md — MFL GameDay Live (Android)

Authoritative engineering, release, and operational guide for AI agents (`Antigravity`, `Claude Code`, `Codex`) working in this repository.

---

## 1. Product & Distribution Architecture

GameDay Live builds **two distinct signed Android product flavors** from a single Kotlin + Jetpack Compose codebase (`minSdk = 31` Android 12, `targetSdk = 36` / `compileSdk = 36` Android 16):

| Flavor | `applicationId` | Target Channel | Brand / Visual Policy |
| :--- | :--- | :--- | :--- |
| **`direct`** | `com.mflgameday.live` | GitHub Releases & `https://www.motisko.com/gameday` (`/gameday/apk`) | Full 32-team ESPN NFL shield logos, player headshots, and optional `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` helper. |
| **`playStore`** | `com.motiskoventures.gameday` | Google Play Store (`Motisko Ventures LLC`, Account ID `7669036577809025288`) | `IS_PLAY_STORE_BUILD = true`: stylized team-color vector monograms & player initials, Play-compliant manifest (`app/src/playStore/AndroidManifest.xml`), scoped `network_security_config.xml`. |

> **Important Upgrade Note**: Because `direct` and `playStore` use separate `applicationId` values, they install side-by-side on Android devices and do **not** overwrite or migrate settings into each other in-place. Never promise an in-place upgrade between GitHub APK and Google Play installs.

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
3. Stage the matching `GameDay-Live-PlayStore-vX.Y.Z.aab` and `mapping-playStoreRelease-vX.Y.Z.txt` into `C:/Users/motis/OneDrive/Motisko Ventures LLC/Google-Play-Console-Kit/2-App-Bundle-And-Store-Graphics/`.

---

## 5. Critical Engineering Guardrails

1. **Foreground Service & Swipe-Close Lifecycle (`GameDayLiveService` + `ServiceLifecyclePolicy`)**:
   - Pressing Home / switching apps / turning screen off keeps `mediaPlayback` Foreground Service polling & speaking.
   - Swiping closed from Android Recent Apps (`onTaskRemoved`) stops TTS speech & cancels polling cleanly (`isBroadcastPaused = true`, `broadcastGeneration++`) **without** mutating `settings.runInBackgroundService`, and `MainActivity.onStart()` auto-resumes on next open.
   - Never call `syncForegroundServiceState()` from inside `GameDayLiveService.onStartCommand` (use `ensureEngineStartedFromService()` and `isRunningInForeground` to prevent re-entrant `startForegroundService` loops).
   - Always use `nonCancellingCatching` (rethrowing `CancellationException`) and `canEmitForCycle(cycleGen)` around suspend network calls so cancelled polls can never speak after swipe-close.
2. **Privacy, Backup & Telemetry (`AndroidManifest.xml`, `data_extraction_rules.xml`, `GameDayTelemetry`)**:
   - `android:allowBackup="false"` and explicit `<exclude>` rules in `backup_rules.xml` & `data_extraction_rules.xml` prevent encrypted MFL cookies, API keys, and logs from ever leaving the device via cloud backup or device transfer.
   - `GameDayTelemetry` tracks strictly non-PII feed latency/poll health locally and only posts to `https://www.motisko.com/api/gameday/telemetry` when the user explicitly opts in (`optInAnonymousTelemetry = true`) or taps *Send Health Snapshot*.
3. **Living Docs**: Keep `docs/architecture_map.md` and `docs/project_structure.md` updated whenever modules or flows change.
