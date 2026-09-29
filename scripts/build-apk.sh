#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 installation}"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK installation}"
if [[ ! -f signing.properties ]]; then
  echo "Prepare a private signing.properties using the original release key; see README.md." >&2
  exit 1
fi
gradle_cmd="${GRADLE_BIN:-$project_dir/gradlew}"
"$gradle_cmd" --no-daemon --max-workers=2 :core:test :app:lintRelease :app:assembleRelease
tools_dir="$ANDROID_HOME/build-tools/35.0.0"
apk="app/build/outputs/apk/release/app-release.apk"
"$JAVA_HOME/bin/java" -jar "$tools_dir/lib/apksigner.jar" verify --verbose "$apk"
mkdir -p artifacts
"$tools_dir/aapt2" dump permissions "$apk" > artifacts/permissions.txt
if grep -Eq 'android.permission.(INTERNET|ACCESS_NETWORK_STATE|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE)' artifacts/permissions.txt; then
  echo "Unexpected network or image-write permission in APK" >&2
  exit 1
fi
if "$tools_dir/aapt2" dump badging "$apk" | grep -q application-debuggable; then
  echo "Release APK unexpectedly debuggable" >&2
  exit 1
fi
cp "$apk" artifacts/MemeSearch-1.1.0.apk
if command -v sha256sum >/dev/null 2>&1; then
  (cd artifacts && sha256sum MemeSearch-1.1.0.apk > SHA256SUMS)
else
  (cd artifacts && shasum -a 256 MemeSearch-1.1.0.apk > SHA256SUMS)
fi
echo "Signed APK: artifacts/MemeSearch-1.1.0.apk"
