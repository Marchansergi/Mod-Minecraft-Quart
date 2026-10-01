#!/usr/bin/env python3
"""Alçat frontal (façana vista de cara) d'una zona del món generat.
Ús: elevation.py <region> <x0> <z0> <x1> <z1> <y0> <y1> <mirant cap a: N|S|E|W> <sortida.png> [escala]"""
import os, sys
import numpy as np
from PIL import Image
sys.path.insert(0, os.path.dirname(__file__))
from render_world import color
from iso_render import blocks

reg = sys.argv[1]
x0, z0, x1, z1, y0, y1 = map(int, sys.argv[2:8])
look, outp = sys.argv[8], sys.argv[9]
sc = int(sys.argv[10]) if len(sys.argv) > 10 else 12
B = blocks(reg, x0, z0, x1, z1, y0, y1)
# l'observador mira en direcció 'look'; columnes d'esquerra a dreta segons la seva vista
if look == "W":    # mira cap a -X: esquerra = +Z
    cols = [(z, lambda z: [(x, z) for x in range(x1 - 1, x0 - 1, -1)]) for z in range(z1 - 1, z0 - 1, -1)]
elif look == "E":
    cols = [(z, lambda z: [(x, z) for x in range(x0, x1)]) for z in range(z0, z1)]
elif look == "N":  # mira cap a -Z: esquerra = -X
    cols = [(x, lambda x: [(x, z) for z in range(z1 - 1, z0 - 1, -1)]) for x in range(x0, x1)]
else:
    cols = [(x, lambda x: [(x, z) for z in range(z0, z1)]) for x in range(x1 - 1, x0 - 1, -1)]
W, H = len(cols), y1 - y0 + 1
img = np.zeros((H, W, 3), np.uint8)
img[:] = (170, 200, 230)
for ci, (k, ray) in enumerate(cols):
    for y in range(y0, y1 + 1):
        for depth, (x, z) in enumerate(ray(k)):
            n = B.get((x, y, z))
            if n and n not in ("short_grass",):
                c = np.array(color(n), float)
                if "glass" in n:
                    c = np.array((120, 170, 200.0))
                img[y1 - y, ci] = np.clip(c * (1.0 - min(depth, 12) * 0.03), 0, 255)
                break
im = Image.fromarray(img).resize((W * sc, H * sc), Image.NEAREST)
im.save(outp)
print("ok", W, H)
