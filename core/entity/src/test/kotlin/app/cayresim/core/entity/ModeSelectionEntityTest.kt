package app.cayresim.core.entity

import app.cayresim.core.pure.SeededRng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModeSelectionEntityTest {
    private fun entity(vararg modes: ModeKey) = ModeSelectionEntity().apply { updateAvailable(modes.toSet()) }

    // Guter Fall
    @Test fun `Verfuegbarer Modus wird genutzt`() {
        val e = entity(ModeKey.NIGHT, ModeKey.HDR); e.request(ModeKey.NIGHT)
        assertEquals(ModeKey.NIGHT, e.effectiveMode); assertNull(e.fallbackFrom)
    }

    @Test fun `Startzustand ist NORMAL ohne Rueckfall`() {
        val e = ModeSelectionEntity()
        assertEquals(ModeKey.NORMAL, e.effectiveMode); assertNull(e.fallbackFrom)
        assertEquals(listOf(ModeKey.NORMAL), e.offeredModes)
    }

    // Fehlerfall
    @Test fun `Nicht verfuegbarer Modus faellt auf NORMAL zurueck und wird vermerkt`() {
        val e = entity(ModeKey.HDR); e.request(ModeKey.NIGHT)
        assertEquals(ModeKey.NORMAL, e.effectiveMode); assertEquals(ModeKey.NIGHT, e.fallbackFrom)
    }

    @Test fun `Gescheitertes Binden nimmt den Modus aus dem Angebot`() {
        val e = entity(ModeKey.NIGHT, ModeKey.HDR); e.request(ModeKey.NIGHT); e.markFailed(ModeKey.NIGHT)
        assertEquals(ModeKey.NORMAL, e.effectiveMode); assertEquals(ModeKey.NIGHT, e.fallbackFrom)
        assertEquals(listOf(ModeKey.NORMAL, ModeKey.HDR), e.offeredModes)
    }

    @Test fun `NORMAL kann nie als gescheitert markiert werden`() {
        val e = entity(); e.markFailed(ModeKey.NORMAL)
        assertEquals(listOf(ModeKey.NORMAL), e.offeredModes); assertEquals(ModeKey.NORMAL, e.effectiveMode)
    }

    // Randfaelle
    @Test fun `NORMAL ist auch bei leerer Verfuegbarkeit da`() {
        val e = entity(); assertEquals(listOf(ModeKey.NORMAL), e.offeredModes)
    }

    @Test fun `Wegfall der Verfuegbarkeit nach der Wahl fuehrt zum Rueckfall`() {
        val e = entity(ModeKey.BOKEH); e.request(ModeKey.BOKEH); e.updateAvailable(emptySet())
        assertEquals(ModeKey.NORMAL, e.effectiveMode); assertEquals(ModeKey.BOKEH, e.fallbackFrom)
    }

    @Test fun `Zuruecksetzen bietet gescheiterte Modi wieder an`() {
        val e = entity(ModeKey.HDR); e.markFailed(ModeKey.HDR); e.resetFailures()
        e.request(ModeKey.HDR); assertEquals(ModeKey.HDR, e.effectiveMode)
    }

    /** Eigenschaft: Der wirksame Modus ist immer angeboten, und ein Rueckfall gibt es genau dann, wenn er nicht dem Wunsch entspricht. */
    @Test fun `Wirksamer Modus ist immer angeboten (Eigenschaft)`() {
        val rng = SeededRng(99)
        val all = ModeKey.entries
        repeat(10_000) {
            val e = ModeSelectionEntity()
            repeat(1 + rng.nextInt(8)) {
                when (rng.nextInt(4)) {
                    0 -> e.updateAvailable(all.filter { rng.nextInt(2) == 0 }.toSet())
                    1 -> e.request(all[rng.nextInt(all.size)])
                    2 -> e.markFailed(all[rng.nextInt(all.size)])
                    else -> e.resetFailures()
                }
            }
            assertTrue(e.effectiveMode in e.offeredModes)
            assertEquals(e.effectiveMode != e.requestedMode, e.fallbackFrom != null)
            assertTrue(ModeKey.NORMAL in e.offeredModes)
        }
    }
}
