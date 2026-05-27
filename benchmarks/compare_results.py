#!/usr/bin/env python3
import json
import pathlib
import sys

KEYS = ["opsPerSec", "p50ApplyMicros", "p95ApplyMicros", "peakMemoryBytes", "consistencyFailureRate"]

def load_dir(path):
    out = {}
    for f in pathlib.Path(path).glob("*.json"):
        out[f.stem] = json.loads(f.read_text())
    return out

if len(sys.argv) != 3:
    print("usage: compare_results.py <old_dir> <new_dir>")
    sys.exit(1)

old = load_dir(sys.argv[1])
new = load_dir(sys.argv[2])
for name, newv in sorted(new.items()):
    oldv = old.get(name)
    if not oldv:
        print(f"{name}: NEW")
        continue
    print(f"\n{name}")
    for k in KEYS:
        a, b = oldv.get(k, 0), newv.get(k, 0)
        delta = b - a
        pct = 0 if a == 0 else (delta / a * 100)
        print(f"  {k}: {a} -> {b} (delta={delta:.4f}, {pct:.2f}%)")
