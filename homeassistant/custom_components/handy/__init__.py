"""Handy integration for Home Assistant."""

from __future__ import annotations

import logging

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import HandyApiError, HandyV2API
from .const import (
    ATTR_DELTA,
    ATTR_MAX,
    ATTR_MIN,
    ATTR_POSITION,
    ATTR_SPEED,
    CONF_CONNECTION_KEY,
    DOMAIN,
    POSITION_VALUES,
    SERVICE_FASTER,
    SERVICE_PRESET,
    SERVICE_SET_RANGE,
    SERVICE_SLOWER,
    SPEED_VALUES,
)

_LOGGER = logging.getLogger(__name__)

PLATFORMS: list[Platform] = [Platform.FAN]

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


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up Handy from a config entry."""
    session = async_get_clientsession(hass)
    api = HandyV2API(session, entry.data[CONF_CONNECTION_KEY])

    try:
        await api.connect()
    except HandyApiError as err:
        raise HomeAssistantError(f"Failed to connect to Handy: {err}") from err

    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = api

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)

    _register_services(hass)

    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a config entry."""
    if unload_ok := await hass.config_entries.async_unload_platforms(entry, PLATFORMS):
        api: HandyV2API = hass.data[DOMAIN].pop(entry.entry_id)
        try:
            await api.stop()
        except HandyApiError:
            pass
    return unload_ok


def _resolve_api(hass: HomeAssistant) -> HandyV2API:
    """Resolve a single Handy API client. Raises if zero or multiple configured.

    Multi-device support can be added later by routing services via target device_id.
    """
    apis = list(hass.data.get(DOMAIN, {}).values())
    if not apis:
        raise HomeAssistantError("No Handy device is configured.")
    if len(apis) > 1:
        raise HomeAssistantError(
            "Multiple Handy devices configured; service routing not yet supported."
        )
    return apis[0]


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
