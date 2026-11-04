"""Download LADOT's per-district camera PDFs and rebuild data/cameras.csv.

The LADOT page links one PDF per council district (cd-1.pdf ... cd-15.pdf, some named
sss-cd-N.pdf). Each holds a table: ID, Street Segment, From, To, Speed Limit, Operation Status.

Run:  python3 -I data/parse_pdfs.py            (needs curl and pdftotext)
Exit code 0 and prints "CHANGED" or "UNCHANGED" depending on whether cameras.csv moved.
"""
import csv, pathlib, re, subprocess, sys, tempfile, html
from typing import Optional

HERE = pathlib.Path(__file__).parent
PAGE = "https://ladot.lacity.gov/speed-safety-system"
UA = "la-radars-data-refresh/1.0"
COLUMNS = ["id", "district", "street", "from", "to", "speed_limit", "status"]


def curl(url: str, out: Optional[pathlib.Path] = None) -> str:
    args = ["curl", "-sL", "--max-time", "120", "-A", UA, url]
    if out:
        subprocess.run(args + ["-o", str(out)], check=True)
        return ""
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def district_pdf_links() -> dict:
    """Find the current PDF link for every district on the LADOT page."""
    page = curl(PAGE)
    links = {}
    for href in re.findall(r'href="([^"]*cd-(\d+)\.pdf)"', page):
        url, district = html.unescape(href[0]), int(href[1])
        if not url.startswith("http"):
            url = "https://ladot.lacity.gov" + url
        links.setdefault(district, url)
    if len(links) != 15:
        raise SystemExit(f"expected 15 district PDFs on {PAGE}, found {sorted(links)}")
    return links


def parse_text(text: str, district: int) -> list:
    rows = []
    for line in text.splitlines():
        m = re.match(rf"\s*({district}-\d+)\s+(.*)$", line)
        if not m:
            continue
        cols = re.split(r"\s{2,}", m.group(2).strip())
        tail = re.match(r"(\d+)\s+(.*)$", cols[-1])
        if len(cols) == 3 and tail:
            # "From" and "To" separated by a single space: split on the last directional cross street.
            pieces = re.split(r"\s(?=[NSEW] )", cols[1], maxsplit=1)
            if len(pieces) == 2:
                cols = [cols[0], pieces[0], pieces[1], cols[2]]
        if len(cols) != 4 or not tail:
            print(f"  cannot parse: {line.strip()!r}", file=sys.stderr)
            continue
        rows.append({
            "id": m.group(1), "district": district, "street": cols[0], "from": cols[1], "to": cols[2],
            "speed_limit": tail.group(1), "status": tail.group(2).strip(),
        })
    return rows


def main():
    links = district_pdf_links()
    rows = []
    with tempfile.TemporaryDirectory() as tmp:
        for district in range(1, 16):
            pdf = pathlib.Path(tmp) / f"cd-{district}.pdf"
            curl(links[district], pdf)
            text = subprocess.run(["pdftotext", "-layout", str(pdf), "-"], check=True, capture_output=True, text=True).stdout
            parsed = parse_text(text, district)
            if not parsed:
                raise SystemExit(f"no cameras parsed for district {district}: PDF layout changed?")
            rows += parsed
    if len(rows) < 100:
        raise SystemExit(f"only {len(rows)} cameras parsed, refusing to overwrite")

    out = HERE / "cameras.csv"
    before = out.read_text() if out.exists() else ""
    with out.open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS)
        w.writeheader()
        w.writerows(rows)
    after = out.read_text()
    print("CHANGED" if before != after else "UNCHANGED", f"({len(rows)} cameras)")


if __name__ == "__main__":
    main()
