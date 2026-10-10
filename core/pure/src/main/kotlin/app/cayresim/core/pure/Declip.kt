package app.cayresim.core.pure

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * S-006: Signal unter dem Rauschboden zurueckrechnen. Das Handy schneidet jedes 8-Bit-Bild bei 0 ab; bei fast keinem
 * Licht ist das Mittel vieler Bilder dann nicht das Signal, sondern vor allem der abgeschnittene Rauschanteil
 * (etwa 0,4-mal das Rauschen). Im Nachttest S24+ am 10. Oktober wurde genau dieser Anteil 59-fach zu blaugrauem Nebel
 * aufgehellt (Stufe 39, Samsung: 2,5).
 *
 * Aus Mittel und Streuung je Pixel ueber die Bilder folgt bei abgeschnittenem Gauss-Rauschen eindeutig das wahre
 * Signal: Das Verhaeltnis Mittel durch Streuung haengt nur von Signal durch Rauschen ab (bei reinem Rauschen 0,68).
 * Die Umkehrung steht in einer Tabelle. Bei deutlichem Signal (Verhaeltnis ueber [R_MAX]) bleibt das Mittel unveraendert.
 */
object Declip {
    private const val T_MIN = -4.0
    private const val T_STEP = 0.01
    private const val N = 1001 // t von -4 bis 6
    /** Mittel des abgeschnittenen Rauschens ohne Licht, geteilt durch das Rauschen: 1 durch Wurzel aus 2 pi. */
    const val ZERO_SIGNAL_MEAN = 0.39894228f
    /** Ab diesem Verhaeltnis Mittel durch Streuung spielt das Abschneiden keine Rolle mehr. */
    const val R_MAX = 5.0

    private val tTable = DoubleArray(N) { T_MIN + it * T_STEP }
    private val rTable = DoubleArray(N)
    /** Streuung des abgeschnittenen Werts geteilt durch das Rauschen, je t. */
    private val gTable = DoubleArray(N)

    init {
        for (i in 0 until N) {
            val t = tTable[i]
            val cdf = phi(t); val pdf = exp(-t * t / 2) / sqrt(2 * Math.PI)
            val m1 = t * cdf + pdf
            val m2 = (t * t + 1) * cdf + t * pdf
            val g = sqrt((m2 - m1 * m1).coerceAtLeast(1e-12))
            rTable[i] = m1 / g; gTable[i] = g
        }
    }

    /** Verteilungsfunktion der Normalverteilung (Abramowitz und Stegun 7.1.26, Fehler unter 1e-7). */
    private fun phi(x: Double): Double {
        val z = abs(x) / sqrt(2.0)
        val t = 1 / (1 + 0.3275911 * z)
        val erf = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-z * z)
        return if (x >= 0) 0.5 * (1 + erf) else 0.5 * (1 - erf)
    }

    /**
     * Wahres Signal zu einem abgeschnittenen Mittel [mean] und mittleren Quadrat [meanSq] (beide linear). Darf negativ
     * werden (Rauschen ohne Licht); den Schwarzpunkt setzt danach [NightTone.finishNight].
     */
    fun signal(mean: Float, meanSq: Float): Float = solve(mean, meanSq)?.first ?: mean

    /** Mittel des abgeschnittenen Werts geteilt durch das Rauschen, je t (fuer [signalFromMean]). */
    private val mTable = DoubleArray(N) { val t = tTable[it]; t * phi(t) + exp(-t * t / 2) / sqrt(2 * Math.PI) }

    /**
     * Wahres Signal aus dem Mittel allein, bei bekanntem Rauschen [sigma] (fuer das ganze Bild geschaetzt). Viel ruhiger
     * als die Rechnung je Pixel, weil das Mittel ueber alle Bilder kaum streut; die Streuung je Pixel schwankt dagegen stark.
     */
    fun signalFromMean(mean: Float, sigma: Float): Float {
        if (sigma <= 0f) return mean
        val m = mean / sigma.toDouble()
        if (m >= mTable[N - 1]) return mean
        if (m <= mTable[0]) return (T_MIN * sigma).toFloat()
        var lo = 0; var hi = N - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (mTable[mid] <= m) lo = mid else hi = mid }
        val f = ((m - mTable[lo]) / (mTable[hi] - mTable[lo]).coerceAtLeast(1e-12)).coerceIn(0.0, 1.0)
        return ((tTable[lo] + f * (tTable[hi] - tTable[lo])) * sigma).toFloat()
    }

    /** Rauschen (Gauss, vor dem Abschneiden) zu Mittel und mittlerem Quadrat; null, wenn kein Abschneiden vorliegt. */
    fun noise(mean: Float, meanSq: Float): Float? = solve(mean, meanSq)?.second

    private fun solve(mean: Float, meanSq: Float): Pair<Float, Float>? {
        val v = meanSq.toDouble() - mean.toDouble() * mean
        if (v <= 1e-14 || mean <= 0f) return null
        val sd = sqrt(v)
        val r = mean / sd
        if (r >= R_MAX) return null
        // Tabelle ist monoton steigend: binaere Suche
        var lo = 0; var hi = N - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (rTable[mid] <= r) lo = mid else hi = mid }
        val f = ((r - rTable[lo]) / (rTable[hi] - rTable[lo]).coerceAtLeast(1e-12)).coerceIn(0.0, 1.0)
        val t = tTable[lo] + f * (tTable[hi] - tTable[lo]); val g = gTable[lo] + f * (gTable[hi] - gTable[lo])
        val sigma = sd / g
        return (t * sigma).toFloat() to sigma.toFloat()
    }
}
