"""Laufzeit je Block mit kohaerenzgewichteter GCC (Hannan-Thomson / ML) statt GCC-PHAT, fuer echte Schwenk-Dateien.

Anlass: zweiter echter Schwenk (S24+, 10.10., 22:14 Uhr, Musik in etwa 1 m): GCC-PHAT-Median 0,088, keine Richtung.
Die Kohaerenz der Kanaele entspricht einem Hallfeld (hoch unter 300 Hz, kaum ueber 1 kHz); PHAT gewichtet alle
Frequenzen gleich und ertraenkt den brauchbaren Teil. Hier: Kreuzspektrum ueber Bloecke von [B] s gemittelt, Gewicht
gamma^2 / ((1 - gamma^2) |Sxy|), Band 80 bis 4000 Hz.

Aufruf:  python3 tools/schwenk-modell/kohaerenz.py <schwenk-v1.wav> [Blocklaenge s] [--achse-umkehren]
Vorzeichen wie schwenk_modell.gcc (positiv = Kanal 0 hoert spaeter).
"""
import sys
sys.path.insert(0, "tools/schwenk-modell")
import numpy as np, schwenk_modell as sm, auswerten as aw
pcm, sr, rot, acc, meta = aw.read(sys.argv[1])
start = meta["startBootNanos"]; tap = meta["tapSeconds"]
st = (rot[:, 0] - start) / 1e9
sR = np.array([aw.quat_to_R(*r[1:]) for r in rot])
args = [a for a in sys.argv[2:] if not a.startswith("--")]
B = float(args[0]) if args else 0.25
flip = "--achse-umkehren" in sys.argv
n = 2048; hop = 512; w = np.hanning(n); f = np.fft.rfftfreq(n, 1 / sr)
band = (f >= 80) & (f <= 4000)
up = 16; N = n * up; maxlag = int(0.0012 * sr * up)
taus = []; qual = []; centers = []
x = pcm
for s in range(tap * sr, len(x) - int(B * sr), int(B * sr / 2)):
    Sxy = 0; Sxx = 0; Syy = 0
    for t in range(s, s + int(B * sr) - n, hop):
        a = np.fft.rfft(x[t:t + n, 0] * w); b = np.fft.rfft(x[t:t + n, 1] * w)
        Sxy = Sxy + a * np.conj(b); Sxx = Sxx + abs(a) ** 2; Syy = Syy + abs(b) ** 2
    g2 = np.clip(abs(Sxy) ** 2 / (Sxx * Syy + 1e-30), 0, 0.99)
    W = np.where(band, g2 / ((1 - g2) * (abs(Sxy) + 1e-30)), 0.0)
    G = np.zeros(N // 2 + 1, complex); G[:len(W)] = Sxy * W
    cc = np.fft.irfft(G, N); cc = np.concatenate([cc[-maxlag:], cc[:maxlag + 1]])
    i = np.argmax(cc)
    taus.append((i - maxlag) / (sr * up)); qual.append(cc[i] / (np.sum(W * abs(Sxy)) + 1e-30)); centers.append((s - tap * sr) / sr + B / 2 + tap)
taus = np.array(taus); qual = np.array(qual); centers = np.array(centers)
R0 = sm.pose_at(st, sR, 1.5); view = R0 @ np.array([0, 0, -1.0])
y = np.array([0.0, -1.0 if flip else 1.0, 0.0]); ax = sm.axes_for(st, sR, centers, 0.0, y); proj = ax @ view
print(f"Bloecke {len(taus)}, Korrelation tau zu Achse*Blick {np.corrcoef(proj, taus)[0,1]:+.2f}, Steigung {np.polyfit(proj, taus, 1)[0]*1000:+.3f} ms")
for name, fn in [("M1", sm.ls), ("M3", sm.robust_ls)]:
    p, res = fn(ax, taus); u, d = sm.direction(p)
    az = np.degrees(np.arctan2(u[0], u[1]) - np.arctan2(view[0], view[1])); az = (az + 180) % 360 - 180
    el = np.degrees(np.arcsin(np.clip(u[2], -1, 1)) - np.arcsin(np.clip(view[2], -1, 1)))
    print(f"{name}: Abstand {d*100:.1f} cm, Restfehler {np.std(res)*1000:.3f} ms, Richtung seitlich {az:+.0f}, Hoehe {el:+.0f}")
print("Laufzeiten ms Quantile:", np.round(np.quantile(taus * 1000, [0.05, 0.25, 0.5, 0.75, 0.95]), 2))
