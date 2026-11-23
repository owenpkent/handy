# Lovense toys (BLE)

Research and design notes for driving a Lovense toy from android-v2, written while adding support for the owner's **Lovense Gush 2**. Code lives in `app/src/main/java/com/handyvoice/v2/api/` (`LovenseProtocol.kt`, `LovenseBleTransport.kt`, `LovenseGatt.kt`, `AndroidLovenseGattPort.kt`) and, in `ui/`, `ToyPanel.kt` (toy page) and `ToyLabActivity.kt` (test panel), plus `service/ToyConnectionService.kt`. CLAUDE.md has the per-file summary.

Status: everything below is built and merged to `main` (PRs #3 and #4). **v0.23**, published on 2026-11-22 as a test build, had the first hardware try: the Gush 2 was found and then rejected at service discovery (see GATT services below). **v0.24** (published 2026-11-23) fixes that and has not been re-tested on the toy, so nothing past the scan is confirmed on hardware yet. Items marked *unverified* are from third-party sources or inference. The hardware checklist at the end is the open work.

---

## Why a commercial toy at all

The owner used a **We-Vibe Bond** and it powered itself off after about 2 h of no use. As a wheelchair user with muscular dystrophy, they can't reach the device to switch it back on, so an idle shut-off ends the session. A commercial toy is a stopgap until the DIY sleeve (`docs/WEARABLE_DEVICE_RESEARCH.md`) exists. That doc's "Hands-off usability" section lists the general failure modes of commercial toys and the rules the sleeve follows instead.

## Comparable devices and idle shut-off

| Device | Form | Idle shut-off | Notes |
|---|---|---|---|
| We-Vibe Bond | Closed ring | About 2 h (owner experience) | The reason for this work. |
| LELO Tor 3 | Closed ring | 10 to 20 min, set in the app | Worse than the Bond. About 85 min battery. |
| We-Vibe Verge / Pivot | Closed rings | Unknown | Same maker and app as the Bond, so probably the same (*unverified*). |
| Lovense Diamo | Closed ring | None documented | Open BLE protocol. |
| Hot Octopuss Pulse Duo (Lux) | Open wrap around the shaft | Not documented | Has a remote. |
| **Lovense Gush 2** | Open sleeve, hands-free with an optional band | None documented | Open BLE protocol. **The owner has one.** |

No product listing or manual states "never turns off". Only the Tor 3's timer is confirmed.

**Closed rings are ruled out** with a condom catheter: the research doc's safety table bans any closed ring or band (constriction injuries are documented). The Pulse Duo and Gush 2 are not rings.

**Gush 2 fit, to check by hand:** it works mainly on the glans, which is where the catheter sheath and drain tip sit (the same problem noted for the Hot Octopuss Pulse). It is open-ended, so the drain may pass through. The software is the easy part. Fit is the real question.

---

## Protocol

From the [Buttplug Lovense protocol page](https://buttplug.io/stpihkal/protocols/lovense/), plus a community write-up ([kerstz/lovense-remote](https://github.com/kerstz/lovense-remote/blob/main/docs/research/lovense-ble-protocol.md)).

**Discovery.** Toys advertise names starting `LVS-` or `LOVE-`. A name prefix can't go in a `ScanFilter`, so filter in the scan callback.

**GATT services (three generations).** Pick whichever the toy exposes:

| Gen | Service | TX (write) | RX (notify) |
|---|---|---|---|
| 1 | `0000fff0-0000-1000-8000-00805f9b34fb` | `0000fff2-...` | `0000fff1-...` |
| 2 | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` (Nordic UART) | `6e400002-...` | `6e400003-...` |
| 3 | `PPPP0001-002Z-4bd4-bbd5-a6920e4c5653` (`PPPP` is four hex digits that vary per model, Z is 3 or 4) | same UUID with `PPPP0002` | `PPPP0003` |

**The gen 3 prefix is not always `XY30`.** The protocol page describes the pattern as `XY300001-002Z-...`, and the app's first matcher followed it. Buttplug's device config ([`lovense.yml`](https://raw.githubusercontent.com/buttplugio/buttplug/master/crates/buttplug_server_device_config/device-config/protocols/lovense.yml)), which is the list real clients use, has about 40 services, and half of them do not fit: `4c410001`, `45440001`, `45420001`, `45490001`, `45410001`, `45460001`, `454c0001`, `53440001`, `46530001`, `42410001`, `43410001`, `4f430001`, `455a0001`, `57440001`, `414e0001`, `5a410001`, `43420001`, `51420001`, alongside the `xx300001` family (`50`, `5a`, `57`, `53`, `4f`, `42`, `43`, `4c`, `56`, `58`, `52`, `46`, `4a`, `54`, `4e`, `51`, `55`, `48`). One entry is odd: service `50300011-0023-...` with TX `50300012` and RX `50300013`. Buttplug matches an explicit list, and its discovered-services list is longer than its advertised-services list, so match on what discovery returns, not on the advertisement.

**First hardware attempt (2026-11-22, v0.23): the Gush 2 was found by the scan and then rejected** with "This device does not look like a Lovense toy", because its service did not fit `xx300001`. From v0.24, `LovenseProtocol.pickChannel` accepts any `PPPP0001-002Z-4bd4-bbd5-a6920e4c5653` service (plus the odd `50300011`), and if no known layout matches it falls back to **any vendor service with one writable and one notifying characteristic** (standard Bluetooth SIG services and the Nordic firmware-update services are skipped). An RX that can only *indicate* is accepted as well (notify preferred); it is subscribed by writing `02 00` to the CCCD, not the notification value `01 00`, which such a characteristic ignores. The scan has already matched the name, so that guess is safe. If even that fails, the debug log lists every service UUID the toy exposed.

Enable notifications on RX (CCCD `0x2902`). **Which service the Gush 2 uses is still unrecorded.** It isn't in the protocol page's model table (only Nora, Max, Ambi, Lush, Hush, Domi, Edge, Osci, Solace), and Buttplug's config gives it no UUID of its own. The connect log line `Lovense: service <uuid>` will tell us (it adds "unlisted, matched by write + notify" when the fallback picked it); write the UUID here once seen.

**Commands.** ASCII, terminated by `;`. Replies also end in `;` and can be split across notifications or batched, so `LovenseProtocol.ReplyBuffer` reassembles them.

| Command | Reply | Use |
|---|---|---|
| `Vibrate:N;` (N 0..20) | `OK;` | Intensity. 0 stops. |
| `Battery;` | e.g. `85;` | Battery percent. Also the keep-alive poll. |
| `DeviceType;` | e.g. `C:11:0082059AD3BD;` (model code, firmware, BT address). The code is one or two letters: Buttplug lists the Gush as `ED` and the **Gush 2 as `EZ`**. | Logged on connect. |
| `PowerOff;` | `OK;` | Not used: the whole point is to stay on. |
| `AutoSwith:On:Off;` | `OK;` or `ERR;` | Safety settings (below). |
| `GetAS;` | e.g. `AutoSwith:1:0;` | Read the safety settings back. |

**The AutoSwith safety setting (spelling is Lovense's).** Two flags: (1) stop vibrating on an accidental Bluetooth disconnect, (2) return to the last level on reconnect. We want **1 on, 2 off**, so a dropped link stops the toy and it never restarts by surprise. The **write uses words** (`AutoSwith:On:Off;`); the **numeric form is only the `GetAS;` reply**. An early version of our code sent the numeric form as a write, and PR review caught it. The app now writes the word form, requires a non-`ERR` reply, reads it back with `GetAS;`, and only treats protection as active if it reads `1:0`. If not, the toy still connects (its firmware is untested) but the status line shows "Link-loss protection not confirmed". Both flags are marked beta in the source.

**Velocity mapping.** The app's speed scale 0..100 maps to `Vibrate:` level `round(v * 20 / 100)`, with any v > 0 giving at least 1. Writes are deduplicated, since a glide sends many 1-point steps. **Max speed is stored per device** (`lovense_max_speed`, default 100). The Handy's cap is a low stroke-speed comfort limit, and applying it to the toy would pin vibration at level 1 to 2 of 20.

---

## Gush 2: oscillation is undocumented

The Gush 2 has **vibration and oscillation**. The original Gush had vibration only. Its button cycles seven preset vibration and oscillation patterns, and the Lovense Remote app adjusts oscillation levels separately ([review](https://futureofsex.net/sex-tech/lovense-gush-2-review-enjoy-the-embrace-of-this-newest-male-massager/), [manual](https://manuals.plus/asin/B0DJ2M34XF)).

**No public source gives the raw oscillation command.**
- The community protocol write-up lists the Gush as single-vibrator. Its command table covers only `Vibrate`, `Rotate`, `Air`, `Battery`, `DeviceType` and `PowerOff`. It notes that stroker oscillation "isn't reliably documented publicly", and that Buttplug abstracts it as `Oscillate` with position and duration.
- A [Soulkyn forum report](https://bb.soulkyn.com/t/lovense-gush-2-motion-not-supported/2188) on the **Gush 2 Motion** (a separate variant) describes it connecting but not responding. The Lovense app called the mode "Oscillation" while the integration sent vibrate. That suggests oscillation is a distinct command (*unverified*).

- Buttplug itself (checked 2026-11-22) gives the Gush (`ED`) and Gush 2 (`EZ`) its default feature set: one vibrate actuator, 0 to 20, plus battery. Its single-actuator Lovense code sends `Vibrate:N;` even for an oscillate request, and nothing in it sends `Oscillate:` to a Gush. So Buttplug users get vibration only, and the test panel's probe rows are still the way to find the real command.
- Kinky Makers (the OSSM / OSFM open-source machine community) publish nothing on Lovense: a search of their GitHub repos (OSSM-controller, OSFM-docs, OSFM-app and others) for "lovense" finds no hits. Their Bluetooth work is a private control path for their own machines. Their Discord was not searched.

The app only sends `Vibrate:` today, so on a Gush 2 expect the vibration motor only, unless oscillation responds to the same command.

**Probe it on the toy (built).** The test panel (`ui/ToyLabActivity.kt`: Settings, Connection, "Toy test panel", Lovense only, no typing) has a row per candidate with Low (5), Mid (10) and High (20) buttons. Each sends `<Name>:<N>;` and shows the toy's raw reply beside the row (`OK;`, `ERR;` or `(no reply)`), kept until that row is probed again. STOP ALL zeros every candidate, then `Vibrate:0;`. Commands are allow-listed in `LovenseProtocol` (these names x levels 0..20), so nothing else can be sent. While a probe level is nonzero the keep-alive's silent `Vibrate:0;` is paused, so it cannot cancel the experiment; leaving the screen runs Stop all. Every probe and reply goes to the debug log with a timestamp. Candidates:

`Oscillate:N;`, `Vibrate1:N;`, `Vibrate2:N;`, `Rotate:N;`, `Thrusting:N;`, plus `Vibrate:N;` as the baseline.

An `ERR;` reply means the firmware doesn't know the command, so a probe teaches something even when nothing moves. Once the right command is known, add it to `LovenseProtocol` and expose it as a second intensity on the toy page.

---

## Keeping the toy on and connected

Two separate problems.

**1. The phone side: keeping the link alive.** In the app, a BLE connection lives only as long as the process.

| Situation | Without a service | With the v0.23 foreground service |
|---|---|---|
| App on screen | Connected (the screen stays on while the app is in front) | Connected |
| Picture-in-picture | Connected | Connected |
| Screen off (power button) | The link usually holds (the BT controller keeps it), but the 30 s poll can stall while the CPU sleeps. The listening wake lock only runs while voice listening, and the toy page doesn't listen. | The service's partial wake lock keeps the poll on time. |
| Another app in front | At risk: Android can kill or freeze a background app | The foreground service, shown as a "Connected to ..." notification, keeps the process alive |

Plus a one-time prompt to exempt the app from battery optimization, which aggressive OEM battery savers otherwise override.

**2. The toy side: its own idle timer.** No app can override firmware. What the app can do is keep traffic flowing, on the theory that the toy only times out when idle or disconnected (*unverified*):
- `Battery;` every 30 s while connected. It's a query, so it may not count as activity.
- A silent `Vibrate:0;` every 30 s while **stopped**. A motor command is more likely to reset an idle timer, and it can't move the toy. It is sent under the operation lock and only while not running, so it can never stop a running toy.

**Test:** leave the toy connected and idle for 2+ hours (the Bond's timeout) and see whether it's still on. The test panel (built) lets you switch the keep-alive mode (battery only, battery + silent `Vibrate:0;` which is the default, off) and interval (30 s, 1 min, 5 min) live, with no reconnect and no new build (`toy_keepalive_mode`, `toy_keepalive_interval_s`). Its idle timer shows "Connected for 1 h 12 min", "Idle for 45 min" (since the last nonzero motion command, or connect) and "Last keep-alive: 12 s ago (OK)". The last 5 disconnects (time, how long it had been connected, how long idle, unexpected or by you) are saved, so you can come back after hours and see when and after how much idle the toy dropped. Copy report puts all of it (status, probe results, settings, history, last 50 log lines) on the clipboard to paste. If it powers off whatever we send, the firmware can't be worked around, and only the DIY sleeve, whose firmware we write, is guaranteed to stay on.

**Stop is never silently lost.** A stop or disconnect after a probe such as `Oscillate:20;` also writes `<Name>:0;` for every probed name and clears the probe, so the keep-alive resumes. A failed `Vibrate:0;` is retried (150, 300, 600 ms); if it still fails the client reports "Stop not confirmed by toy" and the transport keeps a pending-stop flag. The keep-alive loop then resends `Vibrate:0;` every 2 s in any mode (Off and battery-only included) until one lands. A fresh start or nonzero probe supersedes it.

**Motion loops and connects.** `HandyVoiceApp.awaitMotionTeardown()` cancels and joins the pattern and humanize loops. It runs when the shared client reports DISCONNECTED and, through `api.preConnect`, at the start of every `connect()` (home, toy page, setup panel, splash, bootstrap, test panel), so a stale loop cannot drive a reconnected toy. A connect cancelled after the GATT link opens closes it and rethrows the cancellation.

---

## Hardware test checklist

1. Settings: choose **Lovense toy**. Turn the Gush on, then tap Connect on the toy page and allow the Bluetooth (and notification) permissions.
2. The status line shows the name and battery, with **no** link-loss warning. The debug log (More, then Copy) shows `Lovense: service <uuid>` (which generation), the `DeviceType` reply and the `AutoSwith` and `GetAS` replies.
3. START, the intensity minus and plus buttons, the slider, patterns and STOP. Do ramps feel smooth at BLE write latency? Does the oscillation motor move?
4. The test panel (Settings, Toy test panel): try each probe row at Low / Mid / High and note which moves the toy and which reply `ERR;`. Check STOP ALL stops everything and that the Status block shows the service UUID, DeviceType and AutoSwith / GetAS replies. Try each keep-alive mode and interval, leave the toy idle, then come back and use Copy report to read the idle timer and the disconnect history.
5. Turn Bluetooth off mid-run: the toy stops (AutoSwith), the status line goes red, and reconnecting does **not** restart motion until START.
6. Screen off and another app for a while: still connected, with the notification shown.
7. The idle test (above).

---

## Related: Ferality and Intiface (future option)

[Ferality](https://ferality.app) (solo project, invite-only beta as of 2026-11) plays erotic audio with a haptic score composed on a timeline, so the listener presses one play button and the toy follows the score. It reaches toys over Bluetooth through Intiface / Buttplug, and its author tests mainly on Lovense, We-Vibe and one Svakom, with Thrust / Stroke run on a Handy among others. Handy position mapping was still untested when we heard of it, and the author said the Bluetooth / Intiface route had no dead-man cutoff yet.

Why it matters here:

- **Score playback.** A timeline score is a richer version of `PatternEngine`'s looping envelopes. If Ferality (or anyone) exports a score format, android-v2 could play scores alongside the 12 built-in patterns, with voice `stop` and the idle auto-stop still on top.
- **Intiface / Buttplug as a transport.** Buttplug covers far more toys than our hand-written Lovense transport. A Buttplug-backed `DeviceTransport` (talking to Intiface Central over its local WebSocket) would bring them in without new protocol work, at the cost of a second app running and an extra hop of latency. Not planned; the hand-written transport stays while only the Gush 2 matters.
- **Shared findings.** Its author has Lovense and We-Vibe hardware, so the Gush 2 oscillation probe results and the We-Vibe Bond idle shut-off findings above are worth swapping with them.

---

## Sources

- Lovense BLE protocol: https://buttplug.io/stpihkal/protocols/lovense/
- Buttplug's Lovense device config (the full service UUID list and model codes): https://raw.githubusercontent.com/buttplugio/buttplug/master/crates/buttplug_server_device_config/device-config/protocols/lovense.yml
- Buttplug's Lovense protocol code: https://github.com/buttplugio/buttplug/tree/master/crates/buttplug_server/src/device/protocol_impl/lovense
- Kinky Makers (nothing Lovense-related): https://github.com/KinkyMakers
- Community protocol notes: https://github.com/kerstz/lovense-remote/blob/main/docs/research/lovense-ble-protocol.md
- Gush 2: https://futureofsex.net/sex-tech/lovense-gush-2-review-enjoy-the-embrace-of-this-newest-male-massager/, https://manuals.plus/asin/B0DJ2M34XF, https://manuals.plus/asin/B0DSPNGLVN
- Gush 2 Motion oscillation report: https://bb.soulkyn.com/t/lovense-gush-2-motion-not-supported/2188
- Lovense Gush 2 listing: https://aio.lv/en/product--lovense-54089110000--12427106.md
- We-Vibe Bond: https://www.insidehook.com/sex-and-dating/we-vibe-bond/amp
- Lovense Diamo: https://www.reviewed.com/products/lovense-diamo, https://fcc.report/FCC-ID/2AXNT-DIAMO/5049085.pdf
- LELO Tor 3 (auto power-off): https://lyko.com/en/lelo/lelo-tor-3-black, https://testix.co.uk/test/best-cock-ring
- Hot Octopuss Pulse Duo Lux: https://shop.sexwithemily.com/products/hot-octopuss-pulse-duo-lux
- Commercial-device connection complaints: https://justuseapp.com/en/app/890526071/we-connect-by-we-vibe/reviews, https://mv-support.knowledgeowl.com/help/why-does-my-lovense-toy-have-a-weak-connection, https://apps.apple.com/us/app/lovense-remote/id1027312824
