"""Deterministic host assessment of pick-one burst scoring, not a production capture implementation."""

from collections import Counter

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont


def page(seed):
    bitmap = Image.new("L", (640, 480), 220)
    draw = ImageDraw.Draw(bitmap)
    font = ImageFont.load_default(size=23)
    for row in range(12):
        text = f"INVOICE {seed:03d}    ITEM {row:02d}    Quantity 12    Total 4.85"
        draw.text((28 + 7 * (row % 3), 18 + row * 37), text, font=font, fill=35)
    return bitmap


def film(bitmap, rng, contrast=1.0, noise=0.0, shadow=False):
    a = np.asarray(bitmap, dtype=np.float64)
    if shadow:
        a *= np.linspace(.49, 1.0, a.shape[1])
    return Image.fromarray(np.clip(150 + contrast * (a - 150) + rng.normal(0, noise, a.shape), 0, 255).astype(np.uint8))


def motion(bitmap, horizontal):
    a = np.asarray(bitmap, dtype=np.float64)
    axis = 1 if horizontal else 0
    return Image.fromarray(np.mean([np.roll(a, i, axis=axis) for i in range(-4, 5)], axis=0).astype(np.uint8))


def laplacian(bitmap):
    a = np.asarray(bitmap, dtype=np.float64)
    center = a[1:-1, 1:-1]
    return float(np.var(a[:-2, 1:-1] + a[2:, 1:-1] + a[1:-1, :-2] + a[1:-1, 2:] - 4 * center))


def robust_tenengrad(bitmap):
    # The input has the same page scale and in-page ROI in each aligned frame. Do not score a detected page border.
    raw = np.asarray(bitmap, dtype=np.float64)
    diagonal = raw[::2, ::2] - raw[1::2, ::2] - raw[::2, 1::2] + raw[1::2, 1::2]
    noise = np.median(np.abs(diagonal - np.median(diagonal))) / 1.349
    a = np.asarray(bitmap.filter(ImageFilter.GaussianBlur(.75)), dtype=np.float64)
    l, r = a[:, :-2], a[:, 2:]
    dx = (r[:-2] - l[:-2] + 2 * (r[1:-1] - l[1:-1]) + r[2:] - l[2:]) / 8
    t, b = a[:-2, :], a[2:, :]
    dy = (b[:, :-2] - t[:, :-2] + 2 * (b[:, 1:-1] - t[:, 1:-1]) + b[:, 2:] - t[:, 2:]) / 8
    tiles = []
    for y in range(10, dx.shape[0] - 60, 60):
        for x in range(10, dx.shape[1] - 76, 76):
            ys, xs = slice(y, y + 60), slice(x, x + 76)
            tile = a[ys, xs]
            contrast = np.percentile(tile, 95) - np.percentile(tile, 5)
            # Weak support is not evidence. Floor normalization to avoid rewarding weak noise-only texture.
            if contrast < max(18, 3 * noise):
                continue
            power = dx[ys, xs] ** 2 + dy[ys, xs] ** 2
            # Restrict normalization: aggressive division by local range can reward blurred edges for losing contrast.
            tiles.append(float(np.maximum(power - (1.5 * noise) ** 2, 0).mean() / max(90, contrast) ** 2))
    if len(tiles) < 8:
        return None
    return float(np.median(tiles))


def evaluate(seeds, held_out):
    wins = {name: Counter() for name in ("Laplacian", "Robust Tenengrad")}
    unknown = Counter()
    for seed in seeds:
        bitmap = page(seed)
        rng = np.random.default_rng(20261124 + seed)
        blurred = bitmap.filter(ImageFilter.GaussianBlur(2.2 + .2 * (seed % 4) if held_out else 2.6))
        scenarios = {
            "defocus": [film(bitmap, rng), film(blurred, rng)],
            "motion": [film(bitmap, rng), film(motion(bitmap, seed % 2 == 0), rng)],
            "noise": [film(bitmap, rng, .83, 7 if held_out else 6), film(blurred, rng, .83, 19 if held_out else 21)],
            "shadow": [film(bitmap, rng, .67 if held_out else .72, 5, True), film(blurred, rng, .87, 11)],
        }
        for condition, (sharp, soft) in scenarios.items():
            for name, scorer in (("Laplacian", laplacian), ("Robust Tenengrad", robust_tenengrad)):
                left, right = scorer(sharp), scorer(soft)
                if left is None or right is None:
                    unknown[(name, condition)] += 1
                elif left > right:
                    wins[name][condition] += 1
    for name, results in wins.items():
        print("Variant" if held_out else "Calibration", name, " ".join(
            f"{case}={results[case]}/{len(seeds)} (undecided {unknown[name, case]})" for case in scenarios))
    return wins


def main():
    evaluate(range(12), held_out=False)
    wins = evaluate(range(200, 212), held_out=True)
    assert robust_tenengrad(Image.new("L", (640, 480), 120)) is None
    # Shifting the camera can amputate the document while leaving the interior letters just as crisp.
    bitmap = page(99)
    partial = Image.new("L", bitmap.size, 220)
    partial.paste(bitmap, (-100, 0))
    print(f"Framing counterexample: full sharp={robust_tenengrad(bitmap):.6f}; cropped sharp={robust_tenengrad(partial):.6f}")
    assert sum(wins["Robust Tenengrad"].values()) >= 44, "Too many controlled pairs fail to recommend the sharper frame"
    assert wins["Robust Tenengrad"]["noise"] > wins["Laplacian"]["noise"], "Noise control no longer distinguishes the candidates"


if __name__ == "__main__":
    main()
