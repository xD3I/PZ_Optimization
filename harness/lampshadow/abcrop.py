#!/usr/bin/env python3
"""A/B crops of two --shot-at runs (lamp / ceiling-light shadows): the same crop of shot-game.png from each, an amplified
difference, and the mean darkening inside the crop (B - A luma, so with A = on, B = off a positive number = shadow added).

usage: abcrop.py <run A> <run B> <out prefix> [--box x0,y0,x1,y1] [--shot shot-game.png]
default box: 1000 x 500 around the screen centre (the player)."""
import argparse
import os

from PIL import Image, ImageChops


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("a")
    ap.add_argument("b")
    ap.add_argument("out")
    ap.add_argument("--box", default="")
    ap.add_argument("--shot", default="shot-game.png")
    o = ap.parse_args()
    ims = []
    for r in (o.a, o.b):
        im = Image.open(os.path.join(r, o.shot)).convert("RGB")
        w, h = im.size
        box = tuple(int(v) for v in o.box.split(",")) if o.box else (w // 2 - 500, h // 2 - 260, w // 2 + 500, h // 2 + 240)
        ims.append(im.crop(box))
    ims[0].save(o.out + "-a.png")
    ims[1].save(o.out + "-b.png")
    la, lb = ims[0].convert("L"), ims[1].convert("L")
    d = ImageChops.difference(la, lb)
    d.point(lambda v: min(255, v * 6)).save(o.out + "-diff.png")
    pa, pb = list(la.getdata()), list(lb.getdata())
    darker = [y - x for x, y in zip(pa, pb)]
    n = len(darker)
    print(f"box {box}: mean luma A {sum(pa) / n:.2f} B {sum(pb) / n:.2f}; px darker in A by >= 8: {sum(1 for v in darker if v >= 8)}, "
          f"lighter in A by >= 8: {sum(1 for v in darker if v <= -8)} of {n}")


if __name__ == "__main__":
    main()
