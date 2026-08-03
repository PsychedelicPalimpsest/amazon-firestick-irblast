# ftvir — Fire Stick remote IR for Home Assistant

Blast IR from a paired Fire TV Voice Remote over BLE. **No HDMI-CEC** (CEC crashes this Stick).

## One-time device setup

1. Fire Stick: Magisk + ADB debugging; remote paired.
2. On the HA host (or any machine with `adb` to the Stick):

```bash
cp config.env.example config.env   # set ANDROID_SERIAL / FTVIR_MAC
./ftvir setup
adb reboot
./ftvir status                     # → ok
./ftvir power                      # IR toggle
```

`setup` installs Magisk module **FtvIr** (privileged BLE). After reboot, blasts need **no Magisk prompts**.

## Daily / Home Assistant

```bash
./ftvir power     # uses codes/power.json → exit 0 + prints ok
./ftvir status    # health check
```

Copy this repo onto the HA host (e.g. `/config/ftvir`), ensure `adb` can reach the Stick (`adb connect HOST:5555`), then include:

```yaml
# configuration.yaml
shell_command: !include ftvir/homeassistant/shell_command.yaml
```

Edit paths in `homeassistant/shell_command.yaml` if needed. Call `shell_command.ftvir_power` from any automation.

## Layout

| Path | Purpose |
|------|---------|
| `ftvir` | CLI (HA entrypoint) |
| `config.env.example` | ADB serial, remote MAC, code file |
| `codes/power.json` | InstantFire payload (Vizio #979 POWER_TOGGLE) |
| `setup_permanent.sh` / `ble_ir/` | Magisk priv-app build + install |
| `pronto_to_luckyl.py` | Build other InstantFire JSON from Pronto |
| `homeassistant/` | `shell_command` + automation examples |

## Custom IR codes

```bash
./pronto_to_luckyl.py --pronto "0000 006D ..." > codes/mute.json
./ftvir blast --json codes/mute.json
```

Or point `FTVIR_CODE` in `config.env` at another file for `ftvir power`.

## Do not use

```text
adb shell am broadcast -a amazon.intent.action.TURN_TV_ON   # CEC OTP — crashes OS
```
