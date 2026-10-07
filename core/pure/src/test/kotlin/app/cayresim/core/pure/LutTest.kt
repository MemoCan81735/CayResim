package app.cayresim.core.pure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LutTest {
    @Test fun `Identitaet aendert keine Farbe (alle 8-Bit-Werte einer Achse)`() {
        val lut = Lut3D.identity()
        for (v in 0..255) {
            assertEquals(listOf(v, v, v), lut.apply(v, v, v).toList())
            assertEquals(listOf(v, 0, 255 - v), lut.apply(v, 0, 255 - v).toList())
        }
    }

    @Test fun `Stuetzstellen werden exakt getroffen`() {
        val lut = Looks.lut(LookId.FILM)
        val m = Looks.SIZE - 1
        for (i in 0..m step 4) {
            val x = i / m.toFloat()
            val direct = lut.applyFloat(x, x, x)
            val idx = ((i * Looks.SIZE + i) * Looks.SIZE + i) * 3
            assertEquals(lut.data[idx], direct[0], 1e-5f)
        }
    }

    @Test fun `Mono macht alle Kanaele gleich`() {
        val lut = Looks.lut(LookId.MONO); val rng = SeededRng(1)
        repeat(2_000) {
            val o = lut.apply(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))
            assertTrue(o[0] == o[1] && o[1] == o[2], o.toList().toString())
        }
    }

    @Test fun `Warm hebt Rot gegenueber Blau`() {
        val o = Looks.lut(LookId.WARM).apply(128, 128, 128)
        assertTrue(o[0] > o[2])
    }

    @Test fun `Ausgabe bleibt immer im 8-Bit-Bereich (Eigenschaft)`() {
        val rng = SeededRng(2)
        LookId.entries.forEach { id ->
            val lut = Looks.lut(id)
            repeat(3_000) { lut.apply(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256)).forEach { assertTrue(it in 0..255) } }
        }
    }

    @Test fun `Eingaben ausserhalb von 0 bis 1 werden begrenzt`() {
        val lut = Lut3D.identity(5)
        assertEquals(listOf(1f, 0f, 1f), lut.applyFloat(2f, -1f, 1.5f).toList())
    }

    @Test fun `Interpolation ist monoton fuer monotone LUT`() {
        val lut = Looks.lut(LookId.FILM)
        var last = -1
        for (v in 0..255) { val o = lut.apply(v, v, v)[1]; assertTrue(o >= last, "bei $v"); last = o }
    }

    @Test fun `Kleinste LUT mit zwei Stuetzstellen funktioniert`() {
        assertEquals(listOf(10, 20, 30), Lut3D.identity(2).apply(10, 20, 30).toList())
    }

    @Test fun `Falsche LUT-Daten werden abgelehnt`() {
        assertFailsWith<IllegalArgumentException> { Lut3D(1, FloatArray(3)) }
        assertFailsWith<IllegalArgumentException> { Lut3D(3, FloatArray(10)) }
    }
}
