"""Daily refresh: re-read LADOT's PDFs, carry statuses into cameras.json, bump the manifest.

Coordinates already in cameras.json are kept (matched by camera id); only brand-new
segments are geocoded. Prints "DATA_UPDATED" when cameras.json changed.
"""
import datetime, json, pathlib, subprocess, sys

HERE = pathlib.Path(__file__).parent


def run(script: str) -> str:
    p = subprocess.run([sys.executable, "-I", str(HERE / script)], capture_output=True, text=True)
    sys.stderr.write(p.stderr)
    if p.returncode != 0:
        raise SystemExit(f"{script} failed:\n{p.stdout}")
    return p.stdout


def main():
    print(run("parse_pdfs.py").strip())
    cams_path = HERE / "cameras.json"
    before = cams_path.read_text()
    old = {c["id"]: c for c in json.loads(before)}

    import csv
    rows = list(csv.DictReader((HERE / "cameras.csv").open()))
    new_ids = [r["id"] for r in rows if r["id"] not in old]
    changed_segment = [r["id"] for r in rows if r["id"] in old and
                       (old[r["id"]]["street"], old[r["id"]]["from"], old[r["id"]]["to"]) != (r["street"], r["from"], r["to"])]
    if new_ids or changed_segment:
        # Forget coordinates of moved segments so geocode.py looks them up again.
        kept = [c for c in old.values() if c["id"] not in changed_segment]
        cams_path.write_text(json.dumps(kept, indent=1))
        print("geocoding", new_ids + changed_segment)
        print(run("geocode.py")[-2000:])
    else:
        # Same segments: refresh status / speed limit in place, no network needed.
        merged = []
        for r in rows:
            c = dict(old[r["id"]])
            c["status"] = r["status"]
            c["speed_limit"] = int(r["speed_limit"])
            merged.append(c)
        cams_path.write_text(json.dumps(merged, indent=1) + "\n")

    after = cams_path.read_text()
    if json.loads(before) == json.loads(after):
        cams_path.write_text(before)
        print("DATA_UNCHANGED")
        return
    manifest_path = HERE / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    manifest["data_version"] = datetime.date.today().isoformat()
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    statuses = {}
    for c in json.loads(after):
        statuses[c["status"]] = statuses.get(c["status"], 0) + 1
    print("DATA_UPDATED", manifest["data_version"], statuses)


if __name__ == "__main__":
    main()
