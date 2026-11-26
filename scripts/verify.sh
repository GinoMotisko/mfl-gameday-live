#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

EXTRA_GRADLE_ARGS=()
if [ "${CI:-}" = "true" ]; then
  EXTRA_GRADLE_ARGS+=("-Dorg.gradle.configuration-cache=false")
fi

echo "[GameDay Live] Running unit tests (direct + playStore), pipeline integration tests, lint checks, Direct APK, and Play Store AAB..."
bash ./gradlew \
  testDirectDebugUnitTest \
  testPlayStoreDebugUnitTest \
  lintDirectDebug \
  lintVitalPlayStoreRelease \
  assembleDirectRelease \
  bundlePlayStoreRelease \
  "${EXTRA_GRADLE_ARGS[@]}"

node "${SCRIPT_DIR}/test-play-publish.mjs"

echo "[GameDay Live] VERIFY PASSED"
