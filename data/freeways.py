"""Build data/freeways.json: the freeway centrelines around Los Angeles, from OpenStreetMap.

Source query (Overpass API):
    [out:json][timeout:120];
    way["highway"="motorway"](33.70,-118.68,34.34,-118.14);
    out geom;
Only mainline freeways ("motorway"), not ramps: leaving a freeway towards a camera street
must still alert. Output: {"lines": [[lat, lng, lat, lng, ...], ...]} with 5 decimals.

Run:  python3 -I data/freeways.py <overpass-motorways.json>
"""
import json, pathlib, sys

HERE = pathlib.Path(__file__).parent
src = json.load(open(sys.argv[1]))
lines = []
for e in src["elements"]:
    if e.get("type") != "way" or "geometry" not in e:
        continue
    flat = []
    for p in e["geometry"]:
        flat += [round(p["lat"], 5), round(p["lon"], 5)]
    if len(flat) >= 4:
        lines.append(flat)
out = HERE / "freeways.json"
out.write_text(json.dumps({"source": "OpenStreetMap contributors, ODbL", "lines": lines}, separators=(",", ":")))
print(len(lines), "lines,", sum(len(l) // 2 for l in lines), "points,", out.stat().st_size // 1024, "KB")
