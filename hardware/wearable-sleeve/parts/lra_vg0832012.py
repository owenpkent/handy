"""Vybronics VG0832012 coin LRA stand-in from library/lra-vybronics-vg0832012.json, 8 mm dia x 3.2 mm, axis Z. Origin: centre.
Frames: center."""
from build123d import Cylinder

from nlcad.part import Frame, Part
from nlcad.shapes import lib_bom, library_entry


def make():
    e = library_entry("lra-vybronics-vg0832012")
    env = e["envelope"]
    assert env["axis"] == "z"
    return Part(shape=Cylinder(env["diameter_m"] * 500, env["length_m"] * 1000), frames={"center": Frame((0, 0, 0))},
                mass_kg=e["mass_kg"], material="electronics", bought=True, provenance=e["provenance"],
                status=e.get("status", ""), bom=lib_bom(e))
