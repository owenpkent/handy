# Handy API Notes

Technical findings from experimenting with the Handy REST APIs.

## API Versions

| Device | API Version | Base URL |
|--------|-------------|----------|
| Handy V2 (FW4+) | REST v2 | `https://www.handyfeeling.com/api/handy/v2` |
| Handy V1 | REST v3 | `https://www.handyfeeling.com/api/handy-rest/v3` |

## Authentication

### V2 API (Current)
- Only requires `X-Connection-Key` header
- No API key or token needed for basic operations
- Connection key from user account at handyfeeling.com

### V1 API (Legacy)
- Requires `X-API-Key` header to get auth token
- Token used in `Authorization: Bearer` header
- More complex auth flow

## Modes (V2 API)

| Mode | Name | Description |
|------|------|-------------|
| 0 | HAMP | Handy Alternating Motion Protocol - direct speed/stroke control |
| 1 | HSSP | Handy Synced Streaming Protocol - plays funscripts from URL |
| 2 | HDSP | Handy Direct Streaming Protocol - real-time position commands |
| 3 | MAINTENANCE | Firmware maintenance mode |
| 4 | HBSP | Newer mode - not fully accessible via public API |

## HAMP Mode (Mode 0) - Working ✅

Primary mode for manual control.

### Endpoints
```
PUT /mode                 → {"mode": 0}
PUT /hamp/start           → starts motion
PUT /hamp/stop            → stops motion
PUT /hamp/velocity        → {"velocity": 0-100}
GET /hamp/velocity        → {"result": 0, "velocity": N}
PUT /slide                → {"min": 0-100, "max": 0-100}
GET /slide                → {"min": N, "max": N}
```

### Important Notes
- Set mode to HAMP once on connect, not on every start
- Set velocity AFTER calling start (otherwise it resets)
- Slide values are percentages (0-100)

## HSSP Mode (Mode 1) - Partially Working ⚠️

For playing funscripts from URLs.

### Endpoints Found
```
GET /hssp/state           → {"result": 0, "state": 3}
GET /status               → {"mode": 1, "status": 3}
```

### State Values
- State 3 = STOPPED

### Not Working
- `/hssp/loop` - 404 error
- `/hssp/setup` - no response
- `/hssp/play` - no response

### Notes
HSSP requires hosting a funscript at a URL and calling setup with that URL. May need additional parameters we haven't discovered.

## HBSP Mode (Mode 4) - Not Accessible ❌

This is likely where HRPP (Handy Repeating Pattern Protocol) lives.

### Findings
- `PUT /mode → {"mode": 4}` returns success
- But device reports `{"mode": 1}` when queried via `/status`
- All `/hbsp/*` endpoints return errors

### HRPP Info (from Handy release notes)
HRPP was introduced in FW 4.0.14 and includes 10 predefined patterns:
1. Blowjob
2. Boob face whip
3. Boobjob
4. Cowgirl
5. Cumshot
6. Doggy style
7. Feetjob
8. Handjob
9. Masturbation
10. Missionary

These patterns are stored on the device firmware and appear to only be accessible via the official Handy app, not the public API.

## Response Codes

### Generic Results
- `{"result": 0}` = Success
- `{"result": 1}` = Already in requested state (e.g., mode already set)

### Error Codes
- 1001 = Device not connected
- 1002 = Device timeout
- 3000 = HAMP error
- 4000-4008 = HSSP errors
- 5000 = HDSP error
- 6000-6001 = Maintenance errors

## Rate Limiting

Headers returned:
- `X-RateLimit-Limit`
- `X-RateLimit-Remaining`
- `X-RateLimit-Reset`

## Device Info Response

```json
{
  "fwVersion": "4.1.1+d4829e99",
  "fwStatus": 0,
  "hwVersion": 1,
  "model": "",
  "branch": "",
  "sessionId": "..."
}
```

### Firmware Status
- 0 = UP_TO_DATE
- 1 = UPDATE_REQUIRED (critical)
- 2 = UPDATE_AVAILABLE (non-critical)

## Best Practices

1. **Check connection first** - Call `/connected` before any operations
2. **Set mode once** - Don't call `/mode` on every action
3. **Order matters** - For HAMP: slide → start → velocity
4. **Apply limits client-side** - Safety limits should be enforced before sending to API
5. **Handle errors gracefully** - Device may timeout or disconnect

## Future Work

- Explore HSSP setup with actual funscript URLs
- Monitor for official HRPP API documentation
- Test HDSP for real-time control applications
