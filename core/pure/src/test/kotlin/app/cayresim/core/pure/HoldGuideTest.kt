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

    @Test fun `S-016 Kennzahlen`() {
        val s = HoldGuide.Stats()
        assertEquals(0.0, s.insideShare); assertEquals(0.0, s.maxDeg)
        // 0 bis 1 s ruhig (0,2 Grad), 1 bis 2 s ausserhalb (0,9 Grad), 2 bis 4 s ruhig (0,1 Grad)
        s.add(0L, 0.2); s.add(1_000_000_000L, 0.9); s.add(2_000_000_000L, 0.1); s.add(4_000_000_000L, 0.1)
        assertEquals(0.75, s.insideShare, 1e-9)
        assertEquals(0.9, s.maxDeg, 1e-9)
    }
}
