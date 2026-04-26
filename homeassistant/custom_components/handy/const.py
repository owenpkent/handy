"""Constants for the Handy integration."""

DOMAIN = "handy"

CONF_CONNECTION_KEY = "connection_key"

API_BASE_URL = "https://www.handyfeeling.com/api/handy/v2"

# HAMP mode (mode 0): direct speed + stroke control. The mode used by all manual
# control surfaces (web app, Android app). See docs/HANDY_API_NOTES.md.
MODE_HAMP = 0

# Firmware status codes returned by /info.
FW_STATUS_UP_TO_DATE = 0
FW_STATUS_UPDATE_REQUIRED = 1   # critical
FW_STATUS_UPDATE_AVAILABLE = 2  # non-critical

# Speed presets (velocity 0-100).
SPEED_VALUES: dict[str, int] = {
    "slow": 25,
    "medium": 50,
    "fast": 80,
}

# Position presets — (min, max) stroke percentages.
POSITION_VALUES: dict[str, tuple[int, int]] = {
    "top": (50, 90),
    "middle": (30, 70),
    "bottom": (10, 50),
    "full": (0, 100),
    "natural": (20, 80),
}

# Service names.
SERVICE_SET_RANGE = "set_range"
SERVICE_PRESET = "preset"
SERVICE_FASTER = "faster"
SERVICE_SLOWER = "slower"

# Service field names.
ATTR_MIN = "min"
ATTR_MAX = "max"
ATTR_SPEED = "speed"
ATTR_POSITION = "position"
ATTR_DELTA = "delta"
