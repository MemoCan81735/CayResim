package app.cayresim.core.pure

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FocusAndStarsTest {
    private val w = 96; private val h = 64

    /** Scharfes Muster (Schachbrett) und unscharfes Grau. */
    private fun sharp(x: Int, y: Int) = if ((x / 2 + y / 2) % 2 == 0) 230 else 20
    private fun put(f: ByteArray, x: Int, y: Int, v: Int) { val i = (y * w + x) * 3; f[i] = v.toByte(); f[i + 1] = v.toByte(); f[i + 2] = v.toByte() }

    /** Bild k ist nur im Streifen k scharf (3 senkrechte Streifen). */
    private fun focusSeries(): List<ByteArray> = (0 until 3).map { k ->
        ByteArray(w * h * 3).also { f -> for (y in 0 until h) for (x in 0 until w) put(f, x, y, if (x / 32 == k) sharp(x, y) else 125) }
    }

    @Test fun `Fokus-Stacking waehlt in jedem Streifen das scharfe Bild`() {
        val (choice, out) = FocusStacking.stack(focusSeries(), w, h, block = 16)
        val bw = w / 16
        for (by in 0 until h / 16) for (bx in 0 until bw) assertEquals(bx * 16 / 32, choice[by * bw + bx], "Block $bx,$by")
        for (y in 0 until h) for (x in 0 until w) assertEquals(sharp(x, y).toByte(), out[(y * w + x) * 3], "Pixel $x,$y")
    }

    @Test fun `Ein Bild ergibt sich selbst`() {
        val f = focusSeries()[1]
        assertContentEquals(f, FocusStacking.stack(listOf(f), w, h).second)
    }

    @Test fun `Gleichfoermige Bilder nehmen das erste`() {
        val flat = List(3) { ByteArray(w * h * 3) { 90 } }
        assertTrue(FocusStacking.stack(flat, w, h).first.all { it == 0 })
    }

    @Test fun `Kein vollstaendiger Block am Rand stoert nicht`() {
        val ww = 50; val hh = 37
        val f = ByteArray(ww * hh * 3) { (it % 251).toByte() }
        assertContentEquals(f, FocusStacking.stack(listOf(f, f), ww, hh, block = 16).second)
    }

    @Test fun `Fokus-Stacking lehnt ungueltige Eingaben ab`() {
        assertFailsWith<IllegalArgumentException> { FocusStacking.stack(emptyList(), w, h) }
        assertFailsWith<IllegalArgumentException> { FocusStacking.stack(listOf(ByteArray(5)), w, h) }
    }

    private fun stars(seed: Long): ByteArray {
        val rng = SeededRng(seed); val f = ByteArray(w * h * 3) { 8 }
        repeat(40) { val x = rng.nextInt(w); val y = rng.nextInt(h); put(f, x, y, 250); if (x + 1 < w) put(f, x + 1, y, 180) }
        return f
    }

    @Test fun `Bekannte Verschiebung wird gefunden`() {
        val ref = stars(1)
        for ((dx, dy) in listOf(3 to -2, -5 to 4, 0 to 0, 7 to 7)) {
            val moved = StarAlignment.shift(ref, w, h, -dx, -dy)
            assertEquals(dx to dy, StarAlignment.estimateShift(ref, moved, w, h, maxShift = 8, step = 1), "Verschiebung $dx,$dy")
        }
    }

    @Test fun `Ausgerichtetes Mittel ist schaerfer als ungerichtetes`() {
        val ref = stars(2)
        val frames = listOf(ref) + (1..6).map { StarAlignment.shift(ref, w, h, it % 3, -(it % 2)) }
        val aligned = StarAlignment.alignAndMean(frames, w, h, maxShift = 6)
        val naive = Stacking.stack(frames, w * h, median = false)
        fun peak(x: ByteArray) = x.maxOf { it.toInt() and 0xFF }
        assertTrue(peak(aligned) > peak(naive), "ausgerichtet ${peak(aligned)}, naiv ${peak(naive)}")
    }

    @Test fun `Verschieben um 0 ist die Identitaet`() {
        val f = stars(3); assertContentEquals(f, StarAlignment.shift(f, w, h, 0, 0))
    }

    @Test fun `Zu grosser Suchbereich wird abgelehnt`() {
        assertFailsWith<IllegalArgumentException> { StarAlignment.estimateShift(stars(1), stars(1), w, h, maxShift = 40) }
    }
}
