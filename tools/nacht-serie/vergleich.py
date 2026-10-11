"""Vergleich zweier Fotos derselben Szene (CayResim gegen Samsung) mit festen Messgroessen (CLAUDE.md Abschnitt 5).

Aufruf:  python3 -I tools/nacht-serie/vergleich.py <cayresim.jpg> <samsung.jpg> [weitere ...]
Die Fotos bleiben ausserhalb des Repositorys (CLAUDE.md Abschnitt 7).

Beide Bilder werden auf 1440 px lange Seite gebracht. Messgroessen (8-Bit-Werte des JPEG):
- Helligkeit: Mittel der Luma (0,299 R + 0,587 G + 0,114 B)
- dunkelste / hellste 1 %: 1. und 99. Perzentil der Luma (Schwarzpunkt, Lichter)
- Saettigung: Mittel von (max - min) / max ueber Pixel mit max > 20, in Prozent
- Korn: Streuung des Hochpasses (Pixel minus 3 x 3 Mittel) in dunklen glatten Flaechen (3 x 3 Mittel 5 bis 60)
- Kanten: Mittel des Betrags der Laplace-Antwort; hohes Korn hebt diesen Wert ebenfalls, deshalb nur zusammen mit
  Korn lesen
- Farbstich: Mittel je Kanal R / G / B in den dunkelsten 20 % (Schwarz sollte neutral sein)
"""
import sys

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view
from PIL import Image


def measure(path):
    img = Image.open(path).convert("RGB")
    s = 1440 / max(img.size)
    img = img.resize((round(img.width * s), round(img.height * s)), Image.BILINEAR)
    im = np.asarray(img).astype(float)
    y = 0.299 * im[..., 0] + 0.587 * im[..., 1] + 0.114 * im[..., 2]
    mx = im.max(2); mn = im.min(2)
    sat = np.where(mx > 20, (mx - mn) / np.maximum(mx, 1), np.nan)
    lap = y[1:-1, 1:-1] * 4 - y[:-2, 1:-1] - y[2:, 1:-1] - y[1:-1, :-2] - y[1:-1, 2:]
    blur = sliding_window_view(y, (3, 3)).mean((-1, -2))
    hp = y[1:-1, 1:-1] - blur
    flat = (blur > 5) & (blur < 60)
    dark = y <= np.percentile(y, 20)
    rgb = [im[..., c][dark].mean() for c in range(3)]
    return dict(hell=y.mean(), p1=np.percentile(y, 1), p99=np.percentile(y, 99), sat=np.nanmean(sat) * 100,
                korn=hp[flat].std() if flat.any() else float("nan"), kanten=np.abs(lap).mean(), stich=rgb)


def fmt(v):
    return f"{v:.1f}".replace(".", ",")


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    rows = [(p.split("/")[-1], measure(p)) for p in sys.argv[1:]]
    print(f"{'Datei':28s} {'Helligkeit':>10s} {'1 %':>6s} {'99 %':>6s} {'Saettigung':>11s} {'Korn':>6s} {'Kanten':>7s}  Schwarz R / G / B")
    for name, m in rows:
        print(f"{name[:28]:28s} {fmt(m['hell']):>10s} {fmt(m['p1']):>6s} {fmt(m['p99']):>6s} {fmt(m['sat']) + ' %':>11s} "
              f"{fmt(m['korn']):>6s} {fmt(m['kanten']):>7s}  {' / '.join(fmt(c) for c in m['stich'])}")


if __name__ == "__main__":
    main()
