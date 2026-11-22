"""STOP button retaining nut (Adafruit 559 rear thread), mm. Origin: the nut's top face on the shaft axis, which bears on the lid underside;
the body hangs below (z -nut_t to 0). Hex (flats facing +/-X, so the corners stay out of the lid's locating lip), `af` across flats, bore equal to the button shank. Thread size, across-flats and thickness are
ESTIMATED (the vendor page gives the 16 mm hole only; 22 mm across flats and 3 mm thick are typical for a 16 mm panel-mount button nut and
need checking on the real part). Frames: seat (z +Z, the top face: mates onto the lid's nut_seat frame)."""
from build123d import Cylinder, Plane, Pos, RegularPolygon, Rot, extrude

from nlcad.part import Frame, Part


def make(af=22.0, nut_t=3.0, bore_d=15.8):
    r = af / 2 / 0.8660254
    body = Pos(0, 0, -nut_t) * extrude(Plane.XY * Rot(Z=30) * RegularPolygon(r, 6), nut_t)
    shape = body - Pos(0, 0, -nut_t / 2) * Cylinder(bore_d / 2, nut_t + 1)
    assert len(shape.solids()) == 1
    return Part(shape=shape, frames={"seat": Frame((0, 0, 0), z=(0, 0, 1))}, density_kg_m3=7800, material="steel", bought=True,
                provenance="estimated", status="estimated",
                bom={"vendor": "Adafruit", "part_number": "559 (nut supplied with the button)",
                     "description": "Hex retaining nut for the 16 mm STOP button, retained from the lid underside",
                     "source": "estimated: 22 mm across flats, 3 mm thick, steel; the Adafruit 559 page gives only the 16 mm hole and the shank length is borrowed from product 1440"})
