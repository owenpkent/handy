"""Async client for the Handy V2 REST API.

Mirrors the proven JS client at v2-web/handy-v2-api.js. The web client treats
fwStatus 2 (update available, non-critical) as a fatal error — that's a bug.
This client only fails on fwStatus 1 (update required, critical) and logs a
warning for status 2.
"""

from __future__ import annotations

import logging

import aiohttp

from .const import (
    API_BASE_URL,
    FW_STATUS_UPDATE_AVAILABLE,
    FW_STATUS_UPDATE_REQUIRED,
    MODE_HAMP,
)

_LOGGER = logging.getLogger(__name__)

_REQUEST_TIMEOUT = aiohttp.ClientTimeout(total=10)


class HandyApiError(Exception):
    """Generic Handy API error."""


class HandyNotConnectedError(HandyApiError):
    """Device is online to the cloud but reports not connected."""


class HandyV2API:
    """Async client for the Handy V2 REST API."""

    def __init__(self, session: aiohttp.ClientSession, connection_key: str) -> None:
        self._session = session
        self._key = connection_key

        # Local mirror of last-commanded state. The API doesn't push, so we
        # track what we set; entities read from these.
        self.current_speed: int = 0
        self.current_min: int = 0
        self.current_max: int = 100
        self.is_running: bool = False

    @property
    def _headers(self) -> dict[str, str]:
        return {
            "X-Connection-Key": self._key,
            "Accept": "application/json",
            "Content-Type": "application/json",
        }

    async def _request(
        self,
        method: str,
        path: str,
        json: dict | None = None,
    ) -> dict:
        url = f"{API_BASE_URL}{path}"
        try:
            async with self._session.request(
                method,
                url,
                headers=self._headers,
                json=json,
                timeout=_REQUEST_TIMEOUT,
            ) as resp:
                data = await resp.json(content_type=None)
                if resp.status >= 400:
                    msg = (
                        data.get("error", {}).get("message")
                        if isinstance(data, dict)
                        else None
                    ) or f"HTTP {resp.status}"
                    raise HandyApiError(msg)
                return data if isinstance(data, dict) else {}
        except aiohttp.ClientError as err:
            raise HandyApiError(f"Network error: {err}") from err

    # ---- Public API ----

    async def is_device_connected(self) -> bool:
        data = await self._request("GET", "/connected")
        return bool(data.get("connected"))

    async def get_info(self) -> dict:
        return await self._request("GET", "/info")

    async def connect(self) -> dict:
        """Verify the device is online, put it in HAMP mode, sync local state.

        Returns the device info dict. Raises HandyNotConnectedError if the
        device is offline, HandyApiError for other failures.
        """
        if not await self.is_device_connected():
            raise HandyNotConnectedError("Handy device is not online.")

        info = await self.get_info()
        fw_status = info.get("fwStatus")
        if fw_status == FW_STATUS_UPDATE_REQUIRED:
            raise HandyApiError(
                "Handy firmware update required. Update via handyfeeling.com."
            )
        if fw_status == FW_STATUS_UPDATE_AVAILABLE:
            _LOGGER.warning(
                "Handy firmware update available (non-critical). Current: %s",
                info.get("fwVersion"),
            )

        await self._request("PUT", "/mode", {"mode": MODE_HAMP})

        # Best-effort state sync; missing values are non-fatal.
        try:
            data = await self._request("GET", "/hamp/velocity")
            self.current_speed = int(data.get("velocity", 0))
        except HandyApiError:
            self.current_speed = 0

        try:
            slide = await self._request("GET", "/slide")
            self.current_min = int(slide.get("min", 0))
            self.current_max = int(slide.get("max", 100))
        except HandyApiError:
            pass

        return info

    async def start(self) -> None:
        await self._request("PUT", "/hamp/start")
        self.is_running = True

    async def stop(self) -> None:
        await self._request("PUT", "/hamp/stop")
        self.is_running = False

    async def set_speed(self, velocity: int) -> None:
        velocity = max(0, min(100, int(velocity)))
        await self._request("PUT", "/hamp/velocity", {"velocity": velocity})
        self.current_speed = velocity

    async def set_range(self, min_pct: int, max_pct: int) -> None:
        min_pct = max(0, min(100, int(min_pct)))
        max_pct = max(0, min(100, int(max_pct)))
        if min_pct > max_pct:
            min_pct, max_pct = max_pct, min_pct
        await self._request("PUT", "/slide", {"min": min_pct, "max": max_pct})
        self.current_min = min_pct
        self.current_max = max_pct
