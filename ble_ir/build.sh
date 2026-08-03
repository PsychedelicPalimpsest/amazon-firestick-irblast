#!/usr/bin/env bash
# Build + optionally install the IR-only LuckyL blaster APK (no CEC).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$(ls -1d "$SDK"/build-tools/* | sort -V | tail -1)"
# Prefer API 22 jar if present; else newest
if [[ -f "$SDK/platforms/android-22/android.jar" ]]; then
  ANDROID_JAR="$SDK/platforms/android-22/android.jar"
else
  ANDROID_JAR="$(ls -1d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar"
fi
AAPT="$BT/aapt"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

OUT="$ROOT/out"
GEN="$OUT/gen"
OBJ="$OUT/obj"
CLASSES="$OUT/classes"
rm -rf "$OUT"
mkdir -p "$GEN" "$OBJ" "$CLASSES"

echo "android.jar=$ANDROID_JAR"
"$AAPT" package -f -m -J "$GEN" -M "$ROOT/AndroidManifest.xml" -S "$ROOT/res" -I "$ANDROID_JAR"

# Empty res dir needs a placeholder for aapt package later
mkdir -p "$ROOT/res/values"
[[ -f "$ROOT/res/values/strings.xml" ]] || printf '%s\n' \
  '<?xml version="1.0" encoding="utf-8"?><resources><string name="app_name">FtvIr</string></resources>' \
  >"$ROOT/res/values/strings.xml"

mapfile -t SRC < <(find "$ROOT/src" "$GEN" -name '*.java' | sort)
javac -source 1.8 -target 1.8 -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
  -d "$CLASSES" "${SRC[@]}"

"$D8" --min-api 22 --output "$OBJ" $(find "$CLASSES" -name '*.class')

"$AAPT" package -f -M "$ROOT/AndroidManifest.xml" -S "$ROOT/res" -I "$ANDROID_JAR" -F "$OUT/unsigned.apk"
cd "$OBJ" && zip -q -u "$OUT/unsigned.apk" classes.dex

"$ZIPALIGN" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KS="$OUT/debug.keystore"
if [[ ! -f "$ROOT/debug.keystore" ]]; then
  keytool -genkeypair -v -keystore "$ROOT/debug.keystore" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname 'CN=FtvIr,OU=Dev,O=Local,L=Local,ST=NA,C=US' >/dev/null
fi
"$APKSIGNER" sign --ks "$ROOT/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/ftvir.apk" "$OUT/aligned.apk"

echo "Built $OUT/ftvir.apk"
if [[ "${1:-}" == "install" ]]; then
  adb install -r "$OUT/ftvir.apk"
  echo "Installed com.mitch.ftvir"
fi
