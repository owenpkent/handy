"""REFERENCE FIXTURE, not a purchased or manufactured part: stand-in for the wearer's shaft, a cylinder along X, mm.
Origin: centre of the proximal end (X = 0, the base datum); +X distal. Frames: prox (z +Z, coincident with world.base)."""
from build123d import Cylinder, Pos, Rot

from nlcad.part import Frame, Part


def make(dia=32.0, length=95.0):
    shape = Pos(length / 2, 0, 0) * (Rot(Y=90) * Cylinder(dia / 2, length))
    return Part(shape=shape, frames={"prox": Frame((0, 0, 0)), "distal": Frame((length, 0, 0), z=(1, 0, 0))},
                density_kg_m3=1000, material="reference", bought=False, provenance="estimated",
                bom={"vendor": "", "part_number": "",
                     "description": "REFERENCE FIXTURE (anatomy stand-in), not a bought or made part; leave out of any purchase list",
                     "source": "estimated: typical flaccid dimensions; condom catheter sizes 25 to 41 mm, 32 mm mid-range; parametric"})
