#!/usr/bin/env bash
# Builds build/icloud-frame.apk without Gradle, like wesbos/photo-frame-dashboard's kiosk.
# Needs a JDK (17+) and the Android SDK's build-tools and platform 34:
#   ANDROID_HOME defaults to ~/Library/Android/sdk
#
# Updates only install over a build signed with the same key, so the first build creates
# ~/.android/icloud-frame.keystore. Back it up: without it, installed frames can't be updated.
set -euo pipefail
cd "$(dirname "$0")"

# CI sets these from the release tag (android-v<VERSION_CODE>)
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"
[[ "$VERSION_CODE" =~ ^[1-9][0-9]{0,8}$ ]] || { echo "VERSION_CODE must be a whole number" >&2; exit 1; }

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="${BUILD_TOOLS:-$(ls -d "$ANDROID_HOME"/build-tools/3[4-9]* | sort -V | tail -1)}"
JAR="$ANDROID_HOME/platforms/android-34/android.jar"
KS="${KEYSTORE:-$HOME/.android/icloud-frame.keystore}"
KS_PASS_FILE="$KS.password"

[ -f "$JAR" ] || { echo "Missing $JAR (install platform android-34)" >&2; exit 1; }

rm -rf build && mkdir -p build/gen/com/alexbrazier/icloudframe build/classes

cat > build/gen/com/alexbrazier/icloudframe/BuildInfo.java <<EOF
package com.alexbrazier.icloudframe;

final class BuildInfo {
    static final String VERSION_NAME = "$VERSION_NAME";
    static final int VERSION_CODE = $VERSION_CODE;
}
EOF

"$BT/aapt2" compile --dir res -o build/res.zip
# Target 28 keeps the pre-Android-10 Wi-Fi API and storage access, and installs on Android 6-15
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml build/res.zip \
  --java build/gen --min-sdk-version 23 --target-sdk-version 28 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -classpath "$JAR" -d build/classes \
  $(find src build/gen -name '*.java')

"$BT/d8" --min-api 23 --lib "$JAR" --output build $(find build/classes -name '*.class')
(cd build && zip -qj base.apk classes.dex)
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk

if [ ! -f "$KS" ] && [ -n "${CI:-}" ]; then
  # A new key would make an APK that can't update any existing frame
  echo "Missing signing key $KS (set the ANDROID_KEYSTORE_BASE64 secret)" >&2
  exit 1
fi
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  openssl rand -hex 16 > "$KS_PASS_FILE"
  chmod 600 "$KS_PASS_FILE"
  keytool -genkeypair -keystore "$KS" -storepass "$(cat "$KS_PASS_FILE")" -keypass "$(cat "$KS_PASS_FILE")" \
    -alias icloudframe -keyalg RSA -keysize 3072 -validity 20000 \
    -dname "CN=iCloud Frame" >/dev/null 2>&1
  echo "Created signing key $KS (back it up with $KS_PASS_FILE)"
fi
# The key's password is the keystore's, so one --ks-pass covers both
"$BT/apksigner" sign --ks "$KS" --ks-pass "file:$KS_PASS_FILE" \
  --out build/icloud-frame.apk build/aligned.apk
# The certificate must be the one frames already trust (signing-cert.sha256), so a wrong or
# replaced key fails here instead of shipping an update no frame will install
CERT=$("$BT/apksigner" verify --print-certs build/icloud-frame.apk | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
if [ -f signing-cert.sha256 ] && [ "$CERT" != "$(tr -d '[:space:]' < signing-cert.sha256)" ]; then
  echo "Signed with certificate $CERT, but signing-cert.sha256 expects another key" >&2
  exit 1
fi
echo "Built build/icloud-frame.apk (version $VERSION_NAME, code $VERSION_CODE, certificate $CERT)"
