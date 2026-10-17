#!/usr/bin/env python3
"""Zet een gefilterd OpenStreetMap-bestand (XML) om in knooppunten.json voor de app.

Gebruik: build_data.py gefilterd.osm uitvoer.json

Het bestand bevat de fietsknooppunten (rcn_ref) en de verbindingen ertussen,
al aan elkaar gekoppeld en vereenvoudigd, zodat de app niets hoeft te berekenen.
"""
import json
import math
import re
import heapq
import sys
from datetime import date

import xml.etree.ElementTree as ET

LINK = re.compile(r"(\d{1,3})\s*[-–>]+\s*(\d{1,3})")


class Collector:
    """Leest een OSM-XML-bestand (met knooppunten, wegstukken en routes)."""

    def __init__(self):
        self.nodes = {}  # id -> (ref, lat, lon), alleen knooppunten met rcn_ref
        self.ways = {}   # id -> [node ids]
        self.loc = {}    # node id -> (lat, lon)
        self.oneway = {} # way id -> 1 (vooruit) of -1 (achteruit), alleen voor fietsers
        self.rels = []   # (id, ref, name, note, [way ids])

    def read(self, src):
        loc = self.loc
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
                ids = [int(n.get("ref")) for n in el.findall("nd") if int(n.get("ref")) in loc]
                if len(ids) >= 2:
                    wid = int(el.get("id"))
                    self.ways[wid] = ids
                    d = oneway_dir({t.get("k"): t.get("v") for t in el.findall("tag")})
                    if d:
                        self.oneway[wid] = d
                el.clear()
            elif tag == "relation":
                tags = {t.get("k"): t.get("v") for t in el.findall("tag")}
                if tags.get("route") == "bicycle" and tags.get("network") == "rcn":
                    ways = [int(m.get("ref")) for m in el.findall("member") if m.get("type") == "way"]
                    self.rels.append((int(el.get("id")), tags.get("ref") or "", tags.get("name") or "", tags.get("note") or "", ways))
                el.clear()


def oneway_dir(tags):
    """1 = alleen vooruit, -1 = alleen achteruit, 0 = beide kanten, voor fietsers."""
    v = tags.get("oneway:bicycle") or tags.get("oneway")
    if v is None and tags.get("junction") in ("roundabout", "circular"):
        v = "yes"
    d = 1 if v in ("yes", "true", "1") else -1 if v in ("-1", "reverse") else 0
    if d and any((tags.get(k) or "").startswith("opposite") for k in ("cycleway", "cycleway:left", "cycleway:right", "cycleway:both")):
        d = 0
    return d


def dist(a, b):
    """Afstand in meters tussen twee (lat, lon)-punten."""
    la1, lo1 = math.radians(a[0]), math.radians(a[1])
    la2, lo2 = math.radians(b[0]), math.radians(b[1])
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371000.0 * math.asin(math.sqrt(h))


def norm(ref):
    return ref.strip().lstrip("0") or "0"


def way_graph(way_ids, ways, loc, oneway=None):
    """Graaf van de wegstukken van een route: punt-id -> [(buur, afstand)].

    Met oneway krijgen eenrichtingswegen maar één richting."""
    adj = {}
    for w in way_ids:
        ids = ways.get(w)
        if not ids:
            continue
        for u, v in zip(ids, ids[1:]):
            if u == v:
                continue
            d = dist(loc[u], loc[v])
            way_dir = oneway.get(w, 0) if oneway else 0
            if way_dir >= 0:
                adj.setdefault(u, []).append((v, d))
                adj.setdefault(v, [])
            if way_dir <= 0:
                adj.setdefault(v, []).append((u, d))
                adj.setdefault(u, [])
    return adj


def nearest(candidates, adj, loc, nodes):
    """Knooppunt (uit candidates) met het dichtstbijzijnde wegpunt van de route, binnen 300 m."""
    if not adj:
        return None, None
    lats = [loc[n][0] for n in adj]
    lons = [loc[n][1] for n in adj]
    pad = 0.005
    lo_lat, hi_lat, lo_lon, hi_lon = min(lats) - pad, max(lats) + pad, min(lons) - pad, max(lons) + pad
    best, best_node, best_d = None, None, 300.0
    for nid in candidates:
        pos = (nodes[nid][1], nodes[nid][2])
        if not (lo_lat <= pos[0] <= hi_lat and lo_lon <= pos[1] <= hi_lon):
            continue
        for n in adj:
            d = dist(pos, loc[n])
            if d < best_d:
                best, best_node, best_d = nid, n, d
    return best, best_node


def shortest(adj, src, dst):
    """Kortste weg over de wegstukken (Dijkstra); lijst met punt-id's of None."""
    best = {src: 0.0}
    prev = {}
    heap = [(0.0, src)]
    while heap:
        d, u = heapq.heappop(heap)
        if u == dst:
            break
        if d > best.get(u, float("inf")):
            continue
        for v, w in adj.get(u, ()):
            nd = d + w
            if nd < best.get(v, float("inf")):
                best[v] = nd
                prev[v] = u
                heapq.heappush(heap, (nd, v))
    if dst not in best:
        return None
    path = [dst]
    while path[-1] != src:
        path.append(prev[path[-1]])
    return path[::-1]


def bridge_gaps(graphs, und, loc, maxd=150.0):
    """Verbindt losse uiteinden die dicht bij elkaar liggen (kleine gaten in de route)."""
    ends = [n for n, nb in und.items() if len(nb) == 1]
    for i, u in enumerate(ends):
        for v in ends[i + 1:]:
            d = dist(loc[u], loc[v])
            if d <= maxd:
                for g in graphs:
                    g.setdefault(u, []).append((v, d))
                    g.setdefault(v, []).append((u, d))


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
    skipped = 0
    for rid, ref, name, note, way_ids in c.rels:
        text = next((t for t in (ref, name, note) if LINK.search(t)), None)
        if text is None:
            continue
        m = LINK.search(text)
        ra, rb = norm(m.group(1)), norm(m.group(2))
        if ra == rb:
            continue
        und = way_graph(way_ids, c.ways, c.loc)
        if not und:
            continue
        a, na = nearest(by_ref.get(ra, []), und, c.loc, c.nodes)
        b, nb = nearest(by_ref.get(rb, []), und, c.loc, c.nodes)
        if a is None or b is None or a == b or na == nb:
            continue
        # Fietsers mogen op eenrichtingspaden maar één kant op: heen en terug kunnen dus een andere weg zijn.
        adj = way_graph(way_ids, c.ways, c.loc, c.oneway)
        fwd = shortest(adj, na, nb)
        bwd = shortest(adj, nb, na)
        if fwd is None or bwd is None:
            bridge_gaps([und, adj], und, c.loc)
            fwd = shortest(adj, na, nb)
            bwd = shortest(adj, nb, na)
        if fwd is None and bwd is None:
            fwd = shortest(und, na, nb)
            bwd = None
        if fwd is None and bwd is not None:
            fwd, bwd = bwd[::-1], None
        if fwd is None:
            skipped += 1
            continue
        if bwd is not None and bwd == fwd[::-1]:
            bwd = None
        pa = (c.nodes[a][1], c.nodes[a][2])
        pb = (c.nodes[b][1], c.nodes[b][2])
        pts = simplify([pa] + [c.loc[n] for n in fwd] + [pb])
        back = simplify([pb] + [c.loc[n] for n in bwd] + [pa]) if bwd is not None else None
        edges.append((a, b, pts, back))

    print(f"{skipped} verbindingen overgeslagen (geen doorlopende weg)")
    used = sorted({e[0] for e in edges} | {e[1] for e in edges})
    with_back = sum(1 for e in edges if e[3] is not None)
    print(f"{with_back} verbindingen met een andere weg terug")
    index = {nid: i for i, nid in enumerate(used)}
    data = {
        "date": date.today().isoformat(),
        "nodes": [[nid, c.nodes[nid][0], round(c.nodes[nid][1], 6), round(c.nodes[nid][2], 6)] for nid in used],
        "edges": [
            [index[a], index[b], encode(pts)] + ([encode(back)] if back else [])
            for a, b, pts, back in edges
        ],
    }
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(data, f, separators=(",", ":"))
    print(f"{len(used)} knooppunten en {len(edges)} verbindingen opgeslagen in {dst}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Gebruik: build_data.py invoer.osm uitvoer.json")
    main(sys.argv[1], sys.argv[2])
