"""Electronics enclosure, tray: 3D-printed PETG open box, mm. Origin: centre of the underside, +Z up.
Cavity = hull of the contents (battery, then driver and MCU side by side) plus `clearance` on the sides and top; contents rest on the
floor (standoffs are not modelled). Frames: datum (underside, for the world pack frame), bat_seat, mcu_seat, drv_seat (z +Z on the
floor), lid_seat (top rim, z +Z)."""
from build123d import Box, Pos

from nlcad.part import Frame, Part
from nlcad.shapes import library_entry


def _mm(i):
    return [v * 1000 for v in library_entry(i)["envelope"]["size_m"]]


def make(wall=2.0, clearance=2.0, gap=3.0):
    bx, by, bz = _mm("battery-lipo-1s-1000mah")
    mx, my, mz = _mm("mcu-seeed-xiao-esp32c3")
    dx, dy, dz = _mm("driver-haptic-adafruit-drv2605l")
    hull_x = bx + gap + max(mx, dx)
    hull_y = max(by, my + gap + dy)
    hull_z = max(bz, mz, dz)
    cx, cy, cz = hull_x + 2 * clearance, hull_y + 2 * clearance, hull_z + clearance
    ox, oy, oz = cx + 2 * wall, cy + 2 * wall, cz + wall
    shape = Pos(0, 0, oz / 2) * Box(ox, oy, oz) - Pos(0, 0, wall + cz / 2 + 0.005) * Box(cx, cy, cz + 0.01)
    assert len(shape.solids()) == 1
    bat_x = -cx / 2 + clearance + bx / 2
    brd_x = cx / 2 - clearance - max(mx, dx) / 2
    f = {"datum": Frame((0, 0, 0)),
         "bat_seat": Frame((bat_x, 0, wall)),
         "mcu_seat": Frame((brd_x, -hull_y / 2 + my / 2, wall)),
         "drv_seat": Frame((brd_x, hull_y / 2 - dy / 2, wall)),
         "lid_seat": Frame((0, 0, oz))}
    return Part(shape=shape, frames=f, density_kg_m3=1270, material="petg", bought=False, provenance="published",
                bom={"vendor": "in-house print", "part_number": "enclosure_tray",
                     "description": "Electronics enclosure tray, 3D-printed PETG, 2 mm wall",
                     "source": "published: PETG density about 1270 kg/m3 (typical filament datasheets); geometry estimated"})
