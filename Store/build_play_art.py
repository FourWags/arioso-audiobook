"""Draw original Arioso Play artwork without third-party book covers."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = Path(__file__).resolve().parent
S = 2
SERIF = "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf"
SANS = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
INK = (28, 27, 26)
CREAM = (246, 239, 226)
TAN = (216, 180, 140)
ORANGE = (217, 130, 66)


def font(path, size):
    return ImageFont.truetype(path, size * S)


def book(draw, box, fill, ink, angle=0):
    x0, y0, x1, y1 = [int(v * S) for v in box]
    draw.rounded_rectangle((x0, y0, x1, y1), radius=16 * S, fill=fill)
    draw.line((x0 + 18*S, y0 + 23*S, x1 - 18*S, y0 + 23*S), fill=ink, width=3*S)
    draw.line((x0 + 18*S, y1 - 31*S, x1 - 18*S, y1 - 31*S), fill=ink, width=3*S)
    draw.arc((x0 + 35*S, y0 + 62*S, x1 - 35*S, y1 - 62*S), 195 + angle, 345 + angle, fill=ink, width=6*S)


canvas = Image.new("RGB", (1024*S, 500*S), INK)
pixels = canvas.load()
for y in range(500*S):
    for x in range(1024*S):
        t = x / (1024*S)
        v = y / (500*S)
        pixels[x, y] = (int(111 + 66*t + 12*(1-v)), int(72 + 62*t + 10*(1-v)), int(48 + 44*t + 7*(1-v)))
draw = ImageDraw.Draw(canvas)
draw.rounded_rectangle((42*S, 48*S, 982*S, 452*S), radius=30*S, outline=(249, 216, 178), width=3*S)
draw.text((92*S, 89*S), "ARIOSO", font=font(SANS, 25), fill=CREAM, stroke_width=0)
draw.line((92*S, 132*S, 240*S, 132*S), fill=CREAM, width=3*S)
draw.text((90*S, 173*S), "A place for", font=font(SERIF, 58), fill=CREAM)
draw.text((90*S, 245*S), "every story", font=font(SERIF, 58), fill=CREAM)
draw.text((93*S, 372*S), "YOUR AUDIOBOOKS  ·  YOUR SHELF", font=font(SANS, 18), fill=(255, 233, 205))

shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
sd = ImageDraw.Draw(shadow)
sd.rounded_rectangle((622*S, 68*S, 932*S, 430*S), radius=20*S, fill=(20, 15, 13, 100))
canvas = Image.alpha_composite(canvas.convert("RGBA"), shadow.filter(ImageFilter.GaussianBlur(18*S)))
draw = ImageDraw.Draw(canvas)
book(draw, (668, 112, 916, 407), (76, 55, 44), (229, 182, 128), -10)
book(draw, (626, 90, 872, 393), (229, 194, 149), (83, 55, 42), 13)
book(draw, (600, 78, 842, 385), (244, 227, 197), (120, 80, 56), 0)
draw.line((721*S, 157*S, 721*S, 309*S), fill=(132, 84, 55), width=5*S)
draw.arc((631*S, 154*S, 802*S, 317*S), 195, 344, fill=ORANGE, width=9*S)
draw.arc((648*S, 170*S, 790*S, 301*S), 197, 342, fill=ORANGE, width=7*S)
draw.ellipse((705*S, 296*S, 737*S, 328*S), fill=ORANGE)
canvas.convert("RGB").resize((1024, 500), Image.Resampling.LANCZOS).save(OUT / "arioso-feature-graphic.png")

icon = Image.new("RGB", (512*S, 512*S), INK)
d = ImageDraw.Draw(icon)
d.rounded_rectangle((84*S, 95*S, 428*S, 413*S), radius=40*S, fill=TAN)
d.polygon([(145*S, 186*S), (220*S, 169*S), (256*S, 188*S), (292*S, 169*S), (367*S, 186*S), (367*S, 329*S), (292*S, 310*S), (256*S, 329*S), (220*S, 310*S), (145*S, 329*S)], fill=CREAM)
d.line((256*S, 188*S, 256*S, 329*S), fill=INK, width=11*S)
d.arc((276*S, 188*S, 348*S, 262*S), -65, 65, fill=ORANGE, width=18*S)
d.arc((274*S, 153*S, 384*S, 297*S), -62, 62, fill=ORANGE, width=16*S)
icon.resize((512, 512), Image.Resampling.LANCZOS).save(OUT / "arioso-play-icon.png")
print("Wrote original 1024x500 feature graphic and 512x512 icon")
