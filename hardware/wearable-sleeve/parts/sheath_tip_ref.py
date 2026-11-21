"""REFERENCE FIXTURE, not a purchased or manufactured part: the catheter tip and connector at the distal end, a cylinder along X, mm.
Origin: centre of the proximal end; +X distal. Frames: prox (z +Z). The drain tube attaches at drain."""
from build123d import Cylinder, Pos, Rot

from nlcad.part import Frame, Part


def make(dia=14.0, length=25.0):
    shape = Pos(length / 2, 0, 0) * (Rot(Y=90) * Cylinder(dia / 2, length))
    return Part(shape=shape, frames={"prox": Frame((0, 0, 0)), "drain": Frame((length - dia / 2 - 2, 0, -dia / 2 + 1))},
                density_kg_m3=1000, material="reference", bought=False, provenance="estimated",
                bom={"vendor": "", "part_number": "",
                     "description": "REFERENCE FIXTURE (catheter tip and connector), not a bought or made part; leave out of any purchase list",
                     "source": "estimated: typical external catheter tip/connector, 14 mm x 25 mm; parametric"})
