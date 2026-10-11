#!/usr/bin/env python3
"""Descarga les dades de base de la zona del mod (segons els límits de build_town.py) a tools/data/:

  dem_terrarium_z15.npz  relleu (AWS Terrain Tiles, format Terrarium, zoom 15)
  worldcover.npz         ESA WorldCover 2021 10 m (CC BY 4.0)
  ms_buildings.json.gz   Microsoft Global ML Building Footprints (ODbL)

L'OpenStreetMap es descarrega a part (Overpass o l'API 0.6 per trossos; vegeu el README).
"""
import gzip
import io
import json
import math
import os
import sys
import urllib.request

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
import build_town as bt  # noqa: E402

DATA = os.path.join(os.path.dirname(__file__), "data")
UA = {"User-Agent": "quart-minecraft-mod/0.2"}
PAD = 0.006  # graus de marge al voltant de la zona del ràster


def bounds():
    latN, lonW = bt.to_latlon(bt.X0, bt.Z0)
    latS, lonE = bt.to_latlon(bt.X0 + bt.W, bt.Z0 + bt.H)
    return latS - PAD, latN + PAD, lonW - PAD, lonE + PAD


def get(url):
    for a in range(5):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120).read()
        except Exception as e:  # noqa: BLE001
            print("reintent", url, e, file=sys.stderr)
    raise RuntimeError(url)


def dem(S, N, W, E, z=15):
    def t(lat, lon):
        n = 2 ** z
        return (lon + 180) / 360 * n, (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n
    x0, y0 = t(N, W)
    x1, y1 = t(S, E)
    txs = range(int(x0), int(x1) + 1)
    tys = range(int(y0), int(y1) + 1)
    rows = []
    for ty in tys:
        row = []
        for tx in txs:
            im = np.array(Image.open(io.BytesIO(get(f"https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{tx}/{ty}.png")))
                          .convert("RGB")).astype(np.float32)
            row.append(im[:, :, 0] * 256 + im[:, :, 1] + im[:, :, 2] / 256 - 32768)
        rows.append(np.hstack(row))
    big = np.vstack(rows)
    np.savez_compressed(os.path.join(DATA, "dem_terrarium_z15.npz"), dem=big.astype(np.float32), z=z, tx0=txs[0], ty0=tys[0])
    print("relleu", big.shape, file=sys.stderr)


def worldcover(S, N, W, E):
    import rasterio
    from rasterio.windows import from_bounds
    url = "/vsicurl/https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/ESA_WorldCover_10m_2021_v200_N39E000_Map.tif"
    with rasterio.open(url) as src:
        w = from_bounds(W, S, E, N, src.transform)
        a = src.read(1, window=w)
        tr = src.window_transform(w)
    np.savez_compressed(os.path.join(DATA, "worldcover.npz"), wc=a, transform=np.array(tuple(tr)[:6]))
    print("worldcover", a.shape, file=sys.stderr)


def ms_buildings(S, N, W, E):
    links = get("https://minedbuildings.z5.web.core.windows.net/global-buildings/dataset-links.csv").decode().splitlines()
    keys = set()
    for lat in (S, N):
        for lon in (W, E):
            z = 9
            n = 2 ** z
            x = int((lon + 180) / 360 * n)
            y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
            q = ""
            for i in range(z, 0, -1):
                d, m = 0, 1 << (i - 1)
                d += 1 if x & m else 0
                d += 2 if y & m else 0
                q += str(d)
            keys.add(q)
    out = []
    for line in links:
        p = line.split(",")
        if len(p) >= 3 and p[0] == "Spain" and p[1] in keys:
            for raw in gzip.open(io.BytesIO(get(p[2])), "rt"):
                f = json.loads(raw)
                c = f["geometry"]["coordinates"][0]
                lo = sum(q[0] for q in c) / len(c)
                la = sum(q[1] for q in c) / len(c)
                if S < la < N and W < lo < E:
                    out.append(f)
    with gzip.open(os.path.join(DATA, "ms_buildings.json.gz"), "wt") as f:
        json.dump(out, f)
    print("edificis Microsoft", len(out), file=sys.stderr)


if __name__ == "__main__":
    b = bounds()
    print("límits", [round(v, 4) for v in b], file=sys.stderr)
    dem(*b)
    worldcover(*b)
    ms_buildings(*b)
