#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

EXTRA_GRADLE_ARGS=()
if [ "${CI:-}" = "true" ]; then
  EXTRA_GRADLE_ARGS+=("-Dorg.gradle.configuration-cache=false")
fi

# Keyless CI parity: generate a syntactically valid stub google-services.json when untracked in git
CREATED_STUB_GOOGLE_SERVICES="false"
if [ ! -f "${REPO_ROOT}/app/google-services.json" ]; then
  CREATED_STUB_GOOGLE_SERVICES="true"
  EXTRA_GRADLE_ARGS+=("-PcrashlyticsMappingUpload=false")
  trap 'if [ "${CREATED_STUB_GOOGLE_SERVICES}" = "true" ]; then rm -f "${REPO_ROOT}/app/google-services.json"; fi' EXIT
  cat > "${REPO_ROOT}/app/google-services.json" <<'EOF'
{
  "project_info": {
    "project_number": "000000000000",
    "project_id": "ci-stub-gameday-live",
    "storage_bucket": "ci-stub-gameday-live.firebasestorage.app"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "1:000000000000:android:1111111111111111111111",
        "android_client_info": { "package_name": "com.mflgameday.live" }
      },
      "api_key": [{ "current_key": "AIzaSyCiStubKeyForKeylessVerification00000" }]
    },
    {
      "client_info": {
        "mobilesdk_app_id": "1:000000000000:android:2222222222222222222222",
        "android_client_info": { "package_name": "com.motiskoventures.gameday" }
      },
      "api_key": [{ "current_key": "AIzaSyCiStubKeyForKeylessVerification00000" }]
    }
  ],
  "configuration_version": "1"
}
EOF
fi

echo "[GameDay Live] Running unit tests (direct + playStore), pipeline integration tests, lint checks, Direct APK, and Play Store AAB..."
bash ./gradlew \
  testDirectDebugUnitTest \
  testPlayStoreDebugUnitTest \
  compileDirectDebugAndroidTestKotlin \
  lintDirectDebug \
  lintVitalDirectRelease \
  lintVitalPlayStoreRelease \
  assembleDirectRelease \
  bundlePlayStoreRelease \
  "${EXTRA_GRADLE_ARGS[@]}"

node "${SCRIPT_DIR}/test-play-publish.mjs"
node "${SCRIPT_DIR}/check-release-invariants.mjs"

echo "[GameDay Live] VERIFY PASSED"
