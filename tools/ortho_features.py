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


PLAZA_NONE, PLAZA_CONCRETE, PLAZA_LINE, PLAZA_MOUND = 0, 1, 2, 3


def painted_plazas(o):
    """Places de formigó amb dibuix de línies blanques (p. ex. la del Pavelló): el formigó és el gris de la plaça,
    les "cintes" són les franges més clares (fetes de moltes línies paral·leles) i les línies es dibuixen paral·leles
    a la vora de cada cinta, cada `stripe` metres, com a la realitat. Torna una capa a 1 m alineada amb el ràster."""
    import json
    from PIL import Image, ImageDraw
    marks = json.load(open(os.path.join(ROOT, "tools", "landmarks.json")))
    out = np.zeros((bt.H, bt.W), np.uint8)
    for pz in marks.get("plazas", []):
        pts = [bt.to_xz(la, lo) for la, lo in pz["polygon"]]
        xs = [p[0] for p in pts]
        zs = [p[1] for p in pts]
        x0, x1 = int(np.floor(min(xs))) - 2, int(np.ceil(max(xs))) + 2
        z0, z1 = int(np.floor(min(zs))) - 2, int(np.ceil(max(zs))) + 2
        a = o[2 * (z0 - bt.Z0):2 * (z1 - bt.Z0), 2 * (x0 - bt.X0):2 * (x1 - bt.X0)].astype(np.float32)
        img = Image.new("1", (a.shape[1], a.shape[0]), 0)
        ImageDraw.Draw(img).polygon([((x - x0) * 2, (z - z0) * 2) for x, z in pts], fill=1)
        P = np.array(img, bool)
        L = a.mean(-1)
        mx, mn = a.max(-1), a.min(-1)
        sat = (mx - mn) / np.maximum(mx, 1)
        Ls = ndimage.gaussian_filter(L, 1.0)
        concrete = P & (sat < 0.13) & (L > 165)
        base = float(np.median(Ls[concrete]))
        rib = P & (Ls > base + pz.get("ribbon_delta", 10)) & (sat < 0.13)
        rib = ndimage.binary_closing(ndimage.binary_opening(rib, iterations=1), iterations=2)
        # contorn suau (les cintes reals són corbes contínues)
        rib = P & (ndimage.gaussian_filter(rib.astype(np.float32), 1.5) > 0.5)
        lab, n = ndimage.label(rib)
        if n:
            sz = ndimage.sum(rib, lab, range(1, n + 1))
            rib = np.isin(lab, np.nonzero(sz >= 40)[0] + 1)
        d = ndimage.distance_transform_edt(rib) / 2.0  # metres fins a la vora de la cinta
        # a 1 m: mitjana de la distància dels 4 subpíxels
        Hc, Wc = (z1 - z0), (x1 - x0)
        d1 = d[:Hc * 2, :Wc * 2].reshape(Hc, 2, Wc, 2).mean((1, 3))
        rib1 = rib[:Hc * 2, :Wc * 2].reshape(Hc, 2, Wc, 2).mean((1, 3)) >= 0.5
        P1 = P[:Hc * 2, :Wc * 2].reshape(Hc, 2, Wc, 2).mean((1, 3)) >= 0.5
        st = pz.get("stripe", 2)
        line = rib1 & ((np.floor(d1) % st) == 0)
        layer = np.where(P1, PLAZA_CONCRETE, PLAZA_NONE).astype(np.uint8)
        layer[line] = PLAZA_LINE
        if "mound" in pz:
            mx_, mz_ = bt.to_xz(*pz["mound"]["center"])
            zz, xx = np.mgrid[z0:z1, x0:x1]
            dist = np.hypot(xx + 0.5 - mx_, zz + 0.5 - mz_)
            R = pz["mound"]["radius"]
            inner = dist < R * 0.62
            layer[inner & P1] = PLAZA_MOUND
            # cercles de nivell blancs al flanc de la muntanyeta
            ring = (dist >= R * 0.62) & (dist < R) & P1
            layer[ring] = np.where((np.floor(dist[ring]) % 2) == 0, PLAZA_LINE, PLAZA_MOUND)
        sl = (slice(z0 - bt.Z0, z1 - bt.Z0), slice(x0 - bt.X0, x1 - bt.X0))
        cur = out[sl]
        cur[layer > 0] = layer[layer > 0]
        print(f"plaça {pz['id']}: {int((layer > 0).sum())} m2, {int((layer == PLAZA_LINE).sum())} m2 de línia", file=sys.stderr)
    return out


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
    plaza = painted_plazas(o)
    np.savez_compressed(OUT, oc=oc, trees=trees, plaza=plaza)
    # color mitjà de cada metre quadrat (per triar el material de les teulades)
    from PIL import Image
    rgb1 = o[:bt.H * 2, :bt.W * 2].reshape(bt.H, 2, bt.W, 2, 3).mean((1, 3)).astype(np.uint8)
    Image.fromarray(rgb1).save(os.path.join(ROOT, "tools", "data", "ortho_1m.jpg"), quality=82, optimize=True)
    print("escrit", OUT, os.path.getsize(OUT) // 1024, "KB", file=sys.stderr)


if __name__ == "__main__":
    main()
