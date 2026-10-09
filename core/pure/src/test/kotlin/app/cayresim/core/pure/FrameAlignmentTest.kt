package app.cayresim.core.pure

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** S-004: gemeinsame Ausrichtung fuer Nacht, Langzeit, Menschen wegrechnen und Fokus-Stacking. */
class FrameAlignmentTest {
    private val w = 128; private val h = 96

    /** Unregelmaessige Bloecke von 3 Pixeln (ein Schachbrett waere fuer die Ausrichtung mehrdeutig). */
    private val blocks = kotlin.random.Random(17).let { r -> BooleanArray((h / 3 + 20) * (w / 3 + 20)) { r.nextBoolean() } }

    private fun truth(dx: Int, dy: Int) = IntArray(w * h) { p ->
        val x = p % w + dx + 24; val y = p / w + dy + 24
        if (blocks[(y / 3) * (w / 3 + 20) + x / 3]) 90 else 30
    }

    private fun frame(t: IntArray, rng: Rng) = ByteArray(w * h * 3) { i -> (t[i / 3] + rng.nextInt(7) - 3).coerceIn(0, 255).toByte() }

    /** Mittlere Abweichung der Helligkeit im Inneren (Rand von 16 Pixeln ausgespart). */
    private fun diff(a: ByteArray, b: IntArray): Double {
        var s = 0L; var n = 0
        for (y in 16 until h - 16) for (x in 16 until w - 16) { s += abs((a[(y * w + x) * 3].toInt() and 0xFF) - b[y * w + x]); n++ }
        return s.toDouble() / n
    }

    @Test fun `Guter Fall Verschiebung wird exakt gefunden und die Bilder liegen danach auf dem ersten`() {
        val rng = SeededRng(9)
        val shifts = listOf(0 to 0, 4 to 0, -8 to 4, 12 to -4, 0 to 8)
        val frames = shifts.map { (dx, dy) -> frame(truth(dx, dy), rng) }
        val found = FrameAlignment.alignInPlace(frames, w, h)
        assertEquals(shifts.map { (dx, dy) -> -dx to -dy }, found)
        val ref = truth(0, 0)
        frames.forEachIndexed { i, f -> assertTrue(diff(f, ref) < 3.0, "Bild $i liegt nicht auf dem ersten: ${diff(f, ref)}") }
    }

    @Test fun `Randfall kleine Bilder bleiben unveraendert, jedes Bild wird gemeldet`() {
        val small = List(3) { k -> ByteArray(16 * 16 * 3) { (it * (k + 1)).toByte() } }
        val copies = small.map { it.copyOf() }
        val seen = mutableListOf<Int>()
        assertEquals(List(3) { 0 to 0 }, FrameAlignment.alignInPlace(small, 16, 16) { seen += it })
        small.indices.forEach { assertTrue(small[it].contentEquals(copies[it])) }
        val big = List(3) { frame(truth(0, 0), SeededRng(it + 1L)) }
        FrameAlignment.alignInPlace(big, w, h) { seen += it }
        assertEquals(listOf(0, 1, 2), seen, "vor jedem Bild ein Aufruf (Abbruchpunkt)")
    }

    @Test fun `Fokus-Stacking aus der Hand wird durch Ausrichtung deutlich genauer`() {
        // S-004 K4: Bild A links scharf, rechts weich; Bild B umgekehrt und um (6, -3) verschoben
        fun soft(t: IntArray) = IntArray(t.size) { p ->
            val x = p % w; val y = p / w; var s = 0; var n = 0
            for (oy in -2..2) for (ox in -2..2) { s += t[(y + oy).coerceIn(0, h - 1) * w + (x + ox).coerceIn(0, w - 1)]; n++ }
            s / n
        }
        val t0 = truth(0, 0); val t1 = truth(6, -3)
        val s0 = soft(t0)
        val a = IntArray(w * h) { p -> if (p % w < w / 2) t0[p] else s0[p] }
        val s1 = soft(t1)
        val b = IntArray(w * h) { p -> if (p % w >= w / 2 - 6) t1[p] else s1[p] }
        val rng = SeededRng(3)
        fun pair() = listOf(frame(a, rng), frame(b, rng))
        val plain = FocusStacking.stack(pair(), w, h).second
        val framesAligned = pair().also { FrameAlignment.alignInPlace(it, w, h) }
        val aligned = FocusStacking.stack(framesAligned, w, h).second
        val dPlain = diff(plain, t0); val dAligned = diff(aligned, t0)
        assertTrue(dAligned <= 0.5 * dPlain, "ausgerichtet $dAligned, ohne Ausrichtung $dPlain")
    }
}
