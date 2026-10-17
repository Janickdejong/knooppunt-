"""Plaatsnamen (stad, dorp, gehucht) uit een OSM-bestand halen voor de app.

Gebruik: build_places.py plaatsen.osm plaatsen.json
Uitvoer: [[naam, soort, lat*1e5, lon*1e5], ...]  (soort: c=city, t=town, v=village, h=hamlet)
"""
import json
import sys
import xml.etree.ElementTree as ET

KINDS = {"city": "c", "town": "t", "village": "v", "hamlet": "h"}


def main(src, dst):
    out = []
    for _, el in ET.iterparse(src, events=("end",)):
        if el.tag == "node":
            tags = {t.get("k"): t.get("v") for t in el.findall("tag")}
            kind = KINDS.get(tags.get("place", ""))
            name = tags.get("name:nl") or tags.get("name")
            if kind and name:
                out.append([name, kind, round(float(el.get("lat")) * 1e5), round(float(el.get("lon")) * 1e5)])
            el.clear()
        elif el.tag in ("way", "relation"):
            el.clear()
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(out)} plaatsen geschreven naar {dst}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Gebruik: build_places.py plaatsen.osm plaatsen.json")
    main(sys.argv[1], sys.argv[2])
