"""Handy as a fan entity (on/off + speed%)."""

from __future__ import annotations

import logging
from typing import Any

from homeassistant.components.fan import FanEntity, FanEntityFeature
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .api import HandyApiError, HandyV2API
from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)

# Fallback speed when turning on without an explicit percentage and no prior speed.
_DEFAULT_SPEED = 50


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up the Handy fan entity from a config entry."""
    api: HandyV2API = hass.data[DOMAIN][entry.entry_id]["api"]
    async_add_entities([HandyFan(api, entry)])


class HandyFan(FanEntity):
    """Handy device exposed as a fan: on/off + speed percentage.

    Modeling as a fan makes the device usable as a generic dimmable smart-home
    entity — works with HA voice intents, Alexa Smart Home, Google Home, etc.,
    without any of those platforms needing to know what the device actually is.
    """

    _attr_has_entity_name = True
    _attr_name = None  # use the device's name
    _attr_supported_features = (
        FanEntityFeature.SET_SPEED
        | FanEntityFeature.TURN_ON
        | FanEntityFeature.TURN_OFF
    )
    _attr_speed_count = 100

    def __init__(self, api: HandyV2API, entry: ConfigEntry) -> None:
        self._api = api
        self._attr_unique_id = entry.unique_id
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.unique_id)},
            name="Handy",
            manufacturer="Sweet Tech AS",
            model="Handy V2",
        )

    @property
    def is_on(self) -> bool:
        return self._api.is_running

    @property
    def percentage(self) -> int | None:
        return self._api.current_speed

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        return {
            "stroke_min": self._api.current_min,
            "stroke_max": self._api.current_max,
        }

    async def async_turn_on(
        self,
        percentage: int | None = None,
        preset_mode: str | None = None,
        **kwargs: Any,
    ) -> None:
        try:
            await self._api.start()
            if percentage is not None:
                await self._api.set_speed(percentage)
            elif self._api.current_speed == 0:
                await self._api.set_speed(_DEFAULT_SPEED)
        except HandyApiError as err:
            _LOGGER.error("Failed to turn on Handy: %s", err)
            raise
        self.async_write_ha_state()

    async def async_turn_off(self, **kwargs: Any) -> None:
        try:
            await self._api.stop()
        except HandyApiError as err:
            _LOGGER.error("Failed to turn off Handy: %s", err)
            raise
        self.async_write_ha_state()

    async def async_set_percentage(self, percentage: int) -> None:
        try:
            if percentage == 0:
                await self._api.stop()
            else:
                if not self._api.is_running:
                    await self._api.start()
                await self._api.set_speed(percentage)
        except HandyApiError as err:
            _LOGGER.error("Failed to set Handy speed: %s", err)
            raise
        self.async_write_ha_state()
