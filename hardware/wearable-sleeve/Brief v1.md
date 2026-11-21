# Brief v1, wearable-sleeve

A clinical assistive device for a wheelchair user with muscular dystrophy: a soft vibrating sleeve worn under trousers over an external condom catheter, driven by a chair-mounted electronics pack and controlled by voice over BLE. All naming stays clinical and neutral. Design context and numbers: `W:\dev\handy\docs\WEARABLE_DEVICE_RESEARCH.md`.

Sketch: [brief/sketch_v1.svg](brief/sketch_v1.svg) (side and end views). From the side the sleeve is an 80 mm long shell lying along a 95 mm shaft reference, starting 5 mm from the base, ending 10 mm short of the catheter tip; two small motors sit on its underside, one on each flank at mid length, and a base pod hangs under the proximal end. From the end the shell is a C that wraps the underside and both flanks (240 degrees) and leaves the top open (120 degrees); the pads that hold the motors bulge outward only.

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

## D. Electronics pack

Chair-mounted box, world datum `pack` offset 300 mm in -Y and 150 mm in -Z from the base. The cable between pack and sleeve is not modelled (the repo has no cable pattern).

- Enclosure: 3D-printed PETG (density about 1270 kg/m3, published), 2 mm wall, 2 mm internal clearance on the sides and top of the contents, separate lid with a locating lip. Gasket groove skipped.
- Contents (library stand-ins): 1 x XIAO ESP32-C3, 1 x DRV2605L breakout, 1 x 1S LiPo (new entry `library/battery-lipo-1s-1000mah.json`: 50 x 34 x 6, 20 g, vendor and part number TBD, estimated from the typical 503450 cell).
- **Open question (drivers):** one DRV2605L drives one motor channel (fixed I2C address 0x5A). Driving five motors independently needs a TCA9548A I2C multiplexer and more DRV2605L boards, or motors wired in parallel per zone. v1 models one driver only.
- Open question (drive levels): the VLV101040A takes 2.5 Vrms and the VG0832012 1.8 Vrms; mixed motor types also need a per-channel voltage setting. Not settled.

## E. Mounting

In v1 the sleeve is held by a brief pouch (a garment), not modelled.

## Checks the brief asks for

Overlap (none, apart from the pod fused into the shell and the reference tip meeting its drain tube); shell to tip at least 5 mm; shell to drain tube at least 5 mm; pod to shaft at least 1 mm; shell to shaft at least 0.4 mm; every motor and board can be put in at its assembly step (`qa.insert`); arc under 300 degrees (assert in `parts/sleeve_shell.py`).
