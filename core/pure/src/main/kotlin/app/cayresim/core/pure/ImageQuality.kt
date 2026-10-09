package app.cayresim.core.pure

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Messgroessen fuer die Bildqualitaet (Testlabor, Bildqualitaets-Dossier Abschnitt "Mittlere Helligkeit
 * ist kein Qualitaetsmass"). Immer paarweise lesen: weniger Rauschen zaehlt nur, wenn die Schaerfe bleibt.
 * Alle Werte auf der Helligkeit (Luma, 0..255) eines RGB-Bildes mit 3 Bytes je Pixel.
 */
object ImageQuality {
    data class Rect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        init { require(x1 > x0 && y1 > y0) }
    }

    fun luma(rgb: ByteArray, i: Int): Int =
        ((rgb[i].toInt() and 0xFF) * 54 + (rgb[i + 1].toInt() and 0xFF) * 183 + (rgb[i + 2].toInt() and 0xFF) * 19) shr 8

    /** Mittlere Helligkeit in einem Bereich. */
    fun mean(rgb: ByteArray, width: Int, r: Rect): Double {
        var s = 0L; var n = 0
        for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) { s += luma(rgb, (y * width + x) * 3); n++ }
        return s.toDouble() / n
    }

    /** Streuung der Helligkeit in einer gleichmaessigen Flaeche (Rauschen). */
    fun noise(rgb: ByteArray, width: Int, r: Rect): Double {
        val m = mean(rgb, width, r)
        var s = 0.0; var n = 0
        for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) { val d = luma(rgb, (y * width + x) * 3) - m; s += d * d; n++ }
        return sqrt(s / n)
    }

    /** Relatives Rauschen (Streuung durch Mittelwert): vergleichbar zwischen unterschiedlich hellen Ergebnissen. */
    fun relativeNoise(rgb: ByteArray, width: Int, r: Rect): Double = noise(rgb, width, r) / mean(rgb, width, r).coerceAtLeast(0.5)

    /**
     * Kantenbreite in Pixeln: Abstand vom 10-%- zum 90-%-Punkt des Uebergangs einer senkrechten Kante,
     * gemittelt ueber die Zeilen [y0, y1). Kleiner ist schaerfer; Verwackeln und Weichzeichnen machen sie breiter.
     */
    fun edgeWidth(rgb: ByteArray, width: Int, y0: Int, y1: Int, xFrom: Int, xTo: Int): Double {
        require(xTo - xFrom >= 8)
        val profile = DoubleArray(xTo - xFrom)
        for (y in y0 until y1) for (x in xFrom until xTo) profile[x - xFrom] += luma(rgb, (y * width + x) * 3).toDouble()
        for (i in profile.indices) profile[i] /= (y1 - y0)
        val lo = profile.take(3).average(); val hi = profile.takeLast(3).average()
        if (abs(hi - lo) < 1e-6) return profile.size.toDouble()
        fun cross(level: Double): Double {
            val t = lo + (hi - lo) * level
            for (i in 1 until profile.size) {
                val a = profile[i - 1]; val b = profile[i]
                if ((a - t) * (b - t) <= 0 && a != b) return i - 1 + (t - a) / (b - a)
            }
            return profile.size.toDouble()
        }
        return abs(cross(0.9) - cross(0.1))
    }

    /**
     * Kantenbreite fuer stark verrauschte Bilder (S-001): an das Profil wird eine weiche Stufe angepasst
     * (logistische Kurve, kleinste Quadrate), Ergebnis ist deren Abstand vom 10-%- zum 90-%-Punkt.
     * [edgeWidth] sucht die ersten Schnittpunkte und springt bei Restrauschen auf einzelne Ausreisser: im
     * Python-Modell lieferte es fuer ein ungefiltertes, also scharfes Mittel bis 3 Pixel. Diese Messung ist stabil
     * (8 Rauschmuster: 0,3 bis 0,7 Pixel ohne Entrauschen). Eine ideale Pixelstufe ergibt etwa 0,2.
     */
    fun edgeWidthFit(rgb: ByteArray, width: Int, y0: Int, y1: Int, xFrom: Int, xTo: Int): Double {
        require(xTo - xFrom >= 12 && y1 > y0)
        val n = xTo - xFrom
        val p = DoubleArray(n)
        for (y in y0 until y1) for (x in xFrom until xTo) p[x - xFrom] += luma(rgb, (y * width + x) * 3).toDouble()
        for (i in 0 until n) p[i] /= (y1 - y0)
        val pm = p.average()
        val f = DoubleArray(n)
        var bestErr = Double.MAX_VALUE; var bestS = 0.0
        var s = 0.05
        while (s < 4.0) {
            var x0 = 4.0
            while (x0 <= n - 4.0) {
                for (i in 0 until n) f[i] = 1.0 / (1.0 + kotlin.math.exp(-(i + 0.5 - x0) / s))
                val fm = f.average()
                var cov = 0.0; var vf = 0.0
                for (i in 0 until n) { cov += (f[i] - fm) * (p[i] - pm); vf += (f[i] - fm) * (f[i] - fm) }
                val k = if (vf > 0) cov / vf else 0.0
                var err = 0.0
                for (i in 0 until n) { val d = p[i] - pm - k * (f[i] - fm); err += d * d }
                if (err < bestErr) { bestErr = err; bestS = s }
                x0 += 0.1
            }
            s += 0.05
        }
        return 2 * kotlin.math.ln(9.0) * bestS
    }

    /** Mittlere absolute Abweichung zweier Bilder in einem Bereich (Geisterbild-Rest). */
    fun meanAbsDiff(a: ByteArray, b: ByteArray, width: Int, r: Rect): Double {
        require(a.size == b.size)
        var s = 0L; var n = 0
        for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) { val i = (y * width + x) * 3; s += abs(luma(a, i) - luma(b, i)); n++ }
        return s.toDouble() / n
    }

    /** Anteil fast weisser Pixel (ausgebrannt) im Bereich. */
    fun clipped(rgb: ByteArray, width: Int, r: Rect, level: Int = 250): Double {
        var c = 0; var n = 0
        for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) { if (luma(rgb, (y * width + x) * 3) >= level) c++; n++ }
        return c.toDouble() / n
    }
}
