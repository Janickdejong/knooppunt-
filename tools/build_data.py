#!/usr/bin/env python3
"""Zet een gefilterd OpenStreetMap-bestand (XML) om in knooppunten.json voor de app.

Gebruik: build_data.py gefilterd.osm uitvoer.json

Het bestand bevat de fietsknooppunten (rcn_ref) en de verbindingen ertussen,
al aan elkaar gekoppeld en vereenvoudigd, zodat de app niets hoeft te berekenen.
"""
import json
import math
import re
import sys
from datetime import date

import xml.etree.ElementTree as ET

LINK = re.compile(r"(\d{1,3})\s*[-–>]+\s*(\d{1,3})")


class Collector:
    """Leest een OSM-XML-bestand (met knooppunten, wegstukken en routes)."""

    def __init__(self):
        self.nodes = {}  # id -> (ref, lat, lon), alleen knooppunten met rcn_ref
        self.ways = {}   # id -> [(lat, lon), ...]
        self.rels = []   # (id, ref, name, note, [way ids])

    def read(self, src):
        loc = {}
        for _, el in ET.iterparse(src, events=("end",)):
            tag = el.tag
            if tag == "node":
                nid = int(el.get("id"))
                lat, lon = float(el.get("lat")), float(el.get("lon"))
                loc[nid] = (lat, lon)
                for t in el.findall("tag"):
                    if t.get("k") == "rcn_ref" and t.get("v"):
                        self.nodes[nid] = (t.get("v"), lat, lon)
                el.clear()
            elif tag == "way":
                pts = [loc[int(n.get("ref"))] for n in el.findall("nd") if int(n.get("ref")) in loc]
                if len(pts) >= 2:
                    self.ways[int(el.get("id"))] = pts
                el.clear()
            elif tag == "relation":
                tags = {t.get("k"): t.get("v") for t in el.findall("tag")}
                if tags.get("route") == "bicycle" and tags.get("network") == "rcn":
                    ways = [int(m.get("ref")) for m in el.findall("member") if m.get("type") == "way"]
                    self.rels.append((int(el.get("id")), tags.get("ref") or "", tags.get("name") or "", tags.get("note") or "", ways))
                el.clear()


def dist(a, b):
    """Afstand in meters tussen twee (lat, lon)-punten."""
    la1, lo1 = math.radians(a[0]), math.radians(a[1])
    la2, lo2 = math.radians(b[0]), math.radians(b[1])
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371000.0 * math.asin(math.sqrt(h))


def norm(ref):
    return ref.strip().lstrip("0") or "0"


def nearest(candidates, ways, nodes):
    best, best_d = None, 300.0
    for nid in candidates:
        pos = (nodes[nid][1], nodes[nid][2])
        for w in ways:
            for p in (w[0], w[-1]):
                d = dist(pos, p)
                if d < best_d:
                    best, best_d = nid, d
    return best


def chain(ways, start):
    left = list(ways)
    out = []
    cur = start
    while left:
        bi, br, bd = -1, False, float("inf")
        for i, w in enumerate(left):
            d1, d2 = dist(cur, w[0]), dist(cur, w[-1])
            if d1 < bd:
                bi, br, bd = i, False, d1
            if d2 < bd:
                bi, br, bd = i, True, d2
        if bi < 0 or bd > 200.0:
            break
        w = left.pop(bi)
        if br:
            w = w[::-1]
        out.extend(w)
        cur = w[-1]
    return out


def simplify(pts, tol=3.0):
    """Ramer-Douglas-Peucker; tol in meters."""
    if len(pts) < 3:
        return pts
    kx = math.cos(math.radians(pts[0][0])) * 111320.0
    ky = 110540.0
    xy = [(lon * kx, lat * ky) for lat, lon in pts]
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        i, j = stack.pop()
        ax, ay = xy[i]
        bx, by = xy[j]
        dx, dy = bx - ax, by - ay
        ll = dx * dx + dy * dy
        best, bd = -1, tol
        for k in range(i + 1, j):
            px, py = xy[k]
            if ll == 0:
                d = math.hypot(px - ax, py - ay)
            else:
                t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / ll))
                d = math.hypot(px - (ax + t * dx), py - (ay + t * dy))
            if d > bd:
                best, bd = k, d
        if best >= 0:
            keep[best] = True
            stack.append((i, best))
            stack.append((best, j))
    return [p for p, k in zip(pts, keep) if k]


def encode(pts):
    """Google polyline, precisie 1e-6."""
    out = []
    plat = plon = 0
    for lat, lon in pts:
        la, lo = round(lat * 1e6), round(lon * 1e6)
        for v in (la - plat, lo - plon):
            v = ~(v << 1) if v < 0 else v << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1F)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        plat, plon = la, lo
    return "".join(out)


def main(src, dst):
    c = Collector()
    c.read(src)
    print(f"{len(c.nodes)} knooppunten, {len(c.ways)} wegstukken, {len(c.rels)} verbindingen gelezen")

    by_ref = {}
    for nid, (ref, _, _) in c.nodes.items():
        by_ref.setdefault(norm(ref), []).append(nid)

    edges = []
    for rid, ref, name, note, way_ids in c.rels:
        text = next((t for t in (ref, name, note) if LINK.search(t)), None)
        if text is None:
            continue
        m = LINK.search(text)
        ra, rb = norm(m.group(1)), norm(m.group(2))
        if ra == rb:
            continue
        ways = [c.ways[w] for w in way_ids if w in c.ways]
        if not ways:
            continue
        a = nearest(by_ref.get(ra, []), ways, c.nodes)
        b = nearest(by_ref.get(rb, []), ways, c.nodes)
        if a is None or b is None or a == b:
            continue
        pa = (c.nodes[a][1], c.nodes[a][2])
        pb = (c.nodes[b][1], c.nodes[b][2])
        chained = chain(ways, pa)
        if len(chained) < 2:
            continue
        pts = simplify([pa] + chained + [pb])
        edges.append((a, b, pts))

    used = sorted({e[0] for e in edges} | {e[1] for e in edges})
    index = {nid: i for i, nid in enumerate(used)}
    data = {
        "date": date.today().isoformat(),
        "nodes": [[nid, c.nodes[nid][0], round(c.nodes[nid][1], 6), round(c.nodes[nid][2], 6)] for nid in used],
        "edges": [[index[a], index[b], encode(pts)] for a, b, pts in edges],
    }
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(data, f, separators=(",", ":"))
    print(f"{len(used)} knooppunten en {len(edges)} verbindingen opgeslagen in {dst}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Gebruik: build_data.py invoer.osm uitvoer.json")
    main(sys.argv[1], sys.argv[2])
