# Fire TV IR (Home Assistant)

Native Home Assistant integration that turns a Fire Stick + Amazon Voice Remote into a **TV IR blaster**. No HDMI-CEC (CEC can crash some Fire OS builds).

## What you get

- Config Flow (ADB host/port/adbkey like [androidtv](https://www.home-assistant.io/integrations/androidtv))
- `media_player` (TV power, volume, mute, HDMI inputs) + `remote` (every function in the codeset)
- Live Amazon IDC TV catalog via a Stick-side Magisk agent (MAP token) — **no vendored IR dump**
- Only the codeset you select is stored in the config entry
- Pluggable TV state: HDMI HPD (read-only), ping, HA entity, or assumed

## Requirements

1. Fire Stick with **Magisk** and ADB debugging
2. Paired Amazon Voice Remote with IR LED
3. Stick signed into Amazon (needed for IDC catalog)
4. Home Assistant 2024.1+ (HACS custom component)

## Install

### 1. Magisk helper (one-time on the Stick)

From this repo on a machine that can `adb` to the Stick:

```bash
cp config.env.example config.env   # set ANDROID_SERIAL
./ftvir setup
adb reboot
./ftvir status                     # → ok
```

This installs privileged `com.mitch.ftvir` (`AMAZON_REMOTE_DFU`) with InstantFire blast + IDC catalog RPC.

### 2. HACS / Home Assistant

1. Copy `custom_components/firetv_ir` into `/config/custom_components/firetv_ir` (or add this repo as a HACS custom repository, category **Integration**).
2. Restart Home Assistant.
3. **Settings → Devices & services → Add integration → Fire TV IR**.
4. Enter Stick ADB host/port → pick remote → search TV brand → test blast → confirm profile → choose power mode + state source.

### 3. Optional debug CLI

`./ftvir` remains for install/debug (`status`, `setup`, raw `blast`). Day-to-day control is the integration, not `shell_command`.

## Power modes

| Mode | `turn_on` | `turn_off` |
|------|-----------|------------|
| `discrete` | `POWER_ON` if present, else toggle-if-off | `POWER_OFF` if present, else toggle-if-on |
| `toggle_with_state` | `POWER_TOGGLE` only if state off | `POWER_TOGGLE` only if state on |
| `toggle_only` | always `POWER_TOGGLE` | always `POWER_TOGGLE` |

Toggle-only brands (e.g. many Vizio profiles) should use **toggle_with_state** plus HDMI-link or ping.

## State sources (never IR)

- **HDMI link** — read-only HPD / sink props on the Stick (no CEC transmits)
- **Ping** — TV LAN IP from the Stick
- **HA entity** — another binary_sensor / switch / etc.
- **Assumed** — last command (weak; last resort)

## Services

- `firetv_ir.send_function` — `function: HDMI_4` (optional `entry_id`)
- `firetv_ir.refresh_profile` — re-fetch codeset from IDC via Stick

## Layout

| Path | Purpose |
|------|---------|
| `custom_components/firetv_ir/` | HACS integration |
| `ble_ir/` | Magisk priv-app sources (`AgentService` RPC) |
| `ftvir` / `setup_permanent.sh` | Install + debug CLI |
| `codes/power.json` | CLI fixture only (not used by HA after profile select) |
| `pronto_to_luckyl.py` | Offline Pronto → InstantFire helper |

## Legal / catalog

This project does **not** ship Amazon IR profile dumps (`ir_profiles/`). Catalog calls go to Amazon IDC from the signed-in Stick; Home Assistant persists only the user-selected codeset.

## Risks

- Magisk + privileged helper required for blast + catalog (`devicecontrol.WRITE` + InstantFire)
- Catalog uses DeviceControl’s AIDL (Stick must be Amazon-signed-in). Known codeset id works when brand lists are thin.
- HDMI state must stay **read-only** (no CEC OTP / Image View On)
- Amazon schema may drift; agent parsing is tolerant

## Do not use

```text
adb shell am broadcast -a amazon.intent.action.TURN_TV_ON   # CEC OTP — can crash OS
```
