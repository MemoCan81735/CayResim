package app.cayresim.core.entity

import kotlin.math.abs

/**
 * Zoom-Regeln: Grenzen, Schnellwahl-Stufen und Zwei-Finger-Zoom.
 * Reine Logik, unabhaengig von CameraX (R5).
 */
class ZoomEntity(min: Float, max: Float) {
    val min: Float = if (min.isFinite() && min > 0f) min else 1f
    val max: Float = if (max.isFinite() && max >= this.min) max else this.min

    /** Begrenzt einen Wunschwert; ungueltige Werte (NaN, unendlich) ergeben 1x innerhalb der Grenzen. */
    fun clamp(ratio: Float): Float = if (!ratio.isFinite()) clamp(1f) else ratio.coerceIn(min, max)

    /** Zwei-Finger-Zoom: neuer Wert aus aktuellem Wert und Spreizfaktor der Geste. */
    fun pinch(current: Float, scale: Float): Float =
        if (!scale.isFinite() || scale <= 0f) clamp(current) else clamp(current * scale)

    /**
     * Schnellwahl wie in der Samsung-Kamera: Weitwinkel (unter 1x), 1x und Tele 3x, soweit vorhanden.
     * Nur eine einzige Stufe ergibt keine Schnellwahl (leere Liste).
     */
    val presets: List<Float> = buildList {
        if (this@ZoomEntity.min < 0.95f) add(this@ZoomEntity.min)
        if (1f in this@ZoomEntity.min..this@ZoomEntity.max) add(1f)
        when {
            this@ZoomEntity.max >= TELE -> add(TELE)
            this@ZoomEntity.max >= 1.9f -> add(2f.coerceAtMost(this@ZoomEntity.max))
        }
    }.distinct().let { if (it.size < 2) emptyList() else it }

    /** Die Stufe, die zum aktuellen Wert passt (fuer die Hervorhebung), sonst null. */
    fun activePreset(ratio: Float): Float? = presets.firstOrNull { abs(it - ratio) < 0.05f }

    companion object {
        const val TELE = 3f
    }
}
