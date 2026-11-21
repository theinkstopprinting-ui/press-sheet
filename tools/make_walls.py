"""Press-themed phone wallpapers for Press Sheet, 1080 x 2400."""
import math
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from scipy.ndimage import gaussian_filter

W, H = 1080, 2400
OUT = sys.argv[1] if len(sys.argv) > 1 else "."
FONTS = sys.argv[2] if len(sys.argv) > 2 else "."
rng = np.random.default_rng(7)

C = np.array([0, 174, 239]) / 255
M = np.array([236, 0, 140]) / 255
Y = np.array([255, 230, 0]) / 255
K = np.array([20, 22, 28]) / 255

yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def halftone(field, cell, angle_deg, gain=0.62):
    """Round dots on a rotated screen; dot size follows `field` (0..1). Returns 0..1 coverage."""
    a = math.radians(angle_deg)
    u = xx * math.cos(a) + yy * math.sin(a)
    v = -xx * math.sin(a) + yy * math.cos(a)
    cu = (np.floor(u / cell) + 0.5) * cell
    cv = (np.floor(v / cell) + 0.5) * cell
    d = np.sqrt((u - cu) ** 2 + (v - cv) ** 2)
    r = np.sqrt(np.clip(field, 0, 1)) * cell * gain
    return 1 - smoothstep(r - 1.0, r + 1.0, d)


def radial(cx, cy, rx, ry):
    return np.clip(1 - np.sqrt(((xx - cx) / rx) ** 2 + ((yy - cy) / ry) ** 2), 0, 1)


def grain(img, amount=0.035, size=1.0):
    n = rng.normal(0, 1, (H, W)).astype(np.float32)
    if size > 1:
        n = gaussian_filter(n, size) * size
    return np.clip(img + n[..., None] * amount, 0, 1)


def vignette(img, strength=0.45):
    v = np.sqrt(((xx - W / 2) / (W * 0.75)) ** 2 + ((yy - H / 2) / (H * 0.7)) ** 2)
    return img * (1 - strength * np.clip(v, 0, 1) ** 2)[..., None]


def over(img, color, alpha):
    a = np.clip(alpha, 0, 1)[..., None]
    return img * (1 - a) + color * a


def screen(img, color, alpha):
    a = np.clip(alpha, 0, 1)[..., None]
    return 1 - (1 - img) * (1 - color * a)


def multiply(img, color, alpha):
    a = np.clip(alpha, 0, 1)[..., None]
    return img * (1 - a + color * a)


def save(img, name):
    arr = (np.clip(img, 0, 1) * 255).astype(np.uint8)
    Image.fromarray(arr).save(f"{OUT}/{name}.jpg", quality=86, optimize=True)
    print("saved", name)


def ground(color):
    return np.ones((H, W, 3), np.float32) * color


def font(name, size):
    try:
        return ImageFont.truetype(f"{FONTS}/{name}", size)
    except Exception:
        return ImageFont.load_default()


def reg_mark(d, cx, cy, r, color, width=4):
    d.ellipse([cx - r * 0.62, cy - r * 0.62, cx + r * 0.62, cy + r * 0.62], outline=color, width=width)
    d.line([cx - r, cy, cx + r, cy], fill=color, width=width)
    d.line([cx, cy - r, cx, cy + r], fill=color, width=width)


# 1 ------------------------------------------------------------------ Halftone glow: CMYK dot screens on black ink
def halftone_glow():
    img = ground(np.array([10, 11, 16]) / 255)
    fc = radial(W * 0.15, H * 0.22, W * 1.1, H * 0.42) * 0.95
    fm = radial(W * 0.95, H * 0.55, W * 1.0, H * 0.40) * 0.95
    fy = radial(W * 0.30, H * 0.95, W * 1.0, H * 0.30) * 0.9
    img = screen(img, C, halftone(fc, 26, 15) * 0.95)
    img = screen(img, M, halftone(fm, 26, 75) * 0.95)
    img = screen(img, Y, halftone(fy, 26, 0) * 0.9)
    img = vignette(img, 0.35)
    save(grain(img, 0.03), "01-halftone-glow")


# 2 ------------------------------------------------------------------ Off register: big CMY shapes printed slightly out of line on paper
def off_register():
    img = ground(np.array([238, 232, 220]) / 255)
    paper = gaussian_filter(rng.normal(0, 1, (H, W)).astype(np.float32), 2.5)
    img = img * (1 + paper[..., None] * 0.04)

    def disc(cx, cy, r):
        d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
        return 1 - smoothstep(r - 2, r + 2, d)

    def bar(y0, h, tilt):
        line = yy - (y0 + (xx - W / 2) * tilt)
        return smoothstep(-2, 2, line) * (1 - smoothstep(h - 2, h + 2, line))

    ink_c = disc(380, 820, 430) * 0.9 + bar(1650, 220, -0.18) * 0.85
    ink_m = disc(380 + 34, 820 + 22, 430) * 0.0 + disc(760, 1280, 360) * 0.88 + bar(1650 + 18, 220, -0.18) * 0.0
    ink_y = disc(700, 520, 300) * 0.9 + bar(1950, 160, 0.12) * 0.9
    ink_m = np.maximum(ink_m, bar(2140, 120, -0.05) * 0.85)
    tex = 0.9 + 0.1 * smoothstep(-1, 1, gaussian_filter(rng.normal(0, 1, (H, W)).astype(np.float32), 1.2))
    img = multiply(img, C, ink_c * tex)
    img = multiply(img, M, ink_m * tex)
    img = multiply(img, Y, ink_y * tex)
    # a halftone shadow under the magenta circle
    img = multiply(img, K, halftone(disc(800, 1330, 360) * 0.5, 18, 45) * 0.8)
    pil = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
    d = ImageDraw.Draw(pil)
    ink = (28, 30, 36)
    for (cx, cy) in [(90, 150), (W - 90, 150), (90, H - 120), (W - 90, H - 120)]:
        reg_mark(d, cx, cy, 34, ink, 3)
    img = np.asarray(pil).astype(np.float32) / 255
    save(grain(img, 0.025), "02-off-register")


# 3 ------------------------------------------------------------------ Press sheet: dark sheet with crop marks, registration marks and a color bar
def press_sheet():
    img = ground(np.array([22, 24, 30]) / 255)
    sheen = radial(W * 0.3, H * 0.15, W * 1.4, H * 0.6) * 0.10
    img = img + sheen[..., None]
    pil = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
    d = ImageDraw.Draw(pil)
    faint = (60, 64, 74)
    for x in range(0, W, 60):
        d.line([x, 0, x, H], fill=(30, 32, 40), width=1)
    for y in range(0, H, 60):
        d.line([0, y, W, y], fill=(30, 32, 40), width=1)
    m = 70
    mark = (200, 204, 214)
    for (x, y, sx, sy) in [(m, m, -1, -1), (W - m, m, 1, -1), (m, H - m, -1, 1), (W - m, H - m, 1, 1)]:
        d.line([x + sx * 10, y, x + sx * 60, y], fill=mark, width=3)
        d.line([x, y + sy * 10, x, y + sy * 60], fill=mark, width=3)
    for (cx, cy) in [(W / 2, 42), (W / 2, H - 42), (34, H / 2), (W - 34, H / 2)]:
        reg_mark(d, cx, cy, 24, mark, 3)
    # color bar along the bottom
    cols = [(0, 174, 239), (236, 0, 140), (255, 230, 0), (20, 22, 28), (0, 174, 239), (236, 0, 140), (255, 230, 0)]
    y0 = H - 150
    x = 130
    for i in range(22):
        c = cols[i % 4] if i < 16 else (int(255 * (i - 15) / 7),) * 3
        tint = 1.0 if i < 4 else [1.0, 0.75, 0.5, 0.25][(i // 4) % 4] if i < 16 else 1.0
        if i < 16:
            c = tuple(int(255 - (255 - v) * tint) if i >= 4 else v for v in c)
        d.rectangle([x, y0, x + 34, y0 + 34], fill=c, outline=(90, 94, 104))
        x += 38
    f = font("DMSans_500Medium.ttf", 22)
    d.text((130, y0 - 34), "THE INK STOP  ·  CRYSTAL LAKE, IL  ·  SHEET 1 OF 1", fill=faint, font=f)
    big = font("BricolageGrotesque_800ExtraBold.ttf", 330)
    layer = Image.new("L", (W, H), 0)
    ImageDraw.Draw(layer).text((40, 1720), "INK", fill=255, font=big)
    layer = layer.filter(ImageFilter.GaussianBlur(0.6))
    arr = np.asarray(pil).astype(np.float32) / 255
    a = np.asarray(layer).astype(np.float32) / 255
    arr = screen(arr, M, a * 0.42)
    arr = screen(arr, C, np.roll(np.roll(a, 10, 0), 8, 1) * 0.12)
    save(grain(arr, 0.02), "03-press-sheet")


# 4 ------------------------------------------------------------------ Ink splash: CMYK ink thrown on black
def ink_splash():
    img = ground(np.array([8, 9, 12]) / 255)

    def splash(cx, cy, r, seed):
        g = np.random.default_rng(seed)
        n = gaussian_filter(g.normal(0, 1, (H // 4, W // 4)).astype(np.float32), 6)
        n = np.array(Image.fromarray(n).resize((W, H), Image.BICUBIC))
        d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / r
        body = smoothstep(0.05, -0.05, d - 1 - n * 3.2)
        drops = np.zeros((H, W), np.float32)
        for _ in range(70):
            ang = g.uniform(0, 2 * math.pi)
            dist = r * g.uniform(1.0, 2.4)
            px, py = cx + math.cos(ang) * dist, cy + math.sin(ang) * dist
            pr = g.uniform(4, 22) * (1.6 - dist / (r * 2.4))
            dd = np.sqrt((xx - px) ** 2 + (yy - py) ** 2)
            drops = np.maximum(drops, 1 - smoothstep(pr - 1.5, pr + 1.5, dd))
        return np.clip(np.maximum(body, drops), 0, 1)

    sc = splash(W * 0.22, H * 0.20, 260, 1)
    sm = splash(W * 0.80, H * 0.52, 300, 2)
    sy = splash(W * 0.35, H * 0.86, 250, 3)
    for col, a in [(C, sc), (M, sm), (Y, sy)]:
        img = over(img, col * 0.92, a)
        hl = gaussian_filter(a, 6) - gaussian_filter(np.roll(a, 8, 0), 6)
        img = img + (np.clip(hl, 0, 1) * 0.35)[..., None] * a[..., None]
    img = vignette(img, 0.3)
    save(grain(img, 0.03), "04-ink-splash")


# 5 ------------------------------------------------------------------ Squeegee: one pull of magenta-to-cyan ink across a screen mesh
def squeegee():
    img = ground(np.array([14, 15, 20]) / 255)
    a = math.radians(-28)
    u = (xx - W / 2) * math.cos(a) + (yy - H * 0.48) * math.sin(a)
    v = -(xx - W / 2) * math.sin(a) + (yy - H * 0.48) * math.cos(a)
    band = smoothstep(-330, -300, v) * (1 - smoothstep(300, 340, v))
    streak = gaussian_filter(rng.normal(0, 1, (H, W)).astype(np.float32), (0.5, 40))
    streak_rot = np.array(Image.fromarray(streak).rotate(28, resample=Image.BICUBIC, expand=False))
    edge = smoothstep(-1600, -900, u) * (1 - smoothstep(900, 1500, u))
    t = np.clip((u + 900) / 1800, 0, 1)
    col = M[None, None, :] * (1 - t[..., None]) + C[None, None, :] * t[..., None]
    alpha = band * edge * np.clip(0.88 + streak_rot * 0.9, 0, 1)
    img = img * (1 - alpha[..., None]) + col * alpha[..., None]
    # yellow ink bead along the leading edge
    bead = smoothstep(260, 300, v) * (1 - smoothstep(300, 345, v)) * edge
    img = over(img, Y, bead * 0.95)
    mesh = (np.sin(xx * 0.9 + yy * 0.9) * np.sin(xx * 0.9 - yy * 0.9))
    img = img * (1 - 0.06 * (mesh > 0.3))[..., None]
    img = vignette(img, 0.3)
    save(grain(img, 0.025), "05-squeegee")


# 6 ------------------------------------------------------------------ Riso dusk: grainy CMY gradient that breaks into halftone
def riso_dusk():
    t = yy / H
    top = np.array([20, 18, 48]) / 255
    mid = np.array([120, 20, 110]) / 255
    low = np.array([235, 90, 60]) / 255
    img = np.where(t[..., None] < 0.55,
                   top * (1 - (t / 0.55)[..., None]) + mid * (t / 0.55)[..., None],
                   mid * (1 - ((t - 0.55) / 0.45)[..., None]) + low * ((t - 0.55) / 0.45)[..., None])
    sun = radial(W * 0.5, H * 0.74, 330, 330)
    img = over(img, Y, halftone(sun * 1.4, 22, 0) * 0.95)
    stars = (rng.random((H, W)) > 0.9993).astype(np.float32) * (1 - t)
    img = img + gaussian_filter(stars, 0.8)[..., None] * 2.5
    hills = yy - (H * 0.83 + np.sin(xx / 140) * 40 + np.sin(xx / 57) * 14)
    img = over(img, np.array([10, 10, 22]) / 255, smoothstep(-2, 2, hills))
    img = screen(img, C, halftone(np.clip((t - 0.86) * 4, 0, 1) * 0.6, 14, 15) * 0.6)
    save(grain(img, 0.05, 1.2), "06-riso-dusk")


for fn in [halftone_glow, off_register, press_sheet, ink_splash, squeegee, riso_dusk]:
    fn()
