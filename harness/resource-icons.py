#!/usr/bin/env python3
"""The PZ OPTIMIZATION screen's resource icons (2026-10-23): one white pictogram per impact-chart resource (the
options file's AXES ids), drawn on a transparent 48 px square and tinted by the menu (drawTextureScaled r, g, b).
The settings list shows the icon of the resource a setting moves most; the card puts each icon before its resource.

    python3 harness/resource-icons.py            # -> src/media/ui/pzopt/icons/<axis>.png
"""
import os
import sys

from PIL import Image, ImageDraw

SIZE = 48
SS = 8  # supersampling: drawn at 384 px, reduced with Lanczos
U = SIZE * SS / 24.0  # one unit of the 24-unit design grid
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "media", "ui", "pzopt", "icons")


def box(x0, y0, x1, y1):
    return [x0 * U, y0 * U, x1 * U - 1, y1 * U - 1]


def rect(d, x0, y0, x1, y1, fill=255):
    d.rectangle(box(x0, y0, x1, y1), fill=fill)


def frame(d, x0, y0, x1, y1, w=2, r=1.5):
    d.rounded_rectangle(box(x0, y0, x1, y1), radius=r * U, fill=255)
    d.rounded_rectangle(box(x0 + w, y0 + w, x1 - w, y1 - w), radius=max(0, r - w / 2) * U, fill=0)


def chip(d, x0, y0, x1, y1, pins=3, pin=2.5, core=True):
    """a processor: a square body, pins on every side, a solid core"""
    frame(d, x0 + pin, y0 + pin, x1 - pin, y1 - pin, w=2, r=1)
    if core:
        m = (x1 - x0) * 0.36
        rect(d, x0 + m, y0 + m, x1 - m, y1 - m)
    span = (x1 - x0) - 2 * pin
    for i in range(pins):
        c = x0 + pin + span * (i + 1) / (pins + 1)
        rect(d, c - 0.75, y0, c + 0.75, y0 + pin)
        rect(d, c - 0.75, y1 - pin, c + 0.75, y1)
        rect(d, x0, c - 0.75, x0 + pin, c + 0.75)
        rect(d, x1 - pin, c - 0.75, x1, c + 0.75)


def cpu(d):  # the game thread: one processor
    chip(d, 1, 1, 23, 23)


def render(d):  # the render thread, which feeds the screen: a monitor on its stand
    frame(d, 1, 3, 23, 18, w=2, r=1.5)
    rect(d, 5, 7, 19, 14, fill=255)
    rect(d, 10.5, 18, 13.5, 21)
    rect(d, 6, 20.5, 18, 22.5)


def cores(d):  # the other cores: four small processors
    for x, y in ((1, 1), (13, 1), (1, 13), (13, 13)):
        frame(d, x + 0.5, y + 0.5, x + 9.5, y + 9.5, w=2, r=1)
        rect(d, x + 3.5, y + 3.5, x + 6.5, y + 6.5)


def gpu(d):  # a graphics card: the board, its fan, the bracket and the slot pins
    rect(d, 0.5, 3, 2.5, 21)  # bracket
    frame(d, 2.5, 5, 23.5, 18, w=2, r=1.5)
    d.ellipse(box(9, 6.7, 20.6, 16.3), fill=255)
    d.ellipse(box(12.1, 9.8, 17.5, 15.2), fill=0)
    d.ellipse(box(13.9, 11.6, 15.7, 13.4), fill=255)
    for x in (6, 9, 12, 15, 18):
        rect(d, x, 18, x + 1.6, 20.5)


def vram(d):  # the graphics card's memory: the same board with memory chips instead of the fan
    rect(d, 0.5, 3, 2.5, 21)
    frame(d, 2.5, 5, 23.5, 18, w=2, r=1.5)
    for x in (6.5, 12, 17.5):
        rect(d, x - 0.2, 8, x + 3.2, 15)
    for x in (6, 9, 12, 15, 18):
        rect(d, x, 18, x + 1.6, 20.5)


def ram(d):  # a memory stick: long board, chips, the notched row of contacts
    frame(d, 1, 6, 23, 16, w=2, r=1)
    for x in (4.5, 9.5, 14.5):
        rect(d, x, 8.5, x + 3.5, 13.5)
    rect(d, 19.5, 8.5, 20.5, 13.5)
    for x in (2.5, 5, 7.5, 10, 14.5, 17, 19.5):
        rect(d, x, 16, x + 1.5, 19)


def disk(d):  # the disk: an M.2 SSD drawn on the diagonal (level, it read as the RAM stick): the gold-finger edge,
    # the board with its controller and flash chips, the screw's half-moon at the far end
    layer = Image.new("L", d._image.size, 0)
    m = ImageDraw.Draw(layer)
    y0, y1 = 7.2, 16.8
    frame(m, 4.6, y0, 26.6, y1, w=1.8, r=0.8)
    m.ellipse(box(23.8, 10.0, 28.2, 14.0), fill=0)
    m.arc(box(23.8, 10.0, 28.2, 14.0), 90, 270, fill=255, width=int(1.8 * U))
    for y in (7.8, 10.2, 13.0, 15.4):  # the contacts, split by the key notch
        rect(m, 0.8, y, 4.6, y + 1.1)
    rect(m, 7.8, 9.9, 11.6, 14.1)  # controller
    rect(m, 13.6, 9.9, 21.4, 14.1)  # flash
    layer = layer.crop((0, 0, int(28 * U), int(24 * U))).resize(layer.size)  # (designed 28 units long, fitted to 24)
    layer = layer.rotate(38, resample=Image.BICUBIC, center=(12 * U, 12 * U))
    d._image.paste(255, (0, 0), layer)


def load(d):  # boot and load time: an hourglass
    rect(d, 4, 1, 20, 3)
    rect(d, 4, 21, 20, 23)
    d.polygon([(6 * U, 3 * U), (18 * U, 3 * U), (12.9 * U, 12 * U), (18 * U, 21 * U), (6 * U, 21 * U), (11.1 * U, 12 * U)],
              fill=255)
    d.polygon([(8.6 * U, 4.8 * U), (15.4 * U, 4.8 * U), (12 * U, 10.6 * U)], fill=0)
    d.polygon([(12 * U, 13.4 * U), (16 * U, 19.2 * U), (8 * U, 19.2 * U)], fill=0)
    d.polygon([(10 * U, 19.2 * U), (14 * U, 19.2 * U), (12 * U, 16.2 * U)], fill=255)  # the sand that fell


def chunks(d):  # chunk arrival: the game's isometric map tiles, one of them arriving (solid)
    def diamond(cx, cy, hw, hh, fill):
        d.polygon([(cx * U, (cy - hh) * U), ((cx + hw) * U, cy * U), (cx * U, (cy + hh) * U), ((cx - hw) * U, cy * U)],
                  fill=fill)
    # four tiles of a 2x2 grid as outlines, the front one solid
    for cx, cy, solid in ((12, 6.5, False), (6.5, 12, False), (17.5, 12, False), (12, 17.5, True)):
        diamond(cx, cy, 5.6, 5.6, 255)
        if not solid:
            diamond(cx, cy, 3.2, 3.2, 0)


ICONS = {"cpu": cpu, "render": render, "cores": cores, "gpu": gpu, "vram": vram, "ram": ram, "disk": disk,
         "load": load, "chunks": chunks}


def main():
    os.makedirs(OUT, exist_ok=True)
    sheet = Image.new("L", (SIZE * len(ICONS) + 8 * (len(ICONS) + 1), SIZE + 16), 0)
    for i, (name, fn) in enumerate(ICONS.items()):
        mask = Image.new("L", (SIZE * SS, SIZE * SS), 0)
        fn(ImageDraw.Draw(mask))
        mask = mask.resize((SIZE, SIZE), Image.LANCZOS)
        img = Image.new("RGBA", (SIZE, SIZE), (255, 255, 255, 0))
        img.putalpha(mask)
        img.save(os.path.join(OUT, name + ".png"), optimize=True)
        sheet.paste(mask, (8 + i * (SIZE + 8), 8))
    if len(sys.argv) > 1:  # a contact sheet to look at: python3 harness/resource-icons.py /path/sheet.png
        sheet.save(sys.argv[1])
    print("wrote %d icons to %s" % (len(ICONS), os.path.normpath(OUT)))


if __name__ == "__main__":
    main()
