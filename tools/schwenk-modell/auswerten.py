"""Auswertung einer echten Schwenk-Messung (schwenk-v1.wav aus der App, S-010) mit den Verfahren aus schwenk_modell.py.

Aufruf:  python3 -I tools/schwenk-modell/auswerten.py <pfad/schwenk-v1.wav> [--spitze 0.1]
Die Datei bleibt ausserhalb des Repositorys (CLAUDE.md Abschnitt 7).
"""
import argparse
import os
import struct
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import schwenk_modell as sm  # noqa: E402


def read(path):
    b = open(path, "rb").read()
    o = 12; chunks = {}; fmt = None
    while o + 8 <= len(b):
        cid = b[o:o + 4].decode("latin1"); ln = struct.unpack("<I", b[o + 4:o + 8])[0]
        chunks[cid] = b[o + 8:o + 8 + ln]
        o += 8 + ln + (ln & 1)
    ch, sr = struct.unpack("<HI", chunks["fmt "][2:8])
    pcm = np.frombuffer(chunks["data"], "<i2").reshape(-1, ch).astype(float) / 32768
    lage = chunks["lage"].decode().splitlines()[2:]
    rot, acc = [], []
    for line in lage:
        k, t, a, b_, c, d = line.split(",")
        if k == "ROTATION":
            rot.append((int(t), float(a), float(b_), float(c), float(d)))
        else:
            acc.append((int(t), float(a), float(b_), float(c)))
    import json
    meta = json.loads(chunks["meta"].decode())
    return pcm, sr, np.array(rot), np.array(acc), meta


def quat_to_R(w, x, y, z):
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)],
        [2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
        [2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)],
    ])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("wav")
    ap.add_argument("--spitze", type=float, default=0.1)
    ap.add_argument("--fenster", type=float, default=0.05)
    a = ap.parse_args()
    pcm, sr, rot, acc, meta = read(a.wav)
    start = meta["startBootNanos"]; tap = meta["tapSeconds"]
    st = (rot[:, 0] - start) / 1e9
    sR = np.array([quat_to_R(*r[1:]) for r in rot])
    print(f"Ton {len(pcm) / sr:.1f} s, Lage {len(rot)} Werte von {st[0]:+.2f} bis {st[-1]:+.2f} s, Rate {len(rot) / (st[-1] - st[0]):.0f} Hz")
    # Pegel je Sekunde
    lv = [20 * np.log10(np.sqrt(np.mean(pcm[i * sr:(i + 1) * sr] ** 2, 0)) + 1e-12) for i in range(int(len(pcm) / sr))]
    print("Pegel je Sekunde (dBFS) Kanal 0 / 1:", " ".join(f"{x[0]:.0f}/{x[1]:.0f}" for x in lv))
    # Spektrum grob
    seg = pcm[tap * sr:, :]
    f = np.fft.rfftfreq(8192, 1 / sr)
    spec = np.mean([np.abs(np.fft.rfft(seg[i:i + 8192, 0])) ** 2 for i in range(0, len(seg) - 8192, 8192)], 0)
    bands = [(50, 300), (300, 1000), (1000, 3000), (3000, 8000), (8000, 20000)]
    tot = spec.sum()
    print("Energie je Band:", " ".join(f"{lo}-{hi} Hz {100 * spec[(f >= lo) & (f < hi)].sum() / tot:.0f}%" for lo, hi in bands))
    flen = int(a.fenster * sr)
    n = (len(pcm) - tap * sr) // flen
    centers = tap + (np.arange(n) + 0.5) * a.fenster
    tops = []; ccs = []; lags = None
    for k in range(n):
        s0 = tap * sr + k * flen
        lags, cc = sm.gcc(pcm[s0:s0 + flen, 0], pcm[s0:s0 + flen, 1])
        ccs.append(cc); tops.append(sm.peaks(lags, cc))
    tau = np.array([t[0][0] for t in tops]); pk = np.array([t[0][1] for t in tops])
    print(f"GCC-Spitze: Median {np.median(pk):.3f}, Anteil >= 0,1: {np.mean(pk >= 0.1):.2f}, >= 0,05: {np.mean(pk >= 0.05):.2f}")
    hist, edges = np.histogram(tau * 1000, bins=np.arange(-1.2, 1.25, 0.1))
    print("Laufzeiten (ms) aller Fenster:", " ".join(f"{e:+.1f}:{h}" for e, h in zip(edges[:-1], hist) if h))
    keep = pk >= a.spitze
    print(f"Fenster mit Spitze >= {a.spitze}: {keep.sum()}")
    y = np.array([0.0, 1.0, 0.0])
    ax = sm.axes_for(st, sR, centers[keep], 0.0, y)
    p, res = sm.ls(ax, tau[keep]); u, d = sm.direction(p)
    print(f"M1 kleinste Quadrate: Abstand {d * 100:.1f} cm, Versatz {p[0] * 1000:+.3f} ms, Restfehler {np.std(res) * 1000:.3f} ms")
    p, res = sm.robust_ls(ax, tau[keep]); u, d = sm.direction(p)
    print(f"M3 robust:           Abstand {d * 100:.1f} cm, Versatz {p[0] * 1000:+.3f} ms, Restfehler {np.std(res) * 1000:.3f} ms")
    # Startblick
    R0 = sm.pose_at(st, sR, 1.5)
    view = R0 @ np.array([0, 0, -1.0])

    def rel(u):
        az = np.degrees(np.arctan2(u[0], u[1]) - np.arctan2(view[0], view[1]))
        az = (az + 180) % 360 - 180
        el = np.degrees(np.arcsin(np.clip(u[2], -1, 1)) - np.arcsin(np.clip(view[2], -1, 1)))
        return az, el
    print("M3 Richtung relativ zum Startblick: seitlich %+.0f, Hoehe %+.0f Grad" % rel(u))
    d3 = sm.search_offset(st, sR, centers[keep], tau[keep], y, robust=True)
    ax3 = sm.axes_for(st, sR, centers[keep], d3, y)
    p, res = sm.robust_ls(ax3, tau[keep]); u3, d = sm.direction(p)
    print(f"M3 mit Versatzsuche ({d3 * 1000:+.0f} ms): Abstand {d * 100:.1f} cm, Restfehler {np.std(res) * 1000:.3f} ms, Richtung %+.0f / %+.0f" % rel(u3))
    axall = sm.axes_for(st, sR, centers, d3, y)
    u0, score = sm.srp(ccs, lags, axall)
    print("M4 Richtungskarte: Gipfel %+.0f / %+.0f Grad, Hoehe %.2f, zweitbester Bereich:" % (*rel(u0), score.max() / len(ccs)), end=" ")
    g = sm.fib_sphere(4000)
    far = g @ u0 < np.cos(np.radians(30))
    u1 = g[far][np.argmax(score[far])]
    print("%+.0f / %+.0f Grad, Hoehe %.2f" % (*rel(u1), score[far].max() / len(ccs)))
    Rs = np.array([sm.pose_at(st, sR, t + d3) for t in centers[keep]])
    u5, b5, t0 = sm.rank1_axis(Rs, tau[keep])
    print("M5 Achse mitgeschaetzt: Richtung %+.0f / %+.0f Grad, Achse (cm) " % rel(u5), np.round(b5 * 100, 1))


if __name__ == "__main__":
    main()
