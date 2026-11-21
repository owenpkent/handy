"""Electronics enclosure, lid: 3D-printed PETG plate with a locating lip, mm. Origin: centre of the plate's underside, +Z up.
The lip hangs below the plate into the tray cavity (cavity size from the same contents hull as the tray). Frames: bottom (z -Z, face down)."""
from build123d import Box, Pos

from nlcad.part import Frame, Part
from nlcad.shapes import library_entry


def _mm(i):
    return [v * 1000 for v in library_entry(i)["envelope"]["size_m"]]


def make(wall=2.0, clearance=2.0, gap=3.0, lip_h=2.0, lip_t=1.5, lip_fit=0.2):
    bx, by, bz = _mm("battery-lipo-1s-1000mah")
    mx, my, mz = _mm("mcu-seeed-xiao-esp32c3")
    dx, dy, dz = _mm("driver-haptic-adafruit-drv2605l")
    cx = bx + gap + max(mx, dx) + 2 * clearance
    cy = max(by, my + gap + dy) + 2 * clearance
    plate = Pos(0, 0, wall / 2) * Box(cx + 2 * wall, cy + 2 * wall, wall)
    ix, iy = cx - 2 * lip_fit, cy - 2 * lip_fit
    lip = Pos(0, 0, -lip_h / 2) * (Box(ix, iy, lip_h) - Box(ix - 2 * lip_t, iy - 2 * lip_t, lip_h + 1))
    shape = plate + lip
    assert len(shape.solids()) == 1
    return Part(shape=shape, frames={"bottom": Frame((0, 0, 0), z=(0, 0, -1))}, density_kg_m3=1270, material="petg",
                bought=False, provenance="published",
                bom={"vendor": "in-house print", "part_number": "enclosure_lid",
                     "description": "Electronics enclosure lid with locating lip, 3D-printed PETG",
                     "source": "published: PETG density about 1270 kg/m3 (typical filament datasheets); geometry estimated"})
