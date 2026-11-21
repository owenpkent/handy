"""STOP pushbutton stand-in from library/button-adafruit-16mm-red-ring-momentary.json, mm. Origin: the panel face, the underside of the head.
Head (library diameter) sits above the panel (z 0 to head_h); the threaded shank (hole size minus `shank_clear`) runs down the rest of the
library length. head_h 5 mm is estimated (the vendor page gives no head height). Frames: seat (z -Z, the head's underside: mates onto the
lid's button_seat frame)."""
from build123d import Cylinder, Pos

from nlcad.part import Frame, Part
from nlcad.shapes import lib_bom, library_entry


def make(hole_d=16.0, shank_clear=0.2, head_h=5.0):
    e = library_entry("button-adafruit-16mm-red-ring-momentary")
    dia, length = e["envelope"]["diameter_m"] * 1000, e["envelope"]["length_m"] * 1000
    assert dia >= 16.0, "cap diameter below the 16 mm brief minimum"
    shank_l = length - head_h
    head = Pos(0, 0, head_h / 2) * Cylinder(dia / 2, head_h)
    shank = Pos(0, 0, -shank_l / 2 + 0.01) * Cylinder((hole_d - shank_clear) / 2, shank_l + 0.02)
    return Part(shape=head + shank, frames={"seat": Frame((0, 0, 0), z=(0, 0, -1))}, mass_kg=e["mass_kg"],
                material="electronics", bought=True, provenance=e["provenance"], status=e.get("status", ""), bom=lib_bom(e))
