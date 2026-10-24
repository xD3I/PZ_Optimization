#!/usr/bin/env python3
"""An ASCII map of the level-0 floor of a world area from one map cell's .lotheader / .lotpack (ground tracks scenes):
g grass, d dirt, D dark dirt, c clay, s sand, v gravel, ~ water, # road / other, . nothing, T a tree on the square.
Reads only the cell(s) the area touches (never the whole map).

usage: groundmap.py X0 Y0 W H [MAPDIR]
"""
import os, struct, sys

x0, y0, w, h = map(int, sys.argv[1:5])
mapdir = sys.argv[5] if len(sys.argv) > 5 else "/games/steamapps/common/ProjectZomboid/projectzomboid/media/maps/Muldraugh, KY"


def header(path):
    data = open(path, "rb").read()
    pos = 4 if data[:4] == b"LOTH" else 0
    ver, n = struct.unpack_from("<ii", data, pos)
    pos += 8
    tiles = []
    for _ in range(n):
        e = data.index(b"\n", pos)
        tiles.append(data[pos:e].decode("latin-1").strip())
        pos = e + 1
    if ver == 0:
        pos += 1
    pos += 8
    if ver == 0:
        lo, hi = 0, struct.unpack_from("<i", data, pos)[0] - 1
    else:
        lo, hi = struct.unpack_from("<ii", data, pos)
    return tiles, lo, hi


def letter(name):
    if name.startswith("blends_natural_01_"):
        i = int(name.rsplit("_", 1)[1])
        return "s" if i < 16 else "g" if i < 64 else "d" if i < 80 else "D" if i < 96 else "c" if i < 112 else "?"
    if name.startswith("floors_exterior_natural_01_"):
        i = int(name.rsplit("_", 1)[1])
        return "g" if i < 3 else "v" if i == 13 else "d" if 16 <= i <= 19 else "s" if i == 24 else "?"
    if name.startswith("blends_street_01_"):
        i = int(name.rsplit("_", 1)[1])
        return "v" if i in (48, 53, 54, 55) else "#"
    if name.startswith("blends_natural_02"):
        return "~"
    return "#"


grid = {}
for cx in range(x0 // 256, (x0 + w - 1) // 256 + 1):
    for cy in range(y0 // 256, (y0 + h - 1) // 256 + 1):
        hp = os.path.join(mapdir, f"{cx}_{cy}.lotheader")
        if not os.path.exists(hp):
            continue
        tiles, lo, hi = header(hp)
        pk = open(os.path.join(mapdir, f"world_{cx}_{cy}.lotpack"), "rb").read()
        ver = struct.unpack_from("<i", pk, 4)[0] if pk[:4] == b"LOTP" else 0
        base = (8 if ver >= 1 else 0) + 4
        for lwx in range(32):
            for lwy in range(32):
                wx0, wy0 = cx * 256 + lwx * 8, cy * 256 + lwy * 8
                if wx0 + 8 <= x0 or wy0 + 8 <= y0 or wx0 >= x0 + w or wy0 >= y0 + h:
                    continue
                pos = struct.unpack_from("<i", pk, base + (lwx * 32 + lwy) * 8)[0]
                skip = 0
                for z in range(max(lo, -32), min(hi, 31) + 1):
                    for x in range(8):
                        for y in range(8):
                            if skip > 0:
                                skip -= 1
                                continue
                            count = struct.unpack_from("<i", pk, pos)[0]; pos += 4
                            if count == -1:
                                skip = struct.unpack_from("<i", pk, pos)[0]; pos += 4
                                if skip > 0:
                                    skip -= 1
                                continue
                            if count <= 1:
                                continue
                            pos += 4
                            names = []
                            for _ in range(count - 1):
                                d = struct.unpack_from("<i", pk, pos)[0]; pos += 4
                                names.append(tiles[d])
                            if z == 0:
                                ch = letter(names[0])
                                if any(n.startswith(("vegetation_trees", "jumbo_tree", "e_")) and "tree" in n or n.startswith("jumbo_tree") or n.startswith("vegetation_trees") for n in names[1:]):
                                    ch = "T"
                                grid[(wx0 + x, wy0 + y)] = ch
print(f"x {x0}..{x0 + w - 1} (left to right), y {y0}..{y0 + h - 1} (top to bottom)")
for y in range(y0, y0 + h):
    print(f"{y:6d} " + "".join(grid.get((x, y), ".") for x in range(x0, x0 + w)))
