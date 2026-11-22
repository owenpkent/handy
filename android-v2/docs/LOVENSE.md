# Lovense toys (BLE)

Research and design notes for driving a Lovense toy from android-v2, written while adding support for the owner's **Lovense Gush 2**. Code lives in `app/src/main/java/com/handyvoice/v2/api/` (`LovenseProtocol.kt`, `LovenseBleTransport.kt`, `LovenseGatt.kt`, `AndroidLovenseGattPort.kt`) and `ui/ToyPanel.kt`. CLAUDE.md has the per-file summary.

Status: **nothing here has been tested on a real toy yet.** Items marked *unverified* are from third-party sources or inference.

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
| 3 | `XY300001-002Z-4bd4-bbd5-a6920e4c5653` (X usually 4 or 5, Y 0x0 to 0xf, Z usually 3 or 4) | same UUID with `...300002...` | `...300003...` |

Enable notifications on RX (CCCD `0x2902`). **Which generation the Gush 2 uses is unknown.** It isn't in the protocol page's model table (only Nora, Max, Ambi, Lush, Hush, Domi, Edge, Osci, Solace). The connect log line `Lovense: service <uuid>` will tell us.

**Commands.** ASCII, terminated by `;`. Replies also end in `;` and can be split across notifications or batched, so `LovenseProtocol.ReplyBuffer` reassembles them.

| Command | Reply | Use |
|---|---|---|
| `Vibrate:N;` (N 0..20) | `OK;` | Intensity. 0 stops. |
| `Battery;` | e.g. `85;` | Battery percent. Also the keep-alive poll. |
| `DeviceType;` | e.g. `C:11:0082059AD3BD;` (model letter, firmware, BT address) | Logged on connect. |
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

The app only sends `Vibrate:` today, so on a Gush 2 expect the vibration motor only, unless oscillation responds to the same command.

**Plan: probe it on the toy.** The test panel (Settings, Lovense only, no typing) sends each candidate at low, medium or high, and shows the toy's reply next to the button:

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

**Test:** leave the toy connected and idle for 2+ hours (the Bond's timeout) and see whether it's still on. The test panel lets you switch the keep-alive mode (battery only, silent vibrate 0, off) and interval (30 s, 1 min, 5 min) without a new build, and shows how long the toy has been connected and idle. If it powers off whatever we send, the firmware can't be worked around, and only the DIY sleeve, whose firmware we write, is guaranteed to stay on.

---

## Hardware test checklist

1. Settings: choose **Lovense toy**. Turn the Gush on, then tap Connect on the toy page and allow the Bluetooth (and notification) permissions.
2. The status line shows the name and battery, with **no** link-loss warning. The debug log (More, then Copy) shows `Lovense: service <uuid>` (which generation), the `DeviceType` reply and the `AutoSwith` and `GetAS` replies.
3. START, the intensity minus and plus buttons, the slider, patterns and STOP. Do ramps feel smooth at BLE write latency? Does the oscillation motor move?
4. The test panel: try each oscillation candidate and note which moves it and which reply `ERR;`.
5. Turn Bluetooth off mid-run: the toy stops (AutoSwith), the status line goes red, and reconnecting does **not** restart motion until START.
6. Screen off and another app for a while: still connected, with the notification shown.
7. The idle test (above).

---

## Sources

- Lovense BLE protocol: https://buttplug.io/stpihkal/protocols/lovense/
- Community protocol notes: https://github.com/kerstz/lovense-remote/blob/main/docs/research/lovense-ble-protocol.md
- Gush 2: https://futureofsex.net/sex-tech/lovense-gush-2-review-enjoy-the-embrace-of-this-newest-male-massager/, https://manuals.plus/asin/B0DJ2M34XF, https://manuals.plus/asin/B0DSPNGLVN
- Gush 2 Motion oscillation report: https://bb.soulkyn.com/t/lovense-gush-2-motion-not-supported/2188
- Lovense Gush 2 listing: https://aio.lv/en/product--lovense-54089110000--12427106.md
- We-Vibe Bond: https://www.insidehook.com/sex-and-dating/we-vibe-bond/amp
- Lovense Diamo: https://www.reviewed.com/products/lovense-diamo, https://fcc.report/FCC-ID/2AXNT-DIAMO/5049085.pdf
- LELO Tor 3 (auto power-off): https://lyko.com/en/lelo/lelo-tor-3-black, https://testix.co.uk/test/best-cock-ring
- Hot Octopuss Pulse Duo Lux: https://shop.sexwithemily.com/products/hot-octopuss-pulse-duo-lux
- Commercial-device connection complaints: https://justuseapp.com/en/app/890526071/we-connect-by-we-vibe/reviews, https://mv-support.knowledgeowl.com/help/why-does-my-lovense-toy-have-a-weak-connection, https://apps.apple.com/us/app/lovense-remote/id1027312824
