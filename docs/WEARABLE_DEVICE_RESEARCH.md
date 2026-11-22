# Wearable Device Research

How we could build our own low-profile device that is worn under trousers while seated, works around an external condom catheter, and is controlled by the `android-v2` app the same way The Handy is.

Status (2026-11-21): research done, motor sample order carted (not yet placed), CAD assembly at v5 in [`hardware/wearable-sleeve/`](../hardware/wearable-sleeve/) (see [CAD assembly](#cad-assembly)). Nothing physical built yet. Sources are listed at the end; items marked *unverified* come from general knowledge, not a source we checked.

---

## Headline findings

1. **Clinical guidance on sex while wearing a condom catheter is mixed.** One spinal-rehab handout (UHN) says not to use a condom catheter during sex. Others (United Spinal, the Scottish spinal unit) suggest leaving slack at the tip, or taking the sheath off and putting it back on afterwards. So "works over the catheter" is not something any guidance endorses. The design should work over the sheath without ever loading it. It should also work with the sheath off, which may be the safer everyday use. **Ask the continence nurse or urologist before wearing a prototype.**
2. **Stroking motion is out.** A linear stroker (Handy, OSR2) can't fit under trousers while seated. The realistic mechanisms are **vibration** and **soft pneumatic pressure**, both on the shaft or base, because the sheath tip and drain cover the glans.
3. **No open-source wearable like this exists.** The closest products are Hot Octopuss Pulse (an oscillating plate that works without an erection, developed with disabled users) and Lovense Max (an air-pump sleeve with an open BLE protocol). Neither fits a catheter, so this would fill a real gap.
4. **The electronics are cheap and well trodden.** An ESP32 or nRF52 BLE board, a haptic driver, a mini pump and valve, and a pressure sensor cost roughly **$70 to $130** in parts.
5. **The app change is small.** Pull a `HapticDevice` interface out of `HandyV2ApiClient`, then add a BLE client. Voice, patterns, humanize and the stop ramp all carry over.

---

## What exists already (prior art)

| Device / project | Mechanism | Lesson for us |
|---|---|---|
| Ferticare 2.0, Viberect X3 (medical penile vibratory stimulation) | Vibration on the glans/frenulum, about 100 Hz, 2.5 to 4 mm amplitude | These are the clinically proven numbers, but they were set for spinal cord injury, where sensation is reduced. Sensation is usually intact in MD, so much smaller consumer amplitudes are probably enough (*unverified*, test it). |
| Hot Octopuss Pulse Solo / PULSE II | Oscillating plate, works without an erection | Closest consumer analogue and has disability roots. Its plate sits on the glans, which the sheath and drain block. |
| Lovense Max | Air-pump contraction sleeve | Proves pneumatic contraction works. It has an open BLE protocol and is supported by Buttplug/Intiface. Too bulky to wear. |
| Arcwave Ion | Air-pressure pulses in a sealed chamber | Needs a seal over the glans, which a catheter tip prevents. |
| Bump'n, Liberator Wanda, Sportsheets thigh harness | Hands-free mounts and harnesses | Shows the conventions for mounting a device without straps around the penis. |
| We-Vibe Bond (owner used it) | App-controlled wearable | It powers itself off after about 2 h of no use, and the owner can't reach it to switch it back on. Ours must never auto power-off while idle (see Hands-off usability). |
| Lovense Diamo | App-controlled vibrating ring | Closed ring, so it breaks our no-ring rule with a catheter. No auto-off timer documented. Open BLE protocol (see App integration). |
| LELO Tor 3 | App-controlled vibrating ring | Closed ring. Auto power-off is set in the app to 10 to 20 min, worse than the Bond. About 85 min battery. |
| We-Vibe Verge / Pivot | App-controlled vibrating rings | Closed rings. Same maker and app as the Bond, so expect the same idle shut-off (*unverified*). |
| Hot Octopuss Pulse Duo (Lux) | Wrap-around shaft vibrator with remote | Open wrap, not a closed ring. Auto-off not documented. |
| Lovense Gush 2 | Open vibrating/oscillating sleeve, hands-free with an optional band | Not a ring. No auto-off documented. Open BLE protocol. |
| OSR2 / SR6 + TCode | Open-source stroker with an open control protocol | Not wearable, but the protocol is a reference for command design. |
| Programmable Air, Soft Robotics Toolkit, CU Haptic Pneumatics Toolkit | Open DIY pump/valve/bladder kits | A ready-made base for the pneumatic part. |

---

## Proposed concept

A **soft pouch insert** in snug briefs. Nothing is strapped around the penis.

- **Sleeve:** a C-shaped medical-silicone shell that sits along the underside and sides of the shaft over the sheath. It is held in place by the briefs' pouch, like packing underwear, not by a band. **Leaving the C open means it can never close into a ring**, which removes the constriction/tourniquet risk the safety research ranks as worst.
- **Phase 1 actuators:** small LRAs in outward pads on the shell (2 underside, 2 flanks) plus a 100 Hz LRA in a pod at the base, five in all. **One DRV2605L per motor** behind a TCA9548A I2C mux, because the DRV2605L has a fixed I2C address. Each motor then has its own waveform and rated voltage, which allows a wave along the shaft.
- **Phase 2 actuators:** thin air bladders in the C-shell that inflate and deflate in rhythm for a squeezing feel. They are soft, flat when idle, and they **vent by default** (a normally-open valve), so a power loss or fault releases the pressure.
- **Drain path:** the tip and tube leave the pouch through a bottom opening, with no contact or pressure on the tip connection. This guards against kinks, twisting and blow-off.
- **Electronics pack:** **mount it on the wheelchair frame, not the body.** A thin tube (pneumatic) or cable (vibration) runs to the sleeve. This keeps the battery and hard parts out of the seat and thigh pressure zone, and away from urine if there's a leak. A belt pack is the fallback.

```
Android app (voice, patterns, humanize)
        | BLE
        v
Chair-mounted pack: XIAO ESP32-C3 / nRF52840
   |- I2C  -> TCA9548A mux -> 5 x DRV2605L -> 5 LRAs in sleeve   (phase 1)
   |- PWM  -> MOSFET   -> mini pump -> bladders     (phase 2)
   |- GPIO -> MOSFET   -> normally-open vent valve
   |- I2C  -> MPRLS pressure sensor (tee on the line)
   |         + mechanical relief valve on the line
   |- big physical STOP button on the pack
   '- 1S LiPo + protection + fuse, USB-C charge (never while worn)
```

---

## Safety constraints (design requirements)

| Constraint | Target | Source / confidence |
|---|---|---|
| No closed ring or band around the penis | Open C-shell only. Any elastic part must break away. | Constriction and strangulation injuries are documented with condom catheters. |
| Sustained skin contact pressure | Well below 32 mmHg, aim for under 20 to 25 mmHg when idle | 32 mmHg capillary closing figure is *unverified*. Check NPIAP guidance. |
| Pneumatic peak pressure | Firmware cap plus a mechanical relief valve set just above it. Treat a failed sensor read as a fault and vent. | Engineering practice |
| Skin-contact temperature | Under 41 C, with a hardware thermal cutoff | IEC 60601-1 (checked through snippets only) |
| Session length | Hard cap (for example 30 min) enforced **on the device**, not just in the app | Safety research recommendation |
| Link-loss watchdog | No heartbeat for about 2 s: stop the motors, open the vent | Firmware design |
| Stops | Voice "stop", the app, PiP, a big button on the pack, plus the watchdog | Matches the existing app's stop paths |
| Removal | Lifting the pouch takes the device off without disturbing the sheath, and can be done with a weak grip | MD-specific need |
| Ingress | Sleeve fully sealed silicone. Electronics pack IP67 or off-body. | Design judgement (urine is conductive and corrosive) |
| Materials | Platinum-cure silicone, ISO 10993-5/-10/-23. Non-porous and washable. | ISO 10993 |
| Electrical | Protected LiPo with a fuse. Never charge while worn, never connect to mains while worn. | Standard practice |
| Skin | Daily skin check. Change the catheter on the normal schedule, which the device must never extend. | Catheter guidance (24 to 48 h changes) |

**Biggest risks, ranked:** (1) constriction, (2) pressure injury from rigid parts while seated, (3) sheath leaks or dislodgement from vibration or friction (we found no data on this, so bench-test it), (4) battery or motor heat in a wet environment, (5) a control failure leaving the device running.

---

## Hands-off usability (lessons from commercial devices)

Commercial app-controlled toys assume you can reach the device and your phone at any time. These are the failures that break that assumption, and what this build does instead.

| Failure seen in commercial devices | Requirement for this build | Source |
|---|---|---|
| Powers itself off after a period idle (We-Vibe Bond: about 2 h), and turning it back on needs a button you can't reach | No idle auto power-off. The board stays awake and reconnectable (low-power BLE advertising). The session cap and watchdog stop the motors only. | Owner experience |
| Power-on is a long press on a small button on the worn part | Nothing on the sleeve needs pressing. Power and STOP live on the chair box, big and reachable. Waking from the app needs no hands at all. | Owner need, accessibility guides |
| BLE drops when the phone screen locks or the app is backgrounded | The app holds the BLE link in a foreground service with a battery-optimization exemption, so screen-off and other apps don't break it. | User reviews (We-Vibe, Lovense support) |
| Has to be re-paired after the app closes, sometimes by forgetting it in phone Bluetooth settings | Bond once. The device auto-reconnects to the last phone and the app reconnects on launch with no pairing screen. | We-Vibe app reviews |
| Loses the link and keeps vibrating at full strength until turned off by hand | Link-loss watchdog stops the motors (already in Safety constraints). | Lovense App Store review |
| Boots or reconnects at the last (or max) intensity | Always boot and reconnect with the motors off. Starting needs a fresh command and ramps up from low. | Design judgement |
| Fiddly charging pins or a magnetic tip that must be lined up on the toy | Never charge the sleeve itself. The battery lives in the chair box, on a large magnetic or barrel connector, or runs off the wheelchair battery. | Accessibility guides (magnetic snap charging) |
| Battery dies mid-session with no warning | Report battery level to the app and warn early. On low battery, wind the motors down instead of cutting out. | Design judgement |
| Buttons on the body get pressed by sitting or leaning | No buttons on the worn part. Box buttons are recessed, except the STOP button. | Owner need (seated all day) |
| App needs an account, cloud or internet, and updates break the connection | Local only, no account, works offline (as android-v2 already does). Firmware updates are opt-in. | Lovense user reports |

---

## App integration

The engines (`Dispatch`, `PatternEngine`, `HumanizeEngine`) currently call `HandyV2ApiClient` directly. Extract an interface and have both clients implement it:

```kotlin
interface HapticDevice {
    val connectionState: StateFlow<ConnState>
    val capabilities: Set<Cap>                 // SPEED, STROKE_RANGE, PRESSURE
    suspend fun connect(): Boolean
    suspend fun disconnect()
    suspend fun start(): Boolean
    suspend fun stop(): Boolean
    suspend fun rampStop(): Boolean
    suspend fun setSpeed(pct: Int): Boolean    // 0..100 intensity
    suspend fun rampSpeed(target: Int): Boolean
    suspend fun setSpeedModulated(pct: Int): Boolean
    suspend fun setStrokeRange(min: Int, max: Int): Boolean          // no-op without STROKE_RANGE
    suspend fun setStrokeRangeTransient(min: Int, max: Int): Boolean
    fun reflectBaseSpeed(pct: Int)
}
// HandyV2ApiClient : HapticDevice      BleWearableClient : HapticDevice
```

- **Mapping:** speed becomes vibration intensity or pump rhythm rate. Stroke range becomes the bladder fill window, or is a no-op on the vibration-only build.
- **BLE protocol:** start with our own GATT service. One write characteristic takes the command (mode, intensity, pressure target, session limit, heartbeat). One notify characteristic reports pressure, state and faults. Buttplug/Intiface compatibility can come later: Buttplug only allows new devices that reuse an existing protocol, such as Lovense.
- The voice vocabulary stays the same: `begin` / `stop` / `faster` / `slower`.
- **Commercial devices as a stopgap (Lovense):** built into android-v2 (v0.23, a test build, untested on a real toy yet); full research, protocol and test plan in [`android-v2/docs/LOVENSE.md`](../android-v2/docs/LOVENSE.md). The seam is not a `HapticDevice` interface yet but a smaller `DeviceTransport` (wire level) under `HandyV2ApiClient`, with `HandyRestTransport` and `LovenseBleTransport`; a wearable BLE transport can slot in the same way. Summary: Lovense toys use an open, documented BLE protocol, so the app drives one before our own sleeve exists. Toys advertise as `LVS-*` / `LOVE-*`, using one of three GATT services (Nordic UART `6e400001-...` on gen 2). Commands are ASCII ending in `;`: `Vibrate:0..20;`, `Battery;`, `DeviceType;`, `PowerOff;`. `AutoSwith:<a>:<b>;` (read with `GetAS;`) sets (a) stop vibrating on an accidental disconnect, and (b) return to the last level on reconnect. Set a on and b off, so a dropped link stops the toy and it never restarts by surprise. No idle power-off timer is documented, but neither is a way to disable one. A periodic `Battery;` poll while connected is the likely keep-awake, still to be tested. The Diamo and Gush 2 are not in the protocol page's model table, so check their device type and service generation on real hardware.

---

## Parts (approx USD)

| Part | Example | Cost |
|---|---|---|
| BLE MCU with LiPo charger | Seeed XIAO ESP32-C3 (or XIAO nRF52840 for lower power) | 5-10 |
| Haptic driver | Adafruit DRV2605L | 8 |
| LRAs | 8-10 mm coin LRA, x2-4 | 2-5 each |
| Air pump (phase 2) | Adafruit 4699 (ZR370-02PM), 4.5 V, about 500 mA | 8-15 |
| Pump and valve drivers | AO3400 MOSFETs + flyback diodes, or DRV8833 | 2-6 |
| Vent valve | 3-6 V mini solenoid, **normally open** | 5-12 |
| Pressure sensor | Adafruit MPRLS 0-25 PSI | 15 |
| Relief valve | Small spring relief valve, a few psi (part TBD) | 5-15 |
| Battery | 1S LiPo, 1000-2000 mAh, protected | 8-15 |
| Sleeve | Platinum-cure silicone (e.g. Dragon Skin / Ecoflex class) + 3D-printed mold | 30-50 |
| Enclosure | 3D-printed, gasketed | 3-10 |

The 600 mA pump bursts need a regulator sized for them, because the XIAO's onboard supply may not be enough. Runtime is about 2 to 4 h of intermittent pump use on 1500 mAh (*unverified estimate*).

---

## Sample order (first round)

Parts to compare by feel before any design is locked. Prices and stock were checked 2026-11-21. *n/l* means the vendor doesn't list it.

### Vibration motors

| Part | Type | Size (mm) | Freq | Strength | Price | Buy | Drive |
|---|---|---|---|---|---|---|---|
| Vybronics VLV101040A | Square LRA | 10 x 10 | 170 Hz | 2.75 G | $7.18 | [DigiKey](https://www.digikey.com/en/products/detail/vybronics-inc/VLV101040A/12323590) | DRV2605L |
| Vybronics VG0832012 | Coin LRA | 8 dia | 235 Hz | 1 G | $3.21 | [DigiKey](https://www.digikey.com/en/products/detail/vybronics-inc/VG0832012/7364317) | DRV2605L |
| Jie Yi JYLRA1030X | X-axis LRA | check datasheet | n/l | 1.8 G | $3.85 | [DigiKey](https://www.digikey.com/en/products/detail/jie-yi-electronics-limited/JYLRA1030X/22519435) | DRV2605L |
| Precision Microdrives C10-100 | Coin LRA | 10 dia x 3.7 | 175 Hz | 1.5 G | quote | [PMD](https://www.precisionmicrodrives.com/products/fb7e332c-199f-4922-ac22-857688f22351/c10-100-10mm-coin-lra) | DRV2605L |
| Adafruit 1201 | Coin ERM | 10 dia x 2.7 | about 183 Hz at 5 V, lower at lower V | n/l | $1.95 | [Adafruit](https://www.adafruit.com/product/1201) | DRV2605L (ERM mode) |
| Precision Microdrives 307-103 | Cylindrical "pager" ERM | 8.7 dia x 25 | about 230 Hz | 7 G | quote | [PMD](https://www.precisionmicrodrives.com/product/307-103-9mm-vibration-motor-25mm-type) | Marginal: the 650 mA start surge exceeds the DRV2605L limit. Drive it from a MOSFET instead. |
| Dayton Audio DAEX19SL-4 | Slim voice-coil exciter | 33 dia x 6.6 | broadband (can go to 100 Hz) | n/l | about $10 | [Dayton Audio](https://daytonaudio.com/product/1279/daex19sl-4-slimline-coin-type-19mm-exciter-4w-4-ohm) | Needs a class-D amp (MAX98357A or PAM8302, about $5) |

- **The clinical bar is about amplitude, not frequency.** Penile vibratory stimulation (Ferticare, Viberect) runs at about 100 Hz with 2.5 to 4 mm peak-to-peak amplitude. That is roughly 50 to 80 G of peak acceleration (a = (2 pi f)^2 x peak displacement). The coin motors here are rated 1 to 7 G, measured on a 100 g test mass, so they are about 10x weaker. The clinical protocol was designed for spinal cord injury, where sensation is reduced and the goal is a reflex ejaculation for fertility. With intact sensation, far less is probably enough (*unverified*). The feel test is what tells us.
- LRAs only work near their resonance (170 to 235 Hz). An ERM's frequency drops as you lower the voltage, and a voice coil can play any frequency. Those two are the only ways to test the clinical 100 Hz figure.
- The voice coil is thicker (6.6 mm) than the planned 3 to 4 mm shell. Treat it as a feel test, not the final part.
- Skipped: Dayton TT25-8 (too big, too low in frequency) and TITAN TacHammer ($450+ dev kit).

**Motor order as carted (2026-11-21):**

| Qty | Part | Why | Vendor | Each |
|---|---|---|---|---|
| 3 | Vybronics VLV101040A | LRA 170 Hz, 2.75 G, 10 x 10 mm | DigiKey | $7.18 |
| 3 | Vybronics VG0832012 | LRA 235 Hz, 1 G, 8 mm baseline | DigiKey | $3.21 |
| 3 | **Vybronics VL32158H-L25** | **LRA at 100 Hz, 5 G**, 15 x 8 mm, 2 VAC. Hits the clinical frequency directly. | DigiKey | $6.54 |
| 2 | **Vybronics VJP20-84J001U** | ERM 6500 RPM (about 108 Hz), 7.6 G, 3 V. Exposed spinning weight, so it needs a housing before going into silicone. Drive it from a MOSFET. | DigiKey | $3.85 |
| 1 | **Vybronics VG2230001H** | Voice-coil LRA 70 Hz, 3.5 G, 22 mm round, 2 VAC. Replaces the Dayton exciter, and the DRV2605L can drive it. | DigiKey | $7.83 |
| 1 | Seeed XIAO ESP32-C3 (113991054) | BLE MCU | DigiKey | $5.25 |
| 5 | Adafruit 1201 | Coin ERM, cheap baseline | Adafruit | $1.95 |
| 1 | Adafruit 2305 | DRV2605L driver board | Adafruit | $7.95 |
| 1 | Adafruit 2130 | PAM8302 amp (only needed if a Dayton exciter is added later) | Adafruit | $3.95 |

DigiKey is about $86 with shipping and tax, and Adafruit is $21.65 plus shipping. Dropped: JYLRA1030X (no size or voltage listed), PMD 307-103 ($13.65 sample, separate shipment from the UK, replaced by VJP20), and Dayton DAEX19SL-4 (replaced by VG2230001H).

- **The low-frequency parts are bigger than DigiKey suggests** (from the datasheets, now nl-cad library entries): VL32158H-L25 is 35.2 x 15 x 8 mm and 13.6 g; VG2230001H is 22 mm dia x 30 mm and 36 g; VJP20 is 20.4 mm dia x 40.5 mm. None fits a 3 to 4 mm shell. They are for the feel test. If one wins, the sleeve gets a thicker pod at the base for it, rather than the motor being embedded along the shaft.
- **Still to buy for the v5 box** (the carts above have only one driver): 4 more Adafruit 2305 DRV2605L, 1 x [Adafruit 2717 TCA9548A mux](https://www.adafruit.com/product/2717), 1 x [Adafruit 559 16 mm STOP button](https://www.adafruit.com/product/559), STEMMA QT cables, a 1S LiPo (about 1000 mAh, part TBD), and a PG7 cable gland.
- The DRV2605L's auto-resonance tracking may not lock onto the 100 Hz and 70 Hz LRAs. If it doesn't, use its open-loop LRA mode with a set drive period. Confirm this on the bench.

### Bladders and materials

| Item | What | Price | Buy |
|---|---|---|---|
| ADC infant BP cuff (12-2241) | Ready-made thin bladder, 10.3 x 3 in, can be trimmed and resealed (*unverified*). Check the listing to see whether the bladder is included. | about $36 (unconfirmed) | [Betty Mills](https://bettymills.com/adc-sphygmomanometer-cuff-2-tube-cuff-only-infant-12-2241-fnt12-2241) |
| 200D TPU-coated nylon | Heat-sealable fabric for custom bladders. One study found it had the highest burst strength of the TPU nylons it tested. | likely under $15 / half yard | [Rockywoods](https://rockywoods.com/products/heat-sealable-200-denier-tpu-marine-safety-orange) |
| 70D TPU-coated nylon (72709) | Thinner, more flexible alternative (EU seller) | EUR 16.40/m | [Extremtextil](https://www.extremtextil.de/en/nylon-70den-tpu-coated-one-side-heat-sealable-220g-sqm.html) |
| Smooth-On Ecoflex 00-30 | Skin-safe silicone to cast the C-shell and encapsulate the bladders and motors | $32.99 trial pint | [Smooth-On](https://smooth-on.com/products/ecoflex-00-30) |

- **Making bladders:** cut two pieces of TPU-coated nylon and seal them coated face to coated face around the edge with a household iron, an impulse sealer or a hot-air tool. Seal a short tube into one corner. This is the method in the Weld n'Cut paper (arxiv.org/abs/2502.06361). Make each bladder a flat 20-40 x 30-80 mm pouch, and never a closed loop.
- Off-the-shelf massage-chair air bags and commercial soft actuators turned up nothing verifiable, so DIY is the main path.

### Pump, valve, fittings

| Part | Specs | Price | Buy |
|---|---|---|---|
| Adafruit 4699 pump (ZR370-02PM) | 4.5-5 V, about 500 mA, 2.5 L/min. Ports 4.3 mm OD. | $7.95 | [Adafruit](https://www.adafruit.com/product/4699) |
| Adafruit 4663 3-port valve (FA0520E) | 6 V (runs at 5 V). Unpowered, the common port connects to the vent port, so **it vents when power drops**. | $2.95 | [Adafruit](https://www.adafruit.com/product/4663) |
| KSV05A valve | 3-5 V, 90 mA, **normally open**, 10 x 12 x 20 mm | about $5-10 | [eBay](https://www.ebay.de/itm/263278112824) |
| Tinkersphere TS2341 pump | 3-6 V, 68 x 27 mm, second pump to compare noise | $10.99 | [Tinkersphere](https://tinkersphere.com/motors-wheels/2341-mini-air-pump-3v-6v-dc.html) |
| Silicone tubing + barbs | about 3 mm ID to fit the pump, step down to 1.5-2.5 mm toward the sleeve | about $10 | any |

- **These pumps can reach about 5.8 to 6.8 psi (300 to 350 mmHg), far above skin-safe levels.** The firmware pressure cap and a **low-set mechanical relief valve** are mandatory before anything is worn. We have not found a relief valve part yet.
- Drive the pump from a MOSFET with a flyback diode, never from a GPIO pin.

**Bladder + pneumatics order, about $85:** 2 x Adafruit 4699, 1 x Adafruit 4663, 1 x KSV05A, 1 x TS2341, tubing + barbs, half yard 200D TPU nylon, Ecoflex 00-30 trial. Add the infant cuff (about $36) only if you want a ready-made bladder to try first.

**Everything together: about $140 to $175.**

---

## CAD assembly

[`hardware/wearable-sleeve/`](../hardware/wearable-sleeve/) is an [nl-cad](https://github.com/owenpkent/nl-cad) project: [Brief v2.md](../hardware/wearable-sleeve/Brief%20v2.md), one build123d file per part in `parts/`, `assembly.yaml`, and numbered builds in `builds/vN/` with checks, BOM, JPG renders, and STEP/GLB (STEP/GLB gitignored, regenerated by each build). Bought parts are stand-ins from nl-cad's shared `library/`. Build from the nl-cad repo:

```powershell
cd W:\dev\nl-cad; uv run python -m nlcad.build W:\dev\handy\hardware\wearable-sleeve --note "what changed"
```

v5 (32 checks pass, 3 accepted warns for the off-body box, 0 fail):

- **Sleeve:** open C-shell (240 degrees, the build asserts it stays under 300) in Ecoflex-class silicone, 3.5 mm wall, 0.5 mm off a 32 mm reference shaft. Four small LRAs sit in pockets that open outward through pads on the outside (pressed in, sealed with adhesive), so the inner surface stays smooth. A base pod holds the 100 Hz LRA. Shell to catheter tip 13.8 mm, to drain tube 22 mm.
- **Box:** printed PETG, 114.6 x 89.2 x 30.4 mm. Five DRV2605L, TCA9548A, XIAO, battery, a 16 mm STOP button in the lid, a USB-C charging slot and a PG7 cable exit.
- **Reference anatomy** (shaft, catheter tip, drain tube) is an estimated, adjustable stand-in, used only for clearances.
- **Open:** STOP button wiring (firmware interrupt and a hardware cut of motor power), the real button and connector positions, the battery part, and the cable. Listed in the brief.

Vendor STEP files and datasheets for the motors are downloaded to [`hardware/cad/`](../hardware/cad/) (gitignored; [README](../hardware/cad/README.md) lists sources).

## Build plan

1. **Talk to the continence nurse or urologist.** Ask about over-the-sheath use versus sheath-off sessions, and about any pressure limits.
2. **Bench prototype, not worn:** XIAO + DRV2605L + LRAs on a breadboard, plus a BLE GATT firmware with a watchdog and session cap. *(Motor samples carted 2026-11-21; CAD at v5.)*
3. **App:** extract `HapticDevice`, add `BleWearableClient`, add a device picker in Settings. The Handy path stays unchanged.
4. **Sleeve v1:** cast an open C-shell in silicone with the LRAs embedded, wear it in a pouch, short sessions. Check the sheath for leaks and the skin afterwards.
5. **Pneumatic v2:** pump + normally-open vent + MPRLS + relief valve. Validate on a pressure gauge and a dummy load before it is ever worn.
6. **Pack on the chair:** move the electronics to a gasketed box on the wheelchair frame with a quick-disconnect tube.

## Open questions

- With sensation intact, does LRA amplitude through a silicone sheath feel like enough, or would we need a larger ERM or a voice-coil actuator?
- Does vibration loosen the sheath adhesive over 30 minutes? No data exists, so a bench test (and a real test) will have to tell us.
- What pressure limit does the clinician want for the pneumatic version?
- Idle battery life with BLE advertising always on: is the XIAO nRF52840 needed (rather than the ESP32-C3) to last a full day, or does the chair box run off the wheelchair battery?

---

## Sources

- External catheter care: https://publicshare.albertahealthservices.ca/teams/policydocuments/1/klink/et-klink-ckv-external-urinary-catheter.pdf, https://pmc.ncbi.nlm.nih.gov/articles/PMC7950631/
- Constriction injuries: https://pubmed.ncbi.nlm.nih.gov/23758994/, https://www.ncbi.nlm.nih.gov/pmc/articles/PMC6040302/
- Sex and condom catheters: https://www.uhn.ca/TorontoRehab/Spinal-Cord-Rehab/Spinal-Cord-Essentials/Documents/Self-Care/SCE2-Sa5-Condom-Catheters.pdf, https://www.rightdecisions.scot.nhs.uk/the-queen-elizabeth-national-spinal-injuries-unit/education/intimacy, https://unitedspinal.org/?p=85651
- Thermal limits: https://www.ele.uri.edu/Courses/bme484/iec60601-1ed3.0_parts.pdf
- Penile vibratory stimulation: https://pubmed.ncbi.nlm.nih.gov/32665708/, https://pmc.ncbi.nlm.nih.gov/articles/PMC5129398, https://www.nature.com/articles/sc201760
- Hot Octopuss: https://disabilityhorizons.com/2015/08/pulse-ii-the-sex-toy-developed-with-disabled-people-in-mind/
- Commercial device failures: https://justuseapp.com/en/app/890526071/we-connect-by-we-vibe/reviews, https://mv-support.knowledgeowl.com/help/why-does-my-lovense-toy-have-a-weak-connection, https://apps.apple.com/us/app/lovense-remote/id1027312824
- Accessible devices: https://newmobility.com/the-quest-for-accessible-sex-toys/, https://www.issm.info/sexual-health-qa/what-are-some-sex-enhancement-devices-for-people-with-disabilities
- Comparable devices: https://www.reviewed.com/products/lovense-diamo, https://fcc.report/FCC-ID/2AXNT-DIAMO/5049085.pdf, https://lyko.com/en/lelo/lelo-tor-3-black, https://testix.co.uk/test/best-cock-ring, https://shop.sexwithemily.com/products/hot-octopuss-pulse-duo-lux, https://www.insidehook.com/sex-and-dating/we-vibe-bond/amp
- Buttplug / Intiface: https://buttplug.io, https://buttplug.io/docs/dev-guide/inflating-buttplug/devices/device-configuration-file, https://buttplug.io/stpihkal/protocols/lovense/
- TCode: https://github.com/multiaxis/tcode-spec
- Soft robotics: https://softroboticstoolkit.com/book/parts-and-materials, https://www.colorado.edu/atlas/haptic-pneumatics-toolkit, https://hackaday.com/2019/06/14/pneumatics-for-the-masses
- Parts: https://www.adafruit.com/products/2305, https://www.adafruit.com/products/3965, https://thepihut.com/products/air-pump-and-vacuum-dc-motor-4-5v-and-1-8-lpm
