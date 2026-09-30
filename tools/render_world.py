#!/usr/bin/env python3
"""Dibuixa una vista zenital d'un món de Minecraft (fitxers .mca) per comprovar la generació.
Ús: render_world.py <carpeta region> <x0> <z0> <x1> <z1> <sortida.png> [escala]"""
import io
import math
import os
import struct
import sys
import zlib

import nbtlib
import numpy as np
from PIL import Image

COL = {
    "grass_block": (110, 165, 70), "dirt": (134, 96, 67), "stone": (125, 125, 125), "water": (60, 100, 210),
    "gray_concrete": (70, 72, 76), "gray_concrete_powder": (90, 92, 96), "white_concrete": (230, 232, 233),
    "white_terracotta": (210, 178, 161), "pink_terracotta": (162, 78, 79), "smooth_stone": (160, 160, 160),
    "red_terracotta": (143, 61, 47), "sand": (219, 207, 163), "gravel": (136, 126, 126), "coarse_dirt": (119, 85, 59),
    "podzol": (92, 63, 24), "farmland": (100, 60, 30), "wheat": (200, 180, 70), "short_grass": (100, 160, 60),
    "oak_leaves": (60, 130, 40), "dark_oak_leaves": (40, 90, 30), "spruce_leaves": (50, 80, 50), "birch_leaves": (90, 140, 60),
    "azalea_leaves": (100, 140, 60), "jungle_leaves": (60, 140, 40), "bricks": (150, 80, 65), "polished_granite": (154, 106, 89),
    "mud_bricks": (137, 104, 79), "acacia_planks": (168, 90, 50), "deepslate_tiles": (54, 54, 55), "brick_slab": (150, 80, 65),
    "polished_granite_slab": (154, 106, 89), "mud_brick_slab": (137, 104, 79), "acacia_slab": (168, 90, 50),
    "deepslate_tile_slab": (54, 54, 55), "light_gray_concrete": (125, 125, 115), "green_concrete": (73, 91, 36),
    "green_concrete_powder": (97, 119, 45), "light_blue_concrete": (36, 137, 199), "smooth_quartz": (236, 230, 223),
    "polished_andesite": (132, 135, 134), "dirt_path": (148, 122, 65), "red_concrete": (142, 33, 33), "terracotta": (152, 94, 67),
    "smooth_sandstone": (223, 214, 170), "black_concrete": (20, 21, 25), "polished_blackstone": (53, 48, 56),
    "andesite_wall": (136, 136, 136), "lantern": (230, 180, 80), "spruce_planks": (114, 84, 48), "spruce_door": (106, 80, 48),
    "glass_pane": (170, 205, 220), "iron_block": (220, 220, 220), "smooth_stone_slab": (160, 160, 160), "spruce_stairs": (114, 84, 48),
    "spruce_fence": (114, 84, 48), "chain": (60, 60, 70), "warped_fence": (43, 104, 99), "yellow_terracotta": (186, 133, 35),
    "orange_terracotta": (161, 83, 37), "light_gray_terracotta": (135, 107, 98), "brown_terracotta": (77, 51, 35),
    "polished_deepslate": (72, 72, 73), "sandstone": (216, 203, 155), "clay": (160, 166, 179), "blackstone_slab": (42, 36, 41),
    "iron_bars": (110, 110, 110), "iron_bars": (110, 110, 110), "spruce_slab": (114, 84, 48), "red_carpet": (160, 39, 34),
    "stone_brick_slab": (122, 121, 122), "polished_blackstone_slab": (53, 48, 56), "black_stained_glass_pane": (25, 25, 25), "iron_bars": (140, 140, 140), "cobblestone": (120, 120, 120), "stone_bricks": (122, 121, 122),
}


def color(name):
    n = name.split(":")[-1]
    if n in COL:
        return COL[n]
    if "leaves" in n:
        return (60, 120, 40)
    if "log" in n:
        return (100, 80, 50)
    if "concrete" in n:
        return (150, 150, 150)
    if "glass" in n:
        return (180, 210, 220)
    return (200, 0, 200)


def read_chunk(f, idx):
    f.seek(idx * 4)
    loc = struct.unpack(">I", f.read(4))[0]
    off, cnt = loc >> 8, loc & 0xFF
    if off == 0:
        return None
    f.seek(off * 4096)
    ln, comp = struct.unpack(">IB", f.read(5))
    data = f.read(ln - 1)
    raw = zlib.decompress(data) if comp == 2 else data
    return nbtlib.File.parse(io.BytesIO(raw))


def unpack(longs, bits, count):
    per = 64 // bits
    mask = (1 << bits) - 1
    out = np.zeros(count, np.int64)
    arr = np.array([int(v) & 0xFFFFFFFFFFFFFFFF for v in longs], dtype=np.uint64)
    for i in range(count):
        l = int(arr[i // per])
        out[i] = (l >> ((i % per) * bits)) & mask
    return out


def main():
    reg, x0, z0, x1, z1, outp = sys.argv[1], *map(int, sys.argv[2:6]), sys.argv[6]
    W, H = x1 - x0, z1 - z0
    img = np.zeros((H, W, 3), np.uint8)
    hmap = np.zeros((H, W), np.int32)
    for cz in range(z0 >> 4, (z1 - 1 >> 4) + 1):
        for cx in range(x0 >> 4, (x1 - 1 >> 4) + 1):
            rp = os.path.join(reg, f"r.{cx >> 5}.{cz >> 5}.mca")
            if not os.path.exists(rp):
                continue
            with open(rp, "rb") as f:
                c = read_chunk(f, (cx & 31) + (cz & 31) * 32)
            if c is None or str(c.get("Status", "")) not in ("minecraft:full", "full"):
                continue
            hm = c["Heightmaps"]["WORLD_SURFACE"]
            heights = unpack(hm, 9, 256) - 1 - 64
            secs = {int(s["Y"]): s for s in c["sections"] if "block_states" in s}
            for i in range(256):
                lx, lz = i & 15, i >> 4
                x, z = cx * 16 + lx, cz * 16 + lz
                if not (x0 <= x < x1 and z0 <= z < z1):
                    continue
                y = int(heights[i])
                sec = secs.get(y >> 4)
                name = "air"
                if sec is not None:
                    bs = sec["block_states"]
                    pal = bs["palette"]
                    if "data" in bs:
                        bits = max(4, math.ceil(math.log2(len(pal))))
                        idx = ((y & 15) * 256 + lz * 16 + lx)
                        per = 64 // bits
                        l = int(bs["data"][idx // per]) & 0xFFFFFFFFFFFFFFFF
                        name = str(pal[(l >> ((idx % per) * bits)) & ((1 << bits) - 1)]["Name"])
                    else:
                        name = str(pal[0]["Name"])
                img[z - z0, x - x0] = color(name)
                hmap[z - z0, x - x0] = y
    gy, gx = np.gradient(hmap.astype(float))
    shade = np.clip(1 + (gx - gy) * 0.06, 0.55, 1.35)
    out = np.clip(img * shade[..., None], 0, 255).astype(np.uint8)
    im = Image.fromarray(out)
    sc = int(sys.argv[7]) if len(sys.argv) > 7 else 1
    if sc > 1:
        im = im.resize((W * sc, H * sc), Image.NEAREST)
    im.save(outp)
    print("ok", outp, hmap.min(), hmap.max())


if __name__ == "__main__":
    main()
