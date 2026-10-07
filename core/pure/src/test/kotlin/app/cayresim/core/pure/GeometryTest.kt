package app.cayresim.core.pure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GeometryTest {
    // Guter Fall
    @Test fun `Mitte der Flaeche ist 0,5`() =
        assertEquals(NormPoint(0.5f, 0.5f), viewToNormalized(540f, 1200f, 1080, 2400))

    @Test fun `Hin und zurueck ergibt denselben Pixel`() {
        val (x, y) = normalizedToView(viewToNormalized(300f, 700f, 1080, 2400), 1080, 2400)
        assertEquals(300f, x, 0.01f); assertEquals(700f, y, 0.01f)
    }

    // Randfaelle
    @Test fun `Punkte ausserhalb werden an den Rand gesetzt`() {
        assertEquals(NormPoint(0f, 1f), viewToNormalized(-50f, 9999f, 100, 100))
    }

    @Test fun `Viermal 90 Grad ergibt den Ausgangspunkt (Eigenschaft)`() {
        val rng = SeededRng(7)
        repeat(5_000) {
            val p = NormPoint(rng.nextInt(1001) / 1000f, rng.nextInt(1001) / 1000f)
            var q = p
            repeat(4) { q = rotateNormalized(q, 90) }
            assertEquals(p.x, q.x, 1e-6f); assertEquals(p.y, q.y, 1e-6f)
        }
    }

    @Test fun `Negative Winkel werden richtig umgerechnet`() {
        val p = NormPoint(0.2f, 0.3f)
        assertEquals(rotateNormalized(p, 270), rotateNormalized(p, -90))
    }

    @Test fun `Zweimal spiegeln ergibt den Ausgangspunkt`() {
        val p = NormPoint(0.1f, 0.9f)
        val q = mirrorNormalized(mirrorNormalized(p))
        assertEquals(p.x, q.x, 1e-6f); assertEquals(p.y, q.y, 1e-6f)
    }

    // Fehlerfaelle
    @Test fun `Flaeche mit Breite 0 wird abgelehnt`() {
        assertFailsWith<IllegalArgumentException> { viewToNormalized(1f, 1f, 0, 10) }
        assertFailsWith<IllegalArgumentException> { normalizedToView(NormPoint(0f, 0f), 10, -1) }
    }

    @Test fun `Schiefer Winkel wird abgelehnt`() {
        assertFailsWith<IllegalArgumentException> { rotateNormalized(NormPoint(0f, 0f), 45) }
    }

    @Test fun `Punkt ausserhalb von 0 bis 1 laesst sich nicht bauen`() {
        assertFailsWith<IllegalArgumentException> { NormPoint(1.01f, 0f) }
        assertFailsWith<IllegalArgumentException> { NormPoint(0f, -0.01f) }
    }
}
