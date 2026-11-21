"""Base pod: a silicone box with a cavity (open underneath) around the 35.2 x 15 x 8 mm LRA (long axis across Y), 2 mm wall on the sides and top, open underneath (motor pressed in from below, sealed with silicone adhesive), mm.
Origin: centre of the pod, authored in world orientation (X along the shaft, Y across, Z up). Joined to the shell's proximal underside.
Frames: origin; motor (at the centre, z +Z, x +Y so the stand-in's 35.2 mm side runs across Y)."""
from build123d import Box, Pos

from nlcad.part import Frame, Part
from nlcad.shapes import library_entry


def make(wall=2.0, clr=0.2):
    sx, sy, sz = (v * 1000 for v in library_entry("lra-vybronics-vl32158h-l25")["envelope"]["size_m"])   # 35.2 x 15 x 8, x long
    cav_y, cav_x, cav_z = sx + clr, sy + clr, sz + clr          # long axis across Y, 15 along X
    shape = Box(cav_x + 2 * wall, cav_y + 2 * wall, cav_z + 2 * wall) - Pos(0, 0, -(wall + 1.0) / 2) * Box(cav_x, cav_y, cav_z + wall + 1.0)   # open underneath: the motor goes in from below
    assert len(shape.solids()) == 1
    return Part(shape=shape, frames={"origin": Frame((0, 0, 0)), "motor": Frame((0, 0, 0), z=(0, 0, 1), x=(0, 1, 0))},
                density_kg_m3=1070, material="silicone", bought=False, provenance="published",
                bom={"vendor": "in-house cast", "part_number": "base_pod",
                     "description": "Base pod around the 100 Hz LRA, platinum-cure silicone, cast",
                     "source": "published: Smooth-On Ecoflex 00-30 density about 1070 kg/m3; geometry estimated"})
