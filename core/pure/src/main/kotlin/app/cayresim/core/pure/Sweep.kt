package app.cayresim.core.pure

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/** Dreidimensionaler Vektor fuer Richtungen und Achsen (S-010). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun norm() = sqrt(dot(this))
    fun normalized(): Vec3 { val n = norm(); return if (n > 0) this * (1 / n) else this }
}

/**
 * Schwenk-Messung (S-010): Aus vielen kurzen Laufzeit-Messungen zweier Mikrofone und der Lage des Handys je Moment
 * wird die Richtung einer festen Geraeuschquelle geschaetzt. Jede Messung legt die Quelle auf einen Kegel um die
 * Mikrofonachse; beim Drehen schneiden sich die Kegel nur in der Richtung der Quelle.
 *
 * Modell je Fenster: tau = tau0 + k * (a . u), a = Mikrofonachse in Weltkoordinaten, u = Richtung der Quelle,
 * k = wirksamer Abstand / Schallgeschwindigkeit. Linear in (tau0, k*u), geloest als Ausgleichsrechnung.
 * Weltkoordinaten wie beim Android-Drehvektor: x Osten, y Norden, z oben.
 */
object SweepMath {
    /** Laenge eines Messfensters. */
    const val FRAME_SECONDS = 0.05
    /** Mindestzahl gueltiger Fenster fuer eine Richtung. */
    const val MIN_FRAMES = 50
    /** Mindest-Abdeckung (0 bis 1) der Achsrichtungen; darunter ist die Richtung nicht eindeutig. */
    const val MIN_COVERAGE = 0.02
    /** Mindesthoehe der GCC-PHAT-Spitze; darunter hat das Fenster keine klare Quelle. */
    const val MIN_PEAK = 0.1
    /**
     * S-012: Mindest-Median der Spitze ueber alle Fenster. Darunter ist die Quelle zu leise ("Signal zu schwach"):
     * S24+ am 10.10. mit 0,074 unbrauchbar, Modell mit Hall und geneigter Achse ab 0,14 sicher.
     */
    const val MIN_MEDIAN_PEAK = 0.10
    /** Groesste gesuchte Laufzeit: 41 cm Abstand, genug fuer jedes Handy. */
    const val MAX_LAG_SECONDS = 0.0012
    const val MIN_SPACING = 0.03
    const val MAX_SPACING = 0.30
    /** Klopfer: Abweichung der Beschleunigung vom Median in m/s^2. */
    const val TAP_ACCEL = 3.0
    const val TAP_ABOVE_FLOOR_DB = 12.0
    const val TAP_RISE_DB = 10.0
    /** Groesster erlaubter Abstand zwischen Klopfer im Sensor und im Ton. */
    const val TAP_PAIR_SECONDS = 0.15
    /** Wie weit eine Lage vom gesuchten Zeitpunkt entfernt sein darf. */
    const val MAX_POSE_GAP_NANOS = 100_000_000L

    /**
     * Mikrofonachse im Geraet: von unten nach oben (y). Kanal 0 hoert spaeter, wenn die Quelle oben liegt, falls
     * Kanal 0 das untere Mikrofon ist. Das Vorzeichen wird im Geraetetest festgestellt (S-010 K9).
     */
    val MIC_AXIS_DEVICE = Vec3(0.0, 1.0, 0.0)

    /** Lage als Einheitsquaternion (w, x, y, z); dreht Geraetekoordinaten in Weltkoordinaten. Zeit in ns seit Start. */
    data class Pose(val nanos: Long, val w: Double, val x: Double, val y: Double, val z: Double)

    /** Betrag der Beschleunigung in m/s^2 (mit Schwerkraft). */
    data class AccelSample(val nanos: Long, val magnitude: Double)

    data class FrameDelay(val centerSeconds: Double, val delaySeconds: Double, val peak: Double)

    data class Measurement(val axis: Vec3, val delaySeconds: Double)

    /**
     * Gruende ohne Richtung (Zweitpruefung S-010, W2: jede Ursache eigen, damit der Hinweis stimmt):
     * NO_STEREO nur ein Kanal; TOO_FEW_MEASUREMENTS zu wenige Fenster mit klarem Signal; NO_POSE Fenster da, aber keine
     * Lage zur Tonzeit (Zeitbezug falsch oder Sensor-Luecke); ONE_SIDED zu einseitig gedreht; IMPLAUSIBLE Abstand unsinnig;
     * WEAK_SIGNAL (S-012) das typische Fenster hat keine klare Spitze, Quelle zu leise.
     */
    enum class SweepFailure { NO_STEREO, TOO_FEW_MEASUREMENTS, NO_POSE, ONE_SIDED, IMPLAUSIBLE, WEAK_SIGNAL }

    sealed interface Estimate {
        val coverage: Double
        val used: Int
        data class Ok(
            val direction: Vec3,
            val spacingMeters: Double,
            val offsetSeconds: Double,
            val residualSeconds: Double,
            override val coverage: Double,
            override val used: Int,
        ) : Estimate
        data class Failed(val reason: SweepFailure, override val coverage: Double, override val used: Int) : Estimate
    }

    /** Ergebnis eines Schwenks; Winkel relativ zur Blickrichtung der Kamera bei Beginn des Schwenks. */
    data class SweepReport(
        /** Ton minus Lage beim Klopfer in s; null ohne Klopfer. */
        val syncSeconds: Double?,
        val sensorRateHz: Double,
        val framesTotal: Int,
        /** Fenster mit Lage, also in die Rechnung eingegangen. */
        val framesUsed: Int,
        val estimate: Estimate,
        /** Grad nach rechts (positiv) oder links gegenueber dem Kamerablick am Start. */
        val relAzimuthDeg: Double?,
        /** Grad nach oben (positiv) oder unten gegenueber dem Kamerablick am Start. */
        val relElevationDeg: Double?,
        /** Fenster mit klarer GCC-PHAT-Spitze, mit oder ohne Lage. */
        val framesWithPeak: Int = framesUsed,
        /** S-012: Median der GCC-PHAT-Spitze ueber alle Fenster nach der Klopfphase (0 bei Stille). */
        val peakMedian: Double = 0.0,
    )

    fun rotate(q: Pose, v: Vec3): Vec3 {
        // v' = q v q*, ausgeschrieben
        val w = q.w; val x = q.x; val y = q.y; val z = q.z
        val tx = 2 * (y * v.z - z * v.y); val ty = 2 * (z * v.x - x * v.z); val tz = 2 * (x * v.y - y * v.x)
        return Vec3(v.x + w * tx + (y * tz - z * ty), v.y + w * ty + (z * tx - x * tz), v.z + w * tz + (x * ty - y * tx))
    }

    fun rotateInverse(q: Pose, v: Vec3): Vec3 = rotate(q.copy(x = -q.x, y = -q.y, z = -q.z), v)

    /** Lage zum Zeitpunkt [nanos], linear zwischen den Nachbarn und normiert; null, wenn keine Lage nahe genug. */
    fun poseAt(poses: List<Pose>, nanos: Long): Pose? {
        if (poses.isEmpty()) return null
        var lo = 0; var hi = poses.size - 1
        if (nanos <= poses[lo].nanos) return poses[lo].takeIf { it.nanos - nanos <= MAX_POSE_GAP_NANOS }
        if (nanos >= poses[hi].nanos) return poses[hi].takeIf { nanos - it.nanos <= MAX_POSE_GAP_NANOS }
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (poses[mid].nanos <= nanos) lo = mid else hi = mid }
        val a = poses[lo]; var b = poses[hi]
        if (b.nanos - a.nanos > 2 * MAX_POSE_GAP_NANOS) return null
        // q und -q sind dieselbe Lage: auf dieselbe Seite bringen
        if (a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z < 0) b = Pose(b.nanos, -b.w, -b.x, -b.y, -b.z)
        val t = (nanos - a.nanos).toDouble() / (b.nanos - a.nanos)
        val w = a.w + (b.w - a.w) * t; val x = a.x + (b.x - a.x) * t; val y = a.y + (b.y - a.y) * t; val z = a.z + (b.z - a.z) * t
        val n = sqrt(w * w + x * x + y * y + z * z)
        return Pose(nanos, w / n, x / n, y / n, z / n)
    }

    /** Laufzeit je Fenster von [FRAME_SECONDS]; Fenster ohne klare Spitze (Stille, kein gemeinsames Signal) fallen weg. */
    fun frameDelays(a: FloatArray, b: FloatArray, sampleRate: Int, maxLagSeconds: Double = MAX_LAG_SECONDS): List<FrameDelay> {
        val len = (FRAME_SECONDS * sampleRate).toInt()
        val n = minOf(a.size, b.size) / maxOf(1, len)
        return framesOf(n, len, sampleRate, maxLagSeconds, null) { from, wa, wb -> a.copyInto(wa, 0, from, from + len); b.copyInto(wb, 0, from, from + len) }
    }

    /**
     * Wie [frameDelays], aber direkt aus verschraenktem PCM ab Frame [startFrame]: nur zwei Fensterpuffer statt Kopien
     * der ganzen Aufnahme (Zweitpruefung S-010, W4: 25 s Stereo sind 4,8 MB, getrennt und als Float das Doppelte).
     */
    fun frameDelaysInterleaved(
        pcm: ShortArray, channels: Int, sampleRate: Int, startFrame: Int, maxLagSeconds: Double = MAX_LAG_SECONDS,
        /** S-012: nimmt die Spitzenhoehe jedes Fensters auf, auch unter [MIN_PEAK] (fuer den Median). */
        allPeaks: MutableList<Double>? = null,
    ): List<FrameDelay> {
        val len = (FRAME_SECONDS * sampleRate).toInt()
        val frames = pcm.size / channels - startFrame
        val n = maxOf(0, frames) / maxOf(1, len)
        return framesOf(n, len, sampleRate, maxLagSeconds, allPeaks) { from, wa, wb ->
            for (i in 0 until len) { val f = (startFrame + from + i) * channels; wa[i] = pcm[f] / 32768f; wb[i] = pcm[f + 1] / 32768f }
        }
    }

    private inline fun framesOf(
        n: Int, len: Int, sampleRate: Int, maxLagSeconds: Double, allPeaks: MutableList<Double>?, fill: (Int, FloatArray, FloatArray) -> Unit,
    ): List<FrameDelay> {
        val out = ArrayList<FrameDelay>(n)
        val wa = FloatArray(len); val wb = FloatArray(len)
        for (k in 0 until n) {
            fill(k * len, wa, wb)
            val d = AudioMath.gccPhat(wa, wb, sampleRate, maxLagSeconds)
            // Stille ergibt keine Spitze und zaehlt fuer den Median als 0
            allPeaks?.add(d?.peak?.takeIf { it.isFinite() } ?: 0.0)
            if (d == null) continue
            if (d.peak >= MIN_PEAK) out += FrameDelay((k + 0.5) * FRAME_SECONDS, d.seconds, d.peak)
        }
        return out
    }

    /**
     * Abdeckung der Achsrichtungen von 0 (alle in einer Ebene oder gleich) bis 1 (gleichmaessig rundum):
     * dreifacher kleinster Eigenwert der Kovarianz der Achsen.
     */
    fun coverage(axes: List<Vec3>): Double {
        if (axes.size < 2) return 0.0
        val m = axes.fold(Vec3(0.0, 0.0, 0.0)) { s, v -> s + v } * (1.0 / axes.size)
        var xx = 0.0; var yy = 0.0; var zz = 0.0; var xy = 0.0; var xz = 0.0; var yz = 0.0
        for (v in axes) {
            val d = v - m
            xx += d.x * d.x; yy += d.y * d.y; zz += d.z * d.z; xy += d.x * d.y; xz += d.x * d.z; yz += d.y * d.z
        }
        val s = 1.0 / axes.size
        return (3 * smallestEigen(xx * s, yy * s, zz * s, xy * s, xz * s, yz * s)).coerceIn(0.0, 1.0)
    }

    /** Kleinster Eigenwert einer symmetrischen 3x3-Matrix (geschlossene Formel). */
    private fun smallestEigen(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double): Double {
        val p1 = d * d + e * e + f * f
        if (p1 < 1e-30) return minOf(a, b, c)
        val q = (a + b + c) / 3
        val p2 = (a - q) * (a - q) + (b - q) * (b - q) + (c - q) * (c - q) + 2 * p1
        val p = sqrt(p2 / 6)
        val ba = (a - q) / p; val bb = (b - q) / p; val bc = (c - q) / p; val bd = d / p; val be = e / p; val bf = f / p
        val det = ba * (bb * bc - bf * bf) - bd * (bd * bc - bf * be) + be * (bd * bf - bb * be)
        val phi = acos((det / 2).coerceIn(-1.0, 1.0)) / 3
        return q + 2 * p * cos(phi + 2 * PI / 3)
    }

    /** Ausgleichsrechnung ueber alle Messungen (Normalgleichungen fuer tau0 und k*u). */
    fun solve(m: List<Measurement>): Estimate {
        val cov = coverage(m.map { it.axis })
        if (m.size < MIN_FRAMES) return Estimate.Failed(SweepFailure.TOO_FEW_MEASUREMENTS, cov, m.size)
        if (cov < MIN_COVERAGE) return Estimate.Failed(SweepFailure.ONE_SIDED, cov, m.size)
        val ata = Array(4) { DoubleArray(4) }; val atb = DoubleArray(4)
        for (x in m) {
            val r = doubleArrayOf(1.0, x.axis.x, x.axis.y, x.axis.z)
            for (i in 0..3) { atb[i] += r[i] * x.delaySeconds; for (j in 0..3) ata[i][j] += r[i] * r[j] }
        }
        val p = solve4(ata, atb) ?: return Estimate.Failed(SweepFailure.ONE_SIDED, cov, m.size)
        val w = Vec3(p[1], p[2], p[3]); val k = w.norm()
        val spacing = k * AudioMath.SPEED_OF_SOUND
        if (spacing !in MIN_SPACING..MAX_SPACING) return Estimate.Failed(SweepFailure.IMPLAUSIBLE, cov, m.size)
        var sq = 0.0
        for (x in m) { val e = x.delaySeconds - p[0] - w.dot(x.axis); sq += e * e }
        return Estimate.Ok(w * (1 / k), spacing, p[0], sqrt(sq / m.size), cov, m.size)
    }

    /** Gauss mit Pivotsuche; null bei (fast) singulaerer Matrix. */
    private fun solve4(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = 4; val m = Array(n) { i -> a[i].copyOf() }; val v = b.copyOf()
        val scale = (0 until n).maxOf { abs(m[it][it]) }.coerceAtLeast(1e-300)
        for (col in 0 until n) {
            val piv = (col until n).maxBy { abs(m[it][col]) }
            if (abs(m[piv][col]) < 1e-12 * scale) return null
            val tr = m[col]; m[col] = m[piv]; m[piv] = tr; val tv = v[col]; v[col] = v[piv]; v[piv] = tv
            for (r in col + 1 until n) {
                val f = m[r][col] / m[col][col]
                for (c in col until n) m[r][c] -= f * m[col][c]
                v[r] -= f * v[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = v[r]; for (c in r + 1 until n) s -= m[r][c] * x[c]
            x[r] = s / m[r][r]
        }
        return x
    }

    /**
     * Gleichlauf aus den Klopfern der ersten [windowSeconds]: Zeit im Ton minus Zeit im Beschleunigungssensor, gemittelt
     * ueber die Paare (erster mit erstem, zweiter mit zweitem). Null, wenn eine Seite keinen Klopfer zeigt.
     */
    fun tapOffsetSeconds(accel: List<AccelSample>, audio: FloatArray, sampleRate: Int, audioStartNanos: Long, windowSeconds: Double): Double? {
        val end = audioStartNanos + (windowSeconds * 1e9).toLong()
        val inWindow = accel.filter { it.nanos in audioStartNanos..end }
        if (inWindow.size < 5) return null
        val median = inWindow.map { it.magnitude }.sorted()[inWindow.size / 2]
        val accelTaps = mutableListOf<Long>()
        for (s in inWindow) {
            if (abs(s.magnitude - median) < TAP_ACCEL) continue
            if (accelTaps.isEmpty() || s.nanos - accelTaps.last() > 250_000_000L) accelTaps += s.nanos
        }
        val n = minOf(audio.size, (windowSeconds * sampleRate).toInt())
        // Klopfer auf das Gehaeuse sind laut, die Geraeuschquelle laeuft aber schon: niedrigere Schwellen als beim Klatschen
        val audioTaps = AudioMath.findClaps(audio.copyOfRange(0, n), sampleRate, aboveFloorDb = TAP_ABOVE_FLOOR_DB, riseDb = TAP_RISE_DB).map { audioStartNanos + it * 1_000_000_000L / sampleRate }
        // je Klopfer im Sensor der naechste im Ton innerhalb +-150 ms; ein falscher Treffer im Ton bleibt so ohne Partner
        val offsets = accelTaps.mapNotNull { t -> audioTaps.minByOrNull { abs(it - t) }?.let { (it - t) / 1e9 }?.takeIf { abs(it) <= TAP_PAIR_SECONDS } }
        return if (offsets.isEmpty()) null else offsets.average()
    }

    /**
     * Startlage fuer "relativ zum Kamerablick": normiertes Mittel der Lagen von 0,5 s bis 0,5 s vor Ende der Klopfphase.
     * Genau am Ende der Klopfphase dreht der Nutzer oft schon (Ansage kommt vor dem Tonbeginn), und das Klopfen ruckelt
     * (Zweitpruefung S-010, W3). Ohne Lagen in dem Bereich: Lage am Ende der Klopfphase.
     */
    fun startPose(poses: List<Pose>, audioStartNanos: Long, tapSeconds: Double): Pose? {
        val from = audioStartNanos + 500_000_000L
        val to = audioStartNanos + ((tapSeconds - 0.5) * 1e9).toLong()
        val inRange = poses.filter { it.nanos in from..to }
        if (inRange.isEmpty()) return poseAt(poses, audioStartNanos + (tapSeconds * 1e9).toLong())
        val ref = inRange.first()
        var w = 0.0; var x = 0.0; var y = 0.0; var z = 0.0
        for (p in inRange) {
            val s = if (p.w * ref.w + p.x * ref.x + p.y * ref.y + p.z * ref.z < 0) -1.0 else 1.0
            w += s * p.w; x += s * p.x; y += s * p.y; z += s * p.z
        }
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n > 0) Pose(to, w / n, x / n, y / n, z / n) else ref
    }

    /** Richtung [u] in Grad gegenueber der Blickrichtung der Kamera (Geraete -z) in der Lage [start]. */
    fun relativeToView(start: Pose, u: Vec3): Pair<Double, Double> {
        val view = rotate(start, Vec3(0.0, 0.0, -1.0)).normalized(); val d = u.normalized()
        var az = Math.toDegrees(atan2(d.x, d.y) - atan2(view.x, view.y))
        while (az > 180) az -= 360
        while (az <= -180) az += 360
        val el = Math.toDegrees(asin(d.z.coerceIn(-1.0, 1.0)) - asin(view.z.coerceIn(-1.0, 1.0)))
        return az to el
    }

    /**
     * Ganze Auswertung eines Schwenks: Gleichlauf aus den Klopfern der ersten [tapSeconds], danach Laufzeit je Fenster,
     * Lage zur Fenstermitte, Ausgleichsrechnung und Richtung relativ zum Kamerablick bei Beginn des Schwenks.
     */
    fun analyze(
        pcm: ShortArray,
        channels: Int,
        sampleRate: Int,
        audioStartNanos: Long,
        poses: List<Pose>,
        accel: List<AccelSample>,
        tapSeconds: Double = 3.0,
        axisDevice: Vec3 = MIC_AXIS_DEVICE,
    ): SweepReport {
        val span = if (poses.size >= 2) (poses.last().nanos - poses.first().nanos) / 1e9 else 0.0
        val rate = if (span > 0) (poses.size - 1) / span else 0.0
        if (channels < 2) return SweepReport(null, rate, 0, 0, Estimate.Failed(SweepFailure.NO_STEREO, 0.0, 0), null, null, 0)
        val totalFrames = pcm.size / channels
        // Klopfer nur aus der Klopfphase: kleiner Mono-Puffer statt der ganzen Aufnahme (W4)
        val tapFrames = (tapSeconds * sampleRate).toInt().coerceIn(0, totalFrames)
        val mono = FloatArray(tapFrames) { i -> maxOf(abs(pcm[i * channels].toInt()), abs(pcm[i * channels + 1].toInt())) / 32768f }
        val sync = tapOffsetSeconds(accel, mono, sampleRate, audioStartNanos, tapSeconds)
        val peaks = ArrayList<Double>()
        val frames = frameDelaysInterleaved(pcm, channels, sampleRate, tapFrames, allPeaks = peaks)
        val peakMedian = if (peaks.isEmpty()) 0.0 else peaks.sorted()[peaks.size / 2]
        val total = (totalFrames - tapFrames) / (FRAME_SECONDS * sampleRate).toInt().coerceAtLeast(1)
        val m = frames.mapNotNull { f ->
            val t = audioStartNanos + ((tapSeconds + f.centerSeconds) * 1e9).toLong()
            poseAt(poses, t)?.let { Measurement(rotate(it, axisDevice), f.delaySeconds) }
        }
        val est = when {
            // S-012: zuerst die Signalstaerke, sonst fuehren Zufallsspitzen zu "unplausibel" (S24+ 10.10., 16:05 Uhr)
            peaks.isNotEmpty() && peakMedian < MIN_MEDIAN_PEAK -> Estimate.Failed(SweepFailure.WEAK_SIGNAL, coverage(m.map { it.axis }), m.size)
            frames.size >= MIN_FRAMES && m.size < MIN_FRAMES -> Estimate.Failed(SweepFailure.NO_POSE, coverage(m.map { it.axis }), m.size)
            else -> solve(m)
        }
        val start = startPose(poses, audioStartNanos, tapSeconds)
        val rel = if (est is Estimate.Ok && start != null) relativeToView(start, est.direction) else null
        return SweepReport(sync, rate, total, m.size, est, rel?.first, rel?.second, frames.size, peakMedian)
    }
}
