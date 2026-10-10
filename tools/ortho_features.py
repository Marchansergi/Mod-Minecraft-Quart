#!/usr/bin/env python3
"""Extreu informació de l'ortofoto PNOA (tools/cache/ortho_05.npy, generada per fetch_ortho.py):

  oc     classe de cada metre quadrat (vegeu CLASSES), alineada amb el ràster de build_town.py
  trees  arbres detectats a les copes: x, z (món), radi de copa (m), lluminositat i to

Sortida: tools/data/ortho_features.npz (es versiona; l'ortofoto sencera no).
Ortofoto: PNOA (c) Instituto Geográfico Nacional de España, CC BY 4.0.
"""
import os
import sys

import numpy as np
from scipy import ndimage

sys.path.insert(0, os.path.dirname(__file__))
import build_town as bt  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "tools", "cache", "ortho_05.npy")
# ortho_1m.jpg: color mitjà per metre (versionat, per a build_town.py)
OUT = os.path.join(ROOT, "tools", "data", "ortho_features.npz")

CLASSES = dict(UNKNOWN=0, LAWN=1, TREE=2, SOIL=3, PAVING=4, ASPHALT=5, POOL=6, ROOF_RED=7, ROOF_ORANGE=8,
               ROOF_BROWN=9, ROOF_GRAY=10, ROOF_LIGHT=11, SHADOW=12, SOLAR=13)
C = CLASSES


def classify(o):
    r, g, b = (ndimage.uniform_filter(o[..., i].astype(np.float32), 3) for i in range(3))
    lum = (r + g + b) / 3.0
    tex = np.sqrt(np.maximum(ndimage.uniform_filter(lum * lum, 5) - ndimage.uniform_filter(lum, 5) ** 2, 0))
    mx = np.maximum(np.maximum(r, g), b)
    mn = np.minimum(np.minimum(r, g), b)
    V = mx / 255.0
    Sat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1), 0)
    exg = 2 * g - r - b
    cls = np.zeros(r.shape, np.uint8)
    valid = o.max(-1) > 0
    # ordre: les regles posteriors sobreescriuen les anteriors
    gray = Sat < 0.16
    cls[gray & (V > 0.62)] = C["PAVING"]
    cls[gray & (V > 0.30) & (V <= 0.62)] = C["ASPHALT"]
    soil = (r >= g) & (g >= b - 4) & (r - b > 18) & (V > 0.42) & (Sat >= 0.14)
    cls[soil] = C["SOIL"]
    warm = (r - g > 24) & (r - b > 34) & (V > 0.40)
    orange = warm & (Sat > 0.42)
    red = warm & ~orange & (Sat > 0.24)
    cls[red] = C["ROOF_RED"]
    cls[orange] = C["ROOF_ORANGE"]
    veg = (exg > 10) & (g >= r - 2)
    lawn = veg & (V >= 0.45) & (tex < 14)
    tree = veg & ~lawn
    # copes fosques (oliveres, pins a contrallum): fosques, no blavoses i amb textura
    dark_canopy = (V < 0.34) & (b <= r + 2) & (b <= g + 2) & (tex > 5)
    shadow = (V < 0.34) & ~dark_canopy & ~veg
    cls[lawn] = C["LAWN"]
    cls[tree | dark_canopy] = C["TREE"]
    cls[shadow] = C["SHADOW"]
    pool = (b > r + 30) & (b > 105) & (g > r + 8) & (V > 0.40) & (Sat > 0.22)
    cls[pool] = C["POOL"]
    cls[~valid] = C["UNKNOWN"]
    return cls


def mode_2x2(cls):
    H2, W2 = cls.shape
    H, W = H2 // 2, W2 // 2
    blocks = cls[:H * 2, :W * 2].reshape(H, 2, W, 2).transpose(0, 2, 1, 3).reshape(H, W, 4)
    nclass = max(C.values()) + 1
    counts = np.zeros((H, W, nclass), np.uint8)
    for k in range(4):
        np.add.at(counts, (np.arange(H)[:, None], np.arange(W)[None, :], blocks[..., k]), 1)
    # prioritat a piscina i arbre si n'hi ha 2 de 4 (són elements petits)
    out = counts.argmax(-1).astype(np.uint8)
    out[counts[..., C["POOL"]] >= 2] = C["POOL"]
    return out


def detect_trees(cls05, o):
    canopy = cls05 == C["TREE"]
    canopy = ndimage.binary_opening(canopy, iterations=1)
    canopy = ndimage.binary_closing(canopy, iterations=1)
    dt = ndimage.distance_transform_edt(canopy)  # en píxels de 0,5 m
    mxf = ndimage.maximum_filter(dt, size=7)
    peaks = (dt == mxf) & (dt >= 2.5)
    zs, xs = np.nonzero(peaks)
    rad = dt[zs, xs]
    order = np.argsort(-rad)
    taken = np.zeros(cls05.shape, bool)
    out = []
    r, g, b = (o[..., i].astype(np.float32) for i in range(3))
    for k in order:
        z, x, rr = zs[k], xs[k], rad[k]
        if taken[z, x]:
            continue
        R = int(max(2, rr * 0.9))
        taken[max(0, z - R):z + R + 1, max(0, x - R):x + R + 1] = True
        sl = (slice(max(0, z - 2), z + 3), slice(max(0, x - 2), x + 3))
        light = float((r[sl] + g[sl] + b[sl]).mean() / 765.0)
        hue = float(((r[sl] - b[sl]) / np.maximum(g[sl], 1)).mean())  # alt = groguenc (pi), baix = blavós (alzina)
        wx = bt.X0 + (x + 0.5) / 2.0
        wz = bt.Z0 + (z + 0.5) / 2.0
        out.append((wx, wz, rr / 2.0 + 0.5, light, hue))
    return np.array(out, np.float32)


def main():
    o = np.load(SRC)
    print("ortofoto", o.shape, file=sys.stderr)
    cls05 = classify(o)
    trees = detect_trees(cls05, o)
    oc = mode_2x2(cls05)
    assert oc.shape == (bt.H, bt.W), (oc.shape, bt.H, bt.W)
    # piscines: només taques prou grans (>= 6 m2) i no gaire allargades
    lab, n = ndimage.label(oc == C["POOL"])
    if n:
        sizes = ndimage.sum(np.ones_like(lab), lab, range(1, n + 1))
        small = np.isin(lab, np.nonzero(sizes < 6)[0] + 1)
        oc[small] = C["UNKNOWN"]
    print("classes", {k: int((oc == v).sum()) for k, v in C.items()}, file=sys.stderr)
    print("arbres", len(trees), file=sys.stderr)
    np.savez_compressed(OUT, oc=oc, trees=trees)
    # color mitjà de cada metre quadrat (per triar el material de les teulades)
    from PIL import Image
    rgb1 = o[:bt.H * 2, :bt.W * 2].reshape(bt.H, 2, bt.W, 2, 3).mean((1, 3)).astype(np.uint8)
    Image.fromarray(rgb1).save(os.path.join(ROOT, "tools", "data", "ortho_1m.jpg"), quality=82, optimize=True)
    print("escrit", OUT, os.path.getsize(OUT) // 1024, "KB", file=sys.stderr)


if __name__ == "__main__":
    main()
