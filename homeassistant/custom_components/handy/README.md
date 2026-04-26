# Handy custom integration for Home Assistant

Wraps the Handy V2 REST API as a Home Assistant `fan` entity plus a few
domain services. Modeling as a fan is deliberate: it's a generic dimmable
entity, so HA voice intents, Alexa Smart Home, and Google all work with it
without any platform needing to know what the device actually is.

## What it exposes

- `fan.handy` — on/off (HAMP start/stop), `percentage` (speed 0-100), with
  `stroke_min` / `stroke_max` as state attributes.
- `handy.set_range` — set min/max stroke positions.
- `handy.preset` — apply a speed (slow/medium/fast) × position
  (top/middle/bottom/full/natural) preset; starts motion if not running.
- `handy.faster` / `handy.slower` — adjust speed by a delta (default 10).

## Deploy

Copy this directory into your HA config:

```bash
# From this repo
cp -r homeassistant/custom_components/handy ~/handy-ha/config/custom_components/

# Or symlink during dev
ln -s "$(pwd)/homeassistant/custom_components/handy" \
      ~/handy-ha/config/custom_components/handy
```

Restart Home Assistant:

```bash
docker compose restart homeassistant
```

Then **Settings → Devices & Services → Add Integration → Handy**, paste your
connection key. The integration verifies the device is online and reachable
before completing setup.

## Test without voice

**Settings → Developer Tools → Services**:

- `fan.turn_on` on `fan.handy` with `percentage: 40` → device starts at 40% speed
- `fan.turn_off` on `fan.handy` → device stops
- `handy.preset` with `speed: fast`, `position: full` → device runs at 80% over the full stroke range
- `handy.set_range` with `min: 20`, `max: 60` → stroke range adjusted
- `handy.faster` (no args) → speed increases by 10

If those work end-to-end, the integration is good and you can move on to
authoring voice intents (see `docs/HOME_ASSISTANT_GUIDE.md`).

## Operation order (why it matters)

The Handy API is stateful and order-sensitive. From `docs/HANDY_API_NOTES.md`:

> Set velocity AFTER calling start (otherwise it resets)

The integration enforces this: `handy.preset` does range → start → speed.
The fan entity's `turn_on` follows the same rule.

## What's not here yet

- Global limits / speed limit (safety caps that scale all commands).
- Sensation presets (vibrate, pulse, tease, etc.).
- HSSP funscript playback.
- Multi-device support (currently allows one configured Handy per HA instance).

These are tracked in `TODO.md` and can be added without breaking the current
entity contract.
