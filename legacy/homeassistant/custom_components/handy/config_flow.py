"""Config flow for the Handy integration."""

from __future__ import annotations

import logging
from typing import Any

import voluptuous as vol

from homeassistant import config_entries
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import HandyApiError, HandyNotConnectedError, HandyV2API
from .const import CONF_CONNECTION_KEY, DOMAIN

_LOGGER = logging.getLogger(__name__)

_USER_SCHEMA = vol.Schema({vol.Required(CONF_CONNECTION_KEY): str})


class HandyConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Handle the Handy config flow (UI setup)."""

    VERSION = 1

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> config_entries.ConfigFlowResult:
        errors: dict[str, str] = {}

        if user_input is not None:
            key = user_input[CONF_CONNECTION_KEY].strip()
            session = async_get_clientsession(self.hass)
            api = HandyV2API(session, key)

            try:
                info = await api.connect()
            except HandyNotConnectedError:
                errors["base"] = "device_offline"
            except HandyApiError as err:
                _LOGGER.error("Handy API error during config flow: %s", err)
                errors["base"] = "api_error"
            except Exception:  # noqa: BLE001
                _LOGGER.exception("Unexpected error during Handy config flow")
                errors["base"] = "unknown"
            else:
                await self.async_set_unique_id(key)
                self._abort_if_unique_id_configured()
                title = f"Handy ({info.get('fwVersion', 'unknown')})"
                return self.async_create_entry(
                    title=title,
                    data={CONF_CONNECTION_KEY: key},
                )

        return self.async_show_form(
            step_id="user",
            data_schema=_USER_SCHEMA,
            errors=errors,
        )
