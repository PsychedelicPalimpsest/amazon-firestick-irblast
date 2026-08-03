#!/usr/bin/env bash
# One-time: Magisk module installing com.mitch.ftvir as priv-app (AMAZON_REMOTE_DFU).
# After reboot, ./ftvir power needs no Magisk prompts.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-adb}"
[[ -f "$ROOT/config.env" ]] && { set -a; # shellcheck disable=SC1091
  source "$ROOT/config.env"; set +a; }

echo "Building FtvIr APK..."
"$ROOT/ble_ir/build.sh"

APK="$ROOT/ble_ir/out/ftvir.apk"
[[ -f "$APK" ]] || { echo "build failed" >&2; exit 1; }

"$ADB" ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} push "$APK" /data/local/tmp/ftvir.apk >/dev/null

cat > /tmp/ftvir_mod.sh <<'EOF'
#!/system/bin/sh
set -e
MOD=/data/adb/modules/ftvir
mkdir -p "$MOD/system/priv-app/FtvIr"
cp /data/local/tmp/ftvir.apk "$MOD/system/priv-app/FtvIr/FtvIr.apk"
chmod 644 "$MOD/system/priv-app/FtvIr/FtvIr.apk"
rm -f "$MOD/disable" "$MOD/remove"
touch "$MOD/auto_mount"
cat > "$MOD/module.prop" <<PROP
id=ftvir
name=FtvIr BLE IR Blaster
version=v1.6
versionCode=7
author=local
description=Privileged InstantFire IR + DeviceControl catalog agent (no CEC)
PROP
# Forever-allow adb shell su (KEYMAP fallback)
cat > "$MOD/service.sh" <<'SVC'
#!/system/bin/sh
(
  sleep 5
  DB=/data/adb/magisk.db
  [ -f "$DB" ] || exit 0
  command -v sqlite3 >/dev/null && \
    sqlite3 "$DB" "REPLACE INTO policies (uid, policy, until, logging, notification) VALUES (2000, 2, 0, 0, 0);" 2>/dev/null || true
  magisk --sqlite "REPLACE INTO policies (uid, policy, until, logging, notification) VALUES (2000, 2, 0, 0, 0);" 2>/dev/null || true
) &
SVC
chmod 755 "$MOD/service.sh"
echo "module ok: $MOD"
EOF

"$ADB" ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} push /tmp/ftvir_mod.sh /data/local/tmp/ftvir_mod.sh >/dev/null
"$ADB" ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} shell chmod 755 /data/local/tmp/ftvir_mod.sh

if ! "$ADB" ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} shell 'su -c id' 2>/dev/null | grep -q 'uid=0'; then
  echo "Magisk su unavailable. Approve prompt / reboot Magisk, then re-run." >&2
  exit 3
fi

"$ADB" ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} shell 'su -c "sh /data/local/tmp/ftvir_mod.sh"' 2>/dev/null \
  | grep -v 'WARNING: linker' || \
"$ADB" ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} shell 'su -mm -c "sh /data/local/tmp/ftvir_mod.sh"' 2>/dev/null \
  | grep -v 'WARNING: linker'

echo
echo "Reboot once:  adb reboot"
echo "Then:         ./ftvir status && ./ftvir power"
