#!/usr/bin/env python3
"""
Builds the offline world map used by the slideshow location map
(mediaslider/mediaslider/src/main/assets/naturalearth/ne_50m.bin).

Source: Natural Earth 1:50m "land" and "admin 0 boundary lines (land)", public domain
(https://www.naturalearthdata.com/about/terms-of-use/). No attribution is required.

Usage:
    python3 tools/natural-earth/build_map_asset.py

Only the Python standard library is needed. The GeoJSON files are downloaded from the
natural-earth-vector repository at a pinned tag, so the output is reproducible.

File format (gzip-compressed, read by nl.giejay.mediaslider.map.WorldMapData):
    bytes  "NEM1"                 magic
    varint scaleBits              coordinates are Web-Mercator world units in [0, 2^scaleBits)
    section land                  closed polygon rings (outer rings and holes, fill with EVEN_ODD)
    section borders               open polylines (country borders on land)
  section := varint count, then per shape: varint pointCount, then pointCount pairs of
             zigzag varints (dx, dy), delta-encoded from the previous point ((0, 0) for the first)
"""
import gzip
import json
import math
import os
import urllib.request

NE_TAG = "v5.1.2"
BASE_URL = f"https://raw.githubusercontent.com/nvkelso/natural-earth-vector/{NE_TAG}/geojson/"
SCALE_BITS = 20
MAX_LAT = 85.05112878  # Web-Mercator limit

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "mediaslider", "mediaslider", "src", "main", "assets", "naturalearth", "ne_50m.bin")


def fetch(name):
    with urllib.request.urlopen(BASE_URL + name + ".geojson", timeout=120) as r:
        return json.load(r)


def project(lon, lat):
    size = 1 << SCALE_BITS
    lat = max(-MAX_LAT, min(MAX_LAT, lat))
    s = math.sin(math.radians(lat))
    x = (lon + 180.0) / 360.0 * size
    y = (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * size
    return min(size - 1, max(0, round(x))), min(size - 1, max(0, round(y)))


def varint(n, out):
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return


def zigzag(n):
    return (n << 1) ^ (n >> 63)


def shapes(feature_collection, kind):
    for feature in feature_collection["features"]:
        geom = feature["geometry"]
        if kind == "polygon":
            polys = geom["coordinates"] if geom["type"] == "MultiPolygon" else [geom["coordinates"]]
            for poly in polys:
                for ring in poly:
                    yield ring
        else:
            lines = geom["coordinates"] if geom["type"] == "MultiLineString" else [geom["coordinates"]]
            for line in lines:
                yield line


def encode_section(coords_iter, closed, out):
    encoded = []
    for coords in coords_iter:
        pts = []
        for lon, lat, *_ in coords:
            p = project(lon, lat)
            if not pts or pts[-1] != p:
                pts.append(p)
        if closed and len(pts) > 1 and pts[0] == pts[-1]:
            pts.pop()  # the reader closes rings itself
        if len(pts) >= (3 if closed else 2):
            encoded.append(pts)
    varint(len(encoded), out)
    for pts in encoded:
        varint(len(pts), out)
        px, py = 0, 0
        for x, y in pts:
            varint(zigzag(x - px), out)
            varint(zigzag(y - py), out)
            px, py = x, y
    return len(encoded), sum(len(p) for p in encoded)


def main():
    out = bytearray(b"NEM1")
    varint(SCALE_BITS, out)
    land = encode_section(shapes(fetch("ne_50m_land"), "polygon"), True, out)
    borders = encode_section(shapes(fetch("ne_50m_admin_0_boundary_lines_land"), "line"), False, out)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as f:
        # mtime=0 keeps the output byte-identical between runs
        f.write(gzip.compress(bytes(out), compresslevel=9, mtime=0))
    print(f"land: {land[0]} rings / {land[1]} points, borders: {borders[0]} lines / {borders[1]} points")
    print(f"wrote {OUT} ({os.path.getsize(OUT) // 1024} KB)")


if __name__ == "__main__":
    main()
