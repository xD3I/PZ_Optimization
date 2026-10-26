#!/usr/bin/env python3
"""Torch shadows loop: a labelled montage of crops of captured frames. montage.py out.png x0,y0,x1,y1 scale file:label ..."""
import sys
from PIL import Image, ImageDraw
out, box, scale = sys.argv[1], [int(v) for v in sys.argv[2].split(",")], float(sys.argv[3])
items = [a.rsplit(":", 1) for a in sys.argv[4:]]
tiles = []
for path, label in items:
    im = Image.open(path).convert("RGB").crop(box)
    im = im.resize((int(im.width * scale), int(im.height * scale)), Image.LANCZOS)
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 8 * len(label) + 10, 18], fill=(0, 0, 0))
    d.text((5, 3), label, fill=(255, 255, 0))
    tiles.append(im)
cols = 3 if len(tiles) > 4 else len(tiles)
rows = (len(tiles) + cols - 1) // cols
W, H = tiles[0].size
m = Image.new("RGB", (W * cols, H * rows))
for i, t in enumerate(tiles):
    m.paste(t, ((i % cols) * W, (i // cols) * H))
m.save(out)
print(out, m.size)
