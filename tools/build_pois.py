"""Cafés, restaurants en toiletten uit een OSM-bestand halen voor de app.

Gebruik: build_pois.py pauze.osm pauze.json
Uitvoer: [[naam, soort, lat*1e5, lon*1e5], ...]
soort: c=cafe, r=restaurant, t=toilets, f=fast_food, p=pub, i=ice_cream
"""
import json
import sys
import xml.etree.ElementTree as ET

KINDS = {"cafe": "c", "restaurant": "r", "toilets": "t", "fast_food": "f", "pub": "p", "ice_cream": "i"}


def main(src, dst):
    loc = {}
    out = []
    seen = set()
    for _, el in ET.iterparse(src, events=("end",)):
        if el.tag == "node":
            lat, lon = float(el.get("lat")), float(el.get("lon"))
            loc[el.get("id")] = (lat, lon)
            tags = {t.get("k"): t.get("v") for t in el.findall("tag")}
            add(out, seen, tags, lat, lon)
            el.clear()
        elif el.tag == "way":
            tags = {t.get("k"): t.get("v") for t in el.findall("tag")}
            pts = [loc[nd.get("ref")] for nd in el.findall("nd") if nd.get("ref") in loc]
            if pts:
                add(out, seen, tags, sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts))
            el.clear()
        elif el.tag == "relation":
            el.clear()
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(out)} pauzeplekken geschreven naar {dst}")


def add(out, seen, tags, lat, lon):
    kind = KINDS.get(tags.get("amenity", ""))
    if not kind:
        return
    if tags.get("access") in ("private", "no", "customers") and kind == "t":
        return
    name = tags.get("name", "")
    if not name and kind != "t":
        return
    key = (name, round(lat, 4), round(lon, 4))
    if key in seen:
        return
    seen.add(key)
    out.append([name, kind, round(lat * 1e5), round(lon * 1e5)])


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Gebruik: build_pois.py pauze.osm pauze.json")
    main(sys.argv[1], sys.argv[2])
