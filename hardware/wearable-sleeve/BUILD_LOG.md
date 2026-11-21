# Build log, wearable-sleeve

An open C-shell silicone sleeve with four shaft LRAs and a base pod LRA, plus a chair-mounted electronics pack, around a reference shaft. Brief: [Brief v2.md](Brief%20v2.md). Sketches: [brief/sketch_v1.svg](brief/sketch_v1.svg), [brief/sketch_v2.svg](brief/sketch_v2.svg).

## Plan checkpoint (before v1)

- Subsystems: reference anatomy (fixtures), sleeve (shell, pod, motors), electronics pack (tray, lid, battery, MCU, driver).
- Joints and datums: all fixed in v1. World frames `base` (X = 0), `shell_start` (X = 5), `tip_start` (X = 95), `pod_c` (10, 0, -24), `pack` (0, -300, -150). Motor pockets are named frames on the shell (`u1`, `u2`, `fl`, `fr`) and the pod (`motor`); the pack seats are frames on the tray.
- Assembly order: reference, shell and pod, motors, then pack tray, contents, lid.
- Routes: none modelled (cable from pack to sleeve is out of scope in v1).

| Build | What the checks and renders showed | What changed |
|---|---|---|
| v1 | 18 pass, 3 warn, 8 fail. Geometry rows all pass: no overlap, shell to tip 13.8 mm, shell to drain 22.0 mm, pod to shaft 1.9 mm, shell to shaft 0.5 mm. The 8 fails are `qa.insert.*`: motors in closed cavities (5), and battery, MCU and driver put in after the lid (3), have no assembly path. Warns: the pack is 294 mm from its parent (3 rows). | First build. Motor pockets closed (1.0 mm skin each side); pads outside the wall for the LRAs (underside to radius 23.1, flank to 21.9). |
| v2 | 26 pass, 3 warn (3 accepted), 0 fail; `nlcad.qa` 19 pass, 3 accepted warn, 0 fail. Total mass 0.1825 kg, of which 0.086 kg is reference fixtures (shaft 76 g, tip 4 g, drain 6 g), so 0.0965 kg of real parts. | Pockets now open through the pad outer face and the pod is open underneath (motors pressed in from outside, sealed with adhesive); steps reordered to tray, contents, lid; the three pack-separation warns accepted with reasons (pack is 300 mm away by design). Diff: 8 insertion checks fail to pass; shell 24 to 23 g, pod 5.2 to 4 g. |
| v3 | 26 pass, 3 warn (3 accepted), 0 fail (from builds/v3/checks_v3.md). Row added retroactively in v4; no notes were logged at the time. | Not recorded. |
| v4 | 32 pass, 3 warn (3 accepted), 0 fail; `nlcad.qa` 25 pass, 3 accepted warn. First build of the revised pack: 5 DRV2605L, TCA9548A, XIAO, LiPo on printed posts; lid STOP button (Adafruit 559, 16 mm hole); USB-C slot (-X wall) and 12.5 mm PG7 hole (+Y wall); box 114.6 x 89.2 x 28.4 mm (tray). Mass 0.2492 kg total, of which 0.086 kg is reference fixtures. Every new part inserts at its step (`qa.insert.*`). | Tray and lid rewritten around a new layout; new parts `mux_tca9548a`, `button_stop`; new library entries for the mux and the button; assembly: 5 driver instances (rotated 90 degrees), mux, stop button, new step. |
| v5 | Same numbers as v4: 32 pass, 3 accepted warn, 0 fail. | Geometry unchanged; accepted-warn reasons corrected for the pack distance (about 270 mm now). Brief renamed to v2, sketch_v2.svg added. |

## Open questions

- Resolved in v4: drivers (one DRV2605L per motor behind a TCA9548A) and per-motor drive voltage (each driver set to its own motor's rating).
- To verify on the bench: DRV2605L VDD from VBAT (2.0 to 5.2 V range covers 1S LiPo 3.0 to 4.2 V) rather than the XIAO 3.3 V regulator; I2C pull-ups at 3.3 V.
- STOP button (Adafruit 559): head diameter (18 mm), overall length (29.4 mm) and mass (5 g) are borrowed from sibling product 1440; confirm on the real part. It must also be wired to a firmware interrupt and to a hardware motor-power cut (not designed yet).
- STEMMA QT connector positions on the DRV2605L (2305) are estimated at both short ends; check the real board. The TCA9548A (2717) has no QT port, so its wiring (header or pads, and the extra height of a soldered header) is not modelled.
- USB-C slot (11 x 5 mm) and its Z height are estimated; check against a real plug. Never charge while worn.
- Battery vendor and part number TBD (library entry is estimated).
- Motor retention (adhesive over an open pocket) and the exposed underside of the pod motor are assumptions to review with the casting method.
- Cable between the pack and the sleeve (10 wires, PG7 gland) is not modelled; gland itself is not modelled, only its hole.
