"""Pattern engine for the Handy integration.

Patterns are time-based motion envelopes driven by HAMP velocity commands. Each
pattern is an async generator yielding (envelope, duration) tuples — envelope is
0.0..1.0, multiplied by the caller's `intensity` to produce the actual velocity
sent to the device.

Why HAMP and not HRPP/HSSP:
- HRPP (firmware patterns) is locked to the official Handy app — see
  docs/HANDY_API_NOTES.md.
- HSSP (funscripts) requires hosting funscript files at a public URL and is
  marked experimental in Alexa code paths; stopping mid-script needs a full
  mode reset.
- HAMP is the proven, always-on path; orchestrating velocity in Python here
  gives smooth, interruptible patterns with server-side speed-cap enforcement.
"""

from __future__ import annotations

import asyncio
import logging
import math
import random
from collections.abc import AsyncIterator, Awaitable, Callable
from typing import TYPE_CHECKING

from .const import GLOBAL_MAX_SPEED

if TYPE_CHECKING:
    from .api import HandyV2API

_LOGGER = logging.getLogger(__name__)

PatternFunc = Callable[[], AsyncIterator[tuple[float, float]]]


# ---- Pattern definitions ----
# Each yields (envelope_0_to_1, duration_seconds). Loops are infinite — the
# engine cancels the task to stop.

async def _heartbeat() -> AsyncIterator[tuple[float, float]]:
    """Lub-dub rhythm: two pulses then a long rest. ~2s cycle."""
    while True:
        yield (0.85, 0.15)  # lub
        yield (0.0, 0.18)   # gap
        yield (0.7, 0.15)   # dub
        yield (0.0, 1.50)   # rest


async def _wave() -> AsyncIterator[tuple[float, float]]:
    """Smooth sine — slow swell from 0 to peak and back. 8s cycle."""
    period = 8.0
    steps = 32
    dt = period / steps
    while True:
        for i in range(steps):
            phase = (2 * math.pi * i) / steps
            env = 0.5 - 0.5 * math.cos(phase)  # 0..1
            yield (env, dt)


async def _surge() -> AsyncIterator[tuple[float, float]]:
    """Long linear ramp to peak, brief hold, drop, pause. ~24s cycle."""
    while True:
        steps = 24
        for i in range(1, steps + 1):
            yield (i / steps, 0.5)
        yield (1.0, 4.0)   # hold at peak
        yield (0.0, 3.0)   # rest


async def _breathe() -> AsyncIterator[tuple[float, float]]:
    """Gentle in-out, never zero, never peak. 6s cycle."""
    period = 6.0
    steps = 24
    dt = period / steps
    while True:
        for i in range(steps):
            phase = (2 * math.pi * i) / steps
            # Range 0.10..0.60 — soft baseline, never silent, never intense.
            env = 0.10 + 0.50 * (0.5 - 0.5 * math.cos(phase))
            yield (env, dt)


async def _storm() -> AsyncIterator[tuple[float, float]]:
    """Random walk — unpredictable bursts and pauses."""
    while True:
        env = random.random()
        duration = random.uniform(0.4, 1.6)
        yield (env, duration)


PATTERNS: dict[str, PatternFunc] = {
    "heartbeat": _heartbeat,
    "wave": _wave,
    "surge": _surge,
    "breathe": _breathe,
    "storm": _storm,
}


class PatternEngine:
    """Owns the asyncio task that drives the current pattern, if any.

    Single-pattern semantics: starting a new pattern cancels the running one.
    Stopping cancels the task and stops the device.
    """

    def __init__(self, api: HandyV2API) -> None:
        self._api = api
        self._task: asyncio.Task | None = None
        self._current_name: str | None = None

    @property
    def current_pattern(self) -> str | None:
        return self._current_name

    @property
    def is_running(self) -> bool:
        return self._task is not None and not self._task.done()

    async def start(self, name: str, intensity: int) -> None:
        if name not in PATTERNS:
            raise ValueError(f"Unknown pattern: {name!r}. Available: {sorted(PATTERNS)}")

        # Clamp intensity into [0, GLOBAL_MAX_SPEED] — the safety floor for the
        # whole engine. Caller's intensity becomes the pattern's peak velocity.
        intensity = max(0, min(GLOBAL_MAX_SPEED, int(intensity)))

        await self._cancel_existing()

        # Ensure the device is moving — patterns can't reach the device through
        # /hamp/velocity alone if /hamp/start hasn't been issued at least once.
        if not self._api.is_running:
            await self._api.start()

        pattern_func = PATTERNS[name]
        self._current_name = name
        self._task = asyncio.create_task(
            self._run(pattern_func, intensity), name=f"handy_pattern_{name}"
        )
        _LOGGER.info("Handy pattern %s started at intensity %s", name, intensity)

    async def stop(self) -> None:
        await self._cancel_existing()
        try:
            await self._api.stop()
        except Exception:  # noqa: BLE001 — stop is best-effort, log and move on
            _LOGGER.exception("Handy stop after pattern cancel failed")

    async def _cancel_existing(self) -> None:
        if self._task is None:
            return
        if not self._task.done():
            self._task.cancel()
            try:
                await self._task
            except (asyncio.CancelledError, Exception):  # noqa: BLE001
                pass
        self._task = None
        self._current_name = None

    async def _run(self, pattern_func: PatternFunc, intensity: int) -> None:
        """Drive the device until cancelled. Velocity = envelope * intensity, rounded."""
        try:
            async for envelope, duration in pattern_func():
                velocity = int(round(max(0.0, min(1.0, envelope)) * intensity))
                try:
                    await self._api.set_speed(velocity)
                except Exception:  # noqa: BLE001
                    # Skip a beat on transient API failure rather than killing
                    # the whole pattern. If the device is truly gone the next
                    # call will raise too — let HA surface it via the entity.
                    _LOGGER.warning("Pattern velocity write failed; continuing")
                await asyncio.sleep(max(0.05, duration))
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            _LOGGER.exception("Pattern loop crashed")
            raise
