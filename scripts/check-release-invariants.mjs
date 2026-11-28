#!/usr/bin/env node
/**
 * Release & CI Invariant Checker (Zero Dependencies)
 * Executed automatically by `scripts/verify.cmd` and `scripts/verify.sh`
 * to prevent version/doc drift, Play Store policy regressions, CI secret leaks,
 * and verify-script divergence between Windows local builds and GitHub Actions.
 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = (rel) => fs.readFileSync(path.join(repoRoot, rel), 'utf8');

// 1. Version & Submission Pack Lockstep
const gradle = read('app/build.gradle.kts');
const vcMatch = gradle.match(/\bversionCode\s*=\s*(\d+)/);
const vnMatch = gradle.match(/\bversionName\s*=\s*"([^"]+)"/);
assert(vcMatch && vnMatch, 'app/build.gradle.kts must declare versionCode and versionName');
const versionCode = vcMatch[1];
const versionName = vnMatch[1];

const pack = read('docs/PLAY_STORE_SUBMISSION_PACK.md');
for (const expectedToken of [
  `v${versionName}`,
  `versionCode = ${versionCode}`,
  `GameDay-Live-PlayStore-v${versionName}.aab`,
  `mapping-playStoreRelease-v${versionName}.txt`,
  `smoke --version=v${versionName} --version-code=${versionCode}`,
]) {
  assert(
    pack.includes(expectedToken),
    `docs/PLAY_STORE_SUBMISSION_PACK.md out of sync with build.gradle.kts — missing "${expectedToken}"`
  );
}
assert(
  !/^\s*-\s*\*\*Phone\*\*:\s*\+?\d/m.test(pack),
  'docs/PLAY_STORE_SUBMISSION_PACK.md must keep app-level Store Listing Contact Phone intentionally blank (no phone number)'
);

// 2. Privacy, Foreground Service & Google Play Manifest Invariants
const mainManifest = read('app/src/main/AndroidManifest.xml');
assert(mainManifest.includes('android:allowBackup="false"'), 'Main manifest must set android:allowBackup="false"');
assert(
  mainManifest.includes('android:foregroundServiceType="mediaPlayback"'),
  'Main manifest must declare foregroundServiceType="mediaPlayback"'
);
assert(
  mainManifest.includes('com.google.android.gms.permission.AD_ID') &&
    mainManifest.includes('tools:node="remove"'),
  'Main manifest must strip AD_ID with tools:node="remove"'
);

const playManifest = read('app/src/playStore/AndroidManifest.xml');
assert(
  playManifest.includes('REQUEST_IGNORE_BATTERY_OPTIMIZATIONS') &&
    playManifest.includes('tools:node="remove"'),
  'Play Store manifest must strip REQUEST_IGNORE_BATTERY_OPTIMIZATIONS'
);

const playNetCfg = read('app/src/playStore/res/xml/network_security_config.xml');
assert(
  playNetCfg.includes('cleartextTrafficPermitted="false"'),
  'Play Store network_security_config.xml must enforce cleartextTrafficPermitted="false"'
);

// 3. Keyless Secret-Free CI & 40-char Commit SHA Pinning
const ciYml = read('.github/workflows/ci.yml');
assert(ciYml.includes('contents: read'), 'ci.yml must restrict permissions to contents: read');
assert(!/\$\{\{\s*secrets\./.test(ciYml), 'ci.yml must remain strictly keyless (zero secrets.* references)');
const usesLines = [...ciYml.matchAll(/uses:\s*([^\s#]+)/g)].map((m) => m[1]);
assert(usesLines.length > 0, 'ci.yml must contain GitHub Actions uses: steps');
for (const actionRef of usesLines) {
  assert(
    /@[0-9a-f]{40}$/.test(actionRef),
    `ci.yml Action "${actionRef}" must be pinned to a full 40-char commit SHA`
  );
}

// 4. Windows verify.cmd <-> Linux/CI verify.sh Task Parity
const verifyCmd = read('scripts/verify.cmd');
const verifySh = read('scripts/verify.sh');
const requiredTasks = [
  'testDirectDebugUnitTest',
  'testPlayStoreDebugUnitTest',
  'compileDirectDebugAndroidTestKotlin',
  'lintDirectDebug',
  'lintVitalDirectRelease',
  'lintVitalPlayStoreRelease',
  'assembleDirectRelease',
  'bundlePlayStoreRelease',
  'test-play-publish.mjs',
  'check-release-invariants.mjs',
];
for (const token of requiredTasks) {
  assert(verifyCmd.includes(token), `scripts/verify.cmd missing required gate "${token}"`);
  assert(verifySh.includes(token), `scripts/verify.sh missing required gate "${token}"`);
}

// 5. Built Artifact & Embedded R8 Mapping Verification
const aabPath = path.join(repoRoot, 'app/build/outputs/bundle/playStoreRelease/app-playStore-release.aab');
assert(fs.existsSync(aabPath), `Missing Play Store AAB at ${aabPath}`);
const aabBytes = fs.readFileSync(aabPath);
assert(aabBytes.length >= 3_000_000, `Play Store AAB unexpectedly small (${aabBytes.length} bytes)`);
assert(
  aabBytes.includes('BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map'),
  'Play Store AAB is missing embedded R8 BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map'
);

const mappingPath = path.join(repoRoot, 'app/build/outputs/mapping/playStoreRelease/mapping.txt');
assert(fs.existsSync(mappingPath), `Missing R8 mapping file at ${mappingPath}`);
assert(fs.statSync(mappingPath).size >= 1_000, 'R8 mapping.txt unexpectedly empty');

const signedApkPath = path.join(repoRoot, 'app/build/outputs/apk/direct/release/app-direct-release.apk');
const unsignedApkPath = path.join(repoRoot, 'app/build/outputs/apk/direct/release/app-direct-release-unsigned.apk');
const apkPath = fs.existsSync(signedApkPath) ? signedApkPath : unsignedApkPath;
assert(fs.existsSync(apkPath), 'Missing built Direct Release APK (signed or unsigned)');
assert(fs.statSync(apkPath).size >= 3_000_000, `Direct APK unexpectedly small (${fs.statSync(apkPath).size} bytes)`);

console.log(
  `[check-release-invariants] ALL INVARIANTS PASSED (v${versionName} / versionCode=${versionCode}, AAB+proguard.map & CI parity verified)`
);
