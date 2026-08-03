"""Constants for Fire TV IR."""

DOMAIN = "firetv_ir"

CONF_HOST = "host"
CONF_PORT = "port"
CONF_ADBKEY = "adbkey"
CONF_ADB_SERVER_IP = "adb_server_ip"
CONF_ADB_SERVER_PORT = "adb_server_port"
CONF_REMOTE_MAC = "remote_mac"
CONF_REGION = "region"
CONF_PROFILE_ID = "profile_id"
CONF_CODESET_ID = "codeset_id"
CONF_PROFILE_NAME = "profile_name"
CONF_BRAND_NAME = "brand_name"
CONF_DEVICE_NAME = "device_name"
CONF_CODES = "codes"
CONF_DUTY_CYCLE = "duty_cycle"
CONF_BLAST_COUNT = "blast_count"
CONF_POWER_MODE = "power_mode"
CONF_STATE_SOURCE = "state_source"
CONF_TV_IP = "tv_ip"
CONF_STATE_ENTITY = "state_entity"

DEFAULT_PORT = 5555
DEFAULT_ADB_SERVER_PORT = 5037
DEFAULT_REGION = "na"

POWER_MODE_DISCRETE = "discrete"
POWER_MODE_TOGGLE_STATE = "toggle_with_state"
POWER_MODE_TOGGLE_ONLY = "toggle_only"

STATE_DEVICECONTROL = "devicecontrol"
STATE_HDMI = "hdmi_link"
STATE_PING = "ping"
STATE_ENTITY = "ha_entity"
STATE_ASSUMED = "assumed"
# Stick awake/screen_on — same signal androidtv HA preview uses; NOT TV power.
STATE_STICK_AWAKE = "stick_awake"

# App cache — Fire OS denies app writes to /data/local/tmp; read via Magisk su.
RPC_OUT = "/data/data/com.mitch.ftvir/cache/ftvir_rpc_out.json"
RPC_STATUS = "/data/data/com.mitch.ftvir/cache/ftvir_rpc_status.json"
AGENT_COMPONENT = "com.mitch.ftvir/.AgentService"
AGENT_ACTION = "com.mitch.ftvir.RPC"

TV_DEVICE_TYPE = 1
