#!/usr/bin/env python3
"""The chunk composite's GPU time per cost mask from a devPplTiming + devPplAlternate run with two or more masks:
ctimen.py <run>... Reads the "composite gpu alt:" / "altn:" lines (each mask's mean over the same stretch, the masks taking
turns every period), drops the first line (warm-up) and prints each mask's mean and its difference to the first mask, per
line (drift cancels within a line)."""
import re
import sys

for run in sys.argv[1:]:
    lines = []
    for line in open(run + "/console.txt", errors="replace"):
        if "composite gpu alt" in line:
            lines.append([(m.group(1), float(m.group(2))) for m in re.finditer(r"m(\d+) ([\d.]+) us", line)])
    if len(lines) < 2:
        print(run, "no alternation lines")
        continue
    rest = lines[1:]
    base = rest[0][0][0]
    print(f"{run}: {len(rest)} lines after the first")
    for i, (mask, _) in enumerate(rest[0]):
        v = [l[i][1] for l in rest]
        d = sorted(l[i][1] - l[0][1] for l in rest)
        print(f"   m{mask:>7}: {sum(v) / len(v):7.1f} us   vs m{base}: {sum(d) / len(d):+7.1f} us (median {d[len(d) // 2]:+.1f})")
