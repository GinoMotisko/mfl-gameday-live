#!/usr/bin/env node
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  EXPECTED_CERT_SHA256,
  enforceReviewQueueSafetyGate,
  parseArgs,
  verifyAabBundleIntegrity,
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

  const callsCommit = [];
  await withPlayAppEdit('tok', { commit: true, fetchImpl: mockEditFetch(callsCommit) }, async () => 'done');
  assert.equal(callsCommit.length, 3);
  assert.match(callsCommit[0], /^POST .*\/edits$/);
  assert.match(callsCommit[1], /^POST .*\/edits\/edit-123:validate$/);
  assert.match(callsCommit[2], /^POST .*\/edits\/edit-123:commit$/);

  // 5. If local signed PlayStore AAB + keystore.properties exist, verify RSA-2048 cert + embedded R8 proguard.map
  const localAab = path.join(REPO_ROOT, 'app', 'build', 'outputs', 'bundle', 'playStoreRelease', 'app-playStore-release.aab');
  const localKeystoreProps = path.join(REPO_ROOT, 'keystore.properties');
  if (fs.existsSync(localAab) && fs.existsSync(localKeystoreProps)) {
    const info = verifyAabBundleIntegrity(localAab);
    assert.equal(info.certSha256, EXPECTED_CERT_SHA256);
    assert.equal(info.hasEmbeddedProguardMap, true);
  }

  console.log('[test-play-publish] ALL 5 SECURITY & TRANSACTION GATE TESTS PASSED');
}

runTests().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
