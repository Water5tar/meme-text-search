#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 installation}"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK installation}"
gradle_cmd="${GRADLE_BIN:-$project_dir/gradlew}"
tools_dir="$ANDROID_HOME/build-tools/34.0.0"
"$gradle_cmd" --no-daemon --max-workers=2 :core:test :app:lintRelease :app:assembleRelease
mkdir -p .signing artifacts
chmod 700 .signing
if [[ ! -f .signing/release.jks ]]; then
  if [[ -f .signing/password ]]; then
    echo "A signing password exists without its keystore; restore the original keystore before continuing." >&2
    exit 1
  fi
  (umask 077; python3 -c 'import secrets; from pathlib import Path; Path(".signing/password").write_text(secrets.token_urlsafe(36)+"\n")')
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore .signing/release.jks \
    -storepass:file .signing/password -keypass:file .signing/password \
    -alias meme-ocr -keyalg RSA -keysize 3072 -validity 10000 \
    -dname "CN=Meme OCR, OU=Local Development, O=Meme OCR, C=CN" -noprompt
  chmod 600 .signing/release.jks
fi
"$tools_dir/zipalign" -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk artifacts/aligned.apk
"$JAVA_HOME/bin/java" -jar "$tools_dir/lib/apksigner.jar" sign --ks .signing/release.jks --ks-key-alias meme-ocr \
  --ks-pass file:.signing/password \
  --out artifacts/MemeOCR-1.0.0.apk artifacts/aligned.apk
rm artifacts/aligned.apk
"$JAVA_HOME/bin/java" -jar "$tools_dir/lib/apksigner.jar" verify --verbose artifacts/MemeOCR-1.0.0.apk
"$tools_dir/aapt2" dump permissions artifacts/MemeOCR-1.0.0.apk > artifacts/permissions.txt
if grep -Eq 'android.permission.(INTERNET|ACCESS_NETWORK_STATE|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE)' artifacts/permissions.txt; then
  echo "Unexpected network or image-write permission in APK" >&2
  exit 1
fi
if "$tools_dir/aapt2" dump badging artifacts/MemeOCR-1.0.0.apk | grep -q application-debuggable; then
  echo "Release APK unexpectedly debuggable" >&2
  exit 1
fi
(cd artifacts && sha256sum MemeOCR-1.0.0.apk > SHA256SUMS)
echo "Signed APK: artifacts/MemeOCR-1.0.0.apk"
