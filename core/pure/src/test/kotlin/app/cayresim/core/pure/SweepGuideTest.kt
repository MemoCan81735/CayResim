package app.cayresim.core.pure

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S-014: fester Ablauf der Schwenk-Messung und laufende Abdeckung. */
class SweepGuideTest {
    @Test fun `S-014 Ablauf`() {
        fun at(s: Double) = SweepGuide.at(s)
        assertEquals(SweepGuide.Step(SweepGuide.Move.TAP, 3, SweepGuide.Move.YAW), at(0.0))
        assertEquals(SweepGuide.Step(SweepGuide.Move.TAP, 1, SweepGuide.Move.YAW), at(2.99))
        assertEquals(SweepGuide.Step(SweepGuide.Move.YAW, 6, SweepGuide.Move.ROLL), at(3.0))
        assertEquals(SweepGuide.Step(SweepGuide.Move.YAW, 1, SweepGuide.Move.ROLL), at(8.5))
        assertEquals(SweepGuide.Step(SweepGuide.Move.ROLL, 6, SweepGuide.Move.PITCH), at(9.0))
        assertEquals(SweepGuide.Step(SweepGuide.Move.PITCH, 6, SweepGuide.Move.CIRCLE), at(15.0))
        assertEquals(SweepGuide.Step(SweepGuide.Move.CIRCLE, 4, null), at(21.0))
        assertEquals(SweepGuide.Step(SweepGuide.Move.CIRCLE, 1, null), at(24.9))
        assertNull(at(25.0)); assertNull(at(-0.1))
        assertEquals(25.0, SweepGuide.TOTAL_SECONDS)
        assertEquals(3.0, SweepGuide.TAP_SECONDS)
        assertEquals(SweepGuide.Move.entries, SweepGuide.PHASES.map { it.move })
    }

    @Test fun `S-014 Abdeckung laufend wie am Ende`() {
        val rnd = Random(14)
        val acc = SweepMath.AxisCoverage()
        assertEquals(0.0, acc.value())
        val axes = ArrayList<Vec3>()
        repeat(400) { i ->
            // erst eine Ebene (Abdeckung 0), dann alle Richtungen
            val v = if (i < 100) Vec3(rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0), 0.0) else Vec3(rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0))
            val u = v.normalized()
            axes += u; acc.add(u)
            if (i % 37 == 0 || i == 399) assertEquals(SweepMath.coverage(axes), acc.value(), 1e-9, "nach ${i + 1} Achsen")
        }
        assertTrue(acc.value() > 0.3, "rundum ${acc.value()}")
        assertEquals(400, acc.count)
    }
}
