"""Auswertung einer gespeicherten Nachtserie (Nachtserie-*.zip aus der App, S-011).

Aufruf:  python3 -I tools/nacht-serie/auswerten.py <pfad/Nachtserie-....zip> [--bilder <ordner>]
Die Datei bleibt ausserhalb des Repositorys (CLAUDE.md Abschnitt 7).

Was gemessen wird:
1. Vollstaendigkeit: Zahl der Helligkeitsbilder gegen "verwendet + verworfen" (S-011 K9).
2. Takt: Bilder je Sekunde und Luecken aus den Zeitstempeln der Kamera.
3. Ausrichtung: eigener Versatz je Bild (Phasenkorrelation gegen das Bezugsbild) neben dem Versatz der App.
   Weicht er um mehr als 2 px ab, war die Ausrichtung der App vermutlich falsch.
4. Schaerfe je Bild (Laplace auf der halben Groesse) im Verhaeltnis zum Bezugsbild: Wackeln innerhalb eines Bilds.
5. Drehung aus dem Lagesensor: Kippen um die Blickachse je Bild (Versatz gleicht das nicht aus, gibt Doppelkanten
   zum Rand hin) und Schwenk, dazu der Zusammenhang Schwenk zu gemessenem Versatz.
6. Rand: dunkler Streifen, den das Verschieben am Rand hinterlaesst (groesster Versatz je Seite).
Mit --bilder werden die drei Farbbilder und das Bezugsbild als PNG abgelegt (nur zum Ansehen, nicht ins Repository).
"""
import argparse
import json
import os
import struct
import sys
import zipfile
import zlib

import numpy as np

FORMAT = 1


def read(path):
    """Liest das Archiv; bricht bei unbekannter Formatversion ab (R26)."""
    z = zipfile.ZipFile(path)
    names = set(z.namelist())
    if "meta.json" not in names:
        sys.exit("Keine meta.json im Archiv: keine Nachtserie aus CayResim")
    meta = json.loads(z.read("meta.json").decode("utf-8"))
    if meta.get("format") != FORMAT:
        sys.exit(f"Unbekannte Formatversion {meta.get('format')}, erwartet {FORMAT}")
    w, h = meta["width"], meta["height"]
    ys = sorted(n for n in names if n.startswith("y-") and n.endswith(".bin"))
    luma = {}
    for n in ys:
        b = z.read(n)
        if len(b) != w * h:
            sys.exit(f"{n}: {len(b)} Byte statt {w * h}")
        luma[int(n[2:-4])] = np.frombuffer(b, np.uint8).reshape(h, w)
    rgb = {}
    for n in ("rgb-first.bin", "rgb-mid.bin", "rgb-last.bin"):
        if n in names:
            rgb[n[4:-4]] = np.frombuffer(z.read(n), np.uint8).reshape(h, w, 3)
    lage = []
    if "lage.csv" in names:
        lines = z.read("lage.csv").decode("utf-8").splitlines()
        if not lines or lines[0] != "format=v1":
            print("lage.csv: unbekanntes Format, Lage wird nicht ausgewertet")
        else:
            for line in lines[2:]:
                k, t, a, b_, c, d = line.split(",")
                lage.append((k, int(t), float(a), float(b_), float(c), float(d)))
    return meta, luma, rgb, lage


def half(img):
    """Halbe Groesse (Mittel aus 2 x 2), als float."""
    h, w = img.shape[0] // 2 * 2, img.shape[1] // 2 * 2
    f = img[:h, :w].astype(np.float64)
    return (f[0::2, 0::2] + f[1::2, 0::2] + f[0::2, 1::2] + f[1::2, 1::2]) / 4


def sharpness(img):
    """Varianz des Laplace auf der halben Groesse; nur der innere Teil, damit der Rand nicht zaehlt."""
    f = half(img)
    lap = f[1:-1, 1:-1] * 4 - f[:-2, 1:-1] - f[2:, 1:-1] - f[1:-1, :-2] - f[1:-1, 2:]
    m = lap.shape[0] // 8, lap.shape[1] // 8
    return float(np.var(lap[m[0]:-m[0], m[1]:-m[1]]))


def shift(ref, img):
    """Versatz (dx, dy) in Pixeln, so dass img(x) = ref(x - d): Phasenkorrelation auf der halben Groesse mit Fenster,
    danach Feinsuche +-1 px auf voller Groesse. Liefert ausserdem die Hoehe der Spitze (0 bis 1, Guete)."""
    a, b = half(ref), half(img)
    win = np.outer(np.hanning(a.shape[0]), np.hanning(a.shape[1]))
    fa = np.fft.rfft2((a - a.mean()) * win)
    fb = np.fft.rfft2((b - b.mean()) * win)
    cross = fb * np.conj(fa)
    cross /= np.abs(cross) + 1e-9
    r = np.fft.irfft2(cross, s=a.shape)
    iy, ix = np.unravel_index(np.argmax(r), r.shape)
    peak = float(r[iy, ix])
    dy = iy if iy <= a.shape[0] // 2 else iy - a.shape[0]
    dx = ix if ix <= a.shape[1] // 2 else ix - a.shape[1]
    dx, dy = dx * 2, dy * 2
    # Feinsuche auf voller Groesse im inneren Bereich (mittlerer absoluter Unterschied)
    H, W = ref.shape
    m = max(abs(dx), abs(dy)) + 4
    if m * 4 >= min(H, W):
        return dx, dy, peak
    best = None
    rf = ref.astype(np.int16); im = img.astype(np.int16)
    for ddy in (-1, 0, 1):
        for ddx in (-1, 0, 1):
            sx, sy = dx + ddx, dy + ddy
            e = np.mean(np.abs(im[m:H - m, m:W - m] - rf[m - sy:H - m - sy, m - sx:W - m - sx]))
            if best is None or e < best[0]:
                best = (e, sx, sy)
    return best[1], best[2], peak


def quat_to_R(w, x, y, z):
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)],
        [2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
        [2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)],
    ])


def rotation_at(rot_t, rot_R, t):
    """Lage zum Zeitpunkt t (naechster Wert); None ausserhalb der Messung (mehr als 50 ms daneben)."""
    i = int(np.argmin(np.abs(rot_t - t)))
    return rot_R[i] if abs(rot_t[i] - t) <= 50_000_000 else None


def write_png(path, img):
    """Kleiner PNG-Schreiber ohne Zusatzpakete (Graustufen oder RGB, 8 Bit)."""
    h, w = img.shape[:2]
    color = 2 if img.ndim == 3 else 0
    raw = b"".join(b"\x00" + img[y].tobytes() for y in range(h))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, color, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


def fmt(v, d=1):
    return "-" if v is None else f"{v:.{d}f}".replace(".", ",")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("zip")
    ap.add_argument("--bilder", help="Ordner fuer PNG-Vorschauen")
    a = ap.parse_args()
    meta, luma, rgb, lage = read(a.zip)
    frames = meta["frames"]
    used, dropped = meta["used"], meta["dropped"]
    print(f"Aufnahme {meta['width']} x {meta['height']}, Drehung {meta['rotation']} Grad, "
          f"1/{round(1e9 / meta['exposureNs']) if meta.get('exposureNs') else '?'} s, ISO {meta.get('iso')}, "
          f"Aufhellung x{fmt(meta['gain'])}, Dauer {fmt((meta.get('durationMs') or 0) / 1000)} s")

    # 1. Vollstaendigkeit (K9): Eintraege je Bild = verwendet + verworfen, Dateien = abgelegte Bilder
    archived = [f["index"] for f in frames if f.get("archived")]
    ok = len(frames) == used + dropped and sorted(luma) == archived
    print(f"[1] Eintraege {len(frames)}, verwendet {used} + verworfen {dropped} = {used + dropped}; Helligkeitsbilder "
          f"{len(luma)}, ausgelassen (Speicher kam nicht nach) {len(frames) - len(archived)}: " + ("stimmt" if ok else "STIMMT NICHT"))
    if not any("dx" in f for f in frames):
        print("    WARNUNG: keine Messwerte je Bild in meta.json (dx, Schaerfe fehlen); Vergleich mit der App nicht moeglich")

    # 2. Takt
    ts = np.array([f["timestampNs"] for f in frames if f.get("timestampNs") is not None], dtype=np.int64)
    if len(ts) >= 2:
        dt = np.diff(ts) / 1e6
        fps = (len(ts) - 1) / ((ts[-1] - ts[0]) / 1e9)
        print(f"[2] {fps:.1f} Bilder je Sekunde, Abstand Median {fmt(np.median(dt))} ms, groesster {fmt(dt.max())} ms, "
              f"Luecken ueber 1,5 x Median: {int(np.sum(dt > 1.5 * np.median(dt)))}")
    else:
        print("[2] keine Zeitstempel")

    # 3. und 4. Ausrichtung und Schaerfe gegen das Bezugsbild der App
    ref_i = next((f["index"] for f in frames if f.get("reference")), min(luma))
    if ref_i not in luma:
        print(f"    Bezugsbild {ref_i} der App wurde nicht abgelegt; Vergleich gegen Bild {min(luma)}, eigene Versaetze relativ dazu")
        ref_i = min(luma)
    ref = luma[ref_i]
    s_ref = sharpness(ref)
    print(f"[3] Bezugsbild {ref_i}; je Bild: App-Versatz, eigener Versatz, Spitze, Schaerfe zum Bezug, Kippen")
    rot_t = np.array([r[1] for r in lage if r[0] == "ROTATION"], dtype=np.int64)
    rot_R = np.array([quat_to_R(*r[2:6]) for r in lage if r[0] == "ROTATION"])
    R0 = None
    ref_ts = frames[ref_i].get("timestampNs") if ref_i < len(frames) else None
    if len(rot_t) and ref_ts is not None:
        R0 = rotation_at(rot_t, rot_R, ref_ts)
        if R0 is None:
            print("    Lage und Kamera haben keine gemeinsame Zeit (Zeitbasis verschieden?): kein Kippen")
    rows = []
    for f in frames:
        i = f["index"]
        if i not in luma:
            continue
        own = shift(ref, luma[i]) if i != ref_i else (0, 0, 1.0)
        sh = sharpness(luma[i]) / s_ref if s_ref > 0 else None
        roll = yaw = pitch = None
        if R0 is not None and f.get("timestampNs") is not None:
            Rt = rotation_at(rot_t, rot_R, f["timestampNs"])
            if Rt is not None:
                Rr = R0.T @ Rt  # Drehung im Geraet seit dem Bezugsbild
                roll = float(np.degrees(np.arctan2(Rr[1, 0], Rr[0, 0])))
                yaw = float(np.degrees(np.arcsin(np.clip(Rr[0, 2], -1, 1))))
                pitch = float(np.degrees(np.arcsin(np.clip(-Rr[1, 2], -1, 1))))
        rows.append(dict(i=i, app=(f.get("dx"), f.get("dy")), own=own[:2], peak=own[2], sharp=sh, roll=roll, yaw=yaw,
                         pitch=pitch, dropped=f.get("dropped"), rej=f.get("shiftRejected")))
    bad = 0
    for r in rows:
        app = r["app"]
        diff = None if app[0] is None else max(abs(app[0] - r["own"][0]), abs(app[1] - r["own"][1]))
        flag = []
        if diff is not None and diff > 2:
            flag.append("ABWEICHUNG"); bad += 1
        if r["dropped"]:
            flag.append("verworfen")
        if r["rej"]:
            flag.append("Versatz verworfen")
        print(f"    {r['i']:3d}: App {str(app[0]):>4} {str(app[1]):>4} | eigen {r['own'][0]:4d} {r['own'][1]:4d} | "
              f"Spitze {fmt(r['peak'], 2)} | Schaerfe {fmt(r['sharp'], 2)} | Kippen {fmt(r['roll'])} Grad {' '.join(flag)}")
    print(f"    Ausrichtung abweichend (mehr als 2 px): {bad} von {len(rows)}")
    sharp = np.array([r["sharp"] for r in rows if r["sharp"] is not None])
    if len(sharp):
        print(f"[4] Schaerfe zum Bezug: Median {fmt(np.median(sharp), 2)}, unter 0,5: {int(np.sum(sharp < 0.5))}, "
              f"unter 0,7: {int(np.sum(sharp < 0.7))}")

    # 5. Drehung
    rolls = np.array([r["roll"] for r in rows if r["roll"] is not None])
    if len(rolls):
        half_diag = np.hypot(meta["width"], meta["height"]) / 2
        worst = float(np.max(np.abs(rolls)))
        print(f"[5] Kippen um die Blickachse: bis {fmt(worst, 2)} Grad, an der Bildecke etwa "
              f"{fmt(np.radians(worst) * half_diag)} px, die kein Versatz ausgleicht")
        # Schwenk gegen gemessenen Versatz: Pixel je Grad (zeigt, ob Lage und Bild zusammenpassen)
        sel = [r for r in rows if r["yaw"] is not None and r["peak"] > 0.05]
        if len(sel) >= 5:
            X = np.array([[r["yaw"], r["pitch"]] for r in sel])
            Y = np.array([r["own"] for r in sel], dtype=float)
            coef, *_ = np.linalg.lstsq(np.c_[X, np.ones(len(X))], Y, rcond=None)
            pred = np.c_[X, np.ones(len(X))] @ coef
            res = np.sqrt(np.mean(np.sum((Y - pred) ** 2, 1)))
            print(f"    Versatz aus Schwenk und Nicken erklaert (px je Grad): dx {fmt(coef[0, 0])} / {fmt(coef[1, 0])}, "
                  f"dy {fmt(coef[0, 1])} / {fmt(coef[1, 1])}; Rest {fmt(res)} px")
    else:
        print("[5] keine Lage")

    # 6. Rand
    used_rows = [r for r in rows if not r["dropped"] and r["app"][0] is not None and not r["rej"]]
    if used_rows:
        dxs = [r["app"][0] for r in used_rows]; dys = [r["app"][1] for r in used_rows]
        # Bild mit Versatz d deckt im Bezug nur x in [-d, Breite - d) ab: positives dx laesst rechts eine Luecke
        print(f"[6] Rand ohne alle Bilder (Richtung des Sensorbilds, im Foto um {meta['rotation']} Grad gedreht): "
              f"links {max(0, -min(dxs))} px, rechts {max(0, max(dxs))} px, oben {max(0, -min(dys))} px, unten {max(0, max(dys))} px")

    if a.bilder:
        os.makedirs(a.bilder, exist_ok=True)
        for k, v in rgb.items():
            write_png(os.path.join(a.bilder, f"rgb-{k}.png"), v)
        write_png(os.path.join(a.bilder, f"y-bezug-{ref_i:03d}.png"), ref)
        print(f"Vorschauen in {a.bilder}")


if __name__ == "__main__":
    main()
