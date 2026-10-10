"""Laufzeit je Block mit kohaerenzgewichteter GCC (Hannan-Thomson) statt GCC-PHAT, fuer echte Schwenk-Dateien (S-013).

Anlass: zweiter echter Schwenk (S24+, 10.10., 22:14 Uhr, Musik in etwa 1 m): GCC-PHAT-Median 0,088, keine Richtung.
Die Kohaerenz der Kanaele entspricht einem Hallfeld (hoch unter 300 Hz, kaum ueber 1 kHz); PHAT gewichtet alle
Frequenzen gleich und ertraenkt den brauchbaren Teil. Rechnung: schwenk_modell.coherent_block (dieselbe wie M7).

Aufruf:  python3 tools/schwenk-modell/kohaerenz.py <schwenk-v1.wav> [Blocklaenge s] [--achse-umkehren]
Vorzeichen wie schwenk_modell.gcc (positiv = Kanal 0 hoert spaeter).
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import auswerten as aw  # noqa: E402
import schwenk_modell as sm  # noqa: E402


def main():
    args = [a for a in sys.argv[2:] if not a.startswith("--")]
    B = float(args[0]) if args else 0.25
    flip = "--achse-umkehren" in sys.argv
    pcm, sr, rot, acc, meta = aw.read(sys.argv[1])
    start = meta["startBootNanos"]; tap = meta["tapSeconds"]
    st = (rot[:, 0] - start) / 1e9
    sR = np.array([aw.quat_to_R(*r[1:]) for r in rot])
    L = int(B * sr); hop = L // 2
    taus, qs, gs, centers = [], [], [], []
    for s in range(tap * sr, len(pcm) - L + 1, hop):
        t, q, g = sm.coherent_block(pcm[s:s + L, 0], pcm[s:s + L, 1])
        taus.append(t); qs.append(q); gs.append(g); centers.append((s + L / 2) / sr)
    taus = np.array(taus); qs = np.array(qs); gs = np.array(gs); centers = np.array(centers)
    R0 = sm.pose_at(st, sR, 1.5); view = R0 @ np.array([0, 0, -1.0])
    y = np.array([0.0, -1.0 if flip else 1.0, 0.0])
    ax = sm.axes_for(st, sR, centers, 0.0, y); proj = ax @ view
    print(f"Bloecke {len(taus)} zu {B} s, Guete Median {np.median(qs):.3f}, Kohaerenz Median {np.median(gs):.3f}")
    print(f"Korrelation Laufzeit zu Achse*Blick {np.corrcoef(proj, taus)[0, 1]:+.2f}, Steigung {np.polyfit(proj, taus, 1)[0] * 1000:+.3f} ms")
    for name, fn in [("M7", sm.ls), ("M7r", sm.robust_ls)]:
        p, res = fn(ax, taus); u, d = sm.direction(p)
        az = np.degrees(np.arctan2(u[0], u[1]) - np.arctan2(view[0], view[1])); az = (az + 180) % 360 - 180
        el = np.degrees(np.arcsin(np.clip(u[2], -1, 1)) - np.arcsin(np.clip(view[2], -1, 1)))
        print(f"{name}: Abstand {d * 100:.1f} cm, Restfehler {np.std(res) * 1000:.3f} ms, Richtung seitlich {az:+.0f}, Hoehe {el:+.0f}")


if __name__ == "__main__":
    main()
