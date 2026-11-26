#!/usr/bin/env node
/**
 * scripts/play-publish.mjs
 *
 * Zero-Browser, Zero-Third-Party-Dependency Google Play Publisher & Vitals CLI
 * for GameDay Live (`com.motiskoventures.gameday`, Account `7669036577809025288`).
 *
 * Security & Operational Architecture:
 * 1. Zero npm supply-chain surface: uses built-in Node 22+ `node:crypto` RS256 JWT + `fetch`
 *    directly against Google's official `androidpublisher v3` & `playdeveloperreporting v1beta1` APIs.
 * 2. Least-privilege 1Password vault isolation: decrypts local Windows DPAPI token
 *    (`keystore/gameday-op-sa.dpapi`, scoped strictly to `GameDay:read_items`), pipes the
 *    GCP Service Account JSON into RAM via `op document get`, mints an ephemeral OAuth2 token,
 *    and writes ZERO secrets to disk, env vars, or argv.
 * 3. Initial Review Queue Lockout: blocks any mutating Play API commit while initial Play Store
 *    review is pending (`play.google.com` HTTP 404) unless `--override-initial-review-lock` is passed.
 * 4. Cryptographic `.aab` & `24-bit RGB` PNG Pre-Flight Gates: verifies signing certificate SHA-256,
 *    embedded R8 `proguard.map`, and PNG `IHDR` `colorType === 2` (Truecolor RGB, no alpha) locally,
 *    and runs server-side `edits.validate` before `edits.commit`.
 *
 * Usage:
 *   node scripts/play-publish.mjs status
 *   node scripts/play-publish.mjs vitals
 *   node scripts/play-publish.mjs reviews
 *   node scripts/play-publish.mjs validate-bundle --aab=app/build/outputs/bundle/playStoreRelease/app-playStore-release.aab
 *   node scripts/play-publish.mjs upload-internal --aab=<path> --notes="Release notes" [--dry-run]
 *   node scripts/play-publish.mjs promote --from=internal --to=production [--rollout=0.20] [--notes="..."] [--dry-run]
 *   node scripts/play-publish.mjs halt-release --track=production --version-code=7
 *   node scripts/play-publish.mjs sync-listing --title="..." --short="..." --full-file=<path> [--dry-run]
 *   node scripts/play-publish.mjs sync-graphics --dir=<2-App-Bundle-And-Store-Graphics> [--dry-run]
 */

import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '..');

export const PACKAGE_NAME = 'com.motiskoventures.gameday';
export const EXPECTED_CERT_SHA256 = '4FFDDDA61399A4B04CF9A530AD68727F702D6DBBF6754107500265A7E2E350EB';
export const OP_VAULT_NAME = 'GameDay';
export const OP_DOCUMENT_TITLE = 'Google Play Publisher Service Account';
export const DEFAULT_DPAPI_PATH = path.join(REPO_ROOT, 'keystore', 'gameday-op-sa.dpapi');

const ANDROID_PUBLISHER_BASE = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE_NAME}`;
const ANDROID_PUBLISHER_UPLOAD = `https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/${PACKAGE_NAME}`;
const REPORTING_BASE = `https://playdeveloperreporting.googleapis.com/v1beta1/apps/${PACKAGE_NAME}`;
const PUBLIC_STORE_URL = `https://play.google.com/store/apps/details?id=${PACKAGE_NAME}`;

export function parseArgs(argv) {
  const positional = [];
  const flags = {};
  for (const arg of argv) {
    if (arg.startsWith('--')) {
      const eq = arg.indexOf('=');
      if (eq > 0) {
        flags[arg.slice(2, eq)] = arg.slice(eq + 1);
      } else {
        flags[arg.slice(2)] = true;
      }
    } else {
      positional.push(arg);
    }
  }
  return { command: positional[0] || 'help', positional, flags };
}

/**
 * Verifies PNG bytes have PNG signature and `IHDR` colorType === 2 (24-bit Truecolor RGB, no alpha).
 */
export function verifyPng24BitRgbBuffer(buf, label = 'image.png') {
  const pngSig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  if (!Buffer.isBuffer(buf) || buf.length < 33 || !buf.subarray(0, 8).equals(pngSig)) {
    throw new Error(`${label}: Not a valid PNG file`);
  }
  const chunkType = buf.subarray(12, 16).toString('ascii');
  if (chunkType !== 'IHDR') {
    throw new Error(`${label}: Missing PNG IHDR header`);
  }
  const width = buf.readUInt32BE(16);
  const height = buf.readUInt32BE(20);
  const bitDepth = buf.readUInt8(24);
  const colorType = buf.readUInt8(25); // 2 = Truecolor RGB (no alpha); 6 = RGBA
  if (bitDepth !== 8 || colorType !== 2) {
    throw new Error(
      `${label}: Must be 8-bit 24-bit RGB PNG (colorType=2, no alpha), got bitDepth=${bitDepth}, colorType=${colorType}`
    );
  }
  return { width, height, bitDepth, colorType };
}

/**
 * Cryptographically verifies an `.aab` bundle's signing certificate SHA-256 and embedded R8 mapping.
 */
export function verifyAabBundleIntegrity(aabPath) {
  if (!fs.existsSync(aabPath)) {
    throw new Error(`AAB file not found: ${aabPath}`);
  }
  const stat = fs.statSync(aabPath);
  if (stat.size < 500_000) {
    throw new Error(`AAB file suspiciously small (${stat.size} bytes): ${aabPath}`);
  }

  // 1. Verify signing certificate SHA-256 via JDK keytool
  const javaHome = process.env.JAVA_HOME || 'C:/Program Files/Android/Android Studio/jbr';
  const keytoolCandidates = [
    path.join(javaHome, 'bin', process.platform === 'win32' ? 'keytool.exe' : 'keytool'),
    'keytool'
  ];
  let keytoolBin = keytoolCandidates.find((c) => c === 'keytool' || fs.existsSync(c)) || 'keytool';
  const certOut = execFileSync(keytoolBin, ['-printcert', '-jarfile', aabPath], {
    encoding: 'utf8',
    timeout: 30000
  });
  const shaMatch = certOut.match(/SHA256:\s*([0-9A-F:]{95})/i);
  if (!shaMatch) {
    throw new Error(`AAB appears unsigned or missing SHA-256 certificate in keytool output: ${aabPath}`);
  }
  const actualSha = shaMatch[1].replace(/:/g, '').toUpperCase();
  if (actualSha !== EXPECTED_CERT_SHA256) {
    throw new Error(
      `AAB signing certificate mismatch!\n  Expected: ${EXPECTED_CERT_SHA256}\n  Actual:   ${actualSha}`
    );
  }

  // 2. Verify R8 obfuscation mapping and native debug symbols are bundled inside BUNDLE-METADATA
  const tarEntries = execFileSync('tar', ['-tf', aabPath], {
    encoding: 'utf8',
    maxBuffer: 16 * 1024 * 1024,
    timeout: 20000
  });
  if (!tarEntries.includes('BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map')) {
    throw new Error(`AAB is missing embedded R8 mapping (BUNDLE-METADATA/.../proguard.map): ${aabPath}`);
  }
  const hasNativeDebugSymbols = tarEntries.includes('BUNDLE-METADATA/com.android.tools.build.debugsymbols/');

  const fileSha256 = crypto.createHash('sha256').update(fs.readFileSync(aabPath)).digest('hex');
  return {
    sizeBytes: stat.size,
    certSha256: actualSha,
    aabSha256: fileSha256,
    hasEmbeddedProguardMap: true,
    hasNativeDebugSymbols
  };
}

/**
 * Checks whether the public Play Store listing is live (HTTP 200) vs still in initial pre-launch review (HTTP 404).
 */
export async function checkPublicPlayStoreLive(fetchImpl = fetch) {
  const res = await fetchImpl(PUBLIC_STORE_URL, { method: 'GET', redirect: 'follow' });
  return { live: res.status === 200, status: res.status };
}

/**
 * Enforces the Initial Review Queue Lockout so automated scripts cannot disturb `v1.2.2` while in initial review.
 */
export async function enforceReviewQueueSafetyGate({ isDryRun, overrideInitialReviewLock, fetchImpl = fetch }) {
  if (isDryRun || overrideInitialReviewLock) return;
  const pub = await checkPublicPlayStoreLive(fetchImpl);
  if (!pub.live) {
    throw new Error(
      `SAFETY LOCKOUT: ${PACKAGE_NAME} is not yet live on public Google Play (HTTP ${pub.status} — initial Production review in progress). ` +
        `Committing Play API edits right now could disturb your place in Google's initial review queue. ` +
        `Use '--dry-run' to validate safely, or pass '--override-initial-review-lock' if intentionally updating an unlaunched release.`
    );
  }
}

/**
 * Resolves the vault-scoped 1Password Service Account token from env or Windows DPAPI (`CurrentUser`).
 */
function loadScopedOpServiceAccountToken(dpapiPath = DEFAULT_DPAPI_PATH) {
  if (process.env.GAMEDAY_OP_SERVICE_ACCOUNT_TOKEN) {
    return process.env.GAMEDAY_OP_SERVICE_ACCOUNT_TOKEN.trim();
  }
  if (!fs.existsSync(dpapiPath)) {
    throw new Error(
      `Missing DPAPI token at ${dpapiPath} (and GAMEDAY_OP_SERVICE_ACCOUNT_TOKEN is unset). ` +
        `Run the one-time vault provisioning script first.`
    );
  }
  if (process.platform !== 'win32') {
    throw new Error('DPAPI decryption requires Windows; set GAMEDAY_OP_SERVICE_ACCOUNT_TOKEN in Linux/macOS CI.');
  }
  const dpapiCipher = fs.readFileSync(dpapiPath, 'utf8').trim();
  return execFileSync(
    'powershell.exe',
    [
      '-NoProfile',
      '-NonInteractive',
      '-Command',
      '$enc = [Console]::In.ReadToEnd().Trim(); $sec = ConvertTo-SecureString -String $enc; [System.Net.NetworkCredential]::new("", $sec).Password'
    ],
    {
      input: Buffer.from(dpapiCipher, 'utf8'),
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe'],
      timeout: 15000
    }
  ).trim();
}

/**
 * Loads GCP Service Account credentials strictly in-memory via 1Password CLI (`GameDay` vault)
 * and exchanges an RS256 signed JWT for a short-lived Google OAuth2 access token.
 */
export async function mintGooglePlayAccessToken({
  scopes = [
    'https://www.googleapis.com/auth/androidpublisher',
    'https://www.googleapis.com/auth/playdeveloperreporting'
  ],
  dpapiPath = DEFAULT_DPAPI_PATH,
  fetchImpl = fetch
} = {}) {
  const opToken = loadScopedOpServiceAccountToken(dpapiPath);
  const rawJson = execFileSync(
    'op',
    ['document', 'get', OP_DOCUMENT_TITLE, '--vault', OP_VAULT_NAME],
    {
      env: { ...process.env, OP_SERVICE_ACCOUNT_TOKEN: opToken },
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      timeout: 30000
    }
  );
  const sa = JSON.parse(rawJson);
  if (!sa.client_email || !sa.private_key) {
    throw new Error('Invalid GCP Service Account JSON retrieved from 1Password GameDay vault');
  }

  const nowSec = Math.floor(Date.now() / 1000);
  const headerB64 = Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT', kid: sa.private_key_id })).toString('base64url');
  const payloadB64 = Buffer.from(
    JSON.stringify({
      iss: sa.client_email,
      scope: scopes.join(' '),
      aud: sa.token_uri || 'https://oauth2.googleapis.com/token',
      iat: nowSec,
      exp: nowSec + 3600
    })
  ).toString('base64url');

  const signingInput = `${headerB64}.${payloadB64}`;
  const signer = crypto.createSign('RSA-SHA256');
  signer.update(signingInput);
  signer.end();
  const signatureB64 = signer.sign(sa.private_key).toString('base64url');
  const assertion = `${signingInput}.${signatureB64}`;

  // Clear private key string reference immediately
  sa.private_key = '';

  const tokenRes = await fetchImpl('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion
    })
  });
  if (!tokenRes.ok) {
    const errTxt = await tokenRes.text();
    throw new Error(`Google OAuth2 token exchange failed (${tokenRes.status}): ${errTxt}`);
  }
  const tokenJson = await tokenRes.json();
  return { accessToken: tokenJson.access_token, serviceAccountEmail: sa.client_email };
}

async function playApiFetch(accessToken, url, { method = 'GET', body = undefined, contentType = 'application/json', fetchImpl = fetch } = {}) {
  const headers = { Authorization: `Bearer ${accessToken}` };
  let payload = body;
  if (body !== undefined && !Buffer.isBuffer(body) && typeof body !== 'string') {
    headers['Content-Type'] = contentType;
    payload = JSON.stringify(body);
  } else if (body !== undefined) {
    headers['Content-Type'] = contentType;
  }
  const res = await fetchImpl(url, { method, headers, body: payload });
  const text = await res.text();
  if (!res.ok) {
    throw new Error(`Play API ${method} ${url} -> HTTP ${res.status}: ${text.slice(0, 600)}`);
  }
  return text ? JSON.parse(text) : {};
}

/**
 * Runs an `androidpublisher v3` AppEdit transaction safely:
 * - Always deletes temporary edits on read-only / dry-run / error paths!
 * - Calls `:validate` on mutating dry-runs (`validate: true, commit: false`) AND before `:commit`!
 */
export async function withPlayAppEdit(
  accessToken,
  { commit = false, validate = commit, changesNotSentForReview = false, fetchImpl = fetch },
  fn
) {
  const edit = await playApiFetch(accessToken, `${ANDROID_PUBLISHER_BASE}/edits`, {
    method: 'POST',
    body: {},
    fetchImpl
  });
  const editId = edit.id;
  let committed = false;
  try {
    const result = await fn(editId);
    if (validate || commit) {
      await playApiFetch(accessToken, `${ANDROID_PUBLISHER_BASE}/edits/${editId}:validate`, {
        method: 'POST',
        fetchImpl
      });
    }
    if (commit) {
      const qs = changesNotSentForReview ? '?changesNotSentForReview=true' : '';
      await playApiFetch(accessToken, `${ANDROID_PUBLISHER_BASE}/edits/${editId}:commit${qs}`, {
        method: 'POST',
        fetchImpl
      });
      committed = true;
    }
    return result;
  } finally {
    if (!committed) {
      try {
        await playApiFetch(accessToken, `${ANDROID_PUBLISHER_BASE}/edits/${editId}`, {
          method: 'DELETE',
          fetchImpl
        });
      } catch {}
    }
  }
}

async function cmdStatus() {
  const [{ accessToken, serviceAccountEmail }, publicStore] = await Promise.all([
    mintGooglePlayAccessToken(),
    checkPublicPlayStoreLive()
  ]);

  const snapshot = await withPlayAppEdit(accessToken, { commit: false }, async (editId) => {
    const [details, tracksResp, listingsResp] = await Promise.all([
      playApiFetch(accessToken, `${ANDROID_PUBLISHER_BASE}/edits/${editId}/details`),
      playApiFetch(accessToken, `${ANDROID_PUBLISHER_BASE}/edits/${editId}/tracks`),
      playApiFetch(accessToken, `${ANDROID_PUBLISHER_BASE}/edits/${editId}/listings`)
    ]);
    return {
      details,
      tracks: (tracksResp.tracks || []).map((t) => ({
        track: t.track,
        releases: (t.releases || []).map((r) => ({
          name: r.name,
          status: r.status,
          versionCodes: r.versionCodes,
          userFraction: r.userFraction
        }))
      })),
      listings: (listingsResp.listings || []).map((l) => ({
        language: l.language,
        title: l.title,
        shortDescription: l.shortDescription
      }))
    };
  });

  console.log(
    JSON.stringify(
      {
        packageName: PACKAGE_NAME,
        authenticatedAs: serviceAccountEmail,
        publicStoreRetailLive: publicStore.live,
        publicStoreHttpStatus: publicStore.status,
        ...snapshot
      },
      null,
      2
    )
  );
}

async function cmdVitals() {
  const { accessToken } = await mintGooglePlayAccessToken();
  const [crashSet, anrSet] = await Promise.all([
    playApiFetch(accessToken, `${REPORTING_BASE}/crashRateMetricSet`).catch((e) => ({ note: e.message })),
    playApiFetch(accessToken, `${REPORTING_BASE}/anrRateMetricSet`).catch((e) => ({ note: e.message }))
  ]);
  console.log(JSON.stringify({ packageName: PACKAGE_NAME, crashRateMetricSet: crashSet, anrRateMetricSet: anrSet }, null, 2));
}

async function cmdReviews() {
  const { accessToken } = await mintGooglePlayAccessToken();
  const reviews = await playApiFetch(accessToken, `${ANDROID_PUBLISHER_BASE}/reviews`);
  console.log(JSON.stringify(reviews, null, 2));
}

async function cmdUploadInternal(flags) {
  const aabPath = path.resolve(flags.aab || 'app/build/outputs/bundle/playStoreRelease/app-playStore-release.aab');
  const targetTrack = String(flags.track || 'internal');
  const isDryRun = Boolean(flags['dry-run']);
  const rollout = flags.rollout !== undefined ? Number(flags.rollout) : 1.0;
  if (Number.isNaN(rollout) || rollout <= 0 || rollout > 1) {
    throw new Error(`Invalid --rollout=${flags.rollout}; must be in (0.0, 1.0]`);
  }
  const notes = String(flags.notes || 'Bug fixes, audio engine improvements, and UI polish.');

  const integrity = verifyAabBundleIntegrity(aabPath);
  console.log('[preflight] AAB integrity verified:', integrity);

  await enforceReviewQueueSafetyGate({
    isDryRun,
    overrideInitialReviewLock: Boolean(flags['override-initial-review-lock'])
  });

  const { accessToken } = await mintGooglePlayAccessToken();
  const aabBytes = fs.readFileSync(aabPath);

  const outcome = await withPlayAppEdit(accessToken, { validate: true, commit: !isDryRun }, async (editId) => {
    const bundle = await playApiFetch(
      accessToken,
      `${ANDROID_PUBLISHER_UPLOAD}/edits/${editId}/bundles?uploadType=media`,
      {
        method: 'POST',
        body: aabBytes,
        contentType: 'application/octet-stream'
      }
    );
    const versionCode = String(bundle.versionCode);
    const existingTrack =
      rollout < 1.0
        ? await playApiFetch(accessToken, `${ANDROID_PUBLISHER_BASE}/edits/${editId}/tracks/${targetTrack}`).catch(() => ({
            releases: []
          }))
        : { releases: [] };
    const preservedCompleted =
      rollout < 1.0
        ? (existingTrack.releases || []).filter(
            (r) => r.status === 'completed' && !(r.versionCodes || []).map(String).includes(versionCode)
          )
        : [];
    const releasePayload = {
      name: `${versionCode} (${flags['version-name'] || 'release'})`,
      versionCodes: [versionCode],
      status: rollout < 1.0 ? 'inProgress' : 'completed',
      ...(rollout < 1.0 ? { userFraction: rollout } : {}),
      releaseNotes: [{ language: 'en-US', text: notes }]
    };
    await playApiFetch(accessToken, `${ANDROID_PUBLISHER_BASE}/edits/${editId}/tracks/${targetTrack}`, {
      method: 'PUT',
      body: {
        track: targetTrack,
        releases: [...preservedCompleted, releasePayload]
      }
    });
    return {
      mode: isDryRun ? 'DRY_RUN_VALIDATED_AND_DISCARDED' : `COMMITTED_TO_${targetTrack.toUpperCase()}`,
      track: targetTrack,
      bundle
    };
  });

  console.log(JSON.stringify(outcome, null, 2));
}

async function cmdPromote(flags) {
  const fromTrack = String(flags.from || 'internal');
  const toTrack = String(flags.to || 'production');
  const isDryRun = Boolean(flags['dry-run']);
  const rollout = flags.rollout !== undefined ? Number(flags.rollout) : 1.0;
  if (Number.isNaN(rollout) || rollout <= 0 || rollout > 1) {
    throw new Error(`Invalid --rollout=${flags.rollout}; must be in (0.0, 1.0]`);
  }

  await enforceReviewQueueSafetyGate({
    isDryRun,
    overrideInitialReviewLock: Boolean(flags['override-initial-review-lock'])
  });

  const { accessToken } = await mintGooglePlayAccessToken();
  const outcome = await withPlayAppEdit(accessToken, { validate: true, commit: !isDryRun }, async (editId) => {
    const [src, dst] = await Promise.all([
      playApiFetch(accessToken, `${ANDROID_PUBLISHER_BASE}/edits/${editId}/tracks/${fromTrack}`),
      rollout < 1.0
        ? playApiFetch(accessToken, `${ANDROID_PUBLISHER_BASE}/edits/${editId}/tracks/${toTrack}`).catch(() => ({ releases: [] }))
        : Promise.resolve({ releases: [] })
    ]);
    const latestRelease = (src.releases || []).find((r) => r.status === 'completed' || r.status === 'inProgress') || (src.releases || [])[0];
    if (!latestRelease?.versionCodes?.length) {
      throw new Error(`No active versionCodes found on source track '${fromTrack}'`);
    }
    const newCodes = new Set(latestRelease.versionCodes.map(String));
    const releasePayload = {
      versionCodes: latestRelease.versionCodes,
      status: rollout < 1.0 ? 'inProgress' : 'completed',
      ...(rollout < 1.0 ? { userFraction: rollout } : {}),
      releaseNotes: flags.notes
        ? [{ language: 'en-US', text: String(flags.notes) }]
        : latestRelease.releaseNotes || []
    };
    // Preserve existing completed baseline release during partial staged rollouts so the remaining (1 - rollout) users still have a live build
    const preservedCompleted = rollout < 1.0
      ? (dst.releases || []).filter(
          (r) => r.status === 'completed' && !(r.versionCodes || []).some((vc) => newCodes.has(String(vc)))
        )
      : [];
    const nextReleases = [...preservedCompleted, releasePayload];
    await playApiFetch(accessToken, `${ANDROID_PUBLISHER_BASE}/edits/${editId}/tracks/${toTrack}`, {
      method: 'PUT',
      body: { track: toTrack, releases: nextReleases }
    });
    return {
      mode: isDryRun ? 'DRY_RUN_VALIDATED_AND_DISCARDED' : 'COMMITTED_PROMOTION',
      fromTrack,
      toTrack,
      releases: nextReleases
    };
  });

  console.log(JSON.stringify(outcome, null, 2));
}

async function cmdHaltRelease(flags) {
  const track = String(flags.track || 'production');
  const versionCode = String(flags['version-code'] || '');
  const isDryRun = Boolean(flags['dry-run']);
  if (!versionCode) throw new Error('Pass --version-code=<N> to halt an active staged or live release');

  await enforceReviewQueueSafetyGate({
    isDryRun,
    overrideInitialReviewLock: Boolean(flags['override-initial-review-lock'])
  });

  const { accessToken } = await mintGooglePlayAccessToken();
  const outcome = await withPlayAppEdit(accessToken, { validate: true, commit: !isDryRun }, async (editId) => {
    const existingTrack = await playApiFetch(accessToken, `${ANDROID_PUBLISHER_BASE}/edits/${editId}/tracks/${track}`);
    let matched = false;
    const updatedReleases = (existingTrack.releases || []).map((r) => {
      if ((r.versionCodes || []).map(String).includes(versionCode)) {
        matched = true;
        const halted = { ...r, status: 'halted' };
        delete halted.userFraction;
        return halted;
      }
      return r;
    });
    if (!matched) {
      throw new Error(`Version code ${versionCode} not found on track '${track}'`);
    }
    await playApiFetch(accessToken, `${ANDROID_PUBLISHER_BASE}/edits/${editId}/tracks/${track}`, {
      method: 'PUT',
      body: {
        track,
        releases: updatedReleases
      }
    });
    return { mode: isDryRun ? 'DRY_RUN_HALT' : 'RELEASE_HALTED', track, versionCode, releases: updatedReleases };
  });
  console.log(JSON.stringify(outcome, null, 2));
}

function listPngsInSubdirOrRoot(rootDir, subdirName, rootFallbackRegex) {
  const subPath = path.join(rootDir, subdirName);
  if (fs.existsSync(subPath) && fs.statSync(subPath).isDirectory()) {
    return fs
      .readdirSync(subPath)
      .filter((f) => f.toLowerCase().endsWith('.png'))
      .sort()
      .map((f) => path.join(subdirName, f));
  }
  if (fs.existsSync(rootDir)) {
    return fs
      .readdirSync(rootDir)
      .filter((f) => rootFallbackRegex.test(f))
      .sort();
  }
  return [];
}

/**
 * Discovers and validates the generated `build-play-store-assets.mjs` store kit layout:
 * - `icon-512x512.png`, `feature-graphic-1024x500.png`
 * - `screenshots-phone/*.png`, `screenshots-tablet-7in/*.png`, `screenshots-tablet-10in/*.png`
 * - Plus pre-flight 24-bit RGB validation of `screenshots-foldable/*.png` (note Play API v3's
 *   `AppImageType` enum does not yet expose a dedicated foldable enum value).
 */
export function discoverStoreGraphics(dir) {
  const slotPatterns = [
    { imageType: 'icon', relFiles: ['icon-512x512.png'] },
    { imageType: 'featureGraphic', relFiles: ['feature-graphic-1024x500.png'] },
    {
      imageType: 'phoneScreenshots',
      relFiles: listPngsInSubdirOrRoot(dir, 'screenshots-phone', /^(?:\d+-)?phone-.*\.png$/i)
    },
    {
      imageType: 'sevenInchScreenshots',
      relFiles: listPngsInSubdirOrRoot(dir, 'screenshots-tablet-7in', /^(?:\d+-)?tab(?:let)?7-.*\.png$/i)
    },
    {
      imageType: 'tenInchScreenshots',
      relFiles: listPngsInSubdirOrRoot(dir, 'screenshots-tablet-10in', /^(?:\d+-)?tab(?:let)?10-.*\.png$/i)
    }
  ];

  const verifiedUploads = [];
  for (const slot of slotPatterns) {
    for (const relFile of slot.relFiles) {
      const fullPath = path.join(dir, relFile);
      if (fs.existsSync(fullPath)) {
        const buf = fs.readFileSync(fullPath);
        const dims = verifyPng24BitRGBOrIcon(buf, relFile, slot.imageType);
        verifiedUploads.push({ imageType: slot.imageType, file: relFile, fullPath, buf, dims });
      }
    }
  }

  // Also pre-flight validate any generated foldable screenshots for 24-bit RGB compliance
  const foldableRelFiles = listPngsInSubdirOrRoot(dir, 'screenshots-foldable', /^(?:\d+-)?fold(?:able)?-.*\.png$/i);
  let verifiedFoldablesCount = 0;
  for (const relFile of foldableRelFiles) {
    const fullPath = path.join(dir, relFile);
    if (fs.existsSync(fullPath)) {
      verifyPng24BitRgbBuffer(fs.readFileSync(fullPath), relFile);
      verifiedFoldablesCount++;
    }
  }

  return { verifiedUploads, verifiedFoldablesCount };
}

async function cmdSyncGraphics(flags) {
  const dir = path.resolve(
    flags.dir || 'C:/Users/motis/OneDrive/Motisko Ventures LLC/Google-Play-Console-Kit/2-App-Bundle-And-Store-Graphics'
  );
  const lang = String(flags.lang || 'en-US');
  const isDryRun = Boolean(flags['dry-run']);

  const { verifiedUploads, verifiedFoldablesCount } = discoverStoreGraphics(dir);
  if (verifiedUploads.length === 0) {
    throw new Error(`No matching graphics found in ${dir}`);
  }
  console.log(
    `[preflight] Verified ${verifiedUploads.length} API-syncable graphics (+ ${verifiedFoldablesCount} 24-bit RGB foldable screenshots) in ${dir}`
  );

  await enforceReviewQueueSafetyGate({
    isDryRun,
    overrideInitialReviewLock: Boolean(flags['override-initial-review-lock'])
  });

  const { accessToken } = await mintGooglePlayAccessToken();
  const outcome = await withPlayAppEdit(accessToken, { validate: true, commit: !isDryRun }, async (editId) => {
    const byType = new Map();
    for (const item of verifiedUploads) {
      if (!byType.has(item.imageType)) byType.set(item.imageType, []);
      byType.get(item.imageType).push(item);
    }
    const uploadedCounts = {};
    for (const [imageType, items] of byType.entries()) {
      await playApiFetch(accessToken, `${ANDROID_PUBLISHER_BASE}/edits/${editId}/listings/${lang}/${imageType}`, {
        method: 'DELETE'
      });
      uploadedCounts[imageType] = 0;
      for (const item of items) {
        await playApiFetch(
          accessToken,
          `${ANDROID_PUBLISHER_UPLOAD}/edits/${editId}/listings/${lang}/${imageType}?uploadType=media`,
          { method: 'POST', body: item.buf, contentType: 'image/png' }
        );
        uploadedCounts[imageType]++;
      }
    }
    return {
      mode: isDryRun ? 'DRY_RUN_VALIDATED_AND_DISCARDED' : 'COMMITTED_GRAPHICS',
      uploadedCounts,
      verifiedFoldablesUiOnlyCount: verifiedFoldablesCount
    };
  });

  console.log(JSON.stringify(outcome, null, 2));
}

function verifyPng24BitRGBOrIcon(buf, file, imageType) {
  // Feature graphic & all screenshots MUST be 24-bit RGB (colorType === 2); 512x512 icon allows RGB or RGBA
  if (imageType === 'icon') {
    const pngSig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
    if (buf.length < 33 || !buf.subarray(0, 8).equals(pngSig)) throw new Error(`${file}: Invalid PNG`);
    return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20), colorType: buf.readUInt8(25) };
  }
  return verifyPng24BitRgbBuffer(buf, file);
}

async function cmdSyncListing(flags) {
  const lang = String(flags.lang || 'en-US');
  const isDryRun = Boolean(flags['dry-run']);
  const title = flags.title ? String(flags.title) : undefined;
  const shortDescription = flags.short ? String(flags.short) : undefined;
  const fullDescription = flags['full-file'] ? fs.readFileSync(path.resolve(flags['full-file']), 'utf8').trim() : undefined;

  if (title && title.length > 30) throw new Error(`Play Store title exceeds 30 chars (${title.length})`);
  if (shortDescription && shortDescription.length > 80) {
    throw new Error(`Play Store shortDescription exceeds 80 chars (${shortDescription.length})`);
  }
  if (fullDescription && fullDescription.length > 4000) {
    throw new Error(`Play Store fullDescription exceeds 4000 chars (${fullDescription.length})`);
  }

  await enforceReviewQueueSafetyGate({
    isDryRun,
    overrideInitialReviewLock: Boolean(flags['override-initial-review-lock'])
  });

  const { accessToken } = await mintGooglePlayAccessToken();
  const outcome = await withPlayAppEdit(accessToken, { validate: true, commit: !isDryRun }, async (editId) => {
    const existing = await playApiFetch(accessToken, `${ANDROID_PUBLISHER_BASE}/edits/${editId}/listings/${lang}`);
    const updated = {
      language: lang,
      title: title ?? existing.title,
      shortDescription: shortDescription ?? existing.shortDescription,
      fullDescription: fullDescription ?? existing.fullDescription,
      video: '' // Explicitly keep blank per AGENTS.md Section 6.1
    };
    await playApiFetch(accessToken, `${ANDROID_PUBLISHER_BASE}/edits/${editId}/listings/${lang}`, {
      method: 'PUT',
      body: updated
    });
    return { mode: isDryRun ? 'DRY_RUN_VALIDATED_AND_DISCARDED' : 'COMMITTED_LISTING', title: updated.title };
  });
  console.log(JSON.stringify(outcome, null, 2));
}

async function main() {
  const { command, flags } = parseArgs(process.argv.slice(2));
  switch (command) {
    case 'status':
      await cmdStatus();
      break;
    case 'vitals':
      await cmdVitals();
      break;
    case 'reviews':
      await cmdReviews();
      break;
    case 'validate-bundle': {
      const aab = path.resolve(flags.aab || 'app/build/outputs/bundle/playStoreRelease/app-playStore-release.aab');
      console.log(JSON.stringify(verifyAabBundleIntegrity(aab), null, 2));
      break;
    }
    case 'upload-internal':
    case 'upload-release':
      await cmdUploadInternal(flags);
      break;
    case 'promote':
      await cmdPromote(flags);
      break;
    case 'halt-release':
      await cmdHaltRelease(flags);
      break;
    case 'sync-graphics':
      await cmdSyncGraphics(flags);
      break;
    case 'sync-listing':
      await cmdSyncListing(flags);
      break;
    default:
      console.log(`Usage: node scripts/play-publish.mjs <command> [flags]
Commands:
  status                                    Read-only Play Console track & listing status (0 mutations)
  vitals                                    Read-only Android Vitals crash & ANR rate sets
  reviews                                   Read-only Google Play user reviews
  validate-bundle [--aab=<path>]            Offline SHA-256 cert + embedded R8 mapping check
  upload-internal [--aab=<path>] [--notes]  Upload signed .aab to Internal track (--dry-run supported)
  promote --from=internal --to=production   Promote track (--rollout=0.20, --dry-run supported)
  halt-release --track=production --version-code=<N>  Emergency 1-second release halt
  sync-graphics [--dir=<path>]              Validate 24-bit RGB PNGs & sync store graphics (--dry-run)
  sync-listing [--title] [--short] [--full-file]  Sync en-US store copy (--dry-run)`);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((err) => {
    console.error(`[play-publish ERROR] ${err.message}`);
    process.exitCode = 1;
  });
}
