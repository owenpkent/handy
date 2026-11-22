# Checks, wearable-sleeve v7

33 pass, 3 warn (3 accepted, 0 untriaged), 0 fail

## Coverage

| Check | Ran over |
|---|---|
| interference | 22 of 24 pairs with overlapping bboxes |
| envelope.width | all |
| envelope.length | all |
| envelope.height | all |
| door | all |
| ground.contact | all |
| ground.clearance | all |
| mass.total | 22 of 22 instances |
| mass.cg | 22 of 22 instances |
| qa.joints | all |
| qa.connected | all |
| qa.joint_contact | 13 of 13 joints |
| qa.mass_density | 1 of 22 instances |
| qa.mass_missing | 22 of 22 instances |
| qa.bom | 15 of 22 instances (bought only) |
| qa.mesh | 22 of 22 instances |
| checks.dist.shell_to_tip@home | 2 of 22 instances |
| checks.dist.shell_to_drain@home | 2 of 22 instances |
| checks.dist.pod_to_shaft@home | 2 of 22 instances |
| checks.dist.shell_to_shaft@home | 2 of 22 instances |
| qa.insert.ref/shaft | 0 of 21 other parts (those present at its step) |
| qa.insert.ref/tip | 1 of 21 other parts (those present at its step) |
| qa.insert.ref/drain | 2 of 21 other parts (those present at its step) |
| qa.insert.sleeve/shell | 3 of 21 other parts (those present at its step) |
| qa.insert.sleeve/pod | 4 of 21 other parts (those present at its step) |
| qa.insert.sleeve/motors/lra_u1 | 5 of 21 other parts (those present at its step) |
| qa.insert.sleeve/motors/lra_u2 | 6 of 21 other parts (those present at its step) |
| qa.insert.sleeve/motors/lra_fl | 7 of 21 other parts (those present at its step) |
| qa.insert.sleeve/motors/lra_fr | 8 of 21 other parts (those present at its step) |
| qa.insert.sleeve/motors/lra_b1 | 9 of 21 other parts (those present at its step) |
| qa.insert.pack/tray | 10 of 21 other parts (those present at its step) |
| qa.insert.pack/electronics/bat | 11 of 21 other parts (those present at its step) |
| qa.insert.pack/electronics/mcu | 12 of 21 other parts (those present at its step) |
| qa.insert.pack/electronics/drv1 | 13 of 21 other parts (those present at its step) |
| qa.insert.pack/electronics/drv2 | 14 of 21 other parts (those present at its step) |
| qa.insert.pack/electronics/drv3 | 15 of 21 other parts (those present at its step) |
| qa.insert.pack/electronics/drv4 | 16 of 21 other parts (those present at its step) |
| qa.insert.pack/electronics/drv5 | 17 of 21 other parts (those present at its step) |
| qa.insert.pack/electronics/mux | 18 of 21 other parts (those present at its step) |
| qa.insert.pack/stop_nut | 19 of 21 other parts (those present at its step) |
| qa.insert.pack/lid | 20 of 21 other parts (those present at its step) |
| qa.insert.pack/stop | 21 of 21 other parts (those present at its step) |
| qa.fasteners | 0 of 0 fasteners |

## Not checked

- interference: pair declared in checks.interference.allow (overlap not measured) (2): ref/tip x ref/drain, sleeve/shell x sleeve/pod
- qa.mass_density: material has no density bound in qa/static.py DENSITY (21): pack/electronics/bat, pack/electronics/drv1, pack/electronics/drv2, pack/electronics/drv3, pack/electronics/drv4, pack/electronics/drv5, pack/electronics/mcu, pack/electronics/mux, and 13 more
- qa.insert.ref/drain: allowed pair (checks.interference.allow): rides through, not an obstacle (1): ref/drain x ref/tip
- qa.insert.sleeve/pod: allowed pair (checks.interference.allow): rides through, not an obstacle (1): sleeve/pod x sleeve/shell

## Checks

| Status | Check | Value | Limit | Detail |
|---|---|---|---|---|
| PASS | Part overlap | 0 cm3 | 0 cm3 | no pair overlaps by 0.001 cm3 or more (0 smaller ignored) |
| INFO | Overall width | 0.3665 m |  | 366 mm |
| INFO | Overall length | 0.1773 m |  | 177 mm |
| INFO | Overall height | 0.166 m |  | 166 mm |
| PASS | Doorway fit | 0.3665 m | 0.813 m | +446 mm total clearance in an 813 mm door |
| PASS | Wheel ground contact | 0 m | 0.002 m | 0 wheels; lowest points  |
| INFO | Ground clearance | -0.15 m |  | lowest non-wheel point -150 mm (pack/tray) |
| INFO | Total mass | 0.2544 kg |  | 0.3 kg over 22 instances |
| INFO | Centre of gravity height | -0.07033 m |  | x +25, y -148, z -70 mm (x forward of the drive-axle datum) |
| PASS | Mass is physically possible | 0 parts | 0 parts | no part is denser than a solid of its material |
| PASS | Every part has a mass | 0 parts | 0 parts | 22 instances carry a positive mass |
| PASS | Bought parts name a vendor or part number | 0 parts | 0 parts | 15 bought instances all named. Status tiers over 22 instances: confirmed 0, measured 0, published 13, estimated 9 |
| PASS | Part meshes are solid | 0 parts | 0 parts | 22 meshes closed with positive volume |
| PASS | Distance shell_to_tip at home | 0.01379 m | 0.005 m | sleeve/shell to ref/tip, nearest points: 13.79 mm at home (every joint at rest); min 5 mm |
| PASS | Distance shell_to_drain at home | 0.022 m | 0.005 m | sleeve/shell to ref/drain, nearest points: 22.00 mm at home (every joint at rest); min 5 mm |
| PASS | Distance pod_to_shaft at home | 0.0019 m | 0.001 m | sleeve/pod to ref/shaft, nearest points: 1.90 mm at home (every joint at rest); min 1 mm |
| PASS | Distance shell_to_shaft at home | 0.0005 m | 0.0004 m | sleeve/shell to ref/shaft, nearest points: 0.50 mm at home (every joint at rest); min 0.4 mm |
| PASS | Insertion, ref/shaft | 0 m |  | first part of its step 'Reference anatomy': nothing present to get past |
| PASS | Insertion, ref/tip | 0.023 m |  | clear along +z (23 mm to leave the 1 parts present at step 'Reference anatomy') |
| PASS | Insertion, ref/drain | 0 m |  | clear along +z (nothing ahead of it among the 2 parts present at step 'Reference anatomy') |
| PASS | Insertion, sleeve/shell | 0.085 m |  | clear along -x (85 mm to leave the 3 parts present at step 'Sleeve shell and base pod'); blocked: +z by ref/shaft at 2 mm, -z by ref/shaft at 2 mm, +x by ref/drain at 22 mm |
| PASS | Insertion, sleeve/pod | 0 m |  | clear along -z (nothing ahead of it among the 4 parts present at step 'Sleeve shell and base pod'); blocked: +z by ref/shaft at 2 mm |
| PASS | Insertion, sleeve/motors/lra_u1 | 0.0055 m |  | clear along -z (6 mm to leave the 5 parts present at step 'Sleeve motors'); blocked: +z by sleeve/shell at 2 mm |
| PASS | Insertion, sleeve/motors/lra_u2 | 0.0055 m |  | clear along -z (6 mm to leave the 6 parts present at step 'Sleeve motors'); blocked: +z by sleeve/shell at 2 mm |
| PASS | Insertion, sleeve/motors/lra_fl | 0.0043 m |  | clear along +y (4 mm to leave the 7 parts present at step 'Sleeve motors'); blocked: +z by sleeve/shell at 2 mm, -z by sleeve/shell at 2 mm, +x by sleeve/shell at 2 mm, -x by sleeve/shell at 2 mm |
| PASS | Insertion, sleeve/motors/lra_fr | 0.0043 m |  | clear along -y (4 mm to leave the 8 parts present at step 'Sleeve motors'); blocked: +z by sleeve/shell at 2 mm, -z by sleeve/shell at 2 mm, +x by sleeve/shell at 2 mm, -x by sleeve/shell at 2 mm, +y by sleeve/shell at 2 mm |
| PASS | Insertion, sleeve/motors/lra_b1 | 0.0101 m |  | clear along -z (10 mm to leave the 9 parts present at step 'Sleeve motors'); blocked: +z by sleeve/shell at 2 mm |
| PASS | Insertion, pack/tray | 0 m |  | clear along +z (nothing ahead of it among the 10 parts present at step 'Pack tray') |
| PASS | Insertion, pack/electronics/bat | 0.0264 m |  | clear along +z (26 mm to leave the 11 parts present at step 'Pack contents') |
| PASS | Insertion, pack/electronics/mcu | 0.0244 m |  | clear along +z (24 mm to leave the 12 parts present at step 'Pack contents') |
| PASS | Insertion, pack/electronics/drv1 | 0.0244 m |  | clear along +z (24 mm to leave the 13 parts present at step 'Pack contents') |
| PASS | Insertion, pack/electronics/drv2 | 0.0244 m |  | clear along +z (24 mm to leave the 14 parts present at step 'Pack contents') |
| PASS | Insertion, pack/electronics/drv3 | 0.0244 m |  | clear along +z (24 mm to leave the 15 parts present at step 'Pack contents') |
| PASS | Insertion, pack/electronics/drv4 | 0.0244 m |  | clear along +z (24 mm to leave the 16 parts present at step 'Pack contents') |
| PASS | Insertion, pack/electronics/drv5 | 0.0244 m |  | clear along +z (24 mm to leave the 17 parts present at step 'Pack contents') |
| PASS | Insertion, pack/electronics/mux | 0.0244 m |  | clear along +z (24 mm to leave the 18 parts present at step 'Pack contents') |
| PASS | Insertion, pack/stop_nut | 0.003 m |  | clear along +z (3 mm to leave the 19 parts present at step 'Pack lid with STOP button') |
| PASS | Insertion, pack/lid | 0.002 m |  | clear along +z (2 mm to leave the 20 parts present at step 'Pack lid with STOP button') |
| PASS | Insertion, pack/stop | 0.0244 m |  | clear along +z (24 mm to leave the 21 parts present at step 'Pack lid with STOP button') |
| INFO | Fasteners | 0 fasteners |  | no fasteners modelled, nothing to check |

## Accepted warns

Triaged in `checks.accepted_warns` of assembly.yaml. Still warns, not failures.

| Status | Check | Value | Limit | Detail |
|---|---|---|---|---|
| ACCEPTED | Joint definitions | 1 issues | 0 issues | 13 joints well formed; origin far from its parts: fx_tray: origin is 314 mm from the parent ref/shaft |
| ACCEPTED | Parts form one connected body | 2 clusters | 1 clusters | 2 separate clusters (largest has 12 of 22 parts): cluster of 10 (ref/drain ...). Unknown whether a mount is missing or the gap is intended; check the listed parts' mating frames and offsets. |
| ACCEPTED | Joint parts touch | 0.2669 m | 0.005 m | 1 of 13 joints have parent and child surfaces apart: fx_tray 267 mm apart. Unknown whether the joint origin or the part placement is off (a hub through a bearing may legitimately leave a gap). |

- qa.joints accepted: fx_tray joins the chair-mounted pack to the shaft reference to keep one tree; the pack sits 300 mm away by design (brief D), the cable is not modelled
- qa.connected accepted: the electronics pack is a separate chair-mounted sub-assembly 300 mm from the sleeve; the cable between them is not modelled
- qa.joint_contact accepted: fx_tray is about 270 mm apart by design: the pack is chair-mounted, not touching the sleeve (brief D)

