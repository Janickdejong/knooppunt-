"""Fiets- en voetveren (route=ferry) uit een OSM-bestand halen voor de app.

Gebruik: build_ferries.py ponten.osm ponten.json
Uitvoer: lijst van objecten {n: naam, h: openingstijden, w: website, o: exploitant, f: kosten, p: [lat1, lon1, lat2, lon2]}
"""
import json
import sys
import xml.etree.ElementTree as ET


def main(src, dst):
    loc = {}
    ways = []
    for _, el in ET.iterparse(src, events=("end",)):
        if el.tag == "node":
            loc[el.get("id")] = (float(el.get("lat")), float(el.get("lon")))
            el.clear()
        elif el.tag == "way":
            tags = {t.get("k"): t.get("v") for t in el.findall("tag")}
            refs = [nd.get("ref") for nd in el.findall("nd")]
            if tags.get("route") == "ferry" and len(refs) >= 2:
                # Alleen veren waar fietsers mee mogen (of waar niets over is vastgelegd).
                if tags.get("bicycle") != "no" and tags.get("motor_vehicle") != "only":
                    ways.append((tags, refs[0], refs[-1]))
            el.clear()
        elif el.tag == "relation":
            el.clear()
    out = []
    for tags, a, b in ways:
        if a not in loc or b not in loc:
            continue
        la, lo = loc[a]
        lb, lob = loc[b]
        # Lange veerdiensten (bijv. naar de Waddeneilanden) zijn geen pontjes onderweg.
        if abs(la - lb) + abs(lo - lob) > 0.25:
            continue
        o = {"p": [round(la, 5), round(lo, 5), round(lb, 5), round(lob, 5)]}
        for key, tag in (("n", "name"), ("h", "opening_hours"), ("w", "website"), ("o", "operator"), ("f", "fee"), ("c", "charge")):
            v = tags.get(tag) or (tags.get("contact:website") if tag == "website" else None)
            if v:
                o[key] = v
        out.append(o)
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(out)} ponten geschreven naar {dst}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Gebruik: build_ferries.py ponten.osm ponten.json")
    main(sys.argv[1], sys.argv[2])
