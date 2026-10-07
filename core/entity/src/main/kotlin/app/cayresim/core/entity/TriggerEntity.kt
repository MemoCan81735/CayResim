package app.cayresim.core.entity

enum class TriggerKind { MOTION, STILLNESS }

/**
 * Regeln des intelligenten Ausloesers:
 * - MOTION: loest aus, sobald das Bewegungsmass ueber [threshold] liegt.
 * - STILLNESS: loest aus, wenn das Mass [stillFrames] Bilder hintereinander unter [threshold] liegt.
 * - Nach jedem Ausloesen [cooldownMillis] Pause, damit nicht jedes Bild ausloest.
 * Die Zeit kommt von aussen (R2).
 */
class TriggerEntity(
    val kind: TriggerKind,
    val threshold: Float = if (kind == TriggerKind.MOTION) 12f else 2.5f,
    val stillFrames: Int = 15,
    val cooldownMillis: Long = 2_000,
) {
    init {
        require(threshold > 0f) { "Schwelle muss positiv sein" }
        require(stillFrames >= 1) { "Mindestens ein ruhiges Bild" }
        require(cooldownMillis >= 0)
    }

    private var calm = 0
    private var lastFire = Long.MIN_VALUE

    /** true, wenn jetzt ausgeloest werden soll. Negative oder ungueltige Werte gelten als keine Bewegung. */
    fun onScore(score: Float, nowMillis: Long): Boolean {
        val s = if (score.isNaN() || score < 0f) 0f else score
        if (lastFire != Long.MIN_VALUE && nowMillis - lastFire < cooldownMillis) { calm = 0; return false }
        val fire = when (kind) {
            TriggerKind.MOTION -> s > threshold
            TriggerKind.STILLNESS -> { calm = if (s < threshold) calm + 1 else 0; calm >= stillFrames }
        }
        if (fire) { lastFire = nowMillis; calm = 0 }
        return fire
    }
}

/**
 * Waermebudget (R27): je weniger Reserve, desto kuerzer die Serie.
 * Headroom wie die Thermal API: 0 = kalt, 1 = Drosselung beginnt; null = unbekannt.
 */
class ThermalBudgetEntity(private val minFrames: Int = 3) {
    fun framesFor(requested: Int, headroom: Float?): Int {
        val r = requested.coerceIn(minFrames, MAX_FRAMES)
        val h = headroom ?: return r
        return when {
            h.isNaN() -> r
            h >= 0.95f -> minFrames
            h >= 0.8f -> maxOf(minFrames, r / 2)
            else -> r
        }
    }

    companion object { const val MAX_FRAMES = 30 }
}
