"""Seeed XIAO ESP32-C3 stand-in from library/mcu-seeed-xiao-esp32c3.json, a box at the library envelope. Origin: centre of the body.
Frames: bottom (the underside, z pointing out of it, so it mates face to face onto a seat frame)."""
from build123d import Box

from nlcad.part import Frame, Part
from nlcad.shapes import lib_bom, library_entry


def make():
    e = library_entry("mcu-seeed-xiao-esp32c3")
    sx, sy, sz = (v * 1000 for v in e["envelope"]["size_m"])
    return Part(shape=Box(sx, sy, sz), frames={"bottom": Frame((0, 0, -sz / 2), z=(0, 0, -1))}, mass_kg=e["mass_kg"],
                material="electronics", bought=True, provenance=e["provenance"], status=e.get("status", ""), bom=lib_bom(e))
