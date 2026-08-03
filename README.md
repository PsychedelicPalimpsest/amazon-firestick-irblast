# Fire TV IR (Home Assistant)

Native Home Assistant integration that turns a Fire Stick + Amazon Voice Remote into a **TV IR blaster**. No HDMI-CEC (CEC can crash some Fire OS builds).

## What you get

- Config Flow (ADB host/port/adbkey like [androidtv](https://www.home-assistant.io/integrations/androidtv))
- `media_player` (TV power, volume, mute, HDMI inputs) + `remote` + `select` IR-button dropdown (every function in the codeset)
- Live Amazon IDC TV catalog via a Stick-side Magisk agent (MAP token) — **no vendored IR dump**
- Only the codeset you select is stored in the config entry
- Pluggable TV state: DeviceControl screen/TV power (recommended), ping, HDMI HPD, HA entity, or assumed (last resort)

## Requirements

1. Fire Stick with **Magisk** and ADB debugging
2. Paired Amazon Voice Remote with IR LED
3. Stick signed into Amazon (needed for IDC catalog)
4. Home Assistant 2024.1+ (HACS custom component)

## Install

Do this in order: Magisk helper on the Stick first, then the Home Assistant integration.

### 1. Magisk helper (one-time on the Stick)

From a machine that can `adb` to the Stick (clone this repo):

```bash
cp config.env.example config.env   # set ANDROID_SERIAL=HOST:5555
./ftvir setup
adb reboot
./ftvir status                     # → ok
```

This installs privileged `com.mitch.ftvir` with InstantFire blast + DeviceControl catalog RPC. Home Assistant will refuse to set up if this package is missing or not privileged.

### 2. Home Assistant integration

The HA host must be able to reach the Stick on TCP ADB (default `5555`). Enable network debugging on the Stick and approve the HA host’s ADB key when prompted.

#### Option A — manual install (copy files)

1. Copy the integration folder onto the HA config share:

   ```bash
   # From this repo → HA config directory
   mkdir -p /config/custom_components
   cp -a custom_components/firetv_ir /config/custom_components/
   ```

   On supervised / HA OS, that is usually the same place as `configuration.yaml` (Samba/SSH add-on: `/config/custom_components/firetv_ir/`).

2. Restart Home Assistant (**Developer tools → YAML → Restart**, or reboot the host).

3. **Settings → Devices & services → Add integration** → search **Fire TV IR**.

4. Complete the Config Flow:
   - **ADB**: Stick host, port (`5555`), optional path to `adbkey`, or ADB server IP/port if you use a shared `adbd` on the HA host
   - **Remote**: pick the bonded Amazon Voice Remote (MAC)
   - **Brand / profile**: search TV brand (e.g. Vizio), or enter a known codeset id; optionally **Test blast**, then **Confirm**
   - **Power & state**: `discrete` / `toggle_with_state` / `toggle_only`, plus DeviceControl TV state (default), ping, HDMI HPD, or assumed. The androidtv ADB entity preview is Stick awake — not TV power.

5. You should get `media_player.*_tv` and `remote.*_tv` entities.

#### Option B — HACS custom repository

1. Install [HACS](https://hacs.xyz/) if you do not already have it.
2. HACS → **⋯** → **Custom repositories** → add this repo URL, category **Integration**.
3. HACS → **Integrations** → find **Fire TV IR** → **Download**.
4. Restart Home Assistant.
5. **Settings → Devices & services → Add integration → Fire TV IR** and finish the Config Flow (same as Option A step 4).

### 3. CLI (same engine as HA)

`./ftvir` uses `custom_components/firetv_ir` over ADB (Python). After Magisk setup:

```bash
cp config.env.example config.env   # ANDROID_SERIAL, FTVIR_MAC, power mode
./ftvir use-profile 4696           # download + cache TV codeset
./ftvir buttons                    # list functions
./ftvir on                         # power on (mode + state)
./ftvir off
./ftvir send VOLUME_UP
./ftvir HDMI_4                     # shorthand for send
./ftvir brands --filter Vizio
```

Needs the project venv deps once: `python3 -m venv .venv && .venv/bin/pip install 'adb-shell' 'pure-python-adb'`.

## Power modes

| Mode | `turn_on` | `turn_off` |
|------|-----------|------------|
| `discrete` | `POWER_ON` if present, else toggle-if-off | `POWER_OFF` if present, else toggle-if-on |
| `toggle_with_state` | `POWER_TOGGLE` only if state off | `POWER_TOGGLE` only if state on |
| `toggle_only` | always `POWER_TOGGLE` | always `POWER_TOGGLE` |

Toggle-only brands (e.g. many Vizio profiles) should use **toggle_with_state** plus a real state source. Unknown state refuses `POWER_TOGGLE` (no blind toggle).

## State sources (never invent from IR)

- **devicecontrol** (default) — DeviceControl `getScreenState` on the Stick (HPD + fused providers). Read-only; no IR / no CEC sends from us.
- **ping** — TV LAN IP from the Stick
- **hdmi_link** — raw HPD sysfs / dumpsys (often empty on tank)
- **ha_entity** — another binary_sensor / switch / etc.
- **stick_awake** — Fire Stick `dumpsys power` awake (same class of signal as the androidtv HA preview). **Not TV power.**
- **assumed** — last command only when explicitly selected (never a silent fallback)

Sending IR never marks the TV on unless `assumed` is the chosen source.

## Running with HA-Firemote and androidtv

[HA-Firemote](https://github.com/PRProd/HA-Firemote) is a **Lovelace card**, not an integration. It does not open its own ADB session. For Fire Stick it drives the stock **[androidtv](https://www.home-assistant.io/integrations/androidtv/)** `media_player` (and optionally **androidtv_remote**) — navigation, apps, play/pause, home, etc. via ADB keyevents on the **Stick**.

**firetv_ir** is a separate integration: IR blasts from the Voice Remote to the **external TV** (power, volume, mute, HDMI inputs).

| Control | Use entity | Integration |
|--------|------------|-------------|
| D-pad, home, back, apps, Stick sleep | `media_player.fire_tv_*` | androidtv (Firemote `entity`) |
| TV power / volume / mute / HDMI | `media_player.*_tv`, `remote.*_tv_remote` | firetv_ir |

Firemote’s default power/volume/mute buttons call `androidtv.adb_command` (`POWER`, `VOLUME_UP`, …) or `media_player.turn_on` on the **Stick** entity. That does **not** send IR to the TV (Firemote’s FAQ says the same — use overrides or CEC). CEC is unsafe on many Fire OS builds; use **button overrides** to firetv_ir instead.

### Shared ADB (one Stick client)

The Stick tolerates **one** TCP ADB attachment. Do **not** run two direct `adb-shell` clients (androidtv + firetv_ir both without a server).

1. Run a single **ADB server** on the HA host (ADB add-on, Docker `adb`, or `adb -a nodaemon server start`).
2. In **androidtv** config: set **ADB server IP** = HA host (`127.0.0.1`), port `5037`.
3. In **firetv_ir** config: same **ADB server IP/port** (not a second direct connection).
4. Use the **same authorized key** (approve once on the Stick for the HA host).

Both integrations talk to `adbd` on the server; the server keeps the one Stick session. Avoid desktop `adb` or `./ftvir` to the Stick while HA is active unless they also use that server (`adb -H 127.0.0.1 …`).

### Firemote → TV via button overrides

Point TV buttons at firetv_ir entities (YAML on the card):

```yaml
type: custom:firemote-card
entity: media_player.fire_tv_192_168_1_112   # androidtv — Stick
device_family: amazon-fire
device_type: fire_stick_4k
button_overrides:
  power-button:
    action: remote.toggle
    target:
      entity_id: remote.bedroom_tv_tv_remote      # firetv_ir
  volume-up-button:
    action: media_player.volume_up
    target:
      entity_id: media_player.bedroom_tv_tv
  volume-down-button:
    action: media_player.volume_down
    target:
      entity_id: media_player.bedroom_tv_tv
  mute-button:
    action: media_player.volume_mute
    target:
      entity_id: media_player.bedroom_tv_tv
```

HDMI launcher buttons on Firemote normally hit androidtv. For **TV** HDMI via IR, use a **custom launcher** or override:

```yaml
custom_launchers:
  - friendly_name: TV HDMI 4
    label: HDMI4
    icon: mdi:hdmi-port
    action: remote.turn_on
    target:
      entity_id: remote.bedroom_tv_tv_remote
    data:
      activity: HDMI_4
app_launch_1: customlauncher TV HDMI 4
```

Keep Firemote’s `entity` on androidtv for everything that controls the Stick; use firetv_ir only for TV-side IR.

### Firemote: empty ADB dropdown + “Device is already configured”

These two symptoms usually mean the same thing: **androidtv is already set up**, but **no `media_player` entity with platform `androidtv` is available** for the card picker.

| Symptom | Meaning |
|--------|---------|
| Firemote **Android Debug Bridge Entity** dropdown is empty (`- - - -`) | No `media_player.*` in the entity registry with `platform: androidtv` |
| Add integration → **Device is already configured** | A config entry for that Stick (MAC/IP) already exists — do not add a second one |

**Do not** add another Android Debug Bridge entry. Fix the existing one:

1. **Settings → Devices & services → Android Debug Bridge** — open the **existing** entry (not Add integration).
2. **Developer Tools → States** — search `media_player.` and look for an entity whose platform is `androidtv` (not `firetv_ir`). `firetv_ir` entities control the **TV** via IR; Firemote ignores them.
3. If no androidtv `media_player` exists, or it is `unavailable`:
   - **Reload** the Android Debug Bridge entry.
   - Check **Settings → System → Logs** for androidtv setup errors (ADB key, timeout, connection refused).
   - Confirm **ADB server IP** matches firetv_ir (`127.0.0.1:5037` on the HA host).
   - On the Stick: approve the HA host ADB key if prompted.
4. If the entry is broken and reload does not help: **delete** the Android Debug Bridge config entry, then add it once with the shared ADB server settings above.
5. **Firemote workaround** — if the entity exists in States but the dropdown is still empty, use card YAML and set `entity` manually:

```yaml
type: custom:firemote-card
entity: media_player.fire_tv_192_168_1_112   # your androidtv media_player entity_id
device_family: amazon-fire
```

Refresh the browser after androidtv recovers.

## Automations — press any IR button

### Remote entity (recommended)

Power and sources use normal HA remote / media_player actions:

```yaml
# TV power
action: remote.turn_off
target:
  entity_id: remote.YOUR_TV_tv_remote

# Switch HDMI input (activity list = HDMI_* / INPUT_* only)
action: remote.turn_on
target:
  entity_id: remote.YOUR_TV_tv_remote
data:
  activity: HDMI_4

# Any codeset function (aliases like volume_up work too)
action: remote.send_command
target:
  entity_id: remote.YOUR_TV_tv_remote
data:
  command: volume_up
```

`remote` attributes: `activity_list` (sources only), `functions` (full codeset for send_command).

### Select dropdown (reduced list)

Entity `select.*_ir_button` lists HDMI/INPUT sources plus common extras (not every codeset button). Full list is in attribute `all_functions`.

```yaml
action: select.select_option
target:
  entity_id: select.YOUR_TV_ir_button
data:
  option: HDMI_4
```

### Domain service (any function by name)

```yaml
action: firetv_ir.send_function
data:
  function: POWER_TOGGLE
  entity_id: media_player.YOUR_TV_tv
```

## Services

- `firetv_ir.send_function` — `function: HDMI_4` + target `entity_id` (or `entry_id`)
- `firetv_ir.refresh_profile` — re-fetch codeset from IDC via Stick
- `firetv_ir.adb_shell` — run a raw shell command on the Stick (returns `output`)

```yaml
action: firetv_ir.adb_shell
data:
  command: getprop ro.build.version.incremental
  entity_id: media_player.YOUR_TV_tv
response_variable: stick_info
```

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
