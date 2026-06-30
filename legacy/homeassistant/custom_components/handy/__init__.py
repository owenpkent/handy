"""Handy integration for Home Assistant."""

from __future__ import annotations

import logging

import voluptuous as vol

from homeassistant.components import persistent_notification
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.exceptions import ConfigEntryNotReady, HomeAssistantError
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import HandyApiError, HandyV2API
from .const import (
    ATTR_DELTA,
    ATTR_INTENSITY,
    ATTR_MAX,
    ATTR_MIN,
    ATTR_PATTERN,
    ATTR_POSITION,
    ATTR_SPEED,
    CONF_CONNECTION_KEY,
    DOMAIN,
    POSITION_VALUES,
    SERVICE_FASTER,
    SERVICE_PRESET,
    SERVICE_SET_RANGE,
    SERVICE_SLOWER,
    SERVICE_START_PATTERN,
    SERVICE_STOP_PATTERN,
    SPEED_VALUES,
)
from .patterns import PATTERNS, PatternEngine

_LOGGER = logging.getLogger(__name__)

PLATFORMS: list[Platform] = [Platform.FAN]

EVENT_CONNECTION_FAILED = "handy_connection_failed"
_NOTIFICATION_ID = "handy_connection_failed"

_SET_RANGE_SCHEMA = vol.Schema(
    {
        vol.Required(ATTR_MIN): vol.All(int, vol.Range(min=0, max=100)),
        vol.Required(ATTR_MAX): vol.All(int, vol.Range(min=0, max=100)),
    }
)

_PRESET_SCHEMA = vol.Schema(
    {
        vol.Required(ATTR_SPEED): vol.In(list(SPEED_VALUES.keys())),
        vol.Required(ATTR_POSITION): vol.In(list(POSITION_VALUES.keys())),
    }
)

_DELTA_SCHEMA = vol.Schema(
    {vol.Optional(ATTR_DELTA, default=10): vol.All(int, vol.Range(min=1, max=100))}
)

_START_PATTERN_SCHEMA = vol.Schema(
    {
        vol.Required(ATTR_PATTERN): vol.In(sorted(PATTERNS.keys())),
        vol.Optional(ATTR_INTENSITY, default=50): vol.All(
            int, vol.Range(min=0, max=100)
        ),
    }
)

_STOP_PATTERN_SCHEMA = vol.Schema({})


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up Handy from a config entry."""
    session = async_get_clientsession(hass)
    api = HandyV2API(session, entry.data[CONF_CONNECTION_KEY])

    try:
        await api.connect()
    except HandyApiError as err:
        reason = str(err)
        hass.bus.async_fire(EVENT_CONNECTION_FAILED, {"reason": reason})
        persistent_notification.async_create(
            hass,
            f"Failed to connect to Handy: {reason}",
            title="Handy offline",
            notification_id=_NOTIFICATION_ID,
        )
        raise ConfigEntryNotReady(f"Failed to connect to Handy: {reason}") from err

    persistent_notification.async_dismiss(hass, _NOTIFICATION_ID)

    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = {
        "api": api,
        "engine": PatternEngine(api),
    }

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)

    _register_services(hass)

    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a config entry."""
    if unload_ok := await hass.config_entries.async_unload_platforms(entry, PLATFORMS):
        record = hass.data[DOMAIN].pop(entry.entry_id)
        engine: PatternEngine = record["engine"]
        api: HandyV2API = record["api"]
        try:
            await engine.stop()
        except Exception:  # noqa: BLE001
            _LOGGER.exception("Pattern engine stop on unload failed")
        try:
            await api.stop()
        except HandyApiError:
            pass
    return unload_ok


def _resolve_record(hass: HomeAssistant) -> dict:
    """Resolve the single Handy entry record. Raises if zero or multiple configured.

    Multi-device support can be added later by routing services via target device_id.
    """
    records = list(hass.data.get(DOMAIN, {}).values())
    if not records:
        raise HomeAssistantError("No Handy device is configured.")
    if len(records) > 1:
        raise HomeAssistantError(
            "Multiple Handy devices configured; service routing not yet supported."
        )
    return records[0]


def _resolve_api(hass: HomeAssistant) -> HandyV2API:
    return _resolve_record(hass)["api"]


def _resolve_engine(hass: HomeAssistant) -> PatternEngine:
    return _resolve_record(hass)["engine"]


def _register_services(hass: HomeAssistant) -> None:
    """Register Handy domain services. Idempotent across config entries."""

    async def handle_set_range(call: ServiceCall) -> None:
        api = _resolve_api(hass)
        await api.set_range(call.data[ATTR_MIN], call.data[ATTR_MAX])

    async def handle_preset(call: ServiceCall) -> None:
        api = _resolve_api(hass)
        speed = SPEED_VALUES[call.data[ATTR_SPEED]]
        pos_min, pos_max = POSITION_VALUES[call.data[ATTR_POSITION]]
        # Operation order matters: range → start → speed.
        # See docs/HANDY_API_NOTES.md (set velocity AFTER start, otherwise it resets).
        await api.set_range(pos_min, pos_max)
        if not api.is_running:
            await api.start()
        await api.set_speed(speed)

    async def handle_faster(call: ServiceCall) -> None:
        api = _resolve_api(hass)
        await api.set_speed(api.current_speed + call.data[ATTR_DELTA])

    async def handle_slower(call: ServiceCall) -> None:
        api = _resolve_api(hass)
        await api.set_speed(api.current_speed - call.data[ATTR_DELTA])

    async def handle_start_pattern(call: ServiceCall) -> None:
        engine = _resolve_engine(hass)
        await engine.start(call.data[ATTR_PATTERN], call.data[ATTR_INTENSITY])

    async def handle_stop_pattern(_: ServiceCall) -> None:
        engine = _resolve_engine(hass)
        await engine.stop()

    if not hass.services.has_service(DOMAIN, SERVICE_SET_RANGE):
        hass.services.async_register(
            DOMAIN, SERVICE_SET_RANGE, handle_set_range, schema=_SET_RANGE_SCHEMA
        )
    if not hass.services.has_service(DOMAIN, SERVICE_PRESET):
        hass.services.async_register(
            DOMAIN, SERVICE_PRESET, handle_preset, schema=_PRESET_SCHEMA
        )
    if not hass.services.has_service(DOMAIN, SERVICE_FASTER):
        hass.services.async_register(
            DOMAIN, SERVICE_FASTER, handle_faster, schema=_DELTA_SCHEMA
        )
    if not hass.services.has_service(DOMAIN, SERVICE_SLOWER):
        hass.services.async_register(
            DOMAIN, SERVICE_SLOWER, handle_slower, schema=_DELTA_SCHEMA
        )
    if not hass.services.has_service(DOMAIN, SERVICE_START_PATTERN):
        hass.services.async_register(
            DOMAIN,
            SERVICE_START_PATTERN,
            handle_start_pattern,
            schema=_START_PATTERN_SCHEMA,
        )
    if not hass.services.has_service(DOMAIN, SERVICE_STOP_PATTERN):
        hass.services.async_register(
            DOMAIN,
            SERVICE_STOP_PATTERN,
            handle_stop_pattern,
            schema=_STOP_PATTERN_SCHEMA,
        )
