"""Schwenk-Ortung (F12): Simulation und Vergleich der Loesungswege, bevor etwas in die App kommt.

Simuliert einen Schwenk des S24+ um eine Geraeuschquelle in einem Raum mit Hall und rechnet daraus die Richtung mit
mehreren Verfahren. Alles kuenstlich (CLAUDE.md Abschnitt 7). Versioniert nach V7; die App-Rechnung bleibt in Kotlin
(:core:pure, SweepMath). Weltkoordinaten wie beim Android-Drehvektor: x Osten, y Norden, z oben.

Aufruf:  python3 -I tools/schwenk-modell/schwenk_modell.py [--seeds 5] [--szenarien S1,S2] [--fenster 0.05]

Verfahren:
  M1  wie die App heute: kleinste Quadrate, Achse = Geraete-y, kein Zeitversatz
  M2  M1 plus Suche des Zeitversatzes Ton zu Lage
  M3  M2 plus robuste Gewichte (Huber, iterativ)
  M4  Richtungskarte ueber alle Fenster (SRP-PHAT), dann je Fenster die passende GCC-Spitze, dann M3
  M5  wie M4, aber Mikrofonachse im Geraet mitgeschaetzt (Rang-1-Zerlegung)
  M6  wie M4 mit der Achse aus einem Eichschwenk (M5 in Szenario S2, einmal je Geraet)
  M7  S-013: kohaerenzgewichtete GCC (Hannan-Thomson) ueber Bloecke von 250 ms, 80 bis 4000 Hz, dann kleinste Quadrate
  M7r wie M7 mit robusten Gewichten (ohne Versatzsuche, wie die App)

Szenarien S7 und S8 (S-013): zusaetzlich ein Hallfeld (viele ebene Wellen aus zufaelligen Richtungen, Kohaerenz wie
im echten Raum: sinc(k d)), 6 bzw. 12 dB ueber dem direkten Schall. Anlass: zweiter echter Schwenk 10.10., 22:14 Uhr.
"""
import argparse
import time

import numpy as np

C = 343.0
FS = 48_000
PEAK_MIN = 0.1  # wie SweepMath.MIN_PEAK


# ---------------------------------------------------------------- Lage
def rot_x(d):
    a = np.radians(d); c, s = np.cos(a), np.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(d):
    a = np.radians(d); c, s = np.cos(a), np.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rot_z(d):
    a = np.radians(d); c, s = np.cos(a), np.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


# quer gehalten: Geraete-x nach unten, Geraete-y nach Osten, Geraete-z nach Sueden (Kamera, -z, blickt nach Norden)
BASE = np.array([[0.0, 1.0, 0.0], [0.0, 0.0, -1.0], [-1.0, 0.0, 0.0]])


def pose(yaw, pitch, roll):
    """Geraet -> Welt. Gieren nach rechts positiv, Nicken nach oben positiv, Rollen um die Blickrichtung."""
    return rot_z(-yaw) @ rot_x(pitch) @ rot_y(roll) @ BASE


def sweep(t, kind):
    """Lage und Verschiebung des Handys zur Zeit t (s)."""
    if kind == "weit":
        yaw = 50 * np.sin(2 * np.pi * t / 7.0)
        pitch = 25 * np.sin(2 * np.pi * t / 5.3)
        roll = 45 - 45 * np.cos(2 * np.pi * t / 9.0)  # quer bis hochkant
    elif kind == "schmal":
        yaw = 40 * np.sin(2 * np.pi * t / 7.0)
        pitch = 10 * np.sin(2 * np.pi * t / 5.3)
        roll = 10 * np.sin(2 * np.pi * t / 9.0)
    else:
        raise ValueError(kind)
    return pose(yaw, pitch, roll)


# ---------------------------------------------------------------- Raum
def image_sources(src, room, order, beta):
    """Spiegelquellen eines Quaderraums bis [order]; liefert Positionen und Daempfung."""
    out = []
    rng = range(-order, order + 1)
    for nx in rng:
        for ny in rng:
            for nz in rng:
                n = abs(nx) + abs(ny) + abs(nz)
                if n > order:
                    continue
                p = np.empty(3)
                refl = 0
                for k, nk in enumerate((nx, ny, nz)):
                    L = room[k]
                    if nk % 2 == 0:
                        p[k] = nk * L + src[k]
                    else:
                        p[k] = (nk + 1) * L - src[k]
                    refl += abs(nk)
                out.append((p, beta ** refl))
    return out


# ---------------------------------------------------------------- Szenarien
SZENARIEN = {
    # name: (hall, achse_geneigt, versatz_s, verschiebung_m, quelle, schwenk, lage_rauschen_grad)
    "S1 ideal": dict(beta=0.0, tilt=False, offset=0.0, shift=0.0, source="rauschen", sweep="weit", noise_deg=0.0),
    "S2 realistisch": dict(beta=0.7, tilt=True, offset=0.03, shift=0.08, source="rauschen", sweep="weit", noise_deg=0.5),
    "S3 Ton 2 kHz": dict(beta=0.7, tilt=True, offset=0.03, shift=0.08, source="ton", sweep="weit", noise_deg=0.5),
    "S4 zwei Quellen": dict(beta=0.7, tilt=True, offset=0.03, shift=0.08, source="zwei", sweep="weit", noise_deg=0.5),
    "S5 schmaler Schwenk": dict(beta=0.7, tilt=True, offset=0.03, shift=0.08, source="rauschen", sweep="schmal", noise_deg=0.5),
    "S6 leise Quelle": dict(beta=0.7, tilt=True, offset=0.03, shift=0.08, source="rauschen", sweep="weit", noise_deg=0.5, snr_db=-3),
    "S7 Hallfeld 6 dB": dict(beta=0.7, tilt=True, offset=0.03, shift=0.08, source="rauschen", sweep="weit", noise_deg=0.5, diffus_db=6),
    "S8 Hallfeld 12 dB": dict(beta=0.7, tilt=True, offset=0.03, shift=0.08, source="rauschen", sweep="weit", noise_deg=0.5, diffus_db=12),
    # Fehlerbudget: je eine Stoerung allein (sonst wie S1)
    "B1 nur Achse geneigt": dict(beta=0.0, tilt=True, offset=0.0, shift=0.0, source="rauschen", sweep="weit", noise_deg=0.0),
    "B2 nur Verschiebung": dict(beta=0.0, tilt=False, offset=0.0, shift=0.08, source="rauschen", sweep="weit", noise_deg=0.0),
    "B3 nur Hall": dict(beta=0.7, tilt=False, offset=0.0, shift=0.0, source="rauschen", sweep="weit", noise_deg=0.0),
    "B4 nur Versatz 30 ms": dict(beta=0.0, tilt=False, offset=0.03, shift=0.0, source="rauschen", sweep="weit", noise_deg=0.0),
    "B5 nur Lagerauschen": dict(beta=0.0, tilt=False, offset=0.0, shift=0.0, source="rauschen", sweep="weit", noise_deg=0.5),
}

ROOM = np.array([5.0, 4.0, 2.7])
PHONE = np.array([2.5, 1.6, 1.3])
SRC_FRONT = PHONE + 1.8 * np.array([0.0, 1.0, 0.0])            # Norden, Brusthoehe
SRC_RIGHT = PHONE + 1.6 * np.array([np.sin(np.radians(70)), np.cos(np.radians(70)), 0.0])  # 70 Grad rechts

# Mikrofone im Geraet (m): unten vorne und hinten oben bei der Kamera (gedachte Lage, S24+ ungefaehr)
MIC_BOTTOM = np.array([0.0, -0.075, 0.0])
MIC_BACK_TILT = np.array([0.025, 0.075, -0.008])
MIC_BACK_STRAIGHT = np.array([0.0, 0.075, 0.0])
TAU0 = -0.00004  # fester Versatz der Kanaele (S-009: Mitte etwa -0,04 ms)


def simulate(name, seed, seconds=22.0, frame=0.05):
    p = SZENARIEN[name]
    rnd = np.random.default_rng(seed)
    flen = int(frame * FS)
    nfr = int(seconds / frame)
    seg = 8192
    nfft = 2 * seg
    freqs = np.fft.rfftfreq(nfft, 1 / FS)
    back = MIC_BACK_TILT if p["tilt"] else MIC_BACK_STRAIGHT
    sources = [(SRC_FRONT, 1.0)]
    if p["source"] == "zwei":
        sources.append((SRC_RIGHT, 0.7))
    imgs = [(image_sources(s, ROOM, 2, p["beta"]) if p["beta"] > 0 else [(s, 1.0)], g) for s, g in sources]
    frames = []
    poses_true = []
    for k in range(nfr):
        t = (k + 0.5) * frame
        R = sweep(t, p["sweep"])
        shift = p["shift"] * np.array([np.sin(2 * np.pi * t / 4.1), np.sin(2 * np.pi * t / 6.3), 0.3 * np.sin(2 * np.pi * t / 3.7)])
        pos = PHONE + shift
        m0 = pos + R @ MIC_BOTTOM
        m1 = pos + R @ back
        x = [np.zeros(nfft // 2 + 1, complex), np.zeros(nfft // 2 + 1, complex)]
        for (imgset, gain) in imgs:
            if p["source"] == "ton":
                tt = np.arange(seg) / FS
                sig = np.sin(2 * np.pi * 2000 * tt + rnd.uniform(0, 6.28)) + 0.02 * rnd.standard_normal(seg)
            else:
                sig = rnd.standard_normal(seg)
            S = np.fft.rfft(sig, nfft) * gain
            for mi, mpos in enumerate((m0, m1)):
                H = np.zeros_like(freqs, dtype=complex)
                for (ip, a) in imgset:
                    d = np.linalg.norm(ip - mpos)
                    H += a / d * np.exp(-2j * np.pi * freqs * d / C)
                if mi == 0:
                    H *= np.exp(-2j * np.pi * freqs * TAU0)  # Kanal 0 um TAU0 verschoben
                x[mi] += S * H
        sig0 = np.fft.irfft(x[0], nfft)[seg // 2: seg // 2 + flen]
        sig1 = np.fft.irfft(x[1], nfft)[seg // 2: seg // 2 + flen]
        lvl = np.std(sig0) + 1e-12
        if "diffus_db" in p:
            # Hallfeld: 32 ebene Wellen aus zufaelligen Richtungen, je eigenes Rauschen (Kohaerenz sinc(k d))
            d0 = np.zeros(nfft // 2 + 1, complex); d1 = np.zeros(nfft // 2 + 1, complex)
            for _ in range(32):
                v = rnd.standard_normal(3); v /= np.linalg.norm(v)
                S = np.fft.rfft(rnd.standard_normal(seg), nfft)
                d0 += S * np.exp(2j * np.pi * freqs * (v @ m0) / C)
                d1 += S * np.exp(2j * np.pi * freqs * (v @ m1) / C)
            h0 = np.fft.irfft(d0, nfft)[seg // 2: seg // 2 + flen]; h1 = np.fft.irfft(d1, nfft)[seg // 2: seg // 2 + flen]
            g = 10 ** (p["diffus_db"] / 20) * lvl / (np.std(h0) + 1e-12)
            sig0 = sig0 + g * h0; sig1 = sig1 + g * h1
        # Eigenrauschen und Raumgeraeusch, je Kanal unabhaengig; snr_db: Quelle gegen Raum
        nl = 10 ** (-p.get("snr_db", 50) / 20)
        sig0 = sig0 + nl * lvl * rnd.standard_normal(flen)
        sig1 = sig1 + nl * lvl * rnd.standard_normal(flen)
        frames.append((sig0, sig1))
        poses_true.append(R)
    # Lagesensor: um den Versatz verschoben, mit Rauschen; 200 Hz
    sensor_t = np.arange(0, seconds + 0.2, 0.005)
    sensor_R = []
    for ts in sensor_t:
        R = sweep(ts - p["offset"], p["sweep"])  # Sensor-Zeitstempel liegt um offset neben dem Ton
        if p["noise_deg"] > 0:
            n = rnd.normal(0, p["noise_deg"], 3)
            R = rot_z(n[0]) @ rot_x(n[1]) @ rot_y(n[2]) @ R
        sensor_R.append(R)
    u_true = (SRC_FRONT - PHONE) / np.linalg.norm(SRC_FRONT - PHONE)
    return frames, sensor_t, np.array(sensor_R), u_true, frame


# ---------------------------------------------------------------- Laufzeit je Fenster
def gcc(a, b, interp=4, max_lag=0.0012):
    n = 1
    while n < 2 * len(a):
        n *= 2
    A = np.fft.rfft(a - a.mean(), n); B = np.fft.rfft(b - b.mean(), n)
    R = A * np.conj(B)
    R /= np.abs(R) + 1e-20
    cc = np.fft.irfft(R, n * interp) * interp  # gleiche Hoehe wie AudioMath.gccPhat (Spitze 1 bei gleichem Signal)
    m = int(max_lag * FS * interp)
    cc = np.concatenate((cc[-m:], cc[:m + 1]))
    lags = np.arange(-m, m + 1) / (FS * interp)
    return lags, cc


def coherent_block(x0, x1, n=2048, hop=512, lo=80.0, hi=4000.0, up=16, max_lag=0.0012):
    """S-013: Laufzeit eines Blocks mit kohaerenzgewichteter GCC (Hannan-Thomson). Liefert (tau, Guete, Kohaerenz).
    tau positiv = Kanal 0 hoert spaeter (wie gcc). Guete: Hoehe der Spitze relativ zur Summe der Gewichte (0 bis 1).
    Kohaerenz: mittleres gamma^2 im Band."""
    w = np.hanning(n); f = np.fft.rfftfreq(n, 1 / FS); band = (f >= lo) & (f <= hi)
    Sxy = 0; Sxx = 0; Syy = 0
    for t in range(0, len(x0) - n + 1, hop):
        a = np.fft.rfft(x0[t:t + n] * w); b = np.fft.rfft(x1[t:t + n] * w)
        Sxy = Sxy + a * np.conj(b); Sxx = Sxx + np.abs(a) ** 2; Syy = Syy + np.abs(b) ** 2
    g2 = np.clip(np.abs(Sxy) ** 2 / (Sxx * Syy + 1e-30), 0, 0.99)
    W = np.where(band, g2 / ((1 - g2) * (np.abs(Sxy) + 1e-30)), 0.0)
    N = n * up; m = int(max_lag * FS * up)
    G = np.zeros(N // 2 + 1, complex); G[:len(W)] = Sxy * W
    cc = np.fft.irfft(G, N); cc = np.concatenate([cc[-m:], cc[:m + 1]])
    i = int(np.argmax(cc))
    q = cc[i] * N / (np.sum(W * np.abs(Sxy)) * 2 + 1e-30)
    return (i - m) / (FS * up), float(np.clip(q, 0, 1)), float(np.mean(g2[band]))


def coherent_delays(frames, frame, per_block=5, step=2):
    """Bloecke aus [per_block] aufeinanderfolgenden Fenstern, Schritt [step] Fenster."""
    taus, qs, gs, centers = [], [], [], []
    for k in range(0, len(frames) - per_block + 1, step):
        x0 = np.concatenate([frames[j][0] for j in range(k, k + per_block)])
        x1 = np.concatenate([frames[j][1] for j in range(k, k + per_block)])
        t, q, g = coherent_block(x0, x1)
        taus.append(t); qs.append(q); gs.append(g); centers.append((k + per_block / 2) * frame)
    return np.array(taus), np.array(qs), np.array(gs), np.array(centers)


def peaks(lags, cc, top=3):
    idx = [i for i in range(1, len(cc) - 1) if cc[i] >= cc[i - 1] and cc[i] >= cc[i + 1]]
    idx.sort(key=lambda i: -cc[i])
    return [(lags[i], cc[i]) for i in idx[:top]]


# ---------------------------------------------------------------- Verfahren
def pose_at(sensor_t, sensor_R, t):
    i = int(np.clip(np.searchsorted(sensor_t, t), 1, len(sensor_t) - 1))
    w = (t - sensor_t[i - 1]) / (sensor_t[i] - sensor_t[i - 1])
    R = (1 - w) * sensor_R[i - 1] + w * sensor_R[i]
    U, _, Vt = np.linalg.svd(R)
    return U @ Vt


def ls(axes, tau, w=None):
    A = np.hstack([np.ones((len(tau), 1)), axes])
    if w is None:
        w = np.ones(len(tau))
    sw = np.sqrt(w)
    p, *_ = np.linalg.lstsq(A * sw[:, None], tau * sw, rcond=None)
    res = tau - A @ p
    return p, res


def robust_ls(axes, tau, iters=10):
    p, res = ls(axes, tau)
    for _ in range(iters):
        s = 1.4826 * np.median(np.abs(res - np.median(res))) + 1e-9
        k = 1.5 * s
        w = np.where(np.abs(res) <= k, 1.0, k / np.abs(res))
        p, res = ls(axes, tau, w)
    return p, res


def direction(p):
    v = p[1:4]
    return v / np.linalg.norm(v), np.linalg.norm(v) * C


def axes_for(sensor_t, sensor_R, centers, delta, axis_dev):
    return np.array([pose_at(sensor_t, sensor_R, t + delta) @ axis_dev for t in centers])


def search_offset(sensor_t, sensor_R, centers, tau, axis_dev, robust=False):
    best = (np.inf, 0.0)
    for d in np.arange(-0.1, 0.1001, 0.005):
        ax = axes_for(sensor_t, sensor_R, centers, d, axis_dev)
        p, res = (robust_ls if robust else ls)(ax, tau)
        score = np.median(np.abs(res)) if robust else np.mean(res ** 2)
        if score < best[0]:
            best = (score, d)
    d0 = best[1]
    for d in np.arange(d0 - 0.005, d0 + 0.0051, 0.001):
        ax = axes_for(sensor_t, sensor_R, centers, d, axis_dev)
        p, res = (robust_ls if robust else ls)(ax, tau)
        score = np.median(np.abs(res)) if robust else np.mean(res ** 2)
        if score < best[0]:
            best = (score, d)
    return best[1]


def fib_sphere(n):
    i = np.arange(n) + 0.5
    phi = np.arccos(1 - 2 * i / n)
    th = np.pi * (1 + 5 ** 0.5) * i
    return np.stack([np.cos(th) * np.sin(phi), np.sin(th) * np.sin(phi), np.cos(phi)], 1)


def srp(ccs, lags, axes, k=0.15 / C, tau0=0.0, grid=None):
    grid = fib_sphere(4000) if grid is None else grid
    score = np.zeros(len(grid))
    step = lags[1] - lags[0]
    for (cc, a) in zip(ccs, axes):
        tau = tau0 + k * (grid @ a)
        idx = np.clip(np.round((tau - lags[0]) / step).astype(int), 0, len(cc) - 1)
        score += cc[idx]
    return grid[np.argmax(score)], score


def rank1_axis(Rs, tau):
    """tau = tau0 + (1/c) u^T R b, linear in M = u b^T (9 Zahlen); Rang-1-Zerlegung liefert u und b."""
    A = np.hstack([np.ones((len(tau), 1)), Rs.reshape(len(tau), 9)])
    p, *_ = np.linalg.lstsq(A, tau, rcond=None)
    M = p[1:].reshape(3, 3) * C
    U, s, Vt = np.linalg.svd(M)
    u = U[:, 0]; b = Vt[0] * s[0]
    if b[1] < 0:
        u, b = -u, -b
    return u, b, p[0]


def evaluate(name, seed, frame=0.05, calib_axis=None):
    frames, st, sR, u_true, frame = simulate(name, seed, frame=frame)
    centers = (np.arange(len(frames)) + 0.5) * frame
    lagsx = None; ccs = []; tops = []
    for (a, b) in frames:
        lags, cc = gcc(a, b)
        lagsx = lags
        ccs.append(cc); tops.append(peaks(lags, cc))
    best_tau = np.array([t[0][0] for t in tops]); best_pk = np.array([t[0][1] for t in tops])
    keep = best_pk >= PEAK_MIN
    y = np.array([0.0, 1.0, 0.0])
    out = {}
    err = lambda u: float(np.degrees(np.arccos(np.clip(u @ u_true, -1, 1)))) if np.all(np.isfinite(u)) else float("nan")
    # M1
    ax0 = axes_for(st, sR, centers[keep], 0.0, y)
    p, _ = ls(ax0, best_tau[keep]); out["M1"] = err(direction(p)[0])
    # M2
    d = search_offset(st, sR, centers[keep], best_tau[keep], y)
    ax = axes_for(st, sR, centers[keep], d, y)
    p, _ = ls(ax, best_tau[keep]); out["M2"] = err(direction(p)[0])
    # M3
    d3 = search_offset(st, sR, centers[keep], best_tau[keep], y, robust=True)
    ax3 = axes_for(st, sR, centers[keep], d3, y)
    p, _ = robust_ls(ax3, best_tau[keep]); out["M3"] = err(direction(p)[0])
    # M4: Richtungskarte mit dem Versatz aus M3, dann je Fenster die passende Spitze
    def m4(axis_dev, delta):
        axall = axes_for(st, sR, centers, delta, axis_dev)
        u0, _ = srp(ccs, lagsx, axall)
        sel_t, sel_a = [], []
        for t, a in zip(tops, axall):
            pred = 0.15 / C * (a @ u0)
            cand = [c for c in t if c[1] >= 0.05 and abs(c[0] - pred) < 0.00012]
            if cand:
                sel_t.append(cand[0][0]); sel_a.append(a)
        if len(sel_t) < 10:
            return np.array([np.nan] * 3), len(sel_t)
        p, _ = robust_ls(np.array(sel_a), np.array(sel_t))
        return direction(p)[0], len(sel_t)
    u4, n4 = m4(y, d3); out["M4"] = err(u4)
    # M5: Achse mitschaetzen (aus den Fenstern von M3)
    Rs = np.array([pose_at(st, sR, t + d3) for t in centers[keep]])
    u5, b5, _ = rank1_axis(Rs, best_tau[keep])
    out["M5"] = err(u5)
    out["b5"] = b5
    # M6: Richtungskarte mit geeichter Achse
    if calib_axis is not None:
        ca = calib_axis / np.linalg.norm(calib_axis)
        d6 = search_offset(st, sR, centers[keep], best_tau[keep], ca, robust=True)
        u6, _ = m4(ca, d6)
        out["M6"] = err(u6)
        out["versatz6_ms"] = d6 * 1000
    # M7 und M7r (S-013): kohaerenzgewichtete GCC ueber Bloecke von 5 Fenstern (250 ms), Schritt 2 Fenster
    ct, cq, cg, cc_centers = coherent_delays(frames, frame)
    ax7 = axes_for(st, sR, cc_centers, 0.0, y)
    p, _ = ls(ax7, ct); out["M7"] = err(direction(p)[0])
    p, _ = robust_ls(ax7, ct); out["M7r"] = err(direction(p)[0])
    out["kohaerenz"] = float(np.median(cg)); out["guete7"] = float(np.median(cq))
    out["phat_median"] = float(np.median(best_pk))
    out["versatz_ms"] = d3 * 1000
    out["fenster"] = int(keep.sum())
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seeds", type=int, default=5)
    ap.add_argument("--szenarien", default=",".join(SZENARIEN))
    ap.add_argument("--fenster", type=float, default=0.05)
    ap.add_argument("--spitze", type=float, default=0.1, help="Mindesthoehe der GCC-Spitze")
    args = ap.parse_args()
    global PEAK_MIN
    PEAK_MIN = args.spitze
    names = [n for n in SZENARIEN if n.split()[0] in args.szenarien.split(",") or n in args.szenarien.split(",")]
    # Eichschwenk einmal je Geraet: Achse aus S2 (Quelle vorne bekannt), gemittelt ueber die Seeds
    t0 = time.time()
    calib = np.mean([evaluate("S2 realistisch", 100 + s, args.fenster)["b5"] for s in range(3)], axis=0)
    print(f"Geeichte Achse (m): {np.round(calib, 4)}, wahr: {MIC_BACK_TILT - MIC_BOTTOM}")
    methods = ["M1", "M3", "M6", "M7", "M7r"]
    print("Fehler der Richtung in Grad, Median / schlechtester von", args.seeds, "Laeufen")
    print(f"{'Szenario':22s}" + "".join(f"{m:>14s}" for m in methods) + "   PHAT-Median  Kohaerenz  Guete M7")
    for n in names:
        rs = [evaluate(n, s, args.fenster, calib) for s in range(args.seeds)]
        row = f"{n:22s}"
        for m in methods:
            v = np.array([r[m] for r in rs])
            row += f"{np.median(v):8.1f} /{v.max():5.1f}"
        row += f"   {np.median([r['phat_median'] for r in rs]):9.3f}  {np.median([r['kohaerenz'] for r in rs]):9.3f}  {np.median([r['guete7'] for r in rs]):8.3f}"
        print(row, flush=True)
    print(f"Dauer {time.time() - t0:.0f} s")


if __name__ == "__main__":
    main()
