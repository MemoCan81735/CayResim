package app.cayresim.core.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManualSettingsEntityTest {
    private val full = ManualLimits(100_000L..30_000_000_000L, 50..3200, 10f)

    @Test fun `Werte werden in die Grenzen gelegt`() {
        val e = ManualSettingsEntity(full)
        assertTrue(e.setExposure(60_000_000_000L, 10_000)); assertEquals(30_000_000_000L, e.exposureNanos); assertEquals(3200, e.iso)
        assertTrue(e.setExposure(1, 1)); assertEquals(100_000L, e.exposureNanos); assertEquals(50, e.iso)
        assertTrue(e.setFocus(20f)); assertEquals(10f, e.focusDiopters); assertTrue(e.setFocus(-3f)); assertEquals(0f, e.focusDiopters)
    }

    @Test fun `Fixfokus-Kamera erlaubt kein Fokussieren und kein Fokus-Stacking`() {
        val e = ManualSettingsEntity(full.copy(maxFocusDiopters = 0f))
        assertFalse(e.canFocus); assertFalse(e.setFocus(1f)); assertEquals(emptyList(), e.focusBracket(5))
        assertFalse(ManualSettingsEntity(full.copy(maxFocusDiopters = null)).canFocus)
    }

    @Test fun `Ohne manuelle Belichtung bleibt alles automatisch`() {
        val e = ManualSettingsEntity(full.copy(exposureNanos = null))
        assertFalse(e.canExpose); assertFalse(e.setExposure(1_000_000, 100)); assertNull(e.exposureNanos); assertNull(e.iso)
    }

    @Test fun `Ungueltiger Fokuswert wird ignoriert`() = assertFalse(ManualSettingsEntity(full).setFocus(Float.NaN))

    @Test fun `Automatik setzt alles zurueck`() {
        val e = ManualSettingsEntity(full); e.setExposure(1_000_000, 100); e.setFocus(2f); e.auto()
        assertNull(e.exposureNanos); assertNull(e.iso); assertNull(e.focusDiopters)
    }

    @Test fun `Fokusreihe geht von nah nach fern und haelt Grenzen ein`() {
        val r = ManualSettingsEntity(full).focusBracket(5)
        assertEquals(listOf(10f, 7.5f, 5f, 2.5f, 0f), r)
        assertEquals(2, ManualSettingsEntity(full).focusBracket(1).size); assertEquals(15, ManualSettingsEntity(full).focusBracket(99).size)
    }
}
