package app.cayresim.core.entity

/** Modi aus Sicht der Regeln. Die Boundary hat eigene Typen; der Adapter uebersetzt (R5). */
enum class ModeKey { NORMAL, AUTO, NIGHT, HDR, BOKEH, FACE_RETOUCH }

/**
 * Regeln fuer die Moduswahl (R13, R14):
 * - NORMAL ist immer verfuegbar.
 * - Ist der gewuenschte Modus nicht verfuegbar oder ist das Binden gescheitert, gilt NORMAL,
 *   und es wird vermerkt, von welchem Modus zurueckgefallen wurde.
 */
class ModeSelectionEntity {
    private var available: Set<ModeKey> = setOf(ModeKey.NORMAL)
    private var requested: ModeKey = ModeKey.NORMAL
    private val failed = mutableSetOf<ModeKey>()

    val requestedMode: ModeKey get() = requested

    val effectiveMode: ModeKey
        get() = if (requested in available && requested !in failed) requested else ModeKey.NORMAL

    /** Modus, von dem zurueckgefallen wurde, sonst null. */
    val fallbackFrom: ModeKey?
        get() = if (effectiveMode != requested) requested else null

    /** Angebotene Modi in fester Reihenfolge, ohne gescheiterte. */
    val offeredModes: List<ModeKey>
        get() = ModeKey.entries.filter { it in available && it !in failed }

    fun updateAvailable(modes: Set<ModeKey>) {
        available = modes + ModeKey.NORMAL
    }

    fun request(mode: ModeKey) {
        requested = mode
    }

    /** Binden ist fuer diesen Modus gescheitert: bis zum naechsten Neustart nicht mehr anbieten. */
    fun markFailed(mode: ModeKey) {
        if (mode != ModeKey.NORMAL) failed += mode
    }

    fun resetFailures() = failed.clear()
}
