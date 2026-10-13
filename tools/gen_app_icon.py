"""生成 Tessera 品牌图标（PNG + 多尺寸 ICO）；几何与 Android 自适应图标一致。"""
from PIL import Image, ImageDraw
from pathlib import Path

BG = (43, 58, 143, 255)
OUT = Path(__file__).resolve().parent.parent / "branding"

def build(size: int, rounded: bool) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    radius = int(112 / 512 * size) if rounded else 0
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=BG)
    s = size / 108.0
    d.rounded_rectangle([29 * s, 33 * s, 79 * s, 48 * s], radius=7.5 * s, fill=(255, 255, 255, 255))
    d.rounded_rectangle([46.5 * s, 33 * s, 61.5 * s, 76 * s], radius=7.5 * s, fill=(255, 255, 255, 255))
    return img

OUT.mkdir(parents=True, exist_ok=True)
build(512, rounded=True).save(OUT / "tessera-icon.png")
build(512, rounded=False).save(OUT / "tessera-icon-square.png")
build(256, rounded=False).save(OUT / "tessera.ico", sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
build(512, rounded=True).resize((192, 192), Image.LANCZOS).save(OUT / "tessera-round-192.png")
print("WROTE:", OUT)
for f in sorted(OUT.iterdir()):
    print("  ", f.name, f.stat().st_size, "bytes")
