"""Vybronics VLV101040A LRA stand-in from library/lra-vybronics-vlv101040a.json, a 10 x 13.9 x 4.4 mm box. Origin: centre.
Local z is the thin axis, the one the pocket frame points out of the skin. Frames: center."""
from build123d import Box

from nlcad.part import Frame, Part
from nlcad.shapes import lib_bom, library_entry


def make():
    e = library_entry("lra-vybronics-vlv101040a")
    sx, sy, sz = (v * 1000 for v in e["envelope"]["size_m"])
    return Part(shape=Box(sx, sy, sz), frames={"center": Frame((0, 0, 0))}, mass_kg=e["mass_kg"], material="electronics",
                bought=True, provenance=e["provenance"], status=e.get("status", ""), bom=lib_bom(e))
