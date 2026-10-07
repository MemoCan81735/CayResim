package app.cayresim.core.entity

/** Grenzen der Kamera fuer manuelle Werte. Ein Bereich null heisst: nicht einstellbar. */
data class ManualLimits(
    val exposureNanos: LongRange?,
    val iso: IntRange?,
    /** 0 = unendlich, groesser = naeher (Dioptrien). null = Fixfokus. */
    val maxFocusDiopters: Float?,
)

/**
 * Regeln fuer manuelle Werte (Phase 4): Werte werden in die Grenzen der Kamera gelegt,
 * nicht einstellbare Werte bleiben automatisch. Belichtung und ISO gehoeren zusammen:
 * wer eines setzt, setzt beide (sonst regelt die Automatik das andere ungewollt nach).
 */
class ManualSettingsEntity(private val limits: ManualLimits) {
    var exposureNanos: Long? = null; private set
    var iso: Int? = null; private set
    var focusDiopters: Float? = null; private set

    val canExpose: Boolean get() = limits.exposureNanos != null && limits.iso != null
    val canFocus: Boolean get() = (limits.maxFocusDiopters ?: 0f) > 0f

    fun setExposure(nanos: Long, isoValue: Int): Boolean {
        val e = limits.exposureNanos ?: return false
        val i = limits.iso ?: return false
        exposureNanos = nanos.coerceIn(e.first, e.last); iso = isoValue.coerceIn(i.first, i.last); return true
    }

    fun setFocus(diopters: Float): Boolean {
        if (!canFocus || diopters.isNaN()) return false
        focusDiopters = diopters.coerceIn(0f, limits.maxFocusDiopters!!); return true
    }

    fun auto() { exposureNanos = null; iso = null; focusDiopters = null }

    /** Gleichmaessig verteilte Fokusschritte von nah nach fern fuer das Fokus-Stacking. */
    fun focusBracket(steps: Int): List<Float> {
        if (!canFocus) return emptyList()
        val n = steps.coerceIn(2, 15); val max = limits.maxFocusDiopters!!
        return List(n) { k -> max * (n - 1 - k) / (n - 1) }
    }
}
