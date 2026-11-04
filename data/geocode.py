"""Geocode the LADOT camera segments (data/cameras.csv) into data/cameras.json.

Each segment is "street, from cross-street, to cross-street". Each end is looked up in
the City of Los Angeles Bureau of Engineering "Intersections" layer (official, public):
https://maps.lacity.org/lahub/rest/services/Street_Information/MapServer/9
Ends that are not intersections (an address, a transit station) go through Nominatim.
Anything still missing is taken from MANUAL below.

Run:  python3 -I data/geocode.py
"""
import csv, json, math, re, subprocess, sys, time, pathlib, urllib.parse

HERE = pathlib.Path(__file__).parent
LAYER = "https://maps.lacity.org/lahub/rest/services/Street_Information/MapServer/9/query"
UA = "la-radars-geocoder/1.0"

SUFFIX = {"BL": "BLVD", "BLVD": "BLVD", "AVE": "AVE", "ST": "ST", "DR": "DR", "PL": "PL", "RD": "ROAD",
          "CIR": "CIR", "HWY": "HWY", "TER": "TER", "WAY": "WAY", "LN": "LN", "CT": "CT"}

# City naming for ends LADOT wrote differently. Value = list of acceptable city names.
ALIASES = {
    "5 FWY": ["GOLDEN STATE FRWY"],
    "405 FWY": ["SAN DIEGO FRWY"],
    "118 FWY": ["RONALD REAGAN FRWY", "SIMI VALLEY FRWY", "SIMI VALLEY-SAN FERNANDO VALLEY FRWY"],
    "101 FWY": ["HOLLYWOOD FRWY"],
    "W MARTIN LUTHER KING JR BLVD": ["MARTIN LUTHER KING, JR BLVD"],
    "S SAINT ANDREWS PL": ["ST ANDREWS PL"],
    "CALNEVA": ["CALNEVA DR"],
    "CORDA": ["CORDA DR"],
    "E N ST": ["N ST"],
    "CITY LIMIT": ["GRAND AVE"],  # Vista Del Mar: the city limit with El Segundo is at Grand Ave
    "FIFTH BLVD": ["FIRTH WALK", "FIRTH BLVD"],  # LADOT's "Fifth Blvd" is Firth (Walk) in Watts
    "MOTT AVE": ["MOTT ST"],
    "FRANKLIN PL": ["FRANKLIN PL", "FRANKLIN AVE"],  # Franklin Pl is a stub 60 m north of Franklin Ave
    "CAMINO PALMERO ST": ["CAMINO PALMERO"],
    "92ND AVE": ["92ND ST"],
}

# Ends that are not street intersections: free-text geocoded with Nominatim.
ADDRESSES = {
    "3721 La Cienega Bl": "3721 S La Cienega Blvd, Los Angeles, CA",
    "High Tech Los Angeles East Dwy": "17111 Victory Blvd, Los Angeles, CA",
    "Orange Line Busway": "6338 Balboa Blvd, Los Angeles, CA",  # Metro G Line Balboa station
}

# Crossings that are not street intersections, resolved once from OpenStreetMap (Overpass):
# (camera id, "from"/"to") -> [lat, lng]
MANUAL = {
    ("3-1", "to"): [34.186617, -118.529056],    # Victory Blvd bridge over the LA River
    ("4-2", "from"): [34.186617, -118.529056],
    ("4-8", "from"): [34.095821, -118.284256],  # Fountain Ave at Sunset Blvd (missing from city layer)
    # Grid crossings missing from the city layer: latitude of one street x longitude of the other.
    ("2-2", "to"): [34.19394, -118.39651],      # Vanowen St at Laurel Canyon Blvd
    ("4-3", "to"): [34.10820, -118.30928],      # Western Ave at Los Feliz Blvd
    ("15-8", "to"): [33.792798, -118.290845],   # Vermont Ave at 255th St (private street, from the centerline layer)
    ("6-1", "from"): [34.235471, -118.457505],  # Nordhoff St bridge over the Pacoima Wash (OpenStreetMap)
    ("7-7", "to"): [34.235471, -118.457505],
    ("7-6", "to"): [34.2668, -118.4406],        # Laurel Canyon Blvd over the Pacoima Wash, at the 118 Fwy interchange
}


def norm(name: str) -> str:
    name = re.sub(r"\s*\((midblock|east|north|west|south)\)\s*$", "", name).strip().upper()
    if name in ALIASES:
        return name
    words = name.split()
    if words and words[-1] in SUFFIX:
        words[-1] = SUFFIX[words[-1]]
    if len(words) > 1 and words[0] in ("N", "S", "E", "W"):
        words = words[1:]
    return " ".join(words)


def candidates(name: str):
    n = norm(name)
    return ALIASES.get(n, [n])


def curl(url: str, params: dict):
    args = ["curl", "-s", "--max-time", "90", "-A", UA, "-G", url]
    for k, v in params.items():
        args += ["--data-urlencode", f"{k}={v}"]
    for attempt in range(4):
        proc = subprocess.run(args, capture_output=True, text=True)
        try:
            return json.loads(proc.stdout)
        except Exception:
            print(f"  retry {attempt+1}: {proc.stdout[:80]!r}", file=sys.stderr)
            time.sleep(5 * (attempt + 1))
    return {}


def city_points(a_names, b_names):
    def clause(names):
        return "(" + " OR ".join(
            f"UPPER(TOOLTIP) LIKE '{n} AT %' OR UPPER(TOOLTIP) LIKE '% AT {n}'" for n in names) + ")"
    where = clause(a_names) + " AND " + clause(b_names)
    d = curl(LAYER, {"where": where, "outFields": "TOOLTIP,LAT,LON", "returnGeometry": "false", "f": "json"})
    return [(f["attributes"]["LAT"], f["attributes"]["LON"], f["attributes"]["TOOLTIP"])
            for f in d.get("features", []) if f["attributes"].get("LAT")]


def nominatim(q: str):
    time.sleep(1.1)  # usage policy: max 1 request per second
    d = curl("https://nominatim.openstreetmap.org/search", {"q": q, "format": "json", "limit": "1"})
    if d:
        return float(d[0]["lat"]), float(d[0]["lon"])
    return None


def dist_m(a, b):
    lat1, lon1, lat2, lon2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 6371000 * 2 * math.asin(math.sqrt(h))


def clusters(points, radius=150):
    groups = []
    for p in points:
        for g in groups:
            if dist_m(p, g[0]) < radius:
                g.append(p)
                break
        else:
            groups.append([p])
    return [(sum(p[0] for p in g) / len(g), sum(p[1] for p in g) / len(g), len(g)) for g in groups]


def resolve_end(street, cross, other=None):
    """Return ([lat, lng], note) for the crossing of `street` and `cross`."""
    if cross in ADDRESSES:
        hit = nominatim(ADDRESSES[cross])
        return (list(hit), "address") if hit else (None, "address not found")
    pts = city_points(candidates(street), candidates(cross))
    if not pts:
        return None, "no intersection match"
    cl = clusters(pts)
    if len(cl) == 1:
        return [round(cl[0][0], 6), round(cl[0][1], 6)], "ok"
    if other:
        best = min(cl, key=lambda c: dist_m(c, other))
        return [round(best[0], 6), round(best[1], 6)], f"ambiguous ({len(cl)} places), nearest to other end"
    best = max(cl, key=lambda c: c[2])
    return [round(best[0], 6), round(best[1], 6)], f"ambiguous ({len(cl)} places), biggest cluster"


def main():
    rows = list(csv.DictReader((HERE / "cameras.csv").open()))
    out_path = HERE / "cameras.json"
    # Ends already resolved cleanly in a previous run are kept; only the rest is queried again.
    previous = {c["id"]: c for c in json.load(out_path.open())} if out_path.exists() else {}
    out = []
    for r in rows:
        cam = {**r, "district": int(r["district"]), "speed_limit": int(r["speed_limit"])}
        notes = {}
        prev = previous.get(r["id"], {})
        for end in ("from", "to"):
            if (r["id"], end) in MANUAL:
                cam[f"{end}_coord"], notes[end] = MANUAL[(r["id"], end)], "manual"
                continue
            prev_note = prev.get("geocode_notes", {}).get(end, "")
            if prev.get(f"{end}_coord") and (prev_note in ("ok", "address") or prev_note.startswith("ambiguous")):
                cam[f"{end}_coord"], notes[end] = prev[f"{end}_coord"], prev_note
                continue
            cam[f"{end}_coord"], notes[end] = resolve_end(r["street"], r[end])
        # Second pass: disambiguate each end against the other one.
        for end, other in (("from", "to"), ("to", "from")):
            if notes[end].startswith("ambiguous") and cam.get(f"{other}_coord"):
                cam[f"{end}_coord"], notes[end] = resolve_end(r["street"], r[end], cam[f"{other}_coord"])
        if cam.get("from_coord") and cam.get("to_coord"):
            cam["length_m"] = round(dist_m(cam["from_coord"], cam["to_coord"]))
        cam["geocode_notes"] = notes
        flag = "" if notes["from"] == "ok" and notes["to"] == "ok" else "   <-- " + json.dumps(notes)
        print(f'{r["id"]:>5} {r["street"]} | {r["from"]} -> {r["to"]} | {cam.get("length_m")} m{flag}')
        out.append(cam)
    json.dump(out, out_path.open("w"), indent=1)
    missing = [(c["id"], e) for c in out for e in ("from", "to") if not c.get(f"{e}_coord")]
    print("MISSING:", missing)


if __name__ == "__main__":
    main()
