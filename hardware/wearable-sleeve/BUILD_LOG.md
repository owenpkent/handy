# Build log, wearable-sleeve

An open C-shell silicone sleeve with four shaft LRAs and a base pod LRA, plus a chair-mounted electronics pack, around a reference shaft. Brief: [Brief v1.md](Brief%20v1.md). Sketch: [brief/sketch_v1.svg](brief/sketch_v1.svg).

## Plan checkpoint (before v1)

- Subsystems: reference anatomy (fixtures), sleeve (shell, pod, motors), electronics pack (tray, lid, battery, MCU, driver).
- Joints and datums: all fixed in v1. World frames `base` (X = 0), `shell_start` (X = 5), `tip_start` (X = 95), `pod_c` (10, 0, -24), `pack` (0, -300, -150). Motor pockets are named frames on the shell (`u1`, `u2`, `fl`, `fr`) and the pod (`motor`); the pack seats are frames on the tray.
- Assembly order: reference, shell and pod, motors, then pack tray, contents, lid.
- Routes: none modelled (cable from pack to sleeve is out of scope in v1).

| Build | What the checks and renders showed | What changed |
|---|---|---|
| v1 | 18 pass, 3 warn, 8 fail. Geometry rows all pass: no overlap, shell to tip 13.8 mm, shell to drain 22.0 mm, pod to shaft 1.9 mm, shell to shaft 0.5 mm. The 8 fails are `qa.insert.*`: motors in closed cavities (5), and battery, MCU and driver put in after the lid (3), have no assembly path. Warns: the pack is 294 mm from its parent (3 rows). | First build. Motor pockets closed (1.0 mm skin each side); pads outside the wall for the LRAs (underside to radius 23.1, flank to 21.9). |
| v2 | 26 pass, 3 warn (3 accepted), 0 fail; `nlcad.qa` 19 pass, 3 accepted warn, 0 fail. Total mass 0.1825 kg, of which 0.086 kg is reference fixtures (shaft 76 g, tip 4 g, drain 6 g), so 0.0965 kg of real parts. | Pockets now open through the pad outer face and the pod is open underneath (motors pressed in from outside, sealed with adhesive); steps reordered to tray, contents, lid; the three pack-separation warns accepted with reasons (pack is 300 mm away by design). Diff: 8 insertion checks fail to pass; shell 24 to 23 g, pod 5.2 to 4 g. |

## Open questions

- Drivers: one DRV2605L per motor channel; five independent motors need a TCA9548A mux plus more DRV2605L boards, or per-zone parallel wiring. v1 models one driver.
- Per-motor drive voltage differs (VLV101040A 2.5 Vrms, VG0832012 1.8 Vrms).
- Battery vendor and part number TBD (library entry is estimated).
- Motor retention (adhesive over an open pocket) and the exposed underside of the pod motor are v1 assumptions to review with the casting method.
- Cable between the pack and the sleeve is not modelled.
