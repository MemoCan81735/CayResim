package app.cayresim.core.pure

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S-010: Schwenk-Messung. Alle Signale und Lagen sind kuenstlich (CLAUDE.md Abschnitt 7).
 * Weltkoordinaten wie beim Android-Drehvektor: x Osten, y Norden, z oben.
 */
class SweepMathTest {
    private val sr = 48_000
    private val c = AudioMath.SPEED_OF_SOUND

    private fun gauss(rnd: Random): Double {
        val u = rnd.nextDouble().coerceAtLeast(1e-12); val v = rnd.nextDouble()
        return sqrt(-2 * ln(u)) * cos(2 * PI * v)
    }

    private fun dir(azDeg: Double, elDeg: Double): Vec3 {
        val az = azDeg * PI / 180; val el = elDeg * PI / 180
        // Azimut von Norden nach Osten (rechts), Hoehe ueber dem Horizont
        return Vec3(cos(el) * sin(az), cos(el) * cos(az), sin(el))
    }

    private fun angleBetween(a: Vec3, b: Vec3) = acos((a.dot(b) / (a.norm() * b.norm())).coerceIn(-1.0, 1.0)) * 180 / PI

    /** Achsen aus Drehungen: quer gehalten zeigt die Achse nach Osten; Schwenk bis +-60 Grad seitlich und in der Hoehe. */
    private fun sweepAxes(rnd: Random, n: Int, elevation: Boolean = true) = List(n) {
        dir(90 + rnd.nextDouble(-60.0, 60.0), if (elevation) rnd.nextDouble(-60.0, 60.0) else 0.0)
    }

    private fun measure(axes: List<Vec3>, u: Vec3, spacing: Double, offset: Double, noise: Double, rnd: Random) =
        axes.map { a -> SweepMath.Measurement(a, offset + spacing / c * a.dot(u) + gauss(rnd) * noise) }

    @Test fun `S-010 Ausgleich findet Quelle vorne, schraeg und hinten`() {
        val rnd = Random(10)
        for ((az, el) in listOf(0.0 to 0.0, 30.0 to 10.0, 160.0 to -5.0)) {
            val u = dir(az, el)
            val m = measure(sweepAxes(rnd, 400), u, 0.15, -0.00004, 0.00002, rnd)
            val e = assertNotNull(SweepMath.solve(m) as? SweepMath.Estimate.Ok, "Quelle $az/$el")
            assertTrue(angleBetween(e.direction, u) <= 3.0, "Quelle $az/$el: Fehler ${angleBetween(e.direction, u)} Grad")
            assertEquals(0.15, e.spacingMeters, 0.005)
            assertEquals(-0.00004, e.offsetSeconds, 0.00001)
            println("Quelle $az/$el: Fehler ${"%.2f".format(angleBetween(e.direction, u))} Grad, Abdeckung ${"%.3f".format(e.coverage)}")
            assertTrue(e.residualSeconds < 0.00003, "Restfehler ${e.residualSeconds}")
            assertEquals(400, e.used)
        }
    }

    @Test fun `S-010 einseitiger Schwenk wird erkannt`() {
        val rnd = Random(11)
        val u = dir(20.0, 0.0)
        val flat = measure(sweepAxes(rnd, 400, elevation = false), u, 0.15, 0.0, 0.00002, rnd)
        assertEquals(SweepMath.SweepFailure.ONE_SIDED, (SweepMath.solve(flat) as SweepMath.Estimate.Failed).reason)
        val still = measure(List(400) { dir(90.0, 0.0) }, u, 0.15, 0.0, 0.00002, rnd)
        assertEquals(SweepMath.SweepFailure.ONE_SIDED, (SweepMath.solve(still) as SweepMath.Estimate.Failed).reason)
        val few = measure(sweepAxes(rnd, SweepMath.MIN_FRAMES - 1), u, 0.15, 0.0, 0.00002, rnd)
        assertEquals(SweepMath.SweepFailure.TOO_FEW_MEASUREMENTS, (SweepMath.solve(few) as SweepMath.Estimate.Failed).reason)
        assertEquals(SweepMath.SweepFailure.TOO_FEW_MEASUREMENTS, (SweepMath.solve(emptyList()) as SweepMath.Estimate.Failed).reason)
        // Abdeckung: gleichmaessig rundum nahe 1, flach 0
        val round = List(2000) { dir(rnd.nextDouble(0.0, 360.0), Math.toDegrees(kotlin.math.asin(rnd.nextDouble(-1.0, 1.0)))) }
        assertEquals(1.0, SweepMath.coverage(round), 0.1)
        assertEquals(0.0, SweepMath.coverage(List(100) { dir(it * 3.6, 0.0) }), 1e-9)
        // unsinniger Abstand (1 m) wird abgelehnt
        val far = measure(sweepAxes(rnd, 400), u, 1.0, 0.0, 0.00002, rnd)
        assertEquals(SweepMath.SweepFailure.IMPLAUSIBLE, (SweepMath.solve(far) as SweepMath.Estimate.Failed).reason)
    }

    @Test fun `S-010 Klopfer misst den Gleichlauf`() {
        val start = 5_000_000_000L
        // Beschleunigung mit 100 Hz, Schwerkraft 9,81, Klopfer bei 1,0 s und 1,6 s nach Tonbeginn
        val accel = List(300) { i ->
            val t = start + i * 10_000_000L
            val tap = i == 100 || i == 160
            SweepMath.AccelSample(t, if (tap) 16.0 else 9.81 + (i % 3) * 0.05)
        }
        val audio = FloatArray(3 * sr)
        val rnd = Random(3)
        for (i in audio.indices) audio[i] = (gauss(rnd) * 0.001).toFloat()
        // im Ton 12 ms spaeter
        for (at in listOf((1.012 * sr).toInt(), (1.612 * sr).toInt())) for (i in 0 until 240) audio[at + i] += (0.5 * kotlin.math.exp(-i / 40.0) * if (i % 2 == 0) 1 else -1).toFloat()
        val off = assertNotNull(SweepMath.tapOffsetSeconds(accel, audio, sr, start, 3.0))
        assertEquals(0.012, off, 0.002)
        // ohne Klopfer: kein Wert, kein Absturz
        val calm = accel.map { it.copy(magnitude = 9.81) }
        assertNull(SweepMath.tapOffsetSeconds(calm, audio, sr, start, 3.0))
        assertNull(SweepMath.tapOffsetSeconds(emptyList(), FloatArray(0), sr, start, 3.0))
    }

    @Test fun `S-010 Laufzeit je Fenster`() {
        val rnd = Random(4)
        val frame = (SweepMath.FRAME_SECONDS * sr).toInt()
        val frames = 40
        val s = DoubleArray(frames * frame + 64) { gauss(rnd) * 0.2 }
        val a = FloatArray(frames * frame); val b = FloatArray(frames * frame)
        val lags = List(frames) { k -> if (k in 18..21) null else -20 + (k * 41 / frames) }
        for (k in 0 until frames) for (j in 0 until frame) {
            val i = k * frame + j
            val lag = lags[k] ?: continue // stille Fenster
            a[i] = s[i + 32 - lag].toFloat(); b[i] = s[i + 32].toFloat()
        }
        val d = SweepMath.frameDelays(a, b, sr, 0.0012)
        assertEquals(frames - 4, d.size, "stille Fenster fallen weg")
        val expected = lags.withIndex().filter { it.value != null }
        d.zip(expected).forEach { (got, exp) ->
            assertEquals((exp.index + 0.5) * SweepMath.FRAME_SECONDS, got.centerSeconds, 1e-9)
            assertEquals(exp.value!!.toDouble() / sr, got.delaySeconds, 0.00001, "Fenster ${exp.index}")
        }
    }

    @Test fun `S-010 Lage dreht die Achse`() {
        // 90 Grad um z: x wird zu y
        val q = SweepMath.Pose(0, cos(PI / 4), 0.0, 0.0, sin(PI / 4))
        val r = SweepMath.rotate(q, Vec3(1.0, 0.0, 0.0))
        assertEquals(0.0, r.x, 1e-12); assertEquals(1.0, r.y, 1e-12)
        assertEquals(1.0, SweepMath.rotateInverse(q, r).x, 1e-12)
        // Zwischenwert zwischen zwei Lagen, Vorzeichenwechsel der Quaternion stoert nicht
        val p0 = SweepMath.Pose(0, 1.0, 0.0, 0.0, 0.0)
        val p1 = SweepMath.Pose(1_000, -cos(PI / 4), 0.0, 0.0, -sin(PI / 4))
        val mid = assertNotNull(SweepMath.poseAt(listOf(p0, p1), 500))
        val v = SweepMath.rotate(mid, Vec3(1.0, 0.0, 0.0))
        assertEquals(45.0, Math.toDegrees(kotlin.math.atan2(v.y, v.x)), 0.5)
        assertNull(SweepMath.poseAt(listOf(p0, p1), 200_000_000), "weit ausserhalb")
        assertNull(SweepMath.poseAt(emptyList(), 0))
    }

    /**
     * Lage aus Gieren (nach rechts positiv), Nicken (nach oben positiv) und Rollen um die Blickrichtung, in Grad.
     * Grundlage: quer gehalten, Kamera (Geraete -z) blickt nach Norden, Geraete-y zeigt nach Osten, Geraete-x nach unten.
     * Liefert die Quaternion (w, x, y, z), die Geraetekoordinaten in Weltkoordinaten dreht.
     */
    private fun quaternionOf(yawDeg: Double, pitchDeg: Double, rollDeg: Double): DoubleArray {
        fun rz(d: Double): Array<DoubleArray> { val a = d * PI / 180; return arrayOf(doubleArrayOf(cos(a), -sin(a), 0.0), doubleArrayOf(sin(a), cos(a), 0.0), doubleArrayOf(0.0, 0.0, 1.0)) }
        fun rx(d: Double): Array<DoubleArray> { val a = d * PI / 180; return arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(a), -sin(a)), doubleArrayOf(0.0, sin(a), cos(a))) }
        fun ry(d: Double): Array<DoubleArray> { val a = d * PI / 180; return arrayOf(doubleArrayOf(cos(a), 0.0, sin(a)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(a), 0.0, cos(a))) }
        fun mul(p: Array<DoubleArray>, q: Array<DoubleArray>) = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> p[i][k] * q[k][j] } } }
        // Spalten: Bilder von Geraete-x (unten), -y (Osten), -z (Sueden)
        val base = arrayOf(doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, -1.0), doubleArrayOf(-1.0, 0.0, 0.0))
        // Gieren nach rechts dreht von oben gesehen im Uhrzeigersinn, also -yaw um z; Nicken um Osten; Rollen um Norden
        val r = mul(rz(-yawDeg), mul(rx(pitchDeg), mul(ry(rollDeg), base)))
        val tr = r[0][0] + r[1][1] + r[2][2]
        val w: Double; val x: Double; val y: Double; val z: Double
        if (tr > 0) {
            val s = sqrt(tr + 1) * 2; w = s / 4; x = (r[2][1] - r[1][2]) / s; y = (r[0][2] - r[2][0]) / s; z = (r[1][0] - r[0][1]) / s
        } else if (r[0][0] > r[1][1] && r[0][0] > r[2][2]) {
            val s = sqrt(1 + r[0][0] - r[1][1] - r[2][2]) * 2; w = (r[2][1] - r[1][2]) / s; x = s / 4; y = (r[0][1] + r[1][0]) / s; z = (r[0][2] + r[2][0]) / s
        } else if (r[1][1] > r[2][2]) {
            val s = sqrt(1 + r[1][1] - r[0][0] - r[2][2]) * 2; w = (r[0][2] - r[2][0]) / s; x = (r[0][1] + r[1][0]) / s; y = s / 4; z = (r[1][2] + r[2][1]) / s
        } else {
            val s = sqrt(1 + r[2][2] - r[0][0] - r[1][1]) * 2; w = (r[1][0] - r[0][1]) / s; x = (r[0][2] + r[2][0]) / s; y = (r[1][2] + r[2][1]) / s; z = s / 4
        }
        return doubleArrayOf(w, x, y, z)
    }

    /** Kuenstlicher Schwenk von 25 s: Lage je 10 ms, Ton mit der Laufzeit, die zur Lage passt. */
    private fun syntheticSweep(u: Vec3, rnd: Random): Triple<ShortArray, List<SweepMath.Pose>, Long> {
        val seconds = 25; val n = seconds * sr; val start = 7_000_000_000L
        val spacing = 0.15
        val poses = List(seconds * 100 + 1) { i ->
            val t = i / 100.0
            // quer gehalten (Geraete-y nach Osten), dann Gieren, Nicken und Rollen als langsame Schwingungen
            val yaw = 50 * sin(2 * PI * t / 9.0); val pitch = 35 * sin(2 * PI * t / 6.0); val roll = 60 * sin(2 * PI * t / 11.0)
            val q = quaternionOf(yaw, pitch, roll)
            SweepMath.Pose(start + i * 10_000_000L, q[0], q[1], q[2], q[3])
        }
        val src = DoubleArray(n + 200) { gauss(rnd) * 3000 }
        val pcm = ShortArray(n * 2)
        for (i in 0 until n) {
            val pose = poses[i / 480]
            val axis = SweepMath.rotate(pose, SweepMath.MIC_AXIS_DEVICE)
            val lag = Math.round(spacing / c * axis.dot(u) * sr).toInt()
            pcm[2 * i] = src[i + 100 - lag].toInt().toShort(); pcm[2 * i + 1] = src[i + 100].toInt().toShort()
        }
        return Triple(pcm, poses, start)
    }

    @Test fun `S-010 Auswertung ist schnell genug`() {
        val rnd = Random(5)
        val u = dir(-40.0, 15.0)
        val (pcm, poses, start) = syntheticSweep(u, rnd)
        repeat(1) { SweepMath.analyze(pcm, 2, sr, start, poses, emptyList()) }
        var best = Long.MAX_VALUE
        var report: SweepMath.SweepReport? = null
        repeat(3) {
            val t0 = System.nanoTime()
            report = SweepMath.analyze(pcm, 2, sr, start, poses, emptyList())
            best = minOf(best, System.nanoTime() - t0)
        }
        println("Schwenk-Auswertung 25 s: ${best / 1_000_000} ms")
        assertTrue(best < 2_000_000_000L, "Auswertung dauerte ${best / 1e6} ms")
        val r = assertNotNull(report)
        val e = assertNotNull(r.estimate as? SweepMath.Estimate.Ok, "Ergebnis ${r.estimate}")
        assertTrue(angleBetween(e.direction, u) <= 5.0, "Fehler ${angleBetween(e.direction, u)} Grad")
        assertEquals(100.0, r.sensorRateHz, 1.0)
        assertTrue(r.framesUsed > 400, "Fenster ${r.framesUsed}")
        assertNull(r.syncSeconds, "kein Klopfer im kuenstlichen Ton")
    }

    @Test fun `S-010 Richtung relativ zur Startlage`() {
        // Startlage quer, Kamera (Geraete -z) blickt nach Norden: Quelle im Norden ist 0/0, im Osten +90 (rechts)
        val q = quaternionOf(0.0, 0.0, 0.0)
        val start = SweepMath.Pose(0, q[0], q[1], q[2], q[3])
        val view = SweepMath.rotate(start, Vec3(0.0, 0.0, -1.0))
        assertTrue(angleBetween(view, dir(0.0, 0.0)) < 1e-6, "Blick nach Norden: $view")
        val (az0, el0) = SweepMath.relativeToView(start, dir(0.0, 0.0))
        assertEquals(0.0, az0, 1e-6); assertEquals(0.0, el0, 1e-6)
        val (az1, _) = SweepMath.relativeToView(start, dir(90.0, 0.0))
        assertEquals(90.0, az1, 1e-6)
        val (az2, el2) = SweepMath.relativeToView(start, dir(-30.0, 20.0))
        assertEquals(-30.0, az2, 1e-6); assertEquals(20.0, el2, 1e-6)
        assertTrue(abs(SweepMath.relativeToView(start, dir(180.0, 0.0)).first) > 179.0)
    }
}
