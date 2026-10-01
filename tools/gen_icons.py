#!/usr/bin/env python3
"""Generate legacy (API < 26) launcher PNGs for ReflectMaster 2.0.

Pure stdlib: no PIL available in this sandbox, so the icon is rasterised by
hand with 4x supersampling and written out as a PNG via zlib + struct.

The artwork mirrors res/drawable/ic_launcher_foreground.xml so the legacy icon
and the adaptive icon look identical:
  * indigo rounded-square backdrop (#3949AB)
  * solid white triangle (left) + translucent mirrored triangle (right)
  * thin vertical "mirror axis"
  * faint diamond outline
"""
import os
import struct
import zlib

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_ROOT = os.path.join(PROJECT_ROOT, "app", "src", "main", "res")

# 108x108 design space (same as the adaptive-icon canvas).
S = 108.0
SS = 4  # supersample factor

BG = (0x39, 0x49, 0xAB)
WHITE = (255, 255, 255)

LEFT_TRI = ((30.0, 42.0), (48.0, 54.0), (30.0, 66.0))
RIGHT_TRI = ((78.0, 42.0), (60.0, 54.0), (78.0, 66.0))
CORNER_R = 20.5
LINE_X0, LINE_X1, LINE_Y0, LINE_Y1 = 52.6, 55.4, 28.0, 80.0
DIAMOND_R = 31.0
DIAMOND_W = 1.25


def in_triangle(px, py, tri):
    (x1, y1), (x2, y2), (x3, y3) = tri
    d = (y2 - y3) * (x1 - x3) + (x3 - x2) * (y1 - y3)
    if d == 0:
        return False
    a = ((y2 - y3) * (px - x3) + (x3 - x2) * (py - y3)) / d
    b = ((y3 - y1) * (px - x3) + (x1 - x3) * (py - y3)) / d
    c = 1.0 - a - b
    return a >= 0 and b >= 0 and c >= 0


def in_rounded_rect(px, py, size, r):
    if px < 0 or py < 0 or px >= size or py >= size:
        return False
    cx = min(max(px, r), size - r)
    cy = min(max(py, r), size - r)
    dx = px - cx
    dy = py - cy
    return dx * dx + dy * dy <= r * r


def in_circle(px, py, cx, cy, r):
    dx = px - cx
    dy = py - cy
    return dx * dx + dy * dy <= r * r


def sample(x, y, round_icon):
    """Return (r, g, b, a) for one point in design space."""
    if round_icon:
        if not in_circle(x, y, S / 2, S / 2, S / 2):
            return (0, 0, 0, 0)
    elif not in_rounded_rect(x, y, S, CORNER_R):
        return (0, 0, 0, 0)

    layers = []
    if in_triangle(x, y, LEFT_TRI):
        layers.append((WHITE, 1.00))
    if in_triangle(x, y, RIGHT_TRI):
        layers.append((WHITE, 0.55))
    if LINE_X0 <= x <= LINE_X1 and LINE_Y0 <= y <= LINE_Y1:
        layers.append((WHITE, 0.90))
    manhattan = abs(x - S / 2) + abs(y - S / 2)
    if abs(manhattan - DIAMOND_R) <= DIAMOND_W:
        layers.append((WHITE, 0.35))

    r, g, b = BG
    a_out = 1.0
    for (cr, cg, cb), ca in layers:
        r = cr * ca + r * (1 - ca)
        g = cg * ca + g * (1 - ca)
        b = cb * ca + b * (1 - ca)
    return (int(r + 0.5), int(g + 0.5), int(b + 0.5), int(255 * a_out))


def render(size, round_icon):
    big = size * SS
    scale = big / S
    rows = []
    for by in range(big):
        y = (by + 0.5) / scale
        row = bytearray()
        for bx in range(big):
            x = (bx + 0.5) / scale
            r, g, b, a = sample(x, y, round_icon)
            row += bytes((r, g, b, a))
        rows.append(row)

    # box downsample
    out = bytearray()
    for oy in range(size):
        line = bytearray()
        for ox in range(size):
            sr = sg = sb = sa = 0
            for j in range(SS):
                base = rows[oy * SS + j]
                off = ox * SS * 4
                for i in range(SS):
                    p = off + i * 4
                    sr += base[p]
                    sg += base[p + 1]
                    sb += base[p + 2]
                    sa += base[p + 3]
            n = SS * SS
            line += bytes((sr // n, sg // n, sb // n, sa // n))
        out += b"\x00" + bytes(line)
    return bytes(out), size


def write_png(path, raw, size):
    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)
    return len(png)


DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

total = 0
for name, size in DENSITIES.items():
    for round_icon, fname in ((False, "ic_launcher.png"), (True, "ic_launcher_round.png")):
        raw, s = render(size, round_icon)
        path = os.path.join(OUT_ROOT, "mipmap-" + name, fname)
        n = write_png(path, raw, s)
        total += n
        print("%-34s %4dx%-4d %7d bytes" % (os.path.relpath(path, OUT_ROOT), s, s, n))

print("total bytes:", total)
