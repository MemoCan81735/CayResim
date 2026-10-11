package app.cayresim.core.pure

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** S-016: Halte-Kreis, Abweichung der Blickrichtung gegen den Start und Abbildung auf den Bildschirm. */
class HoldGuideTest {
    private val start = SweepMath.Pose(0, 1.0, 0.0, 0.0, 0.0)

    /** Lage nach Drehung um eine Geraeteachse (im Startsystem, Start = Welt) um [deg] Grad. */
    private fun turned(ax: Double, ay: Double, az: Double, deg: Double): SweepMath.Pose {
        val h = deg * PI / 360
        return SweepMath.Pose(1, cos(h), ax * sin(h), ay * sin(h), az * sin(h))
    }

    @Test fun `S-016 Abweichung und Bildschirm`() {
        // Kamera nach rechts: Drehung um Geraete-y mit negativem Winkel bringt -z nach +x
        val right = HoldGuide.deviation(start, turned(0.0, 1.0, 0.0, -0.3))
        assertEquals(0.3, right.rightDeg, 0.01); assertEquals(0.0, right.upDeg, 0.01); assertTrue(right.steady)
        val up = HoldGuide.deviation(start, turned(1.0, 0.0, 0.0, 0.8))
        assertEquals(0.0, up.rightDeg, 0.01); assertEquals(0.8, up.upDeg, 0.01); assertFalse(up.steady)
        val roll = HoldGuide.deviation(start, turned(0.0, 0.0, 1.0, 20.0))
        assertEquals(0.0, roll.rightDeg, 1e-9); assertEquals(0.0, roll.upDeg, 1e-9); assertTrue(roll.steady)
        assertEquals(0.5, HoldGuide.RADIUS_DEG)
        // Bildschirm (rechts, unten) fuer die vier Drehungen der Anzeige, Abweichung 0,2 rechts und 0,1 oben
        val d = HoldGuide.Deviation(0.2, 0.1, true)
        assertEquals(0.2 to -0.1, HoldGuide.toScreen(d, 0))
        assertEquals(-0.1 to -0.2, HoldGuide.toScreen(d, 90))
        assertEquals(-0.2 to 0.1, HoldGuide.toScreen(d, 180))
        assertEquals(0.1 to 0.2, HoldGuide.toScreen(d, 270))
    }

    private fun q(ax: Double, ay: Double, az: Double, deg: Double): DoubleArray { val h = deg * PI / 360; return doubleArrayOf(cos(h), ax * sin(h), ay * sin(h), az * sin(h)) }
    private fun mul(p: DoubleArray, r: DoubleArray) = doubleArrayOf(
        p[0] * r[0] - p[1] * r[1] - p[2] * r[2] - p[3] * r[3], p[0] * r[1] + p[1] * r[0] + p[2] * r[3] - p[3] * r[2],
        p[0] * r[2] - p[1] * r[3] + p[2] * r[0] + p[3] * r[1], p[0] * r[3] + p[1] * r[2] - p[2] * r[1] + p[3] * r[0])
    private fun pose(q: DoubleArray) = SweepMath.Pose(0, q[0], q[1], q[2], q[3])

    @Test fun `S-016 Abweichung mit echter Haltung`() {
        // Zweitpruefung S-016, W2: Start nicht die Identitaet, damit eine vertauschte Reihenfolge der Drehungen auffaellt.
        // Hochkant aufrecht: 90 Grad um Welt-x, Kamera (Geraet -z) zeigt nach Norden, Oberkante nach oben
        val upright = q(1.0, 0.0, 0.0, 90.0)
        val turnRight = q(0.0, 0.0, 1.0, -0.4) // Welt-z, im Uhrzeigersinn von oben: Norden nach Osten
        val d0 = HoldGuide.deviation(pose(upright), pose(mul(turnRight, upright)))
        assertEquals(0.4 to 0.0, HoldGuide.toScreen(d0, 0).let { (a, b) -> Math.round(a * 100) / 100.0 to Math.round(b * 100) / 100.0 })
        // quer, Oberkante links (Anzeige 90): zusaetzlich 90 Grad um die Blickachse
        val landscape = mul(upright, q(0.0, 0.0, 1.0, 90.0))
        val d1 = HoldGuide.deviation(pose(landscape), pose(mul(turnRight, landscape)))
        val (r1, down1) = HoldGuide.toScreen(d1, 90)
        assertEquals(0.4, r1, 0.01); assertEquals(0.0, down1, 0.01)
        // quer, Oberkante rechts (Anzeige 270), Kamera nach oben: Punkt geht nach oben
        val landscape270 = mul(upright, q(0.0, 0.0, 1.0, -90.0))
        val tiltUp = q(1.0, 0.0, 0.0, 0.4) // um Welt-x (Osten): Norden nach oben
        val (r2, down2) = HoldGuide.toScreen(HoldGuide.deviation(pose(landscape270), pose(mul(tiltUp, landscape270))), 270)
        assertEquals(0.0, r2, 0.01); assertEquals(-0.4, down2, 0.01)
    }

    @Test fun `S-016 Kennzahlen`() {
        val s = HoldGuide.Stats()
        assertEquals(0.0, s.insideShare); assertEquals(0.0, s.maxDeg)
        // 0 bis 1 s ruhig (0,2 Grad), 1 bis 2 s ausserhalb (0,9 Grad), 2 bis 4 s ruhig (0,1 Grad)
        s.add(0L, 0.2); s.add(1_000_000_000L, 0.9); s.add(2_000_000_000L, 0.1); s.add(4_000_000_000L, 0.1)
        assertEquals(0.75, s.insideShare, 1e-9)
        assertEquals(0.9, s.maxDeg, 1e-9)
        assertEquals(4_000_000_000L, s.totalNanos)
        // Randfaelle: aelterer Zeitstempel wird verworfen, gleicher Zeitstempel zaehlt keine Zeit
        s.add(3_000_000_000L, 2.0)
        assertEquals(0.75, s.insideShare, 1e-9); assertEquals(0.9, s.maxDeg, 1e-9)
        val same = HoldGuide.Stats(); same.add(5L, 0.1); same.add(5L, 0.2)
        assertEquals(0L, same.totalNanos); assertEquals(0.0, same.insideShare)
    }
}
