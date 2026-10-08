package app.cayresim.core.pure

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NightToneTest {
    private val w = 64; private val h = 48; private val px = w * h

    /** Dunkle Szene wie im Zimmer des Nutzers: Hintergrund um 1, ein heller Streifen um 6 (sRGB), mit Rauschen. */
    private fun darkFrame(rng: Rng): ByteArray = ByteArray(px * 3) { i ->
        val x = (i / 3) % w
        val base = if (x in 10..20) 6 else 1
        (base + rng.nextInt(3) - 1).coerceIn(0, 255).toByte()
    }

    private fun meanOf(b: ByteArray) = b.sumOf { it.toInt() and 0xFF }.toDouble() / b.size

    @Test fun `Guter Fall sehr dunkle Serie wird deutlich heller, Verstaerkung am Anschlag`() {
        val rng = SeededRng(4711)
        val r = NightTone.meanAndBrighten(List(20) { darkFrame(rng) }, px, w)
        assertEquals(NightTone.MAX_GAIN, r.gain)
        assertTrue(meanOf(r.rgb) > 10, "Mittel ${meanOf(r.rgb)} statt vorher etwa 1,5")
    }

    @Test fun `Guter Fall maessig dunkle Szene landet beim Ziel sRGB 40 im Median`() {
        val rng = SeededRng(11)
        val frames = List(10) { ByteArray(px * 3) { (10 + rng.nextInt(5) - 2).toByte() } }
        val r = NightTone.meanAndBrighten(frames, px, w)
        assertTrue(r.gain in 2f..NightTone.MAX_GAIN, "Verstaerkung ${r.gain}")
        val lin = FloatArray(r.rgb.size) { NightTone.srgbToLinear((r.rgb[it].toInt() and 0xFF) / 255f) }
        val medianSrgb = NightTone.linearToSrgb(NightTone.lumaPercentile(lin, 0.5f)) * 255
        assertTrue(medianSrgb in 34f..46f, "Median $medianSrgb")
    }

    @Test fun `Guter Fall heller Streifen bleibt heller als der Hintergrund`() {
        val rng = SeededRng(1)
        val r = NightTone.meanAndBrighten(List(20) { darkFrame(rng) }, px, w)
        val stripe = (0 until h).map { y -> r.rgb[(y * w + 15) * 3].toInt() and 0xFF }.average()
        val back = (0 until h).map { y -> r.rgb[(y * w + 40) * 3].toInt() and 0xFF }.average()
        assertTrue(stripe > back + 20, "Streifen $stripe, Hintergrund $back")
    }

    @Test fun `Guter Fall Mitteln in Gleitkomma senkt das Rauschen etwa mit Wurzel N`() {
        val rng = SeededRng(7)
        fun noisy() = ByteArray(px * 3) { (100 + rng.nextInt(41) - 20).toByte() }
        val single = NightTone.meanAndBrighten(listOf(noisy()), px, w).rgb
        val merged = NightTone.meanAndBrighten(List(16) { noisy() }, px, w).rgb
        fun sd(b: ByteArray): Double { val m = meanOf(b); return sqrt(b.sumOf { val d = (it.toInt() and 0xFF) - m; d * d } / b.size) }
        assertTrue(sd(merged) < sd(single) / 3, "Rauschen ${sd(single)} -> ${sd(merged)}")
    }

    @Test fun `Randfall helle Szene bleibt bitgenau unveraendert`() {
        val rng = SeededRng(3)
        val bright = ByteArray(px * 3) { (60 + rng.nextInt(180)).toByte() }
        val r = NightTone.brightenBytes(bright, w)
        assertEquals(1f, r.gain)
        assertContentEquals(bright, r.rgb)
    }

    @Test fun `Randfall jeder 8-Bit-Wert uebersteht Hin- und Rueckweg`() {
        for (v in 0..255) {
            val back = NightTone.linearToSrgb(NightTone.srgbToLinear(v / 255f)) * 255f
            assertTrue(abs(back - v) < 0.01f, "Wert $v -> $back")
        }
    }

    @Test fun `Randfall komplett schwarz ergibt keine Fehler und bleibt schwarz`() {
        val r = NightTone.meanAndBrighten(List(5) { ByteArray(px * 3) }, px, w)
        assertEquals(NightTone.MAX_GAIN, r.gain)
        assertTrue(r.rgb.all { it.toInt() == 0 }, "Schwarz darf durch Dithering nicht grau werden")
    }

    @Test fun `Randfall Lichter brennen nicht hart aus, zwei Lampen bleiben unterscheidbar`() {
        // Zwei Lampen (120 und 60) vor dunklem Raum: hohe Verstaerkung, trotzdem bleiben sie verschieden hell
        val img = ByteArray(px * 3) { i -> when ((i / 3) % w) { in 0..3 -> 120; in 4..7 -> 60; else -> 2 }.toByte() }
        val r = NightTone.brightenBytes(img, w)
        assertTrue(r.gain > 4f)
        val strong = r.rgb[0].toInt() and 0xFF; val weak = r.rgb[5 * 3].toInt() and 0xFF
        assertTrue(strong > weak + 10, "Lampen $strong und $weak")
        assertTrue(NightTone.shoulder(10f) <= 1f && NightTone.shoulder(0.3f) == 0.3f && NightTone.shoulder(1.2f) < NightTone.shoulder(1.5f))
    }

    @Test fun `Eigenschaft Schulter ist monoton und nie ueber 1`() {
        var last = -1f
        for (i in 0..2000) { val v = NightTone.shoulder(i / 100f); assertTrue(v >= last && v <= 1f); last = v }
    }

    @Test fun `Fehlerfall falsche Groessen`() {
        assertFailsWith<IllegalArgumentException> { NightTone.meanAndBrighten(emptyList(), px, w) }
        assertFailsWith<IllegalArgumentException> { NightTone.LinearSum(px).add(ByteArray(5)) }
        assertFailsWith<IllegalArgumentException> { NightTone.brighten(FloatArray(10), 3) }
    }
}
