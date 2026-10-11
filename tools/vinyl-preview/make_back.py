"""The back of a sleeve, with the record's sides printed on it: make_back.py <cover.jpg> <out.png>"""
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

cover = Image.open(sys.argv[1]).convert("RGB").resize((1024, 1024), Image.LANCZOS)
# The front's own colours, far out of focus and darkened: a back that belongs to its front.
field = cover.filter(ImageFilter.GaussianBlur(140)).point(lambda v: int(v * 0.42 + 10))
d = ImageDraw.Draw(field)
F = "C:/Windows/Fonts/"
title = ImageFont.truetype(F + "georgiab.ttf", 54)
head = ImageFont.truetype(F + "arialbd.ttf", 22)
track = ImageFont.truetype(F + "georgia.ttf", 34)
small = ImageFont.truetype(F + "arial.ttf", 17)
ink, dim = (236, 228, 212), (176, 168, 152)
d.text((84, 84), "KIND OF BLUE", font=title, fill=ink)
d.text((86, 152), "MILES DAVIS", font=head, fill=dim)
d.line((84, 204, 940, 204), fill=dim, width=2)
sides = [("SIDE A", [("So What", "9:22"), ("Freddie Freeloader", "9:46"), ("Blue in Green", "5:37")]),
         ("SIDE B", [("All Blues", "11:33"), ("Flamenco Sketches", "9:26")])]
y = 250
n = 1
for name, tracks in sides:
    d.text((86, y), name, font=head, fill=dim)
    y += 46
    for t, length in tracks:
        d.text((86, y), f"{n}", font=track, fill=dim)
        d.text((150, y), t, font=track, fill=ink)
        w = d.textlength(length, font=track)
        d.text((940 - w, y), length, font=track, fill=dim)
        y += 64
        n += 1
    y += 34
d.line((84, 800, 940, 800), fill=dim, width=1)
notes = ["Miles Davis, trumpet · Julian \"Cannonball\" Adderley, alto saxophone · John Coltrane, tenor saxophone",
         "Bill Evans and Wynton Kelly, piano · Paul Chambers, bass · Jimmy Cobb, drums",
         "Recorded at Columbia 30th Street Studio, New York City, March 2 and April 22, 1959"]
for i, line in enumerate(notes):
    d.text((86, 826 + i * 30, ), line, font=small, fill=dim)
d.text((86, 950), "STEREO · 33⅓ RPM", font=head, fill=dim)
field.save(sys.argv[2])
