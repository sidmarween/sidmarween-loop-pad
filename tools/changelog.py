#!/usr/bin/env python3
"""Erzeugt app/src/main/assets/changelog.json: je Version (1.0.<Build-Nummer>) Datum und Stichpunkte
aus den Commit-Nachrichten. Version = erfolgreicher Build-Lauf; Commits fehlgeschlagener Läufe
landen in der nächsten Version. Läuft in GitHub Actions (aktueller Lauf = neueste Version) und lokal."""
import json, os, re, subprocess, sys
from datetime import datetime, timezone, timedelta

REPO = os.environ.get("GITHUB_REPOSITORY", "sidmarween/sidmarween-loop-pad")
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "changelog.json")

def sh(*a):
    return subprocess.run(a, capture_output=True, text=True, check=True).stdout

def berlin(iso):
    t = datetime.fromisoformat(iso.replace("Z", "+00:00"))
    # Mitteleuropa: Sommerzeit grob (letzter So. März … letzter So. Oktober)
    y = t.year
    def last_sunday(m):
        d = datetime(y, m, 31 if m in (3, 10) else 30, tzinfo=timezone.utc)
        return d - timedelta(days=(d.weekday() + 1) % 7)
    dst = last_sunday(3).replace(hour=1) <= t < last_sunday(10).replace(hour=1)
    return t + timedelta(hours=2 if dst else 1)

def bullets(subject):
    s = subject.strip()
    # an Kommas/Semikolons außerhalb von Klammern trennen
    out, depth, cur = [], 0, ""
    for ch in s:
        if ch in "([": depth += 1
        if ch in ")]": depth = max(0, depth - 1)
        if ch in ",;" and depth == 0:
            out.append(cur.strip()); cur = ""
        else:
            cur += ch
    out.append(cur.strip())
    res = []
    for b in out:
        if not b: continue
        b = b[0].upper() + b[1:]
        res.append(b)
    return res

def runs():
    items = []
    for page in range(1, 6):
        try:
            data = json.loads(sh("gh", "api", f"repos/{REPO}/actions/workflows/build.yml/runs?per_page=100&page={page}"))
        except Exception:
            break
        rs = data.get("workflow_runs", [])
        if not rs: break
        items += rs
    return items

def main():
    cur_run = os.environ.get("GITHUB_RUN_NUMBER")
    cur_sha = os.environ.get("GITHUB_SHA")
    rs = runs()
    ok = {}
    for r in rs:
        n = r["run_number"]
        if r.get("conclusion") == "success" or (cur_run and str(n) == cur_run):
            ok[n] = (r["head_sha"], r["created_at"])
    if cur_run and cur_sha:
        ok[int(cur_run)] = (cur_sha, datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"))
    if not ok:
        print("keine Läufe gefunden – changelog.json bleibt", file=sys.stderr)
        return
    entries = []
    prev = None
    for n in sorted(ok):
        sha, created = ok[n]
        try:
            rng = f"{prev}..{sha}" if prev else sha
            subs = [l for l in sh("git", "log", "--format=%s", rng).splitlines() if l and not l.startswith("Build-Log")]
        except Exception:
            subs = []
        prev = sha
        pts = []
        for s in reversed(subs):
            for b in bullets(s):
                if b not in pts: pts.append(b)
        if not pts: pts = ["Kleinere Verbesserungen"]
        d = berlin(created)
        entries.append({"version": f"1.0.{n}", "date": d.strftime("%d.%m.%Y %H:%M"), "items": pts})
    entries.reverse()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(entries, f, ensure_ascii=False, indent=1)
    print(f"{len(entries)} Versionen → {OUT}")

main()
