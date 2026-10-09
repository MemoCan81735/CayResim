package app.cayresim.feature.camera.control

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

/** Regler-Umrechnung fuer das Pro-Panel. Belichtung logarithmisch, weil sie ueber viele Zehnerpotenzen reicht. */
object ProScale {
    /**
     * S-004: Kehrwertregel, 1 durch Brennweite (Hauptkamera etwa 24 mm Kleinbild): laenger als 1/24 s verwackelt
     * aus der Hand leicht. Nur ein Hinweis; der Stabilisator schafft oft mehr.
     */
    const val SHAKE_LIMIT_NS = 1_000_000_000L / 24

    fun fromSlider(v: Float, range: LongRange): Long {
        val lo = ln(range.first.coerceAtLeast(1).toDouble()); val hi = ln(range.last.coerceAtLeast(range.first + 1).toDouble())
        return exp(lo + v.coerceIn(0f, 1f) * (hi - lo)).roundToLong().coerceIn(range)
    }

    fun toSlider(nanos: Long, range: LongRange): Float {
        val lo = ln(range.first.coerceAtLeast(1).toDouble()); val hi = ln(range.last.coerceAtLeast(range.first + 1).toDouble())
        return ((ln(nanos.coerceIn(range).coerceAtLeast(1).toDouble()) - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
    }

    /** "1/250 s" fuer kurze, "0,5 s" oder "2 s" fuer lange Zeiten. */
    fun exposureText(nanos: Long): String {
        val s = nanos / 1e9
        return if (s < 0.5) "1/${(1 / s).roundToLong()} s" else if (s < 10) String.format(java.util.Locale.GERMANY, "%.1f s", s) else "${s.roundToLong()} s"
    }
}
