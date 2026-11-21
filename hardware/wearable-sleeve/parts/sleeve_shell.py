"""Sleeve shell: an OPEN C-section cast in platinum-cure silicone (Ecoflex 00-30 class), mm. Axis along X (the shaft axis),
X = 0 at the proximal end of the shell, +X distal. The arc is centred on the underside (-Z), so the opening faces +Z.
Motor pockets are cavities with a `skin` of silicone on the shaft side and open through the OUTER face (the motor is pressed in from
outside and sealed with silicone adhesive; a closed cavity cannot be assembled, qa.insert v1). Where a motor does not fit the wall, a pad
(boss) is added on the OUTSIDE only; the inner surface stays at radius `ri`.
Frames: origin; pocket frames u1, u2 (underside), fl, fr (flanks) at each pocket centre, z pointing radially out, x set so the
stand-in's local axes land the way the brief wants (long side of the underside LRA along the shaft).
Parameters: ri inner radius (shaft radius 16 + 0.5 clearance), wall, length, arc_deg (must stay under 300), skin, pad_rim
(pad margin around a pocket), clr (pocket clearance), u_fracs / f_frac (pocket positions along the length)."""
import math

from build123d import Box, Cylinder, Location, Plane, Polygon, Pos, Rot, extrude

from nlcad.part import Frame, Part
from nlcad.shapes import library_entry


def _at(origin, normal, xdir):
    return Location(Plane(origin=origin, x_dir=xdir, z_dir=normal))


def make(ri=16.5, wall=3.5, length=80.0, arc_deg=240.0, skin=1.0, pad_rim=2.0, clr=0.2, u_fracs=(0.30, 0.65), f_frac=0.50):
    assert arc_deg < 300.0, "the shell must never form a closed ring: arc must stay under 300 degrees"
    ro = ri + wall
    half = math.radians((360.0 - arc_deg) / 2)

    ring = Pos(length / 2, 0, 0) * (Rot(Y=90) * (Cylinder(ro, length) - Cylinder(ri, length + 2)))
    r = 3 * ro
    cutter = extrude(Plane.YZ.offset(-1) * Polygon((0, 0), (r * math.sin(half), r * math.cos(half)),
                                                   (-r * math.sin(half), r * math.cos(half)), align=None), amount=length + 2)
    body = ring - cutter
    zmax = body.bounding_box().max.Z
    assert abs(zmax - ro * math.cos(half)) < 0.05, f"opening edge at z {zmax:.2f}, expected {ro * math.cos(half):.2f}"

    vx, vy, vt = (v * 1000 for v in library_entry("lra-vybronics-vlv101040a")["envelope"]["size_m"])   # tangential 10, axial 13.9, thin 4.4
    vg = library_entry("lra-vybronics-vg0832012")["envelope"]
    gd, gt = vg["diameter_m"] * 1000, vg["length_m"] * 1000
    frames, pads, cavities = {"origin": Frame((0, 0, 0))}, [], []

    # underside LRAs: local z = radial outward = -Z
    tp = vt + clr
    pad_out_u = ri + skin + tp + skin            # outer face of the pad; the wall alone reaches ro
    cz = ri + skin + tp / 2
    for name, f in zip(("u1", "u2"), u_fracs):
        xc = f * length
        loc = _at((xc, 0, -cz), (0, 0, -1), (0, 1, 0))     # local x = world Y (tangential), local y = world X (axial)
        pads.append(loc * Pos(0, 0, (ri + 0.5 + pad_out_u) / 2 - cz) * Box(vx + clr + 2 * pad_rim, vy + clr + 2 * pad_rim, pad_out_u - ri - 0.5))
        cavities.append(loc * Pos(0, 0, (skin + 1.0) / 2) * Box(vx + clr, vy + clr, tp + skin + 1.0))      # open through the pad's outer face
        frames[name] = Frame((xc, 0, -cz), z=(0, 0, -1), x=(0, 1, 0))
    # flank coin LRAs: local z = radial outward = +-Y
    tg = gt + clr
    pad_out_f = ri + skin + tg + skin
    cf = ri + skin + tg / 2
    xf = f_frac * length
    for name, s in (("fl", 1), ("fr", -1)):
        loc = _at((xf, s * cf, 0), (0, s, 0), (1, 0, 0))
        pads.append(loc * Pos(0, 0, (ri + 0.5 + pad_out_f) / 2 - cf) * Cylinder(gd / 2 + clr / 2 + pad_rim, pad_out_f - ri - 0.5))
        cavities.append(loc * Pos(0, 0, (skin + 1.0) / 2) * Cylinder(gd / 2 + clr / 2, tg + skin + 1.0))   # open through the pad's outer face
        frames[name] = Frame((xf, s * cf, 0), z=(0, s, 0))
    shell = body
    for p in pads:
        shell = shell + p
    for c in cavities:
        shell = shell - c
    assert len(shell.solids()) == 1
    return Part(shape=shell, frames=frames, density_kg_m3=1070, material="silicone", bought=False, provenance="published",
                bom={"vendor": "in-house cast", "part_number": "sleeve_shell",
                     "description": "Open C-section sleeve, platinum-cure silicone (Ecoflex 00-30 class), cast",
                     "source": "published: Smooth-On Ecoflex 00-30 density about 1070 kg/m3 (specific volume 0.9 cu in/lb); geometry estimated"})
