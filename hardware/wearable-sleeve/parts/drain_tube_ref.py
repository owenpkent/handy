"""REFERENCE FIXTURE, not a purchased or manufactured part: the catheter drain tube, simplified to a straight cylinder running down -Z, mm.
Origin: centre of the top end; the tube hangs along -Z. Frames: top (z +Z)."""
from build123d import Cylinder, Pos

from nlcad.part import Frame, Part


def make(dia=8.0, length=120.0):
    shape = Pos(0, 0, -length / 2) * Cylinder(dia / 2, length)
    return Part(shape=shape, frames={"top": Frame((0, 0, 0))}, density_kg_m3=1000, material="reference", bought=False,
                provenance="estimated",
                bom={"vendor": "", "part_number": "",
                     "description": "REFERENCE FIXTURE (drain tube, straight stand-in for a bend), not a bought or made part; leave out of any purchase list",
                     "source": "estimated: typical drain tube 8 mm OD, 120 mm run before the bend; parametric"})
