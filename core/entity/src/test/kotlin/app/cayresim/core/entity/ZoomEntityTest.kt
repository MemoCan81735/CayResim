package app.cayresim.core.entity

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZoomEntityTest {
    private val s24 = ZoomEntity(0.6f, 10f)

    @Test fun `Guter Fall S24+ bietet 0,6x, 1x und 3x`() = assertEquals(listOf(0.6f, 1f, 3f), s24.presets)

    @Test fun `Guter Fall Zwei-Finger-Zoom multipliziert`() = assertEquals(2f, s24.pinch(1f, 2f))

    @Test fun `Randfall nur Hauptkamera ohne Zoom ergibt keine Schnellwahl`() = assertEquals(emptyList(), ZoomEntity(1f, 1f).presets)

    @Test fun `Randfall Digitalzoom bis 4x ergibt 1x und 3x`() = assertEquals(listOf(1f, 3f), ZoomEntity(1f, 4f).presets)

    @Test fun `Randfall Digitalzoom bis 2x ergibt 1x und 2x`() = assertEquals(listOf(1f, 2f), ZoomEntity(1f, 2f).presets)

    @Test fun `Randfall Grenzen werden eingehalten`() {
        assertEquals(0.6f, s24.clamp(0.1f)); assertEquals(10f, s24.clamp(99f))
        assertEquals(0.6f, s24.pinch(0.6f, 0.5f)); assertEquals(10f, s24.pinch(9f, 3f))
    }

    @Test fun `Fehlerfall ungueltige Werte`() {
        assertEquals(1f, s24.clamp(Float.NaN)); assertEquals(1f, s24.clamp(Float.POSITIVE_INFINITY))
        assertEquals(2f, s24.pinch(2f, Float.NaN)); assertEquals(2f, s24.pinch(2f, 0f)); assertEquals(2f, s24.pinch(2f, -1f))
    }

    @Test fun `Fehlerfall kaputte Grenzen von der Kamera`() {
        val z = ZoomEntity(Float.NaN, 0.5f)
        assertEquals(1f, z.min); assertEquals(1f, z.max); assertEquals(1f, z.clamp(5f))
    }

    @Test fun `Eigenschaft jeder Pinch bleibt in den Grenzen`() {
        val r = Random(4711)
        repeat(5_000) {
            val v = s24.pinch(r.nextFloat() * 20f - 5f, r.nextFloat() * 6f - 1f)
            assertTrue(v in 0.6f..10f, "Wert $v ausserhalb")
        }
    }

    @Test fun `Aktive Stufe`() {
        assertEquals(3f, s24.activePreset(3.02f)); assertNull(s24.activePreset(2.4f))
    }
}
