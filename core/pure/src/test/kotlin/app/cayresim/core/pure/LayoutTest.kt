package app.cayresim.core.pure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LayoutTest {
    @Test fun `Gleiche Seitenverhaeltnisse fuellen genau`() =
        assertEquals(RectF(0f, 0f, 1080f, 1440f), fillCenter(3000, 4000, 1080, 1440))

    @Test fun `4 zu 3 Foto im hohen Display wird seitlich beschnitten und mittig gesetzt`() {
        val r = fillCenter(3000, 4000, 1080, 2340)
        assertEquals(2340f, r.height, 0.01f); assertTrue(r.width > 1080f)
        assertEquals((1080f - r.width) / 2f, r.left, 0.01f); assertEquals(0f, r.top, 0.01f)
    }

    @Test fun `Bedeckt immer die ganze Flaeche (Eigenschaft)`() {
        val rng = SeededRng(4)
        repeat(5_000) {
            val sw = 1 + rng.nextInt(5000); val sh = 1 + rng.nextInt(5000); val dw = 1 + rng.nextInt(3000); val dh = 1 + rng.nextInt(3000)
            val r = fillCenter(sw, sh, dw, dh)
            assertTrue(r.left <= 0.001f && r.top <= 0.001f)
            assertTrue(r.left + r.width >= dw - 0.01f && r.top + r.height >= dh - 0.01f)
            assertEquals(sw.toFloat() / sh, r.width / r.height, 0.001f * sw / sh + 0.001f)
        }
    }

    @Test fun `Nullgroessen werden abgelehnt`() {
        assertFailsWith<IllegalArgumentException> { fillCenter(0, 1, 1, 1) }
        assertFailsWith<IllegalArgumentException> { fillCenter(1, 1, 1, -1) }
    }

    @Test fun `Zeitraffer-Plan rechnet Dauer aus`() {
        val p = timelapsePlan(30, 10)
        assertEquals(100_000L, p.frameDurationMicros); assertEquals(3_000_000L, p.totalMicros)
    }

    @Test fun `Zeitraffer mit einem Foto ist erlaubt`() = assertEquals(1, timelapsePlan(1, 1).photoCount)

    @Test fun `Zeitraffer ohne Foto oder mit unsinniger Rate wird abgelehnt`() {
        assertFailsWith<IllegalArgumentException> { timelapsePlan(0, 10) }
        assertFailsWith<IllegalArgumentException> { timelapsePlan(5, 0) }
        assertFailsWith<IllegalArgumentException> { timelapsePlan(5, 61) }
    }
}
