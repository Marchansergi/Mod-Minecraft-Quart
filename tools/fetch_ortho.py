#!/usr/bin/env python3
"""Descarrega l'ortofoto PNOA (Instituto Geográfico Nacional, CC BY 4.0) de la zona del poble
i la reprojecta a la graella del mod (0,5 m per píxel).

Sortida: tools/cache/ortho_05.npy  (alçada x amplada x 3, uint8), alineada amb el ràster de build_town.py
Les rajoles es guarden a tools/cache/pnoa/ per no tornar-les a baixar.
"""
import io
import math
import os
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

import numpy as np
from PIL import Image
from scipy import ndimage

sys.path.insert(0, os.path.dirname(__file__))
import build_town as bt  # noqa: E402

Z = 19
URL = "https://tms-pnoa-ma.idee.es/1.0.0/pnoa-ma/{z}/{x}/{ty}.jpeg"
CACHE = os.path.join(os.path.dirname(__file__), "cache", "pnoa")
EXTRA = 24  # metres de marge fora del nucli


def tile_xy(lat, lon, z=Z):
    n = 2 ** z
    return (lon + 180) / 360 * n, (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n


def fetch(x, y):
    p = os.path.join(CACHE, str(Z), str(x), f"{y}.jpeg")
    if os.path.exists(p) and os.path.getsize(p) > 0:
        return p
    os.makedirs(os.path.dirname(p), exist_ok=True)
    ty = (1 << Z) - 1 - y
    for attempt in range(5):
        try:
            req = urllib.request.Request(URL.format(z=Z, x=x, ty=ty), headers={"User-Agent": "quart-minecraft-mod/0.2"})
            data = urllib.request.urlopen(req, timeout=60).read()
            Image.open(io.BytesIO(data)).verify()
            with open(p, "wb") as f:
                f.write(data)
            return p
        except Exception as e:  # noqa: BLE001
            time.sleep(2 ** attempt)
            err = e
    print("error", x, y, err, file=sys.stderr)
    return None


def main():
    x0w, z0w = bt.CORE[0] - EXTRA, bt.CORE[1] - EXTRA
    x1w, z1w = bt.CORE[2] + EXTRA, bt.CORE[3] + EXTRA
    latN, lonW = bt.to_latlon(x0w, z0w)
    latS, lonE = bt.to_latlon(x1w, z1w)
    tx0, ty0 = tile_xy(latN, lonW)
    tx1, ty1 = tile_xy(latS, lonE)
    xs = range(int(tx0), int(tx1) + 1)
    ys = range(int(ty0), int(ty1) + 1)
    jobs = [(x, y) for x in xs for y in ys]
    print(f"{len(jobs)} rajoles z{Z}", file=sys.stderr)
    done = 0
    with ThreadPoolExecutor(6) as ex:
        for _ in ex.map(lambda j: fetch(*j), jobs):
            done += 1
            if done % 200 == 0:
                print(f"  {done}/{len(jobs)}", file=sys.stderr)
    # mosaic
    mos = np.zeros((len(ys) * 256, len(xs) * 256, 3), np.uint8)
    for i, x in enumerate(xs):
        for j, y in enumerate(ys):
            p = os.path.join(CACHE, str(Z), str(x), f"{y}.jpeg")
            if os.path.exists(p):
                mos[j * 256:(j + 1) * 256, i * 256:(i + 1) * 256] = np.array(Image.open(p).convert("RGB"))
    # reprojecció a la graella del ràster a 0,5 m
    W2, H2 = bt.W * 2, bt.H * 2
    gx = (np.arange(W2) + 0.5) / 2 + bt.X0
    gz = (np.arange(H2) + 0.5) / 2 + bt.Z0
    out = np.zeros((H2, W2, 3), np.uint8)
    n = 2 ** Z
    for r0 in range(0, H2, 512):
        zz = gz[r0:r0 + 512]
        XX, ZZ = np.meshgrid(gx, zz)
        lat = bt.LAT0 - ZZ / bt.M_LAT
        lon = bt.LON0 + XX / bt.M_LON
        px = ((lon + 180) / 360 * n - xs[0]) * 256 - 0.5
        py = ((1 - np.arcsinh(np.tan(np.radians(lat))) / math.pi) / 2 * n - ys[0]) * 256 - 0.5
        for c in range(3):
            out[r0:r0 + 512, :, c] = np.clip(ndimage.map_coordinates(mos[..., c], [py, px], order=1, mode="constant", cval=0), 0, 255)
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    np.save(os.path.join(os.path.dirname(CACHE), "ortho_05.npy"), out)
    Image.fromarray(out[::4, ::4]).save(os.path.join(os.path.dirname(CACHE), "ortho_preview.jpg"), quality=85)
    print("fet", out.shape, file=sys.stderr)


if __name__ == "__main__":
    main()
