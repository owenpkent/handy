"""Electronics enclosure, tray: 3D-printed PETG open box, mm. Origin: centre of the underside, +Z up.
Layout (top view, +Y up, all gaps `clearance` = 2 mm unless noted; the lid has the same function, keep them equal):
  row 1 (+Y): 5 x DRV2605L side by side along X, long axis Y, 8 mm cable-bend keepout at each short end (STEMMA QT ends, estimated at both
              ends of the board) so the row is 8 + 25.8 + 8 deep. The sleeve harness gland (PG7) is in the +Y wall in front of this row.
  row 2:      left column XIAO (USB-C end against the -X wall, slot cut there) over the TCA9548A, then the battery, then a free column
              under the STOP button (the button shank hangs 24.4 mm below the lid, nothing under it).
Boards sit on 3 x 3 x 2 mm printed posts at their corners; the battery rests on the floor. Frames: datum (underside, for the world pack
frame), bat_seat, mcu_seat, mux_seat, drv_seat_1..5 (z +Z on the post tops or floor), lid_seat (top rim, z +Z).
Numbers: board and battery sizes from the library entries; 8 mm bend and 2 mm clearance from the brief; PG7 thread 12.5 mm published
(DIN 40430 major diameter); USB-C slot 11 x 5 mm estimated (receptacle 8.9 x 3.3 mm plus plug overmould and wiggle)."""
from build123d import Box, Cylinder, Pos, Rot

from nlcad.part import Frame, Part
from nlcad.shapes import library_entry


def _mm(i):
    return [v * 1000 for v in library_entry(i)["envelope"]["size_m"]]


def layout(clearance=2.0, bend=8.0, post=2.0, btn_d=18.0, btn_depth=24.4):
    """Hull of the contents (x, y), the cavity height and each item's footprint centre, relative to the cavity centre."""
    bx, by, bz = _mm("battery-lipo-1s-1000mah")
    mx, my, mz = _mm("mcu-seeed-xiao-esp32c3")
    tx, ty, tz = _mm("mux-i2c-adafruit-tca9548a")
    dx, dy, dz = _mm("driver-haptic-adafruit-drv2605l")
    c = clearance
    row1_y = bend + dx + bend                       # drivers stand with their long axis along Y
    row1_x = 5 * dy + 4 * c
    col_w = max(tx, mx)
    row2_y = max(by, my + c + ty)
    btn_w = btn_d + 2 * c
    hx = max(row1_x, col_w + c + bx + c + btn_w)
    hy = row1_y + c + row2_y
    cz = max(post + max(mz, tz, dz), bz, btn_depth) + c
    left, top = -hx / 2, hy / 2
    r2top = top - row1_y - c
    pos = {"drv": [(left + dy / 2 + i * (dy + c), top - bend - dx / 2) for i in range(5)],
           "mcu": (left + mx / 2, r2top - my / 2),
           "mux": (left + tx / 2, r2top - my - c - ty / 2),
           "bat": (left + col_w + c + bx / 2, r2top - by / 2),
           "btn": (hx / 2 - btn_w / 2, r2top - by / 2)}
    return dict(hx=hx, hy=hy, cz=cz, pos=pos, dims=dict(bx=bx, by=by, bz=bz, mx=mx, my=my, mz=mz, tx=tx, ty=ty, tz=tz, dx=dx, dy=dy, dz=dz),
                left=left, row1_x=row1_x)


def make(wall=2.0, clearance=2.0, post=2.0, post_w=3.0, gland_d=12.5, usb_w=11.0, usb_h=5.0):
    L = layout(clearance=clearance, post=post)
    d = L["dims"]
    cx, cy, cz = L["hx"] + 2 * clearance, L["hy"] + 2 * clearance, L["cz"]
    ox, oy, oz = cx + 2 * wall, cy + 2 * wall, cz + wall
    shape = Pos(0, 0, oz / 2) * Box(ox, oy, oz) - Pos(0, 0, wall + cz / 2 + 0.005) * Box(cx, cy, cz + 0.01)
    # printed posts under every board (battery sits on the floor); each post overlaps the floor by 0.5 mm so it fuses
    def posts(x, y, w, h):
        out = []
        for sx in (-1, 1):
            for sy in (-1, 1):
                px, py = x + sx * (w / 2 - 0.5 - post_w / 2), y + sy * (h / 2 - 0.5 - post_w / 2)
                out.append(Pos(px, py, wall + post / 2 - 0.25) * Box(post_w, post_w, post + 0.5))
        return out
    for i, (x, y) in enumerate(L["pos"]["drv"]):
        for p in posts(x, y, d["dy"], d["dx"]):
            shape = shape + p
    for k, (w, h) in (("mcu", (d["mx"], d["my"])), ("mux", (d["tx"], d["ty"]))):
        x, y = L["pos"][k]
        for p in posts(x, y, w, h):
            shape = shape + p
    # sleeve harness gland (PG7) through the +Y wall in front of the driver row, USB-C slot through the -X wall at the XIAO
    gx = L["left"] + L["row1_x"] / 2
    gz = wall + 9.0
    shape = shape - Pos(gx, oy / 2 - wall / 2, gz) * Rot(X=90) * Cylinder(gland_d / 2, wall + 2)
    ux, uy = L["pos"]["mcu"]
    uz = wall + post + d["mz"] / 2
    shape = shape - Pos(-ox / 2 + wall / 2, uy, uz) * Box(wall + 2, usb_w, usb_h)
    assert len(shape.solids()) == 1
    f = {"datum": Frame((0, 0, 0)), "lid_seat": Frame((0, 0, oz)),
         "bat_seat": Frame((*L["pos"]["bat"], wall)),
         "mcu_seat": Frame((*L["pos"]["mcu"], wall + post)),
         "mux_seat": Frame((*L["pos"]["mux"], wall + post))}
    for i, (x, y) in enumerate(L["pos"]["drv"]):
        f[f"drv_seat_{i + 1}"] = Frame((x, y, wall + post))
    return Part(shape=shape, frames=f, density_kg_m3=1270, material="petg", bought=False, provenance="published",
                bom={"vendor": "in-house print", "part_number": "enclosure_tray",
                     "description": "Electronics enclosure tray, 3D-printed PETG, 2 mm wall, board posts, PG7 gland hole, USB-C slot",
                     "source": "published: PETG density about 1270 kg/m3 (typical filament datasheets), PG7 thread 12.5 mm (DIN 40430); geometry estimated"})
