package app.cayresim.core.pure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RawDevelopTest {
    private val black = floatArrayOf(64f, 64f, 64f, 64f)
    private val ones = floatArrayOf(1f, 1f, 1f)

    /** 4x2-Bild: zwei 2x2-Bloecke im Muster RGGB mit den Werten r, g, b. */
    private fun block(r: Int, g: Int, b: Int) = shortArrayOf(r.toShort(), g.toShort(), r.toShort(), g.toShort(), g.toShort(), b.toShort(), g.toShort(), b.toShort())

    @Test fun `Guter Fall Schwarz ergibt 0, Weiss ergibt 1`() {
        val dark = RawDevelop.binToLinear(block(64, 64, 64), 4, 2, 4, RawDevelop.Cfa.RGGB, black, 1023f, ones, RawDevelop.IDENTITY)
        assertTrue(dark.all { it == 0f }, dark.toList().toString())
        val bright = RawDevelop.binToLinear(block(1023, 1023, 1023), 4, 2, 4, RawDevelop.Cfa.RGGB, black, 1023f, ones, RawDevelop.IDENTITY)
        assertTrue(bright.all { kotlin.math.abs(it - 1f) < 1e-6f })
    }

    @Test fun `Guter Fall Farbmuster legt Rot, Gruen und Blau richtig ab`() {
        val out = RawDevelop.binToLinear(block(1023, 543, 64), 4, 2, 4, RawDevelop.Cfa.RGGB, black, 1023f, ones, RawDevelop.IDENTITY)
        assertEquals(1f, out[0], 1e-5f); assertEquals(0.5f, out[1], 1e-3f); assertEquals(0f, out[2], 1e-5f)
        // dasselbe Bild als BGGR gelesen: Rot und Blau vertauscht
        val bggr = RawDevelop.binToLinear(block(1023, 543, 64), 4, 2, 4, RawDevelop.Cfa.BGGR, black, 1023f, ones, RawDevelop.IDENTITY)
        assertEquals(0f, bggr[0], 1e-5f); assertEquals(1f, bggr[2], 1e-5f)
    }

    @Test fun `Guter Fall Weissabgleich und Farbmatrix werden angewendet`() {
        val swap = floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f, 1f, 0f, 0f) // Rot und Blau tauschen
        val out = RawDevelop.binToLinear(block(543, 64, 64), 4, 2, 4, RawDevelop.Cfa.RGGB, black, 1023f, floatArrayOf(2f, 1f, 1f), swap)
        assertEquals(0f, out[0], 1e-5f); assertEquals(1f, out[2], 1e-3f)
    }

    @Test fun `Randfall unter Schwarz wird nicht abgeschnitten`() {
        val out = RawDevelop.binToLinear(block(60, 60, 60), 4, 2, 4, RawDevelop.Cfa.RGGB, black, 1023f, ones, RawDevelop.IDENTITY)
        assertTrue(out.all { it < 0f }, "negatives Rauschen muss erhalten bleiben")
    }

    @Test fun `Randfall Zeilenabstand groesser als die Breite`() {
        val raw = ShortArray(6 * 2) { 64 }.also { it[0] = 1023 }
        val out = RawDevelop.binToLinear(raw, 4, 2, 6, RawDevelop.Cfa.RGGB, black, 1023f, ones, RawDevelop.IDENTITY)
        assertEquals(6, out.size); assertEquals(1f, out[0], 1e-5f)
    }

    @Test fun `Fehlerfall ungueltige Eingaben`() {
        assertFailsWith<IllegalArgumentException> { RawDevelop.binToLinear(ShortArray(3), 4, 2, 4, RawDevelop.Cfa.RGGB, black, 1023f, ones, RawDevelop.IDENTITY) }
        assertFailsWith<IllegalArgumentException> { RawDevelop.binToLinear(block(1, 1, 1), 4, 2, 4, RawDevelop.Cfa.RGGB, black, 50f, ones, RawDevelop.IDENTITY) }
        assertFailsWith<IllegalArgumentException> { RawDevelop.binToLinear(block(1, 1, 1), 4, 2, 4, RawDevelop.Cfa.RGGB, black, 1023f, floatArrayOf(1f), RawDevelop.IDENTITY) }
    }
}

class NightPathRuleTest {
    @Test fun `Guter Fall schneller Strom, keine Nullen, kalibriert, Probenacht gelungen ergibt RAW`() {
        assertEquals(null, NightPathRule.precheck(true, 15f, 0.01f, true))
        assertEquals(NightPathRule.Verdict(NightPath.RAW, NightPathRule.Reason.RAW_OK), NightPathRule.afterProbe(true, 4_000))
    }

    @Test fun `Fehlerfall jede verletzte Bedingung ergibt 8 Bit mit Grund`() {
        assertEquals(NightPathRule.Reason.NO_RAW, NightPathRule.precheck(false, 30f, 0f, true)!!.reason)
        assertEquals(NightPathRule.Reason.SLOW_STREAM, NightPathRule.precheck(true, 7.9f, 0f, true)!!.reason)
        assertEquals(NightPathRule.Reason.SLOW_STREAM, NightPathRule.precheck(true, null, 0f, true)!!.reason)
        assertEquals(NightPathRule.Reason.CLIPPED, NightPathRule.precheck(true, 30f, 0.25f, true)!!.reason)
        assertEquals(NightPathRule.Reason.NO_CALIBRATION, NightPathRule.precheck(true, 30f, 0f, false)!!.reason)
        assertEquals(NightPathRule.Reason.PROBE_FAILED, NightPathRule.afterProbe(false, 100).reason)
        assertEquals(NightPathRule.Reason.PROBE_SLOW, NightPathRule.afterProbe(true, 6_001).reason)
        listOf(NightPathRule.afterProbe(false, 1), NightPathRule.afterProbe(true, 9_999)).forEach { assertEquals(NightPath.YUV, it.path) }
    }

    @Test fun `Randfall genau an den Grenzen gilt noch als gut`() {
        assertEquals(null, NightPathRule.precheck(true, NightPathRule.MIN_STREAM_FPS, NightPathRule.MAX_ZERO_SHARE, true))
        assertEquals(NightPath.RAW, NightPathRule.afterProbe(true, NightPathRule.MAX_PROBE_MS).path)
    }
}
