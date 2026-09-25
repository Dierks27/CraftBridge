#!/usr/bin/env python3
"""Generate the starter textures of the CraftBridge resource packs.

Writes, relative to the repository root:
  src/main/resources/resourcepack/java/assets/craftbridge/textures/block/*.png   (16x16 face textures)
  src/main/resources/resourcepack/java/pack.png                                   (64x64 pack icon)
  src/main/resources/resourcepack/bedrock/textures/craftbridge/*.png              (64x32 geometry atlases)
  src/main/resources/resourcepack/bedrock/textures/items/craftbridge/*.png        (32x32 inventory icons)
  src/main/resources/resourcepack/bedrock/pack_icon.png                           (64x64 pack icon)

Plain Python 3, no third-party modules. The output is deterministic, so re-running it changes
nothing unless this script changed. Once you edit a texture by hand (in Blockbench or any
paint program) stop running this script, or it will overwrite your work.
"""

import os
import struct
import zlib

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
PACK = os.path.join(ROOT, "src", "main", "resources", "resourcepack")


# ---- tiny PNG writer --------------------------------------------------------------------

def write_png(path, pixels):
    """pixels: list of rows, each a list of (r, g, b, a)."""
    height = len(pixels)
    width = len(pixels[0])
    raw = bytearray()
    for row in pixels:
        raw.append(0)  # filter: none
        for r, g, b, a in row:
            raw += bytes((r, g, b, a))

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def hexc(value, alpha=255):
    value = value.lstrip("#")
    return (int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), alpha)


def shade(color, factor):
    r, g, b, a = color
    return (min(255, int(r * factor)), min(255, int(g * factor)), min(255, int(b * factor)), a)


def blank(w=16, h=16, color=(0, 0, 0, 0)):
    return [[color for _ in range(w)] for _ in range(h)]


class Noise:
    """A small deterministic hash so wood grain looks the same on every run."""

    @staticmethod
    def at(x, y, seed):
        n = (x * 374761393 + y * 668265263 + seed * 2246822519) & 0xFFFFFFFF
        n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
        return ((n ^ (n >> 16)) & 0xFF) / 255.0


# ---- palettes -----------------------------------------------------------------------------

OAK = [hexc("#6B5130"), hexc("#86663D"), hexc("#9C7A4A"), hexc("#B08E58")]   # dark .. light
SPRUCE = [hexc("#4A3421"), hexc("#5E432A"), hexc("#735335"), hexc("#86633F")]
COPPER = [hexc("#8A4A31"), hexc("#B8653F"), hexc("#D9845A"), hexc("#F0A77E")]  # shadow .. highlight
TEAL = [hexc("#0F4F4A"), hexc("#1E7D72"), hexc("#35B3A0"), hexc("#8CEBD9")]
SLATE = [hexc("#141B1E"), hexc("#1F2A2E"), hexc("#2C3B40")]


def planks(seed, palette, vertical=False, rows=(0, 4, 8, 12)):
    """Planks with a seam every 4 pixels and a light grain."""
    img = blank()
    for y in range(16):
        for x in range(16):
            u, v = (y, x) if vertical else (x, y)
            n = Noise.at(u // 3, v, seed)
            base = palette[1] if n < 0.35 else palette[2]
            if Noise.at(u, v, seed + 7) > 0.9:
                base = palette[3]
            if v in rows:
                base = palette[0]
            img[y][x] = base
    return img


def rect(img, x0, y0, x1, y1, color):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            img[y][x] = color


def frame(img, x0, y0, x1, y1, color, highlight=None):
    for x in range(x0, x1 + 1):
        img[y0][x] = highlight or color
        img[y1][x] = color
    for y in range(y0, y1 + 1):
        img[y][x0] = highlight or color
        img[y][x1] = color


def pearl(img, cx, cy):
    """A 4x4 ender-pearl gem: the 'linked' accent both blocks share."""
    pts = {(1, 0): 2, (2, 0): 2, (0, 1): 2, (1, 1): 3, (2, 1): 2, (3, 1): 1,
           (0, 2): 1, (1, 2): 2, (2, 2): 1, (3, 2): 1, (1, 3): 0, (2, 3): 0}
    for (dx, dy), tone in pts.items():
        img[cy + dy][cx + dx] = TEAL[tone]


# ---- Linked Workbench -----------------------------------------------------------------------

def workbench_top():
    img = planks(11, OAK)
    frame(img, 0, 0, 15, 15, OAK[0])
    # a 3x3 crafting grid drawn with copper lines, 4x4 cells
    for line in (5, 10):
        for t in range(1, 15):
            img[line][t] = COPPER[1]
            img[t][line] = COPPER[2] if t % 5 else COPPER[3]
    for (x, y) in ((5, 5), (10, 5), (5, 10), (10, 10)):
        img[y][x] = COPPER[3]
    for x in range(1, 15):
        img[1][x] = shade(img[1][x], 1.1)  # light catches the far edge
    # the centre cell holds the pearl
    pearl(img, 6, 6)
    return img


def workbench_side():
    img = planks(23, OAK, vertical=True, rows=(0, 5, 10, 15))
    # corner posts
    for y in range(16):
        img[y][0] = OAK[0]
        img[y][15] = OAK[0]
    # copper band under the top
    for x in range(16):
        img[0][x] = COPPER[3] if x % 4 == 1 else COPPER[2]
        img[1][x] = COPPER[1]
        img[2][x] = COPPER[0]
    # darker skirting
    for x in range(16):
        img[15][x] = OAK[0]
        img[14][x] = shade(img[14][x], 0.85)
    # rivets
    for x in (2, 13):
        img[1][x] = COPPER[3]
    return img


def workbench_front():
    img = workbench_side()
    # hanging tools: a saw on the left, a hammer on the right (vanilla crafting-table nod)
    for y in range(5, 12):
        img[y][3] = hexc("#C9CDD1") if y < 11 else OAK[0]
        img[y][4] = hexc("#9EA3A8") if y < 11 else OAK[0]
    img[4][3] = OAK[0]
    img[4][4] = OAK[0]
    rect(img, 11, 5, 13, 6, hexc("#9EA3A8"))
    img[5][11] = hexc("#C9CDD1")
    for y in range(7, 12):
        img[y][12] = OAK[0]
    # a copper plaque with the pearl in the middle
    frame(img, 6, 6, 9, 11, COPPER[1], COPPER[2])
    rect(img, 7, 7, 8, 10, SLATE[1])
    img[8][7] = TEAL[2]
    img[8][8] = TEAL[3]
    img[9][7] = TEAL[1]
    img[9][8] = TEAL[2]
    return img


def workbench_bottom():
    return planks(31, OAK)


# ---- Combo Chest --------------------------------------------------------------------------

def combo_side():
    img = planks(41, SPRUCE, vertical=True, rows=(0, 4, 8, 12))
    for band in (2, 12):
        for x in range(16):
            img[band][x] = COPPER[2] if x % 5 else COPPER[3]
            img[band + 1][x] = COPPER[0]
    return img


def combo_front():
    img = combo_side()
    # the panel element sits over rows 5..10; give its surround a dark rim so edges read well
    frame(img, 3, 4, 12, 11, SPRUCE[0])
    return img


def combo_top():
    img = planks(53, SPRUCE)
    frame(img, 0, 0, 15, 15, SPRUCE[0])
    # copper ring
    for y in range(16):
        for x in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            if 5.2 <= d < 6.4:
                img[y][x] = COPPER[2] if (x + y) % 3 else COPPER[1]
            elif 6.4 <= d < 7.0:
                img[y][x] = COPPER[0]
    pearl(img, 6, 6)
    return img


def combo_bottom():
    img = planks(61, SPRUCE)
    frame(img, 0, 0, 15, 15, SPRUCE[0])
    return img


def combo_panel():
    """8x6 storage screen, laid out in a 16x16 texture (top-left) with a 1px copper rim."""
    img = blank(16, 16, SLATE[0])
    rect(img, 0, 0, 7, 5, SLATE[1])
    frame(img, 0, 0, 7, 5, COPPER[1], COPPER[2])
    # three "item rows" on the screen
    for row, length in ((1, 5), (2, 3), (3, 4)):
        for x in range(1, 1 + length):
            img[row][x] = TEAL[2] if x % 2 else TEAL[1]
    img[4][6] = TEAL[3]  # a status light
    # the side strips (used by the panel's thin edges)
    rect(img, 8, 0, 15, 15, COPPER[0])
    return img


# ---- Bedrock atlases and icons -------------------------------------------------------------

def atlas(faces):
    """64x32: top, bottom, front, side in the first row; panel (if any) at (0,16)."""
    img = blank(64, 32)
    for i, name in enumerate(("top", "bottom", "front", "side")):
        for y in range(16):
            for x in range(16):
                img[y][16 * i + x] = faces[name][y][x]
    if "panel" in faces:
        for y in range(16):
            for x in range(16):
                img[16 + y][x] = faces["panel"][y][x]
    return img


def iso_icon(top, left, right, size=32):
    """A little isometric block render: top face, left face (front), right face (side)."""
    img = blank(size, size)
    s = size / 32.0
    # corners in 32-px space
    faces = [
        (top, (0, 8), (16, 0), (16, 16), 1.0),      # origin, u axis end, v axis end
        (left, (0, 8), (16, 16), (0, 24), 0.82),
        (right, (16, 16), (32, 8), (16, 32), 0.64),
    ]
    for py in range(size):
        for px in range(size):
            X, Y = (px + 0.5) / s, (py + 0.5) / s
            for tex, o, ue, ve, light in faces:
                ux, uy = ue[0] - o[0], ue[1] - o[1]
                vx, vy = ve[0] - o[0], ve[1] - o[1]
                det = ux * vy - uy * vx
                dx, dy = X - o[0], Y - o[1]
                u = (dx * vy - dy * vx) / det
                v = (ux * dy - uy * dx) / det
                if 0 <= u < 1 and 0 <= v < 1:
                    c = tex[int(v * 16)][int(u * 16)]
                    img[py][px] = shade(c, light)
                    break
    return img


def scale2(img):
    out = []
    for row in img:
        wide = []
        for c in row:
            wide += [c, c]
        out.append(wide)
        out.append(list(wide))
    return out


def main():
    java_tex = os.path.join(PACK, "java", "assets", "craftbridge", "textures", "block")
    wb = {"top": workbench_top(), "side": workbench_side(), "front": workbench_front(), "bottom": workbench_bottom()}
    cc = {"top": combo_top(), "side": combo_side(), "front": combo_front(), "bottom": combo_bottom(),
          "panel": combo_panel()}
    for name, img in wb.items():
        write_png(os.path.join(java_tex, "linked_workbench_" + name + ".png"), img)
    for name, img in cc.items():
        write_png(os.path.join(java_tex, "combo_chest_" + name + ".png"), img)

    bedrock = os.path.join(PACK, "bedrock", "textures")
    write_png(os.path.join(bedrock, "craftbridge", "linked_workbench.png"), atlas(wb))
    write_png(os.path.join(bedrock, "craftbridge", "combo_chest.png"), atlas(cc))

    cc_front_with_panel = [list(r) for r in cc["front"]]
    for y in range(6):
        for x in range(8):
            cc_front_with_panel[5 + y][4 + x] = cc["panel"][y][x]
    wb_icon = iso_icon(wb["top"], wb["front"], wb["side"])
    cc_icon = iso_icon(cc["top"], cc_front_with_panel, cc["side"])
    write_png(os.path.join(bedrock, "items", "craftbridge", "linked_workbench.png"), wb_icon)
    write_png(os.path.join(bedrock, "items", "craftbridge", "combo_chest.png"), cc_icon)

    icon = scale2(wb_icon)
    write_png(os.path.join(PACK, "java", "pack.png"), icon)
    write_png(os.path.join(PACK, "bedrock", "pack_icon.png"), icon)


if __name__ == "__main__":
    main()
