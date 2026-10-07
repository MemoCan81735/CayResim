package app.cayresim.core.entity

import app.cayresim.core.pure.SeededRng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeriesEntityTest {
    private fun s() = assertNotNull(SeriesEntity.create(1, "Garten"))

    @Test fun `Fotos werden chronologisch gefuehrt, juengstes ist das Overlay`() {
        val e = s(); e.add(SeriesPhoto("b", 20)); e.add(SeriesPhoto("a", 10)); e.add(SeriesPhoto("c", 30))
        assertEquals(listOf("a", "b", "c"), e.photos.map { it.uri }); assertEquals("c", e.latest?.uri)
    }

    @Test fun `Leere Serie hat kein Overlay`() = assertNull(s().latest)

    @Test fun `Doppeltes Foto wird abgelehnt`() {
        val e = s(); assertTrue(e.add(SeriesPhoto("a", 1))); assertFalse(e.add(SeriesPhoto("a", 2)))
        assertEquals(1, e.photos.size)
    }

    @Test fun `Volle Serie lehnt weitere Fotos ab`() {
        val e = s(); repeat(SeriesEntity.MAX_PHOTOS) { assertTrue(e.add(SeriesPhoto("u$it", it.toLong()))) }
        assertFalse(e.add(SeriesPhoto("zu-viel", 1)))
    }

    @Test fun `Ungueltige Namen werden abgelehnt`() {
        assertNull(SeriesEntity.create(1, "")); assertNull(SeriesEntity.create(1, "   "))
        assertNull(SeriesEntity.create(1, "x".repeat(41)))
        assertEquals("Balkon", SeriesEntity.create(1, "  Balkon ")?.name)
        val e = s(); assertFalse(e.rename(" ")); assertEquals("Garten", e.name); assertTrue(e.rename("Hof")); assertEquals("Hof", e.name)
    }

    @Test fun `Kaputte gespeicherte Daten werden bereinigt`() {
        val e = assertNotNull(SeriesEntity.create(1, "x", listOf(SeriesPhoto("a", 5), SeriesPhoto("a", 5), SeriesPhoto("b", 1))))
        assertEquals(listOf("b", "a"), e.photos.map { it.uri })
    }

    @Test fun `Entfernen`() {
        val e = s(); e.add(SeriesPhoto("a", 1)); assertTrue(e.remove("a")); assertFalse(e.remove("a")); assertNull(e.latest)
    }

    @Test fun `Juengstes Foto ist immer das mit der groessten Zeit (Eigenschaft)`() {
        val rng = SeededRng(8)
        repeat(500) {
            val e = s()
            repeat(1 + rng.nextInt(40)) { e.add(SeriesPhoto("u${rng.nextInt(60)}", rng.nextInt(1000).toLong())) }
            assertEquals(e.photos.maxOf { it.takenAtMillis }, e.latest!!.takenAtMillis)
            assertEquals(e.photos.size, e.photos.map { it.uri }.toSet().size)
        }
    }
}
