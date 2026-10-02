"""
The device over the window, graded as the pocket room grades it.

    python grade.py <window.f32> <device.npy> <out.png>

In linear light, then Filament's chain as `PocketAtmosphere` sets it up: bloom over the HDR
threshold only (strength 0.085, added), the tonemapper Filament uses when nothing sets one
(ACES, Narkowicz's fit), a light vignette, sRGB. Approximations of Filament's own passes — close
enough to judge a composition and a palette, not to settle a luminance argument; that is still
the phone's (`tools/emulator/README.md`).
"""
import sys
import numpy as np
from PIL import Image

BLOOM_STRENGTH = 0.085


def blur(img, sigma):
    """Three box passes, about a gaussian of [sigma]."""
    r = max(1, int(sigma * 0.85))
    out = img
    for _ in range(3):
        c = np.cumsum(np.pad(out, ((r + 1, r), (0, 0), (0, 0)), mode="edge"), axis=0)
        out = (c[2 * r + 1:] - c[:-2 * r - 1]) / (2 * r + 1)
        c = np.cumsum(np.pad(out, ((0, 0), (r + 1, r), (0, 0)), mode="edge"), axis=1)
        out = (c[:, 2 * r + 1:] - c[:, :-2 * r - 1]) / (2 * r + 1)
    return out


def aces(x):
    return np.clip((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0, 1)


def srgb(x):
    return np.where(x <= 0.0031308, 12.92 * x, 1.055 * np.power(np.maximum(x, 0), 1 / 2.4) - 0.055)


def grade(lin, vig_mid=0.68):
    h, w, _ = lin.shape
    bright = np.maximum(lin - 1.0, 0.0)
    halo = sum(blur(bright, s * w / 720.0) for s in (2, 4, 8, 16, 32, 64)) / 6.0
    lin = lin + BLOOM_STRENGTH * halo * 3.0
    yy, xx = np.mgrid[0:h, 0:w]
    u = (xx + 0.5) / w - 0.5
    v = ((yy + 0.5) / h - 0.5) * (h / w) ** 0.5
    r = np.sqrt(u * u + v * v) / 0.5
    vig = 1.0 - 0.55 * np.clip((r - vig_mid) / (1.6 - vig_mid), 0, 1) ** 1.6
    return srgb(aces(lin * vig[..., None]))


if __name__ == "__main__":
    win, dev, out = sys.argv[1:4]
    d = np.load(dev)
    h, w = d.shape[:2]
    bg = np.fromfile(win, dtype=np.float32).reshape(h, w, 4)[::-1, :, :3]
    lin = d[..., :3] + bg * (1 - d[..., 3:4])
    Image.fromarray((grade(lin) * 255 + 0.5).astype(np.uint8)).save(out)
    print("graded", out)
