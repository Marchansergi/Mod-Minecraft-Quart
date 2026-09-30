#!/usr/bin/env python3
"""Render isomètric d'una zona d'un món generat (per revisar façanes).
Ús: iso_render.py <region> <x0> <z0> <x1> <z1> <y0> <y1> <sortida.png> [vista 0-3]"""
import io, math, os, struct, sys, zlib
import nbtlib
import numpy as np
from PIL import Image, ImageDraw
sys.path.insert(0, os.path.dirname(__file__))
from render_world import color, read_chunk


def blocks(reg, x0, z0, x1, z1, y0, y1):
    out = {}
    for cz in range(z0 >> 4, ((z1 - 1) >> 4) + 1):
        for cx in range(x0 >> 4, ((x1 - 1) >> 4) + 1):
            rp = os.path.join(reg, f"r.{cx >> 5}.{cz >> 5}.mca")
            if not os.path.exists(rp):
                continue
            with open(rp, "rb") as f:
                c = read_chunk(f, (cx & 31) + (cz & 31) * 32)
            if c is None:
                continue
            for s in c["sections"]:
                if "block_states" not in s:
                    continue
                sy = int(s["Y"])
                if sy * 16 + 15 < y0 or sy * 16 > y1:
                    continue
                bs = s["block_states"]
                pal = [str(p["Name"]).split(":")[-1] for p in bs["palette"]]
                if "data" not in bs:
                    if pal[0] == "air":
                        continue
                    idxs = np.zeros(4096, int)
                else:
                    bits = max(4, math.ceil(math.log2(len(pal))))
                    per = 64 // bits
                    data = [int(v) & 0xFFFFFFFFFFFFFFFF for v in bs["data"]]
                    idxs = np.array([(data[i // per] >> ((i % per) * bits)) & ((1 << bits) - 1) for i in range(4096)])
                for i in range(4096):
                    n = pal[idxs[i]]
                    if n in ("air", "cave_air", "void_air"):
                        continue
                    lx, lz, ly = i & 15, (i >> 4) & 15, i >> 8
                    x, y, z = cx * 16 + lx, sy * 16 + ly, cz * 16 + lz
                    if x0 <= x < x1 and z0 <= z < z1 and y0 <= y <= y1:
                        out[(x, y, z)] = n
    return out


def main():
    reg = sys.argv[1]
    x0, z0, x1, z1, y0, y1 = map(int, sys.argv[2:8])
    outp = sys.argv[8]
    view = int(sys.argv[9]) if len(sys.argv) > 9 else 0
    B = blocks(reg, x0, z0, x1, z1, y0, y1)
    S = int(os.environ.get('ISO_S', '6'))  # mida de la cara
    # gira la vista
    def rot(x, z):
        cx, cz = x - x0, z - z0
        W, D = x1 - x0, z1 - z0
        return [(cx, cz), (D - 1 - cz, cx), (W - 1 - cx, D - 1 - cz), (cz, W - 1 - cx)][view]
    items = []
    for (x, y, z), n in B.items():
        rx, rz = rot(x, z)
        items.append((rx + rz, y, rx, rz, n))
    items.sort()
    Wimg = (x1 - x0 + z1 - z0) * S + 40
    Himg = (x1 - x0 + z1 - z0) * S // 2 + (y1 - y0) * S + 60
    im = Image.new("RGB", (Wimg, Himg), (170, 200, 230))
    dr = ImageDraw.Draw(im)
    ox, oy = (z1 - z0) * S + 20, (y1 - y0) * S + 30
    for _, y, rx, rz, n in items:
        c = np.array(color(n), float)
        if n in ("water",):
            c = np.array((60, 110, 210.0))
        sx = ox + (rx - rz) * S
        sy = oy + (rx + rz) * S // 2 - (y - y0) * S
        top = [(sx, sy), (sx + S, sy + S // 2), (sx, sy + S), (sx - S, sy + S // 2)]
        left = [(sx - S, sy + S // 2), (sx, sy + S), (sx, sy + 2 * S), (sx - S, sy + S + S // 2)]
        right = [(sx, sy + S), (sx + S, sy + S // 2), (sx + S, sy + S + S // 2), (sx, sy + 2 * S)]
        t = lambda k: tuple(int(v) for v in np.clip(c * k, 0, 255))
        if "glass" in n:
            t = lambda k: tuple(int(v) for v in np.clip(np.array((150, 190, 215)) * k, 0, 255))
        dr.polygon(left, fill=t(0.75))
        dr.polygon(right, fill=t(0.6))
        dr.polygon(top, fill=t(1.0))
    im.save(outp)
    print("ok", len(B))


if __name__ == "__main__":
    main()
