package app.cayresim.core.pure

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Testkonzept: kuenstliche Serien mit bekanntem Ergebnis. */
class StackingTest {
    private val w = 64; private val h = 48; private val px = w * h

    private fun background(rng: Rng) = ByteArray(px * 3) { rng.nextInt(256).toByte() }

    /** Serie: fester Hintergrund, in jedem Bild wandert ein anderes "Person"-Rechteck durch. */
    private fun seriesWithWalkers(n: Int, seed: Long): Pair<ByteArray, List<ByteArray>> {
        val rng = SeededRng(seed); val bg = background(rng)
        val frames = (0 until n).map { k ->
            bg.copyOf().also { f ->
                val x0 = (k * (w - 10)) / maxOf(1, n - 1)
                for (y in 10 until 40) for (x in x0 until x0 + 10) { val i = (y * w + x) * 3; f[i] = 10; f[i + 1] = 20; f[i + 2] = 30 }
            }
        }
        return bg to frames
    }

    @Test fun `Median findet den Hintergrund pixelgenau wieder`() {
        val (bg, frames) = seriesWithWalkers(21, 5)
        assertContentEquals(bg, Stacking.stack(frames, px, median = true))
    }

    @Test fun `Median auch mit kleinen Kacheln identisch`() {
        val (_, frames) = seriesWithWalkers(15, 6)
        assertContentEquals(Stacking.stack(frames, px, true), Stacking.stack(frames, px, true, tilePixels = 7))
    }

    @Test fun `Mittelwert senkt Rauschen messbar`() {
        val rng = SeededRng(9)
        val truth = ByteArray(px * 3) { 128.toByte() }
        val frames = (0 until 16).map { ByteArray(px * 3) { (128 + rng.nextInt(61) - 30).toByte() } }
        fun err(x: ByteArray) = x.indices.sumOf { kotlin.math.abs((x[it].toInt() and 0xFF) - 128) }.toDouble() / x.size
        val single = err(frames[0]); val stacked = err(Stacking.stack(frames, px, median = false))
        assertTrue(stacked < single / 3, "Rauschen einzeln $single, gestapelt $stacked")
        assertEquals(truth.size, frames[0].size)
    }

    @Test fun `Ein einzelnes Bild bleibt unveraendert`() {
        val f = background(SeededRng(2))
        assertContentEquals(f, Stacking.stack(listOf(f), px, true)); assertContentEquals(f, Stacking.stack(listOf(f), px, false))
    }

    @Test fun `Einfarbige Serie bleibt einfarbig`() {
        val frames = List(10) { ByteArray(px * 3) { 77 } }
        assertTrue(Stacking.stack(frames, px, true).all { it == 77.toByte() })
        assertTrue(Stacking.stack(frames, px, false).all { it == 77.toByte() })
    }

    @Test fun `Mittelwert rundet und bleibt bei 255`() {
        val frames = listOf(ByteArray(3) { 255.toByte() }, ByteArray(3) { 254.toByte() })
        assertEquals(255, Stacking.stack(frames, 1, false)[0].toInt() and 0xFF)
    }

    @Test fun `Median ist der untere Wert bei gerader Anzahl`() {
        val frames = listOf(byteArrayOf(1, 1, 1), byteArrayOf(9, 9, 9))
        assertEquals(1, Stacking.stack(frames, 1, true)[0].toInt())
    }

    @Test fun `Abbruch zwischen den Kacheln`() {
        val (_, frames) = seriesWithWalkers(5, 1)
        var tiles = 0
        assertFailsWith<IllegalStateException> {
            Stacking.stack(frames, px, true, tilePixels = 100) { t -> tiles = t; if (t == 3) throw IllegalStateException("abgebrochen") }
        }
        assertEquals(3, tiles)
    }

    @Test fun `Leere oder ungleich grosse Serie wird abgelehnt`() {
        assertFailsWith<IllegalArgumentException> { Stacking.stack(emptyList(), px, true) }
        assertFailsWith<IllegalArgumentException> { Stacking.stack(listOf(ByteArray(3), ByteArray(6)), 1, true) }
    }

    @Test fun `Bewegungsmass ist 0 bei gleichen Bildern und gross bei Bewegung`() {
        val (bg, frames) = seriesWithWalkers(3, 4)
        assertEquals(0f, Stacking.motionScore(bg, bg.copyOf(), px))
        assertTrue(Stacking.motionScore(frames[0], frames[2], px) > 5f)
        val black = ByteArray(px * 3); val white = ByteArray(px * 3) { 255.toByte() }
        assertEquals(255f, Stacking.motionScore(black, white, px, 1), 1f)
    }

    @Test fun `Bewegungsmass ist symmetrisch (Eigenschaft)`() {
        val rng = SeededRng(12)
        repeat(50) {
            val a = background(rng); val b = background(rng)
            assertEquals(Stacking.motionScore(a, b, px), Stacking.motionScore(b, a, px))
        }
    }
}

class LumaMedianAndSoftGammaTest {
    private val px = 4

    @Test fun `Median ueber Helligkeit uebernimmt die Farbe eines echten Bildes`() {
        // drei Bilder: rot, gruen, grau gleicher Helligkeitsreihenfolge; Kanalmedian wuerde Farben mischen
        val red = ByteArray(px * 3) { if (it % 3 == 0) 200.toByte() else 20.toByte() }
        val gray = ByteArray(px * 3) { 90.toByte() }
        val green = ByteArray(px * 3) { if (it % 3 == 1) 220.toByte() else 30.toByte() }
        val out = ByteArray(px * 3)
        Stacking.lumaMedianRange(listOf(red, gray, green), out, 0, px)
        // Helligkeiten: rot ~58, grau 90, gruen ~163 -> Median ist grau, und zwar ganz
        kotlin.test.assertContentEquals(gray, out)
    }

    @Test fun `Median ueber Helligkeit entfernt einen Passanten`() {
        val bg = ByteArray(px * 3) { 50.toByte() }
        val person = ByteArray(px * 3) { 240.toByte() }
        val out = ByteArray(px * 3)
        Stacking.lumaMedianRange(listOf(bg, person, bg, bg, person), out, 0, px)
        kotlin.test.assertContentEquals(bg, out)
    }

    @Test fun `Weiches Gamma laesst Lichtspuren heller als der Mittelwert`() {
        val w = 4; val n = 10
        // ein Licht zieht durch: in 1 von 10 Bildern hell (200), sonst dunkel (10)
        val frames = List(n) { k -> ByteArray(px * 3) { if (k == 0) 200.toByte() else 10.toByte() } }
        val mean = NightTone.meanAndBrighten(frames, px, w).rgb[0].toInt() and 0xFF
        val soft = NightTone.softGammaMeanAndBrighten(frames, px, w).rgb[0].toInt() and 0xFF
        assertTrue(soft > mean + 20, "Lichtspur $soft statt deutlich ueber $mean")
    }

    @Test fun `Weiches Gamma aendert ruhige Flaechen nicht`() {
        val w = 4
        val frames = List(8) { ByteArray(px * 3) { 120.toByte() } }
        kotlin.test.assertContentEquals(frames[0], NightTone.softGammaMeanAndBrighten(frames, px, w).rgb)
    }

    @Test fun `Weiches Gamma mit k gleich 1 ist der Mittelwert`() {
        val w = 4; val rng = SeededRng(2)
        val frames = List(6) { ByteArray(px * 3) { (60 + rng.nextInt(100)).toByte() } }
        kotlin.test.assertContentEquals(NightTone.meanAndBrighten(frames, px, w).rgb, NightTone.softGammaMeanAndBrighten(frames, px, w, k = 1f).rgb)
    }

    @Test fun `Fehlerfall ungueltiges k und leere Serie`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { NightTone.softGammaMeanAndBrighten(emptyList(), px, 4) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { NightTone.softGammaMeanAndBrighten(listOf(ByteArray(px * 3)), px, 4, k = 0.5f) }
    }
}
