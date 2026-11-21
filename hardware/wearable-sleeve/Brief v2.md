# Brief v2, wearable-sleeve

A clinical assistive device for a wheelchair user with muscular dystrophy: a soft vibrating sleeve worn under trousers over an external condom catheter, driven by a chair-mounted electronics pack and controlled by voice over BLE. All naming stays clinical and neutral. Design context and numbers: `W:\dev\handy\docs\WEARABLE_DEVICE_RESEARCH.md`.

Sketch: [brief/sketch_v1.svg](brief/sketch_v1.svg) (side and end views of the sleeve), [brief/sketch_v2.svg](brief/sketch_v2.svg) (top view of the electronics box). From the side the sleeve is an 80 mm long shell lying along a 95 mm shaft reference, starting 5 mm from the base, ending 10 mm short of the catheter tip; two small motors sit on its underside, one on each flank at mid length, and a base pod hangs under the proximal end. From the end the shell is a C that wraps the underside and both flanks (240 degrees) and leaves the top open (120 degrees); the pads that hold the motors bulge outward only.

## Frame

Z up, X forward (distal), Y left, millimetres. Base datum at X = 0, the proximal end of the shaft reference, on the shaft axis.

## A. Reference anatomy (fixtures, not parts)

Not bought, not manufactured. They carry `bought=False`, material `reference`, and a description starting "REFERENCE FIXTURE". The build format has no BOM exclusion flag, so they appear in `bom_vN.csv` (rows `shaft`, `tip`, `drain`) and in the total mass; strike them from any purchase list.

| Fixture | Geometry | Provenance |
|---|---|---|
| `shaft_ref` | cylinder 32 dia x 95, axis X, X 0 to 95 | estimated: typical flaccid dimensions; condom catheter sizes 25 to 41 mm, 32 mm mid-range; parametric |
| `sheath_tip_ref` | cylinder 14 dia x 25, X 95 to 120 | estimated: typical external catheter tip and connector; parametric |
| `drain_tube_ref` | cylinder 8 OD x 120, straight, down -Z from the tip (a bend is simplified away) | estimated: typical drain tube; parametric |
| base datum | world frame `base` at X = 0 | a datum, not a part |

## B. Sleeve shell

Cast platinum-cure silicone, Ecoflex 00-30 class, density about 1070 kg/m3 (published, Smooth-On). Open C-section around the shaft: inner radius 16.5 (0.5 clearance, parametric), wall 3.5, length 80 from X = 5, arc 240 degrees centred on the underside, so the opening is 120 degrees on top. Rules: the part asserts the arc is under 300 degrees (it can never close into a ring) and that the opening edge lands where the arc says; ends sit 10 mm short of the tip, and clearance rows require at least 5 mm to the tip and to the drain tube.

Motor pockets (envelope plus 0.2 mm), each with 1.0 mm of silicone between the motor and the shaft:

| Motor | Where | Pocket | Wall decision |
|---|---|---|---|
| 2 x VLV101040A (10 x 13.9 x 4.4) | underside, 30 and 65 percent of the shell length (X 29 and 57) | 10.2 x 14.1 x 4.6, thin axis radial | 4.6 + 1.0 skin + 1.0 skin = 6.6 mm does not fit the 3.5 mm wall, so a rectangular pad (14.2 x 18.1) is added on the OUTSIDE, outer face at radius 23.1 (3.1 mm proud). The inner surface is untouched. |
| 2 x VG0832012 (8 dia x 3.2) | left and right flanks, 50 percent (X 45) | 8.1 dia x 3.4 | 3.4 + 2.0 = 5.4 mm does not fit either, so a round pad (12.2 dia) is added outside, outer face at radius 21.9 (1.9 mm proud). |

Pockets are open through the pad outer face: the motor is pressed in from outside and sealed with silicone adhesive. (Closed cavities were tried in v1 and have no way to be assembled; see BUILD_LOG.) The long side of the underside motors runs along the shaft; the flank motors vibrate along Y (radial).

## C. Base pod

Same silicone, one pour with the proximal underside of the shell. Holds 1 x VL32158H-L25 (35.2 x 15 x 8, 100 Hz) with its long axis across Y (tangential), 2 mm wall on the sides and top, open underneath for the motor. It sits under the shaft base (X 0.4 to 19.6, top 1.9 mm below the shaft surface). Check: pod to shaft_ref at least 1 mm.

## D. Electronics pack (revised in v2)

Chair-mounted box, world datum `pack` offset 300 mm in -Y and 150 mm in -Z from the base. The cable between pack and sleeve is not modelled (the repo has no cable pattern).

Contents (library stand-ins): 1 x XIAO ESP32-C3, 1 x Adafruit TCA9548A 1-to-8 I2C multiplexer (new entry `library/mux-i2c-adafruit-tca9548a.json`, Adafruit 2717, 30.6 x 17.6 x 2.7 mm, 1.8 g, published), 5 x Adafruit DRV2605L (one per sleeve motor: 2 x VLV101040A, 2 x VG0832012, 1 x VL32158H-L25), 1 x 1S LiPo (`battery-lipo-1s-1000mah`, 50 x 34 x 6, estimated). Lid part: 1 x Adafruit 559 16 mm momentary pushbutton with red LED ring (new entry `library/button-adafruit-16mm-red-ring-momentary.json`).

- **Drivers.** Each motor has its own DRV2605L, so each is driven independently: a wave pattern along the shaft, and a per-motor rated voltage. The DRV2605L has a fixed I2C address (0x5A), so the TCA9548A (0x70) selects one driver at a time.
- **STOP button (safety).** A large physical stop on the lid, reachable from the wheelchair with weak grip (`docs/WEARABLE_DEVICE_RESEARCH.md`). Adafruit 559, momentary, 1 NO + 1 NC, 1.5 to 2.5 N, 2 mm stroke; 16 mm drill hole (published); head 18 mm, length 29.4 mm and 5 g are borrowed from the sibling product 1440 (estimated, 559 gives no outline). Head 18 mm clears the 16 mm minimum cap diameter. The lid hole is 16 mm; the shank hangs 24.4 mm into a free column of the tray (nothing under it). Wiring (to a XIAO GPIO interrupt and to cut motor power) is firmware and electronics, not modelled.
- **Openings.** USB-C slot, 11 x 5 mm (estimated), in the -X wall aligned with the XIAO's USB-C connector, for charging. **Never charge while worn.** One round cable exit, 12.5 mm, in the +Y wall in front of the driver row for a PG7 cable gland (PG7 thread 12.5 mm, published, DIN 40430); the sleeve harness is 10 wires for 5 motors. The gland is not modelled, only its hole.
- **Layout (top view, sketch_v2).** Row 1: five DRV2605L side by side, long axis Y, with 8 mm for cable bends beyond each short end (STEMMA QT ends, estimated at both ends). Row 2: XIAO (USB-C end at the -X wall) over the TCA9548A, then the battery beside them, then a free column under the STOP button. Boards sit on 3 x 3 x 2 mm printed posts; the battery rests on the floor. 2 mm clearance elsewhere. Enclosure: 3D-printed PETG, 2 mm wall, separate lid with a locating lip. Outside about 114.6 x 89.2 x 30.4 mm (lid on), button head 5 mm proud.
- **Power (to verify on the bench).** DRV2605L VDD is fed from VBAT (supply range 2.0 to 5.2 V covers a 1S LiPo at 3.0 to 4.2 V), not from the XIAO 3.3 V regulator. I2C pull-ups at 3.3 V.

Resolved since v1: the driver question (one DRV2605L per motor behind a TCA9548A) and the per-motor drive voltage (each driver is set to its own motor's rated level: VLV101040A 2.5 Vrms, VG0832012 1.8 Vrms).

Open questions: see BUILD_LOG.md.

## E. Mounting

In v1 the sleeve is held by a brief pouch (a garment), not modelled.

## Checks the brief asks for

Overlap (none, apart from the pod fused into the shell and the reference tip meeting its drain tube); shell to tip at least 5 mm; shell to drain tube at least 5 mm; pod to shaft at least 1 mm; shell to shaft at least 0.4 mm; every motor, board and the STOP button can be put in at its assembly step (`qa.insert`); arc under 300 degrees (assert in `parts/sleeve_shell.py`).
