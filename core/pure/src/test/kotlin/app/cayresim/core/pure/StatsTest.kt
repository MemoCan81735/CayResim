package app.cayresim.core.pure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StatsTest {
    @Test fun `Median einer ungeraden Liste`() = assertEquals(5, medianOf(intArrayOf(9, 1, 5)))
    @Test fun `Median einer geraden Liste ist der untere Wert`() = assertEquals(2, medianOf(intArrayOf(4, 1, 2, 3)))
    @Test fun `Median beruecksichtigt nur count Werte`() = assertEquals(1, medianOf(intArrayOf(1, 1, 200, 200, 200), 2))
    @Test fun `Median veraendert die Eingabe nicht`() {
        val v = intArrayOf(3, 2, 1); medianOf(v); assertEquals(listOf(3, 2, 1), v.toList())
    }
    @Test fun `Ein einzelner Wert ist sein eigener Median und Mittelwert`() {
        assertEquals(42, medianOf(intArrayOf(42))); assertEquals(42, meanOf(intArrayOf(42)))
    }
    @Test fun `Einfarbige Serie bleibt einfarbig`() {
        assertEquals(128, medianOf(IntArray(30) { 128 })); assertEquals(128, meanOf(IntArray(30) { 128 }))
    }
    @Test fun `Mittelwert wird kaufmaennisch gerundet`() {
        assertEquals(2, meanOf(intArrayOf(1, 2))) // 1,5 -> 2
        assertEquals(255, meanOf(intArrayOf(255, 255, 254)))
    }

    /** Eigenschaft: Der Median ist robust gegen eine Minderheit von Ausreissern (Grundlage fuer "Menschen wegrechnen"). */
    @Test fun `Median ignoriert eine Minderheit von Ausreissern`() {
        val rng = SeededRng(11)
        repeat(3_000) {
            val n = 3 + rng.nextInt(28)
            val background = rng.nextInt(256)
            val outliers = rng.nextInt((n - 1) / 2 + 1) // echte Minderheit
            val values = IntArray(n) { i -> if (i < outliers) rng.nextInt(256) else background }
            assertEquals(background, medianOf(values), "n=$n, Ausreisser=$outliers")
        }
    }

    @Test fun `Median liegt immer zwischen Minimum und Maximum`() {
        val rng = SeededRng(3)
        repeat(3_000) {
            val v = IntArray(1 + rng.nextInt(40)) { rng.nextInt(256) }
            val m = medianOf(v); assertTrue(m in v.min()..v.max())
            val a = meanOf(v); assertTrue(a in v.min()..v.max())
        }
    }

    @Test fun `Leere Eingabe oder falsches count wird abgelehnt`() {
        assertFailsWith<IllegalArgumentException> { medianOf(IntArray(0)) }
        assertFailsWith<IllegalArgumentException> { medianOf(intArrayOf(1, 2), 3) }
        assertFailsWith<IllegalArgumentException> { meanOf(intArrayOf(1), 0) }
    }

    @Test fun `SeededRng ist reproduzierbar und bleibt im Bereich`() {
        val a = SeededRng(5); val b = SeededRng(5)
        repeat(1000) { val x = a.nextInt(10); assertEquals(x, b.nextInt(10)); assertTrue(x in 0 until 10) }
        assertFailsWith<IllegalArgumentException> { a.nextInt(0) }
        assertTrue(SeededRng(0).nextInt(100) in 0 until 100)
    }
}
