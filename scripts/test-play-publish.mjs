#!/usr/bin/env node
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  EXPECTED_CERT_SHA256,
  buildHaltTrackUpdate,
  buildSetRolloutTrackUpdate,
  buildTrackReleaseUpdate,
  discoverStoreGraphics,
  enforceReviewQueueSafetyGate,
  evaluateSmokeGate,
  parseArgs,
  validateRolloutFraction,
  validateUpdatePriority,
  verifyAabBundleIntegrity,
  verifyAabStructure,
  verifyPng24BitRgbBuffer,
  withPlayAppEdit
} from './play-publish.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '..');

function buildMockPngHeader({ width = 1024, height = 500, bitDepth = 8, colorType = 2 }) {
  const buf = Buffer.alloc(40, 0);
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(buf, 0);
  buf.writeUInt32BE(13, 8);
  buf.write('IHDR', 12, 'ascii');
  buf.writeUInt32BE(width, 16);
  buf.writeUInt32BE(height, 20);
  buf.writeUInt8(bitDepth, 24);
  buf.writeUInt8(colorType, 25);
  return buf;
}

async function runTests() {
  // 1. CLI arg parser
  const parsed = parseArgs(['promote', '--from=internal', '--to=production', '--rollout=0.25', '--dry-run']);
  assert.equal(parsed.command, 'promote');
  assert.equal(parsed.flags.from, 'internal');
  assert.equal(parsed.flags.to, 'production');
  assert.equal(parsed.flags.rollout, '0.25');
  assert.equal(parsed.flags['dry-run'], true);

  // 2. 24-bit RGB PNG gate accepts colorType=2 and rejects RGBA colorType=6
  const rgbOk = verifyPng24BitRgbBuffer(buildMockPngHeader({ colorType: 2 }), 'feature.png');
  assert.equal(rgbOk.width, 1024);
  assert.equal(rgbOk.colorType, 2);
  assert.throws(
    () => verifyPng24BitRgbBuffer(buildMockPngHeader({ colorType: 6 }), 'rgba.png'),
    /Must be 8-bit 24-bit RGB PNG/
  );

  // 3. Initial Review Queue Lockout blocks live mutations on HTTP 404, allows dry-run
  const mock404Fetch = async () => ({ status: 404 });
  await assert.rejects(
    () => enforceReviewQueueSafetyGate({ isDryRun: false, overrideInitialReviewLock: false, fetchImpl: mock404Fetch }),
    /SAFETY LOCKOUT/
  );
  await enforceReviewQueueSafetyGate({ isDryRun: true, overrideInitialReviewLock: false, fetchImpl: mock404Fetch });

  // 4. withPlayAppEdit transaction safety: read-only deletes edit; commit runs :validate BEFORE :commit
  const callsReadOnly = [];
  const mockEditFetch = (callsArr) => async (url, opts = {}) => {
    const method = opts.method || 'GET';
    callsArr.push(`${method} ${url}`);
    if (method === 'POST' && url.endsWith('/edits')) {
      return { ok: true, status: 200, text: async () => JSON.stringify({ id: 'edit-123' }) };
    }
    return { ok: true, status: 200, text: async () => JSON.stringify({ ok: true }) };
  };

  await withPlayAppEdit('tok', { commit: false, fetchImpl: mockEditFetch(callsReadOnly) }, async (editId) => {
    assert.equal(editId, 'edit-123');
    return 'ok';
  });
  assert.equal(callsReadOnly.length, 2);
  assert.match(callsReadOnly[0], /^POST .*\/edits$/);
  assert.match(callsReadOnly[1], /^DELETE .*\/edits\/edit-123$/);

  const callsDryRunValidate = [];
  await withPlayAppEdit('tok', { validate: true, commit: false, fetchImpl: mockEditFetch(callsDryRunValidate) }, async () => 'dry');
  assert.equal(callsDryRunValidate.length, 3);
  assert.match(callsDryRunValidate[0], /^POST .*\/edits$/);
  assert.match(callsDryRunValidate[1], /^POST .*\/edits\/edit-123:validate$/);
  assert.match(callsDryRunValidate[2], /^DELETE .*\/edits\/edit-123$/);

  const callsCommit = [];
  await withPlayAppEdit('tok', { commit: true, fetchImpl: mockEditFetch(callsCommit) }, async () => 'done');
  assert.equal(callsCommit.length, 3);
  assert.match(callsCommit[0], /^POST .*\/edits$/);
  assert.match(callsCommit[1], /^POST .*\/edits\/edit-123:validate$/);
  assert.match(callsCommit[2], /^POST .*\/edits\/edit-123:commit$/);

  // 5. Track-release builders: full rollout, partial rollout baseline retention, set-rollout, and halt-release guards
  assert.throws(() => validateRolloutFraction('0'), /Invalid --rollout/);
  assert.throws(() => validateRolloutFraction('1.5'), /Invalid --rollout/);

  const staged = buildTrackReleaseUpdate({
    track: 'production',
    existingReleases: [{ name: '6 (1.2.2)', versionCodes: ['6'], status: 'completed' }],
    versionCodes: [7],
    rollout: 0.2,
    releaseName: '7 (1.2.3)',
    notesText: 'Staged canary'
  });
  assert.equal(staged.releases.length, 2);
  assert.deepEqual(staged.releases[0].versionCodes, ['6']);
  assert.equal(staged.releases[0].status, 'completed');
  assert.deepEqual(staged.releases[1].versionCodes, ['7']);
  assert.equal(staged.releases[1].status, 'inProgress');
  assert.equal(staged.releases[1].userFraction, 0.2);
  assert.equal(staged.releases[1].inAppUpdatePriority, 0);

  assert.equal(validateUpdatePriority(undefined), 0);
  assert.equal(validateUpdatePriority('5'), 5);
  assert.throws(() => validateUpdatePriority(true), /Invalid --update-priority/);
  assert.throws(() => validateRolloutFraction(true), /Invalid --rollout/);
  assert.throws(() => validateUpdatePriority('-1'), /Invalid --update-priority/);
  assert.throws(() => validateUpdatePriority('6'), /Invalid --update-priority/);
  assert.throws(() => validateUpdatePriority('2.5'), /Invalid --update-priority/);

  const urgentRelease = buildTrackReleaseUpdate({
    track: 'production',
    existingReleases: [],
    versionCodes: [11],
    rollout: 1.0,
    updatePriority: 5,
    releaseName: '11 (1.3.1)',
    notesText: 'Critical API update'
  });
  assert.equal(urgentRelease.releases[0].inAppUpdatePriority, 5);

  const halted = buildHaltTrackUpdate({
    track: 'production',
    existingReleases: staged.releases,
    versionCode: '7'
  });
  assert.equal(halted.releases[1].status, 'halted');
  assert.equal('userFraction' in halted.releases[1], false);

  // Google Play API v3 forbids halting a 100% completed release — verify fail-fast operator guard
  assert.throws(
    () =>
      buildHaltTrackUpdate({
        track: 'production',
        existingReleases: [{ versionCodes: ['7'], status: 'completed' }],
        versionCode: '7'
      }),
    /Cannot API-halt versionCode 7/
  );

  // set-rollout requires an explicit rollout fraction (never silently defaults to 1.0)
  assert.throws(
    () =>
      buildSetRolloutTrackUpdate({
        track: 'production',
        existingReleases: staged.releases,
        versionCode: '7',
        rollout: undefined
      }),
    /explicit --rollout/
  );

  // Expand staged 20% rollout to 100% completed via set-rollout
  const completedFromStaged = buildSetRolloutTrackUpdate({
    track: 'production',
    existingReleases: staged.releases,
    versionCode: '7',
    rollout: 1.0
  });
  assert.equal(completedFromStaged.releases.length, 1);
  assert.deepEqual(completedFromStaged.releases[0].versionCodes, ['7']);
  assert.equal(completedFromStaged.releases[0].status, 'completed');
  assert.equal('userFraction' in completedFromStaged.releases[0], false);

  // Google Play API v3 forbids mutating inAppUpdatePriority on an already-rolled-out release
  assert.throws(
    () =>
      buildSetRolloutTrackUpdate({
        track: 'production',
        existingReleases: staged.releases,
        versionCode: '7',
        rollout: 0.5,
        updatePriority: 5
      }),
    /Cannot change inAppUpdatePriority/
  );

  // 5b. Smoke gate rejects halted releases, mismatched SHA-256 checksums, and stale /gameday showcase HTML
  const validSha = '002f0ad8b4ac09e790d7b2affb9aa159d331ce27b8b9ef37a783aa4ccfa10c68';
  const validShowcase = '<a href="https://play.google.com/store/apps/details?id=com.motiskoventures.gameday">Get it on Google Play</a><span>v1.2.3</span>';
  const haltedSmoke = evaluateSmokeGate({
    publicStoreLive: true,
    prodReleases: [{ versionCodes: ['7'], status: 'halted' }],
    expectedVersion: 'v1.2.3',
    expectedVersionCode: '7',
    expectedSha256: validSha,
    releaseApi: { version: 'v1.2.3', sha256: validSha, playStoreLive: true },
    showcaseStatus: 200,
    showcaseHtml: validShowcase,
    apkRedirectStatus: 307,
    apkRedirectLocation: 'https://github.com/GinoMotisko/mfl-gameday-live/releases/latest/download/MFL-GameDay-Live.apk',
    apkTargetHttpOk: true,
    storeHtml: '1.2.3'
  });
  assert.equal(haltedSmoke.verdict, 'FAIL');
  assert.equal(haltedSmoke.checks.playProductionTrackActive, false);

  const badShaSmoke = evaluateSmokeGate({
    publicStoreLive: true,
    prodReleases: [{ versionCodes: ['7'], status: 'completed' }],
    expectedVersion: 'v1.2.3',
    expectedVersionCode: '7',
    expectedSha256: validSha,
    releaseApi: { version: 'v1.2.3', sha256: 'f'.repeat(64), playStoreLive: true },
    showcaseStatus: 200,
    showcaseHtml: validShowcase,
    apkRedirectStatus: 307,
    apkRedirectLocation: 'https://github.com/GinoMotisko/mfl-gameday-live/releases/latest/download/MFL-GameDay-Live.apk',
    apkTargetHttpOk: true,
    storeHtml: '1.2.3'
  });
  assert.equal(badShaSmoke.verdict, 'FAIL');
  assert.equal(badShaSmoke.checks.motiskoReleaseApiSha256Match, false);

  const staleShowcaseSmoke = evaluateSmokeGate({
    publicStoreLive: true,
    prodReleases: [{ versionCodes: ['7'], status: 'completed' }],
    expectedVersion: 'v1.2.3',
    expectedVersionCode: '7',
    expectedSha256: validSha,
    releaseApi: { version: 'v1.2.3', sha256: validSha, playStoreLive: true },
    showcaseStatus: 200,
    showcaseHtml: '<div>v1.2.2 In Final Review</div>',
    apkRedirectStatus: 307,
    apkRedirectLocation: 'https://github.com/GinoMotisko/mfl-gameday-live/releases/latest/download/MFL-GameDay-Live.apk',
    apkTargetHttpOk: true,
    storeHtml: '1.2.3'
  });
  assert.equal(staleShowcaseSmoke.verdict, 'FAIL');
  assert.equal(staleShowcaseSmoke.checks.motiskoShowcasePageOk, false);

  const exposedPhoneSmoke = evaluateSmokeGate({
    publicStoreLive: true,
    prodReleases: [{ versionCodes: ['7'], status: 'completed' }],
    appDetails: { contactEmail: 'support.gameday@motisko.com', contactPhone: '+15706844750' },
    expectedVersion: 'v1.2.3',
    expectedVersionCode: '7',
    expectedSha256: validSha,
    releaseApi: { version: 'v1.2.3', sha256: validSha, playStoreLive: true },
    showcaseStatus: 200,
    showcaseHtml: validShowcase,
    apkRedirectStatus: 307,
    apkRedirectLocation: 'https://github.com/GinoMotisko/mfl-gameday-live/releases/latest/download/MFL-GameDay-Live.apk',
    apkTargetHttpOk: true,
    storeHtml: '1.2.3'
  });
  assert.equal(exposedPhoneSmoke.verdict, 'FAIL');
  assert.equal(exposedPhoneSmoke.checks.appSupportPhoneOmitted, false);

  const omittedPhoneSmoke = evaluateSmokeGate({
    publicStoreLive: true,
    prodReleases: [{ versionCodes: ['7'], status: 'completed' }],
    appDetails: { contactEmail: 'support.gameday@motisko.com' },
    expectedVersion: 'v1.2.3',
    expectedVersionCode: '7',
    expectedSha256: validSha,
    releaseApi: { version: 'v1.2.3', sha256: validSha, playStoreLive: true },
    showcaseStatus: 200,
    showcaseHtml: validShowcase,
    apkRedirectStatus: 307,
    apkRedirectLocation: 'https://github.com/GinoMotisko/mfl-gameday-live/releases/latest/download/MFL-GameDay-Live.apk',
    apkTargetHttpOk: true,
    storeHtml: '1.2.3'
  });
  assert.equal(omittedPhoneSmoke.verdict, 'PASS_ALL_LIVE');
  assert.equal(omittedPhoneSmoke.checks.appSupportPhoneOmitted, true);

  // 6. discoverStoreGraphics discovers generated kit subdirectories (phone, 7in, 10in, foldable)
  const tmpKit = fs.mkdtempSync(path.join(os.tmpdir(), 'play-kit-test-'));
  try {
    const rgb24 = buildMockPngHeader({ width: 1080, height: 1920, colorType: 2 });
    fs.writeFileSync(path.join(tmpKit, 'icon-512x512.png'), buildMockPngHeader({ width: 512, height: 512, colorType: 2 }));
    fs.writeFileSync(path.join(tmpKit, 'feature-graphic-1024x500.png'), buildMockPngHeader({ width: 1024, height: 500, colorType: 2 }));
    for (const sub of ['screenshots-phone', 'screenshots-tablet-7in', 'screenshots-tablet-10in', 'screenshots-foldable']) {
      fs.mkdirSync(path.join(tmpKit, sub), { recursive: true });
    }
    fs.writeFileSync(path.join(tmpKit, 'screenshots-phone', '01-phone-live-spoken-audio.png'), rgb24);
    fs.writeFileSync(path.join(tmpKit, 'screenshots-tablet-7in', '01-tab7-live-scoreboard.png'), rgb24);
    fs.writeFileSync(path.join(tmpKit, 'screenshots-tablet-10in', '01-tab10-command-center.png'), rgb24);
    fs.writeFileSync(path.join(tmpKit, 'screenshots-foldable', '01-fold-unfolded-1x1-oled.png'), rgb24);

    const discovered = discoverStoreGraphics(tmpKit);
    assert.equal(discovered.verifiedUploads.length, 5);
    assert.equal(discovered.verifiedFoldablesCount, 1);
  } finally {
    fs.rmSync(tmpKit, { recursive: true, force: true });
  }

  // 7. Keyless CI & Local AAB structure + embedded R8 proguard.map check (plus RSA-2048 cert when signed locally)
  const localAab = path.join(REPO_ROOT, 'app', 'build', 'outputs', 'bundle', 'playStoreRelease', 'app-playStore-release.aab');
  const localKeystoreProps = path.join(REPO_ROOT, 'keystore.properties');
  if (fs.existsSync(localAab)) {
    const struct = verifyAabStructure(localAab);
    assert.equal(struct.hasEmbeddedProguardMap, true);
    assert.ok(struct.sizeBytes >= 500_000);
    if (fs.existsSync(localKeystoreProps)) {
      const info = verifyAabBundleIntegrity(localAab);
      assert.equal(info.certSha256, EXPECTED_CERT_SHA256);
      assert.equal(info.hasEmbeddedProguardMap, true);
      assert.equal(typeof info.hasNativeDebugSymbols, 'boolean');
    }
  }

  console.log('[test-play-publish] ALL 7 SECURITY, TRACK-ROLLOUT & BUNDLE GATE TESTS PASSED');
}

runTests().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
