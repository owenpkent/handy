"""Electronics enclosure, lid: 3D-printed PETG plate with a locating lip and the STOP button hole, mm. Origin: centre of the plate's
underside, +Z up. The lip hangs below the plate into the tray cavity (cavity size from the same layout as the tray; keep the two
`layout` functions equal). The 16 mm round hole (published drill size of the Adafruit 559 button) sits over the free column of the tray.
Frames: bottom (z -Z, face down), button_seat (top face at the hole, z +Z)."""
from build123d import Box, Cylinder, Pos

from nlcad.part import Frame, Part
from nlcad.shapes import library_entry


def _mm(i):
    return [v * 1000 for v in library_entry(i)["envelope"]["size_m"]]


def layout(clearance=2.0, bend=8.0, post=2.0, btn_d=18.0, btn_depth=24.4):
    bx, by, bz = _mm("battery-lipo-1s-1000mah")
    mx, my, mz = _mm("mcu-seeed-xiao-esp32c3")
    tx, ty, tz = _mm("mux-i2c-adafruit-tca9548a")
    dx, dy, dz = _mm("driver-haptic-adafruit-drv2605l")
    c = clearance
    row1_y, row1_x = bend + dx + bend, 5 * dy + 4 * c
    col_w, row2_y, btn_w = max(tx, mx), max(by, my + c + ty), btn_d + 2 * c
    hx, hy = max(row1_x, col_w + c + bx + c + btn_w), row1_y + c + row2_y
    r2top = hy / 2 - row1_y - c
    return hx, hy, (hx / 2 - btn_w / 2, r2top - by / 2)


def make(wall=2.0, clearance=2.0, lip_h=2.0, lip_t=1.5, lip_fit=0.2, hole_d=16.0):
    hx, hy, (bxp, byp) = layout(clearance=clearance)
    cx, cy = hx + 2 * clearance, hy + 2 * clearance
    plate = Pos(0, 0, wall / 2) * Box(cx + 2 * wall, cy + 2 * wall, wall)
    ix, iy = cx - 2 * lip_fit, cy - 2 * lip_fit
    lip = Pos(0, 0, -lip_h / 2) * (Box(ix, iy, lip_h) - Box(ix - 2 * lip_t, iy - 2 * lip_t, lip_h + 1))
    shape = plate + lip - Pos(bxp, byp, wall / 2) * Cylinder(hole_d / 2, wall + 1)
    assert len(shape.solids()) == 1
    return Part(shape=shape, frames={"bottom": Frame((0, 0, 0), z=(0, 0, -1)), "button_seat": Frame((bxp, byp, wall))},
                density_kg_m3=1270, material="petg", bought=False, provenance="published",
                bom={"vendor": "in-house print", "part_number": "enclosure_lid",
                     "description": "Electronics enclosure lid with locating lip and 16 mm STOP button hole, 3D-printed PETG",
                     "source": "published: PETG density about 1270 kg/m3 (typical filament datasheets), 16 mm hole per Adafruit 559; geometry estimated"})
