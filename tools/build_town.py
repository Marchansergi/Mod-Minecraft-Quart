#!/usr/bin/env python3
"""Converteix les dades geogràfiques reals de Quart en un ràster 1 bloc = 1 metre
que el mod llegeix per generar el poble.

Entrades (tools/data):
  osm.json.gz            OpenStreetMap (carrers, edificis, usos del sòl)  (c) OSM contributors, ODbL
  ms_buildings.json.gz   Microsoft Global ML Building Footprints (ODbL) - omple forats d'OSM
  dem_terrarium_z15.npz  Model d'elevació (AWS Terrain Tiles, EU-DEM/SRTM)
  worldcover.npz         ESA WorldCover 2021 10 m (CC BY 4.0) - bosc/conreus on OSM no arriba
  landmarks.json         edificis destacats i decoració

Sortides (src/main/resources/data/quartmod/town):
  raster.bin.gz          capes per columna
  meta.json              taula d'edificis, paletes i decoració
i una vista prèvia a docs/preview.png.
"""
import gzip
import json
import math
import os
import struct
import sys
import zlib

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage
from shapely.geometry import LineString, Point, Polygon, MultiPolygon
from shapely.ops import linemerge, polygonize, unary_union

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "tools", "data")
OUT = os.path.join(ROOT, "src", "main", "resources", "data", "quartmod", "town")

# ---------------------------------------------------------------- projecció
# Origen (x=0, z=0) = Estació de Quart. +X = est, +Z = sud (com Minecraft).
LAT0, LON0 = 41.938347, 2.839370
_phi = math.radians(LAT0)
M_LAT = 111132.92 - 559.82 * math.cos(2 * _phi) + 1.175 * math.cos(4 * _phi)
M_LON = 111412.84 * math.cos(_phi) - 93.5 * math.cos(3 * _phi) + 0.118 * math.cos(5 * _phi)

# Zona del poble (nucli) i marge de transició amb el món normal
CORE_S, CORE_N, CORE_W, CORE_E = 41.9290, 41.9505, 2.8285, 2.8520
MARGIN = 96

# Alçada: 1 m real = 1 bloc. L'estació (98 m s.n.m.) queda a Y=72
ELEV_REF, Y_REF = 98.0, 72


def to_xz(lat, lon):
    return (lon - LON0) * M_LON, (LAT0 - lat) * M_LAT


def to_latlon(x, z):
    return LAT0 - z / M_LAT, LON0 + x / M_LON


cx0, cz0 = to_xz(CORE_N, CORE_W)
cx1, cz1 = to_xz(CORE_S, CORE_E)
CORE = (int(math.floor(cx0 / 16) * 16), int(math.floor(cz0 / 16) * 16),
        int(math.ceil(cx1 / 16) * 16), int(math.ceil(cz1 / 16) * 16))
X0, Z0 = CORE[0] - MARGIN, CORE[1] - MARGIN
W, H = CORE[2] - CORE[0] + 2 * MARGIN, CORE[3] - CORE[1] + 2 * MARGIN


def px(x, z):
    """coordenada món -> píxel del ràster (centre de columna a +0.5)"""
    return x - X0, z - Z0


def ll_px(lat, lon):
    x, z = to_xz(lat, lon)
    return px(x, z)


# ---------------------------------------------------------------- codis de superfície
S = dict(
    GRASS=0, FOREST=1, FARMLAND=2, ROAD=3, MARKING=4, SIDEWALK=5, PATH=6, TRACK=7,
    CYCLEWAY=8, PARKING=9, PLAZA=10, PARK=11, TURF=12, TURF_LINE=13, COURT=14,
    WATER=15, POOL=16, GARDEN=17, SAND=18, GRAVEL=19, SHRUB=20, BARE=21, CEMETERY=22,
    PLAZA_TREES=23, STREAM=24, MEADOW=25, COURT_LINE=26, ROAD_MAIN=27,
)
DECO = dict(NONE=0, WALL=1, HEDGE=2, FENCE=3, GOAL=10, HOOP=11, RETAINING=4, FENCE_LOW=5)


def log(*a):
    print(*a, file=sys.stderr, flush=True)


# ---------------------------------------------------------------- dades
def load_osm():
    d = json.load(gzip.open(os.path.join(DATA, "osm.json.gz"), "rt"))
    nodes = {e["id"]: (e["lat"], e["lon"]) for e in d["elements"] if e["type"] == "node"}
    ways = {e["id"]: e for e in d["elements"] if e["type"] == "way"}
    rels = [e for e in d["elements"] if e["type"] == "relation"]
    return nodes, ways, rels


def way_xy(w, nodes):
    return [px(*to_xz(*nodes[n])) for n in w["nodes"] if n in nodes]


def rel_polygons(rel, ways, nodes):
    outers, inners = [], []
    for m in rel.get("members", []):
        if m["type"] != "way" or m["ref"] not in ways:
            continue
        pts = way_xy(ways[m["ref"]], nodes)
        if len(pts) < 2:
            continue
        (inners if m.get("role") == "inner" else outers).append(LineString(pts))
    polys = list(polygonize(linemerge(outers))) if outers else []
    holes = list(polygonize(linemerge(inners))) if inners else []
    res = []
    for p in polys:
        for hh in holes:
            if p.contains(hh.representative_point()):
                p = p.difference(hh)
        res.append(p)
    return res


# ---------------------------------------------------------------- raster helpers
def draw_poly(img_draw, geom, value):
    if geom.is_empty:
        return
    geoms = geom.geoms if isinstance(geom, MultiPolygon) else [geom]
    for g in geoms:
        if g.geom_type != "Polygon":
            continue
        img_draw.polygon(list(g.exterior.coords), fill=value)
        for hole in g.interiors:
            img_draw.polygon(list(hole.coords), fill=None)


def poly_mask(geom):
    img = Image.new("1", (W, H), 0)
    dr = ImageDraw.Draw(img)
    geoms = geom.geoms if isinstance(geom, MultiPolygon) else [geom]
    for g in geoms:
        if g.geom_type != "Polygon":
            continue
        dr.polygon(list(g.exterior.coords), fill=1)
        for hole in g.interiors:
            dr.polygon(list(hole.coords), fill=0)
    return np.array(img, dtype=bool)


def line_mask(pts, width):
    img = Image.new("1", (W, H), 0)
    dr = ImageDraw.Draw(img)
    width = max(1, int(round(width)))
    dr.line(pts, fill=1, width=width, joint="curve")
    if width > 2:
        r = width / 2.0
        for x, z in pts:
            dr.ellipse([x - r, z - r, x + r, z + r], fill=1)
    return np.array(img, dtype=bool)


def dashed_points(pts, dash, gap):
    out = []
    per = dash + gap
    s = 0.0
    for (x0, z0), (x1, z1) in zip(pts, pts[1:]):
        L = math.hypot(x1 - x0, z1 - z0)
        n = max(1, int(L * 2))
        for i in range(n):
            t = i / n
            if (s + t * L) % per < dash:
                out.append((int(x0 + (x1 - x0) * t), int(z0 + (z1 - z0) * t)))
        s += L
    return out


# ---------------------------------------------------------------- terreny
def build_height():
    npz = np.load(os.path.join(DATA, "dem_terrarium_z15.npz"))
    dem, z, tx0, ty0 = npz["dem"], int(npz["z"]), int(npz["tx0"]), int(npz["ty0"])
    n = 2 ** z
    xs = np.arange(W) + X0 + 0.5
    zs = np.arange(H) + Z0 + 0.5
    XX, ZZ = np.meshgrid(xs, zs)
    lat = LAT0 - ZZ / M_LAT
    lon = LON0 + XX / M_LON
    tx = (lon + 180) / 360 * n
    ty = (1 - np.arcsinh(np.tan(np.radians(lat))) / math.pi) / 2 * n
    col = (tx - tx0) * 256 - 0.5
    row = (ty - ty0) * 256 - 0.5
    elev = ndimage.map_coordinates(dem, [row, col], order=3, mode="nearest")
    elev = ndimage.gaussian_filter(elev, 3.0)
    return elev - ELEV_REF + Y_REF  # float Y


def worldcover_base():
    npz = np.load(os.path.join(DATA, "worldcover.npz"))
    wc, t = npz["wc"], npz["transform"]
    xs = np.arange(W) + X0 + 0.5
    zs = np.arange(H) + Z0 + 0.5
    XX, ZZ = np.meshgrid(xs, zs)
    lat = LAT0 - ZZ / M_LAT
    lon = LON0 + XX / M_LON
    c = np.clip(((lon - t[2]) / t[0]).astype(int), 0, wc.shape[1] - 1)
    r = np.clip(((lat - t[5]) / t[4]).astype(int), 0, wc.shape[0] - 1)
    v = wc[r, c]
    surf = np.full((H, W), S["GRASS"], np.uint8)
    surf[v == 10] = S["FOREST"]
    surf[v == 20] = S["SHRUB"]
    surf[v == 30] = S["MEADOW"]
    surf[v == 40] = S["FARMLAND"]
    surf[v == 50] = S["GARDEN"]
    surf[v == 60] = S["BARE"]
    surf[v == 80] = S["WATER"]
    return surf


LANDUSE = {
    ("landuse", "forest"): "FOREST", ("natural", "wood"): "FOREST", ("natural", "scrub"): "SHRUB",
    ("landuse", "farmland"): "FARMLAND", ("landuse", "orchard"): "FARMLAND", ("landuse", "vineyard"): "FARMLAND",
    ("landuse", "allotments"): "FARMLAND", ("landuse", "meadow"): "MEADOW", ("landuse", "grass"): "GRASS",
    ("landuse", "greenfield"): "MEADOW", ("natural", "grassland"): "MEADOW", ("landuse", "residential"): "GARDEN",
    ("landuse", "industrial"): "GRAVEL", ("landuse", "farmyard"): "GRAVEL", ("landuse", "retail"): "PLAZA",
    ("landuse", "cemetery"): "CEMETERY", ("leisure", "park"): "PARK", ("leisure", "garden"): "GARDEN",
    ("leisure", "common"): "PLAZA_TREES", ("leisure", "playground"): "SAND", ("amenity", "parking"): "PARKING",
    ("leisure", "sports_centre"): "PLAZA", ("leisure", "stadium"): "PLAZA", ("natural", "water"): "WATER",
    ("leisure", "swimming_pool"): "POOL", ("leisure", "track"): "COURT", ("amenity", "school"): "SAND",
    ("amenity", "kindergarten"): "SAND", ("highway", "pedestrian"): "PLAZA", ("man_made", "wastewater_plant"): "GRAVEL",
    ("leisure", "golf_course"): "GRASS", ("landuse", "construction"): "BARE",
}

ROADS = {  # amplada calçada (m), vorera?, codi
    "trunk": (11, False, "ROAD_MAIN"), "trunk_link": (6, False, "ROAD_MAIN"),
    "primary": (9, True, "ROAD_MAIN"), "primary_link": (6, False, "ROAD_MAIN"),
    "secondary": (8, True, "ROAD_MAIN"), "secondary_link": (6, False, "ROAD_MAIN"),
    "tertiary": (7, True, "ROAD"), "tertiary_link": (6, False, "ROAD"),
    "unclassified": (6, True, "ROAD"), "residential": (7, True, "ROAD"), "living_street": (6, True, "PLAZA"),
    "service": (4, False, "ROAD"), "pedestrian": (5, False, "PLAZA"), "footway": (2, False, "SIDEWALK"),
    "steps": (2, False, "PLAZA"), "cycleway": (3, False, "CYCLEWAY"), "path": (2, False, "PATH"),
    "track": (3, False, "TRACK"), "bridleway": (2, False, "PATH"),
}


# ---------------------------------------------------------------- estils d'edifici
WALLS = [("minecraft:white_terracotta", 30), ("minecraft:smooth_sandstone", 18), ("minecraft:white_concrete", 12),
         ("minecraft:orange_terracotta", 8), ("minecraft:bricks", 8), ("minecraft:terracotta", 8),
         ("minecraft:yellow_terracotta", 5), ("minecraft:pink_terracotta", 6), ("minecraft:light_gray_terracotta", 5)]
ROOFS = [("bricks", 35), ("granite", 35), ("mud_bricks", 12), ("acacia", 10), ("deepslate_tiles", 8)]


def pick(options, h):
    tot = sum(w for _, w in options)
    r = h % tot
    for v, w in options:
        if r < w:
            return v
        r -= w
    return options[0][0]


def hash32(*vals):
    h = 2166136261
    for v in vals:
        h = ((h ^ (int(v) & 0xFFFFFFFF)) * 16777619) & 0xFFFFFFFF
    return h


def main():
    nodes, ways, rels = load_osm()
    marks = json.load(open(os.path.join(ROOT, "tools", "landmarks.json")))
    log(f"raster {W}x{H} origin ({X0},{Z0}) core {CORE}")

    heightf = build_height()
    surf = worldcover_base()
    deco = np.zeros((H, W), np.uint8)

    # ------------------------------------------------ usos del sòl (OSM)
    areas = []
    for w in ways.values():
        t = w.get("tags", {})
        if not t or w["nodes"][0] != w["nodes"][-1] or "building" in t:
            continue
        code = None
        for (k, v), c in LANDUSE.items():
            if t.get(k) == v and (k != "highway" or t.get("area") == "yes"):
                code = c
        if t.get("leisure") == "pitch":
            code = "PITCH"
        if code:
            pts = way_xy(w, nodes)
            if len(pts) >= 4:
                g = Polygon(pts).buffer(0)
                areas.append((g.area, code, g, t))
    for r in rels:
        t = r.get("tags", {})
        if t.get("type") != "multipolygon" or "building" in t:
            continue
        code = None
        for (k, v), c in LANDUSE.items():
            if t.get(k) == v:
                code = c
        if code:
            for g in rel_polygons(r, ways, nodes):
                areas.append((g.area, code, g.buffer(0), t))
    areas.sort(key=lambda a: -a[0])
    pitches = []
    for _, code, g, t in areas:
        if code == "PITCH":
            pitches.append((g, t))
            continue
        m = poly_mask(g)
        surf[m] = S[code]
    log(f"areas {len(areas)} pitches {len(pitches)}")

    # ------------------------------------------------ pistes esportives
    for g, t in pitches:
        m = poly_mask(g)
        sport = t.get("sport", "")
        if sport == "soccer" and g.area > 1500:
            surf[m] = S["TURF"]
            draw_soccer_lines(surf, deco, g)
        elif sport in ("tennis", "padel"):
            surf[m] = S["TURF"]
            draw_court_lines(surf, g, S["TURF_LINE"])
        else:
            surf[m] = S["COURT"]
            draw_court_lines(surf, g, S["COURT_LINE"])
            if sport in ("basketball", "multi", ""):
                add_hoops(deco, g)

    # ------------------------------------------------ edificis (per saber on és "urbà")
    blds = collect_buildings(ways, rels, nodes)
    bmask_all = np.zeros((H, W), bool)
    for b in blds:
        bmask_all |= poly_mask(b["geom"])
    urban = ndimage.binary_dilation(bmask_all, iterations=35)

    # ------------------------------------------------ aigua (rieres)
    for w in ways.values():
        t = w.get("tags", {})
        ww = t.get("waterway")
        if ww in ("stream", "river", "canal", "ditch", "drain") and t.get("tunnel") not in ("yes", "culvert"):
            pts = way_xy(w, nodes)
            if len(pts) >= 2:
                width = {"river": 10, "canal": 4}.get(ww, 2)
                surf[line_mask(pts, width)] = S["STREAM"]

    # ------------------------------------------------ carrers
    road_lines = []
    carriage = np.zeros((H, W), bool)
    sidewalk = np.zeros((H, W), bool)
    road_geoms = []  # (LineString en píxels, amplada) per a llocs destacats
    markings = []
    for w in ways.values():
        t = w.get("tags", {})
        hw = t.get("highway")
        if t.get("railway") in ("abandoned", "disused", "razed") and not hw:
            hw = "cycleway"  # el Carrilet Girona - Sant Feliu és via verda
        if hw not in ROADS or t.get("tunnel") == "yes" or t.get("area") == "yes":
            continue
        pts = way_xy(w, nodes)
        if len(pts) < 2:
            continue
        width, walk, code = ROADS[hw]
        if "width" in t:
            try:
                width = float(t["width"].split()[0])
            except ValueError:
                pass
        road_lines.append((pts, width, walk, code, hw))
        if hw in ("footway", "path", "track", "cycleway", "steps", "bridleway"):
            continue
        road_geoms.append((LineString(pts), width))
    # ordre: primer voreres, després calçades, després marques
    for pts, width, walk, code, hw in road_lines:
        if walk:
            m = line_mask(pts, width + 4) & urban
            sidewalk |= m
    surf[sidewalk] = S["SIDEWALK"]
    order = sorted(road_lines, key=lambda r: r[1])
    for pts, width, walk, code, hw in order:
        m = line_mask(pts, width)
        if code in ("PATH", "TRACK") and hw != "cycleway":
            m &= ~carriage
            if urban[m].mean() > 0.6 if m.any() else False:
                code = "SIDEWALK"
        surf[m] = S[code]
        if code in ("ROAD", "ROAD_MAIN"):
            carriage |= m
        if hw in ("primary", "secondary", "trunk"):
            markings.append(pts)
    for pts in markings:
        for x, z in dashed_points(pts, 3, 5):
            if 0 <= x < W and 0 <= z < H and carriage[z, x]:
                surf[z, x] = S["MARKING"]

    # ------------------------------------------------ barreres
    for w in ways.values():
        t = w.get("tags", {})
        b = t.get("barrier")
        code = {"wall": "WALL", "retaining_wall": "RETAINING", "hedge": "HEDGE", "fence": "FENCE"}.get(b)
        if code:
            pts = way_xy(w, nodes)
            if len(pts) >= 2:
                m = line_mask(pts, 1)
                deco[m & ~carriage] = DECO[code]

    # ------------------------------------------------ edificis
    bid = np.zeros((H, W), np.uint16)
    road_px = carriage | sidewalk
    meta_buildings = [None]
    landmark_of = {}
    lm_list = marks["buildings"]
    custom = []  # rectangles
    for lm in lm_list:
        if lm["source"] == "rect":
            custom.append((lm, rect_for(lm, road_geoms)))
    for lm in lm_list:
        if lm["source"] in ("osm", "osm_named", "osm_nearest_school"):
            idx = find_building(blds, lm)
            if idx is None:
                log("!! no building for", lm["id"])
                continue
            landmark_of[idx] = lm
            log(f"landmark {lm['id']} -> {blds[idx]['src']} {blds[idx]['osm_id']}")

    order = sorted(range(len(blds)), key=lambda i: -blds[i]["geom"].area)
    for i in order:
        b = blds[i]
        g = b["geom"]
        m = poly_mask(g)
        if m.sum() < 4:
            continue
        # no trepitgem calçades (errors d'alineació entre fonts)
        if b["src"] == "ms":
            m &= ~carriage
            if m.sum() < 6:
                continue
        n = len(meta_buildings)
        bid[m] = n
        meta_buildings.append(dict(src=b["src"], osm_id=b["osm_id"], tags=b["tags"], area=float(g.area),
                                   landmark=landmark_of.get(i), geom=g))
    for lm, g in custom:
        m = poly_mask(g)
        n = len(meta_buildings)
        bid[m] = n
        meta_buildings.append(dict(src="rect", osm_id=0, tags={}, area=float(g.area), landmark=lm, geom=g))
        log(f"landmark {lm['id']} rect {int(g.area)} m2")

    # neteja fragments minúsculs que han quedat tapats
    counts = np.bincount(bid.ravel(), minlength=len(meta_buildings))

    # ------------------------------------------------ alçades
    hy = np.round(heightf).astype(np.int16)
    road_y = hy.copy()
    # carrers: suavitzem més perquè no facin esglaons bruscos
    smooth = np.round(ndimage.gaussian_filter(heightf, 5.0)).astype(np.int16)
    hy[road_px] = smooth[road_px]

    objs = ndimage.find_objects(bid.astype(np.int32))
    rd = np.zeros((H, W), np.uint8)
    fu = np.full((H, W), 255, np.uint8)
    fl = np.zeros((H, W), np.uint8)
    out_b = [dict()]
    for n in range(1, len(meta_buildings)):
        sl = objs[n - 1] if n - 1 < len(objs) else None
        mb = meta_buildings[n]
        if sl is None or counts[n] < 4:
            out_b.append(dict(skip=1))
            continue
        sl = (slice(max(sl[0].start - 1, 0), min(sl[0].stop + 1, H)), slice(max(sl[1].start - 1, 0), min(sl[1].stop + 1, W)))
        m = bid[sl] == n
        base = int(np.median(hy[sl][m]))
        hy[sl][m] = base
        d = ndimage.distance_transform_edt(m)
        rd[sl][m] = np.clip(np.floor(d[m]), 1, 250).astype(np.uint8)
        lm = mb["landmark"]
        spec = style_for(n, mb, road_px, sl, m)
        spec["base"] = base
        wall_geometry(fu, fl, sl, m, mb["geom"])
        if lm:
            front = mark_facade(fu, fl, sl, m, road_geoms, lm)
            spec["facing"] = front
        else:
            mark_door(fl, sl, m, road_px)
        out_b.append(spec)

    # piscines: una mica per sota del terra ja es fa al mod
    # ------------------------------------------------ decoració
    decos = []
    for dcfg in marks["decorations"]:
        x, z = to_xz(*dcfg["point"])
        e = dict(dcfg)
        e.pop("point")
        e["x"], e["z"] = int(round(x)), int(round(z))
        decos.append(e)
    sx, sz = to_xz(*marks["spawn"])
    spawn = [int(round(sx)), int(round(sz))]

    # porterias i cistelles com a decoració puntual
    gz, gx = np.nonzero(deco >= 10)
    for z, x in zip(gz, gx):
        decos.append(dict(type="goal" if deco[z, x] == DECO["GOAL"] else "hoop", x=int(x + X0), z=int(z + Z0),
                          yaw=int(goal_yaw.get((int(x), int(z)), 0))))
        deco[z, x] = 0

    write_output(hy, surf, bid, rd, fu, fl, deco, out_b, decos, spawn)
    preview(hy, surf, bid, out_b)


# ---------------------------------------------------------------- pistes
goal_yaw = {}


def pitch_frame(g):
    r = g.minimum_rotated_rectangle
    c = list(r.exterior.coords)[:4]
    e0 = (c[1][0] - c[0][0], c[1][1] - c[0][1])
    e1 = (c[2][0] - c[1][0], c[2][1] - c[1][1])
    L0, L1 = math.hypot(*e0), math.hypot(*e1)
    d0, d1 = (e0[0] / L0, e0[1] / L0), (e1[0] / L1, e1[1] / L1)
    # des de c0, e0 i e1 (= c3 - c0) apunten cap a dins del rectangle
    if L0 >= L1:
        return c[0], d0, d1, L0, L1
    return c[0], d1, d0, L1, L0


def frame_pt(o, u, v, a, b):
    return (o[0] + u[0] * a + v[0] * b, o[1] + u[1] * a + v[1] * b)


def draw_soccer_lines(surf, deco, g):
    o, u, v, L, Wd = pitch_frame(g)
    m = 1.5
    img = Image.new("1", (W, H), 0)
    dr = ImageDraw.Draw(img)
    P = lambda a, b: frame_pt(o, u, v, a, b)
    dr.polygon([P(m, m), P(L - m, m), P(L - m, Wd - m), P(m, Wd - m)], outline=1)
    dr.line([P(L / 2, m), P(L / 2, Wd - m)], fill=1)
    cxy = P(L / 2, Wd / 2)
    rr = min(9.15, Wd / 6)
    dr.ellipse([cxy[0] - rr, cxy[1] - rr, cxy[0] + rr, cxy[1] + rr], outline=1)
    bw, bd = min(40.3, Wd * 0.6), min(16.5, L * 0.16)
    sw, sd = min(18.3, Wd * 0.3), min(5.5, L * 0.055)
    for a0, sgn in ((m, 1), (L - m, -1)):
        dr.polygon([P(a0, Wd / 2 - bw / 2), P(a0 + sgn * bd, Wd / 2 - bw / 2), P(a0 + sgn * bd, Wd / 2 + bw / 2), P(a0, Wd / 2 + bw / 2)], outline=1)
        dr.polygon([P(a0, Wd / 2 - sw / 2), P(a0 + sgn * sd, Wd / 2 - sw / 2), P(a0 + sgn * sd, Wd / 2 + sw / 2), P(a0, Wd / 2 + sw / 2)], outline=1)
        gx, gz = P(a0 - sgn * 0.5, Wd / 2)
        gx, gz = int(round(gx)), int(round(gz))
        if 0 <= gx < W and 0 <= gz < H:
            deco[gz, gx] = DECO["GOAL"]
            # yaw: direcció de la línia de gol (v)
            goal_yaw[(gx, gz)] = int(round(math.degrees(math.atan2(v[1], v[0]))))
    lm = np.array(img, bool)
    surf[lm] = S["TURF_LINE"]


def draw_court_lines(surf, g, code):
    o, u, v, L, Wd = pitch_frame(g)
    img = Image.new("1", (W, H), 0)
    dr = ImageDraw.Draw(img)
    P = lambda a, b: frame_pt(o, u, v, a, b)
    dr.polygon([P(1, 1), P(L - 1, 1), P(L - 1, Wd - 1), P(1, Wd - 1)], outline=1)
    dr.line([P(L / 2, 1), P(L / 2, Wd - 1)], fill=1)
    surf[np.array(img, bool)] = code


def add_hoops(deco, g):
    o, u, v, L, Wd = pitch_frame(g)
    if L < 14:
        return
    for a in (1.2, L - 1.2):
        x, z = frame_pt(o, u, v, a, Wd / 2)
        x, z = int(round(x)), int(round(z))
        if 0 <= x < W and 0 <= z < H:
            deco[z, x] = DECO["HOOP"]
            goal_yaw[(x, z)] = int(round(math.degrees(math.atan2(u[1], u[0]) if a < L / 2 else math.atan2(-u[1], -u[0]))))


# ---------------------------------------------------------------- edificis
def collect_buildings(ways, rels, nodes):
    osm = []
    for w in ways.values():
        t = w.get("tags", {})
        if "building" not in t or t.get("building") in ("no",):
            continue
        pts = way_xy(w, nodes)
        if len(pts) >= 4:
            g = Polygon(pts).buffer(0)
            if g.area > 3:
                osm.append(dict(geom=g, src="osm", osm_id=w["id"], tags=t))
    for r in rels:
        t = r.get("tags", {})
        if "building" in t:
            for g in rel_polygons(r, ways, nodes):
                osm.append(dict(geom=g.buffer(0), src="osm", osm_id=r["id"], tags=t))
    # fora de la zona del ràster no ens interessen
    frame = Polygon([(0, 0), (W, 0), (W, H), (0, H)])
    osm = [b for b in osm if b["geom"].intersects(frame)]
    union = unary_union([b["geom"] for b in osm])
    ms = []
    for f in json.load(gzip.open(os.path.join(DATA, "ms_buildings.json.gz"), "rt")):
        ring = f["geometry"]["coordinates"][0]
        g = Polygon([px(*to_xz(la, lo)) for lo, la in ring]).buffer(0)
        if g.area < 8 or not g.intersects(frame):
            continue
        inter = g.intersection(union).area
        if inter > 0.15 * g.area:
            continue
        g = g.difference(union).buffer(-0.3).buffer(0.3)
        if g.area < 8:
            continue
        ms.append(dict(geom=g, src="ms", osm_id=0, tags={"building": "yes"}))
    log(f"buildings: osm {len(osm)} + microsoft {len(ms)}")
    return osm + ms


def find_building(blds, lm):
    p = Point(*ll_px(*lm["point"]))
    if lm["source"] == "osm_named":
        for i, b in enumerate(blds):
            if b["osm_id"] == lm["osm_id"]:
                return i
    cands = []
    for i, b in enumerate(blds):
        if lm["source"] == "osm_nearest_school" and b["tags"].get("building") not in ("school", "kindergarten"):
            continue
        d = b["geom"].distance(p)
        if d < 40:
            cands.append((d, i))
    return min(cands)[1] if cands else None


def rect_for(lm, road_geoms):
    """Rectangle d'edifici davant la càmera de Street View (lat, lon, rumb) que el mira."""
    lat, lon, heading = lm["view"]
    p = Point(*ll_px(lat, lon))
    hx, hz = math.sin(math.radians(heading)), -math.cos(math.radians(heading))
    line, width = min(road_geoms, key=lambda r: r[0].distance(p))
    s = line.project(p)
    a = line.interpolate(max(0, s - 2))
    b = line.interpolate(min(line.length, s + 2))
    tx, tz = b.x - a.x, b.y - a.y
    L = math.hypot(tx, tz)
    tx, tz = tx / L, tz / L
    c = line.interpolate(s)
    nx, nz = -tz, tx
    if nx * hx + nz * hz < 0:
        nx, nz = -nx, -nz
    front = width / 2 + 2.0  # calçada + vorera
    wdt, dep = lm["width"], lm["depth"]
    # on el raig de la càmera talla la línia de façana
    along = (hx * tx + hz * tz) * front / max(0.3, hx * nx + hz * nz)
    fx, fz = c.x + nx * front + tx * along, c.y + nz * front + tz * along
    corners = [(fx - tx * wdt / 2, fz - tz * wdt / 2), (fx + tx * wdt / 2, fz + tz * wdt / 2),
               (fx + tx * wdt / 2 + nx * dep, fz + tz * wdt / 2 + nz * dep), (fx - tx * wdt / 2 + nx * dep, fz - tz * wdt / 2 + nz * dep)]
    return Polygon(corners)


def edge_cols(m):
    er = m & ~ndimage.binary_erosion(m, structure=np.array([[0, 1, 0], [1, 1, 1], [0, 1, 0]]), border_value=0)
    return er


def wall_geometry(fu, fl, sl, m, geom):
    """Per a cada columna de vora: coordenada al llarg de la paret (fu), orientació cap a fora i
    si és cantonada, calculades sobre el polígon real (no sobre l'escala de píxels)."""
    if isinstance(geom, MultiPolygon):
        geom = max(geom.geoms, key=lambda g: g.area)
    poly = geom.simplify(0.6)
    if poly.is_empty or poly.geom_type != "Polygon":
        return
    pts = np.array(poly.exterior.coords)[:-1]
    if len(pts) < 3:
        return
    zs, xs = np.nonzero(edge_cols(m))
    if len(zs) == 0:
        return
    P = np.stack([xs + sl[1].start + 0.5, zs + sl[0].start + 0.5], 1)
    A = pts
    B = np.roll(pts, -1, 0)
    D = B - A
    L = np.maximum(np.hypot(D[:, 0], D[:, 1]), 1e-6)
    # distància de cada punt a cada segment
    rel = P[:, None, :] - A[None, :, :]
    t = np.clip((rel * D[None]).sum(-1) / (L * L)[None], 0, 1)
    proj = A[None] + t[..., None] * D[None]
    dist = np.hypot(*(P[:, None, :] - proj).transpose(2, 0, 1))
    seg = dist.argmin(1)
    tt = t[np.arange(len(P)), seg]
    u = np.floor(tt * L[seg]).astype(int)
    # normal cap a fora
    nx, nz = D[:, 1] / L, -D[:, 0] / L
    mid = A + D / 2
    for k in range(len(A)):
        if poly.contains(Point(mid[k, 0] + nx[k] * 0.4, mid[k, 1] + nz[k] * 0.4)):
            nx[k], nz[k] = -nx[k], -nz[k]
    cnx, cnz = nx[seg], nz[seg]
    card = np.where(np.abs(cnx) >= np.abs(cnz), np.where(cnx > 0, 1, 3), np.where(cnz > 0, 2, 0))
    # vèrtexs amb gir fort = cantonades
    prev = np.roll(pts, 1, 0)
    v1 = pts - prev
    v2 = B - pts
    ang = np.abs(np.degrees(np.arctan2(v1[:, 0] * v2[:, 1] - v1[:, 1] * v2[:, 0], (v1 * v2).sum(1))))
    corners = pts[ang > 35]
    corner = np.zeros(len(P), bool)
    if len(corners):
        dc = np.hypot(P[:, None, 0] - corners[None, :, 0], P[:, None, 1] - corners[None, :, 1]).min(1)
        corner = dc < 1.25
    fu_sl, fl_sl = fu[sl], fl[sl]
    fu_sl[zs, xs] = np.clip(u, 0, 250)
    fl_sl[zs, xs] = (corner.astype(np.uint8) * 1) | 8 | (card.astype(np.uint8) << 4)


def mark_facade(fu, fl, sl, m, road_geoms, lm):
    """Marca les columnes de la façana principal amb l'índex u (0..amplada-1) d'esquerra a dreta mirant des del carrer."""
    zs, xs = np.nonzero(edge_cols(m))
    gz, gx = zs + sl[0].start, xs + sl[1].start
    cxz = Point(float(gx.mean()), float(gz.mean()))
    line, width = min(road_geoms, key=lambda r: r[0].distance(cxz))
    s = line.project(cxz)
    c = line.interpolate(s)
    a = line.interpolate(max(0, s - 2))
    b = line.interpolate(min(line.length, s + 2))
    tx, tz = b.x - a.x, b.y - a.y
    L = math.hypot(tx, tz) or 1
    tx, tz = tx / L, tz / L
    # normal que apunta de l'edifici cap al carrer
    nx, nz = c.x - cxz.x, c.y - cxz.y
    nl = math.hypot(nx, nz) or 1
    nx, nz = nx / nl, nz / nl
    # façana = columnes de vora amb projecció màxima sobre la normal
    proj = (gx - cxz.x) * nx + (gz - cxz.y) * nz
    front = proj >= proj.max() - 1.6
    # u creix d'esquerra a dreta mirant cap a l'edifici (mirant en direcció -n): dreta = (-nz, nx)...
    rx, rz = nz, -nx
    along = (gx - cxz.x) * rx + (gz - cxz.y) * rz
    af = along[front]
    lo = af.min()
    u = np.round(along - lo).astype(int)
    fu_sl, fl_sl = fu[sl], fl[sl]
    for k in np.nonzero(front)[0]:
        fu_sl[zs[k], xs[k]] = min(max(u[k], 0), 250)
        fl_sl[zs[k], xs[k]] |= 4
    # orientació cap on mira la façana (graus: 0 = +X, 90 = +Z)
    return int(round(math.degrees(math.atan2(nz, nx))))


def mark_door(fl, sl, m, road_px):
    er = edge_cols(m) & ((fl[sl] & 1) == 0)
    zs, xs = np.nonzero(er)
    if len(zs) == 0:
        return
    rp = road_px[sl]
    if not rp.any():
        return
    dist = ndimage.distance_transform_edt(~rp)
    k = int(np.argmin(dist[zs, xs]))
    if dist[zs[k], xs[k]] < 30:
        fl[sl][zs[k], xs[k]] |= 2  # porta


def style_for(n, mb, road_px, sl, m):
    t = mb["tags"]
    kind = t.get("building", "yes")
    area = mb["area"]
    lm = mb["landmark"]
    h = hash32(n, int(area))
    spec = dict(wall=pick(WALLS, h), accent=None, roof=pick(ROOFS, h >> 8), roof_type="hipped", floors=2, fh=3)
    near_main = False
    if lm is None:
        if kind in ("industrial", "warehouse", "farm_auxiliary", "greenhouse", "hangar"):
            spec.update(wall="minecraft:light_gray_concrete", roof="flat:minecraft:gray_concrete", roof_type="flat", floors=1, fh=6)
        elif kind == "roof":
            spec.update(roof_type="canopy", floors=1, fh=4, roof="flat:minecraft:smooth_stone_slab")
        elif kind in ("church", "chapel") or t.get("amenity") == "place_of_worship":
            spec.update(wall="minecraft:stone_bricks", roof="bricks", floors=1, fh=8)
        elif kind == "ruins" or t.get("ruins") == "yes":
            spec.update(wall="minecraft:mossy_cobblestone", roof_type="ruin", floors=1, fh=3)
        elif kind in ("school", "kindergarten"):
            spec.update(wall="minecraft:white_concrete", roof="flat:minecraft:light_gray_concrete", roof_type="flat", floors=1, fh=4)
        elif area < 22:
            spec.update(floors=1, fh=3, roof_type="flat", roof="flat:minecraft:smooth_stone_slab")  # coberts, garatges
        elif kind == "yes" and area > 700:
            spec.update(wall="minecraft:light_gray_concrete", roof="flat:minecraft:gray_concrete", roof_type="flat", floors=1, fh=7)
        else:
            # cases al llarg de la carretera principal i al centre: 3 plantes
            if t.get("building:levels"):
                try:
                    spec["floors"] = max(1, min(8, int(float(t["building:levels"]))))
                except ValueError:
                    pass
            elif mb["src"] == "ms" and area > 160:
                spec["floors"] = 3
            if area > 400 and kind in ("yes", "apartments", "residential"):
                spec["roof_type"] = "flat" if h % 3 == 0 else "hipped"
                if spec["roof_type"] == "flat":
                    spec["roof"] = "flat:minecraft:terracotta"
        return spec
    spec.update(wall=lm["wall"], accent=lm.get("accent"), roof=lm["roof"], roof_type=lm["roof_type"],
                floors=lm["floors"], fh=lm.get("floor_height", 3), landmark=lm["id"], facade=lm.get("facade", []))
    if "band" in lm:
        spec["band"] = lm["band"]
    if "fence" in lm:
        spec["fence"] = lm["fence"]
    return spec


# ---------------------------------------------------------------- sortida
def write_output(hy, surf, bid, rd, fu, fl, deco, blds, decos, spawn):
    os.makedirs(OUT, exist_ok=True)
    buf = bytearray()
    buf += b"QRT1"
    buf += struct.pack(">iiiiiiiii", 2, X0, Z0, W, H, *CORE)
    buf += hy.astype(">i2").tobytes()
    buf += surf.astype(np.uint8).tobytes()
    buf += bid.astype(">u2").tobytes()
    buf += rd.astype(np.uint8).tobytes()
    buf += fu.astype(np.uint8).tobytes()
    buf += fl.astype(np.uint8).tobytes()
    buf += deco.astype(np.uint8).tobytes()
    with gzip.open(os.path.join(OUT, "raster.bin.gz"), "wb", compresslevel=9) as f:
        f.write(bytes(buf))
    # alçada del spawn
    sx, sz = px(*spawn)
    sy = int(hy[int(sz), int(sx)]) + 1
    meta = dict(origin=[LAT0, LON0], core=list(CORE), spawn=[spawn[0], sy, spawn[1]],
                buildings=blds, decorations=[dict(d, y=int(hy[int(d["z"] - Z0), int(d["x"] - X0)])) for d in decos],
                surface_codes=S,
                attribution="Dades: (c) OpenStreetMap contributors (ODbL); Microsoft Global ML Building Footprints (ODbL); "
                            "ESA WorldCover 2021 (CC BY 4.0); AWS Terrain Tiles (EU-DEM, SRTM).")
    with open(os.path.join(OUT, "meta.json"), "w") as f:
        json.dump(meta, f, separators=(",", ":"), ensure_ascii=False)
    log(f"escrit {OUT}: raster {os.path.getsize(os.path.join(OUT, 'raster.bin.gz')) / 1e6:.1f} MB, {len(blds) - 1} edificis, spawn {meta['spawn']}")


COLORS = {
    "GRASS": (120, 170, 80), "FOREST": (40, 95, 45), "FARMLAND": (200, 180, 110), "ROAD": (95, 95, 95),
    "ROAD_MAIN": (75, 75, 78), "MARKING": (240, 240, 240), "SIDEWALK": (215, 170, 170), "PATH": (170, 140, 90),
    "TRACK": (150, 130, 100), "CYCLEWAY": (170, 70, 60), "PARKING": (130, 130, 130), "PLAZA": (190, 185, 175),
    "PARK": (100, 180, 90), "TURF": (40, 140, 60), "TURF_LINE": (250, 250, 250), "COURT": (180, 70, 60),
    "COURT_LINE": (250, 250, 250), "WATER": (60, 110, 200), "STREAM": (60, 110, 200), "POOL": (80, 200, 230),
    "GARDEN": (140, 185, 95), "SAND": (225, 205, 150), "GRAVEL": (160, 155, 150), "SHRUB": (95, 140, 70),
    "BARE": (175, 150, 120), "CEMETERY": (150, 150, 140), "PLAZA_TREES": (200, 180, 140), "MEADOW": (150, 180, 90),
}


def preview(hy, surf, bid, blds):
    inv = {v: k for k, v in S.items()}
    lut = np.zeros((256, 3), np.uint8)
    for k, v in S.items():
        lut[v] = COLORS.get(k, (255, 0, 255))
    img = lut[surf].astype(float)
    # ombrejat del relleu
    gy, gx = np.gradient(hy.astype(float))
    shade = np.clip(1 + (gx - gy) * 0.08, 0.6, 1.3)
    img *= shade[..., None]
    roof = np.array([(200, 90, 60)] * len(blds), float)
    for i, b in enumerate(blds):
        if b.get("roof_type") == "flat":
            roof[i] = (170, 165, 160)
        if b.get("landmark"):
            roof[i] = (230, 40, 200)
    bm = bid > 0
    img[bm] = roof[bid[bm]]
    img = np.clip(img, 0, 255).astype(np.uint8)
    im = Image.fromarray(img)
    os.makedirs(os.path.join(ROOT, "docs"), exist_ok=True)
    im.save(os.path.join(ROOT, "docs", "preview.png"), optimize=True)
    log("docs/preview.png")


if __name__ == "__main__":
    main()
