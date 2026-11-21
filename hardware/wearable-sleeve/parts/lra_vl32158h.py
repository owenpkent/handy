"""Vybronics VL32158H-L25 LRA stand-in from library/lra-vybronics-vl32158h-l25.json, a 35.2 x 15 x 8 mm box. Origin: centre.
Frames: center."""
from build123d import Box

from nlcad.part import Frame, Part
from nlcad.shapes import lib_bom, library_entry


def make():
    e = library_entry("lra-vybronics-vl32158h-l25")
    sx, sy, sz = (v * 1000 for v in e["envelope"]["size_m"])
    return Part(shape=Box(sx, sy, sz), frames={"center": Frame((0, 0, 0))}, mass_kg=e["mass_kg"], material="electronics",
                bought=True, provenance=e["provenance"], status=e.get("status", ""), bom=lib_bom(e))
