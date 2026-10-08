package app.cayresim.core.entity

import app.cayresim.core.pure.SeededRng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TriggerEntityTest {
    @Test fun `Bewegung loest aus, Ruhe nicht`() {
        val t = TriggerEntity(TriggerKind.MOTION, warmupFrames = 0)
        assertFalse(t.onScore(1f, 0)); assertFalse(t.onScore(30f, 100), "ein einzelnes Bild reicht nicht")
        assertTrue(t.onScore(30f, 133))
    }

    @Test fun `Nach dem Ausloesen gilt die Pause`() {
        val t = TriggerEntity(TriggerKind.MOTION, cooldownMillis = 1000, confirmFrames = 1, warmupFrames = 0)
        assertTrue(t.onScore(50f, 0)); assertFalse(t.onScore(50f, 500)); assertTrue(t.onScore(50f, 1000))
    }

    @Test fun `Ruhe loest erst nach genug ruhigen Bildern aus`() {
        val t = TriggerEntity(TriggerKind.STILLNESS, stillFrames = 5, cooldownMillis = 0)
        repeat(4) { assertFalse(t.onScore(0.5f, it.toLong())) }
        assertTrue(t.onScore(0.5f, 4))
    }

    @Test fun `Eine Bewegung setzt die Ruhezaehlung zurueck`() {
        val t = TriggerEntity(TriggerKind.STILLNESS, stillFrames = 3, cooldownMillis = 0)
        t.onScore(0f, 0); t.onScore(0f, 1); t.onScore(10f, 2)
        assertFalse(t.onScore(0f, 3)); assertFalse(t.onScore(0f, 4)); assertTrue(t.onScore(0f, 5))
    }

    @Test fun `Unsinnige Werte gelten als keine Bewegung`() {
        val t = TriggerEntity(TriggerKind.MOTION, warmupFrames = 0)
        assertFalse(t.onScore(Float.NaN, 0)); assertFalse(t.onScore(-5f, 1))
    }

    @Test fun `Ungueltige Einstellungen werden abgelehnt`() {
        assertFailsWith<IllegalArgumentException> { TriggerEntity(TriggerKind.MOTION, threshold = 0f) }
        assertFailsWith<IllegalArgumentException> { TriggerEntity(TriggerKind.STILLNESS, stillFrames = 0) }
    }

    @Test fun `Zwischen zwei Ausloesungen liegt immer mindestens die Pause (Eigenschaft)`() {
        val rng = SeededRng(3)
        repeat(200) {
            val t = TriggerEntity(if (rng.nextInt(2) == 0) TriggerKind.MOTION else TriggerKind.STILLNESS, cooldownMillis = 300)
            var now = 0L; var last = -1L
            repeat(500) {
                now += 1 + rng.nextInt(60)
                if (t.onScore(rng.nextInt(40).toFloat(), now)) { if (last >= 0) assertTrue(now - last >= 300); last = now }
            }
        }
    }

    @Test fun `Waermebudget kuerzt Serien`() {
        val b = ThermalBudgetEntity()
        assertEquals(20, b.framesFor(20, null)); assertEquals(20, b.framesFor(20, 0.3f))
        assertEquals(10, b.framesFor(20, 0.85f)); assertEquals(3, b.framesFor(20, 0.99f))
        assertEquals(20, b.framesFor(20, Float.NaN))
    }

    @Test fun `Waermebudget haelt Grenzen ein`() {
        val b = ThermalBudgetEntity()
        assertEquals(3, b.framesFor(1, null)); assertEquals(ThermalBudgetEntity.MAX_FRAMES, b.framesFor(500, null))
        assertEquals(3, b.framesFor(4, 0.85f))
    }

    // ---------- Robustheit (Dossier: Rauschen im Dunkeln, Helligkeitsspruenge) ----------

    @Test fun `Fehlerfall ein einzelner Helligkeitssprung loest nicht aus`() {
        val t = TriggerEntity(TriggerKind.MOTION, cooldownMillis = 0)
        repeat(20) { assertFalse(t.onScore(2f, it.toLong())) }
        assertFalse(t.onScore(40f, 20)) // Belichtungsautomatik springt einmal
        repeat(20) { assertFalse(t.onScore(2f, 21L + it)) }
    }

    @Test fun `Fehlerfall starkes Rauschen im Dunkeln loest nicht dauernd aus`() {
        val t = TriggerEntity(TriggerKind.MOTION, cooldownMillis = 0)
        val rng = SeededRng(8)
        var fires = 0
        // Rauschen um 10 mit Ausschlaegen bis 18: ueber der festen Schwelle 12, aber kein echtes Motiv
        repeat(300) { if (t.onScore(6f + rng.nextInt(13), it.toLong())) fires++ }
        assertTrue(fires <= 3, "$fires Fehlausloesungen")
        assertTrue(t.motionThreshold > 30f, "Grundrauschen wurde nicht gelernt: ${t.motionThreshold}")
    }

    @Test fun `Guter Fall echte Bewegung im Dunkeln loest trotzdem aus`() {
        val t = TriggerEntity(TriggerKind.MOTION, cooldownMillis = 0)
        val rng = SeededRng(9)
        repeat(100) { t.onScore(6f + rng.nextInt(13), it.toLong()) }
        assertFalse(t.onScore(80f, 100)); assertTrue(t.onScore(80f, 101))
    }

    @Test fun `Ungueltige Bestaetigung`() {
        assertFailsWith<IllegalArgumentException> { TriggerEntity(TriggerKind.MOTION, confirmFrames = 0) }
        assertFailsWith<IllegalArgumentException> { TriggerEntity(TriggerKind.MOTION, warmupFrames = -1) }
    }
}
