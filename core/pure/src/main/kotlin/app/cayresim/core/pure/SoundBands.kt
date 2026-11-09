package app.cayresim.core.pure

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Schallbild je Frequenzband aus zwei Mikrofonen (S-018, Stufe 1 der akustischen Kamera F11, R28).
 *
 * Rechenweg: Rahmen von [FRAME] Werten (Hann-Fenster, halb ueberlappt), je Rahmen das Spektrum beider Kanaele; je
 * Frequenz gleitend gemittelt (Zeitkonstante [TIME_CONSTANT_SECONDS]) die Leistung je Kanal und das Kreuzspektrum. Je
 * Band die Kreuzkorrelation ueber [GRID] moegliche Laufzeiten zwischen ganz links und ganz rechts; ihr Maximum ist die
 * Richtung, seine Hoehe (0 bis 1) der Anteil des Schalls aus dieser einen Richtung.
 *
 * Verworfen: GCC-PHAT je Band (im Hall unbrauchbar, Schwenk-Lauf S24+ vom 10.10.) und Gewichtung nach Gleichklang je
 * Frequenz (ein reiner Ton fuellt nur wenige Frequenzen eines Bands und fiele unter die Grenze).
 *
 * Vorzeichen wie in [AudioMath]: positive Laufzeit heisst, Kanal 0 hoert spaeter. Der Kosinus hier ist die Laufzeit
 * geteilt durch die halbe Spanne: +1 = Quelle am Ende der Achse, an dem Kanal 0 zuletzt hoert.
 */
object SoundBands {
    const val FRAME = 1024
    const val HOP = FRAME / 2
    /** Gleitendes Mittel: lang genug, dass unabhaengiges Rauschen sicher unter [MIN_SHARE] bleibt (K3), kurz genug zum Folgen (K7). */
    const val TIME_CONSTANT_SECONDS = 0.4
    /** Bandgrenzen in Hz: fuenf Oktaven. Darunter ist die Laufzeit zu klein gegen die Wellenlaenge, darueber kommt kaum Sprache oder Musik. */
    val EDGES_HZ: List<Int> = listOf(200, 400, 800, 1600, 3200, 6400)
    /** Stuetzstellen der Kurve je Band, gleichmaessig im Kosinus von -1 bis 1. */
    const val GRID = 61
    /**
     * Mindestanteil gerichteten Schalls; darunter kein Streifen. Unabhaengiges Rauschen erreicht eingeschwungen im
     * tiefsten Band (nur 4 Frequenzen) hoechstens 0,21 in 16.800 Momentaufnahmen, in den anderen weniger (Labor 9.11.).
     */
    const val MIN_SHARE = 0.3
    /**
     * Einschwingen: Streifen erst, wenn das gleitende Mittel diesen Anteil seines Gewichts erreicht hat (nach 0,8 s).
     * Vorher mittelt es ueber zu wenige Rahmen; unabhaengiges Rauschen kam im Labor in den ersten 2 s bis 0,39.
     */
    const val READY_WEIGHT = 0.86
    /** Mindestpegel eines Bands in dBFS; ruhiger Raum am S24+ gesamt -70 bis -79 dBFS (S-008). */
    const val MIN_LEVEL_DBFS = -75.0
    /** Helligkeit 1 ab diesem Pegel (Musik in 1 m am S24+ gesamt etwa -49 dBFS). */
    const val FULL_LEVEL_DBFS = -40.0
    /** Anteil der Schwerkraft auf der Querachse, ab dem das Handy als quer gilt (bis 60 Grad nach hinten geneigt). */
    const val LANDSCAPE_MIN = 0.5
    /**
     * S24+ (Schwenk-Lauf 10.10., 22:14 Uhr, und Klatsch-Probe 12:33 Uhr): positive Laufzeit heisst, die Quelle liegt am
     * unteren Ende des Handys. Vorlaeufig, bis der Geraetetest S-018 K19 es mit einer Quelle rechts bestaetigt.
     */
    const val DELAY_POSITIVE_TOWARDS_BOTTOM = true

    /**
     * Ein Band. [share]: Hoehe des Maximums der Kreuzkorrelation, 0 bis 1. [cosine]: Richtung (siehe Klassenkommentar),
     * null wenn das Band nicht gilt. [response]: die Kurve an [GRID] Stellen von Kosinus -1 bis 1, fuer die Anzeige
     * (ein Ton passt zu mehreren Laufzeiten).
     */
    data class Band(val lowHz: Int, val highHz: Int, val levelDbfs: Double, val share: Double, val cosine: Double?, val response: List<Float>) {
        val valid: Boolean get() = cosine != null
    }

    /** [mainCosine]: Maximum der gemittelten Kurven aller gueltigen Baender; null, wenn keines gilt. */
    data class Snapshot(val bands: List<Band>, val mainCosine: Double?, val mainShare: Double)

    /** Haltung des Handys, vom Nutzer aus gesehen mit dem Bildschirm zu ihm. */
    enum class Hold { TOP_LEFT, TOP_RIGHT, NOT_LANDSCAPE }

    /** Winkel zur Bildmitte in Grad aus dem Kosinus zur Achse: -90 bis +90. */
    fun angleDegrees(cosine: Double): Double = Math.toDegrees(asin(cosine.coerceIn(-1.0, 1.0)))

    fun gridCosine(index: Int): Double = -1.0 + 2.0 * index / (GRID - 1)

    /**
     * Haltung aus dem Beschleunigungssensor in Ruhe (zeigt im Geraet nach oben): x des Geraets nach oben heisst, die
     * Oberkante zeigt nach links. Hochkant, flach liegend oder ohne gueltigen Wert: nicht quer.
     */
    fun hold(ax: Double, ay: Double, az: Double): Hold {
        val len = sqrt(ax * ax + ay * ay + az * az)
        if (!len.isFinite() || len < 1.0) return Hold.NOT_LANDSCAPE
        val up = ax / len
        return when {
            up >= LANDSCAPE_MIN -> Hold.TOP_LEFT
            up <= -LANDSCAPE_MIN -> Hold.TOP_RIGHT
            else -> Hold.NOT_LANDSCAPE
        }
    }

    /** +1: positiver Kosinus liegt rechts im Bild; -1: links; 0: nicht quer. */
    fun screenSign(hold: Hold): Int {
        val bottomRight = when (hold) {
            Hold.TOP_LEFT -> 1
            Hold.TOP_RIGHT -> -1
            Hold.NOT_LANDSCAPE -> 0
        }
        return if (DELAY_POSITIVE_TOWARDS_BOTTOM) bottomRight else -bottomRight
    }

    /** Helligkeit eines Streifens: 0 bei [MIN_LEVEL_DBFS], 1 ab [FULL_LEVEL_DBFS], dazwischen gerade in dB. */
    fun brightness(levelDbfs: Double): Float =
        if (levelDbfs.isNaN()) 0f else ((levelDbfs - MIN_LEVEL_DBFS) / (FULL_LEVEL_DBFS - MIN_LEVEL_DBFS)).coerceIn(0.0, 1.0).toFloat()

    /**
     * Rechnet laufenden Ton ein. Feste Puffer, keine Felder je Rahmen (R19 sinngemaess); nicht fuer mehrere Threads.
     * [halfSpanSeconds]: Laufzeit einer Quelle auf der Achse (Mikrofonabstand durch Schallgeschwindigkeit),
     * [centerSeconds]: Laufzeit einer Quelle genau vorne.
     */
    class Analyzer(
        private val sampleRate: Int, halfSpanSeconds: Double, centerSeconds: Double = 0.0,
        timeConstantSeconds: Double = TIME_CONSTANT_SECONDS,
    ) {
        private val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / FRAME) }
        private val windowPower = window.sumOf { it * it }
        private val binHz = sampleRate.toDouble() / FRAME
        private val firstBin = ceil(EDGES_HZ.first() / binHz).toInt()
        private val bins = (ceil(EDGES_HZ.last() / binHz).toInt().coerceAtMost(FRAME / 2) - firstBin).coerceAtLeast(0)
        /** Erste Frequenz je Band, bezogen auf [firstBin]; ein Eintrag mehr als Baender. */
        private val bandStart = IntArray(EDGES_HZ.size) { (ceil(EDGES_HZ[it] / binHz).toInt().coerceAtMost(FRAME / 2) - firstBin).coerceIn(0, bins) }
        private val keep = exp(-HOP / (sampleRate * timeConstantSeconds))
        private val sxx = DoubleArray(bins); private val syy = DoubleArray(bins)
        private val cre = DoubleArray(bins); private val cim = DoubleArray(bins)
        /** Summe der Gewichte des gleitenden Mittels; gleicht den Anlauf aus. */
        private var weight = 0.0
        private val steerCos = DoubleArray(GRID * bins); private val steerSin = DoubleArray(GRID * bins)
        private val a = DoubleArray(FRAME); private val b = DoubleArray(FRAME)
        private var filled = 0
        private val ar = DoubleArray(FRAME); private val ai = DoubleArray(FRAME)
        private val br = DoubleArray(FRAME); private val bi = DoubleArray(FRAME)

        init {
            require(sampleRate > 0 && halfSpanSeconds > 0.0)
            for (i in 0 until GRID) {
                val tau = centerSeconds + gridCosine(i) * halfSpanSeconds
                for (k in 0 until bins) {
                    val phase = 2 * PI * (firstBin + k) * binHz * tau
                    steerCos[i * bins + k] = cos(phase); steerSin[i * bins + k] = sin(phase)
                }
            }
        }

        /** Verschraenktes 16-Bit-PCM mit mindestens zwei Kanaelen; anderes wird ignoriert. */
        fun add(pcm: ShortArray, channels: Int) {
            if (channels < 2) return
            val frames = pcm.size / channels
            for (f in 0 until frames) {
                a[filled] = pcm[f * channels] / 32768.0; b[filled] = pcm[f * channels + 1] / 32768.0
                if (++filled == FRAME) {
                    frame()
                    a.copyInto(a, 0, HOP, FRAME); b.copyInto(b, 0, HOP, FRAME)
                    filled = FRAME - HOP
                }
            }
        }

        private fun frame() {
            for (i in 0 until FRAME) { ar[i] = a[i] * window[i]; ai[i] = 0.0; br[i] = b[i] * window[i]; bi[i] = 0.0 }
            AudioMath.fft(ar, ai, false); AudioMath.fft(br, bi, false)
            val add = 1 - keep
            for (k in 0 until bins) {
                val j = firstBin + k
                sxx[k] = keep * sxx[k] + add * (ar[j] * ar[j] + ai[j] * ai[j])
                syy[k] = keep * syy[k] + add * (br[j] * br[j] + bi[j] * bi[j])
                // A * conj(B), wie in AudioMath.gccPhat
                cre[k] = keep * cre[k] + add * (ar[j] * br[j] + ai[j] * bi[j])
                cim[k] = keep * cim[k] + add * (ai[j] * br[j] - ar[j] * bi[j])
            }
            weight = keep * weight + add
        }

        fun snapshot(): Snapshot {
            val sum = DoubleArray(GRID)
            var valid = 0
            val bands = List(EDGES_HZ.size - 1) { n ->
                val from = bandStart[n]; val to = bandStart[n + 1]
                var pa = 0.0; var pb = 0.0
                for (k in from until to) { pa += sxx[k]; pb += syy[k] }
                val den = sqrt(pa * pb)
                val curve = DoubleArray(GRID)
                if (den > 0.0) for (i in 0 until GRID) {
                    var s = 0.0
                    val o = i * bins
                    for (k in from until to) s += cre[k] * steerCos[o + k] - cim[k] * steerSin[o + k]
                    curve[i] = s / den
                }
                // Leistung je Kanal im Band: Parseval mit Fenster, einseitiges Spektrum
                val power = if (weight > 0.0) (pa + pb) / 2 / weight * 2 / (FRAME * windowPower) else 0.0
                val level = if (power > 0.0) 10 * log10(power) else Double.NEGATIVE_INFINITY
                val peak = peak(curve)
                val ok = weight >= READY_WEIGHT && peak.second >= MIN_SHARE && level >= MIN_LEVEL_DBFS
                if (ok) { valid++; for (i in 0 until GRID) sum[i] += curve[i] }
                Band(EDGES_HZ[n], EDGES_HZ[n + 1], level, peak.second.coerceIn(0.0, 1.0), if (ok) peak.first else null, List(GRID) { curve[it].toFloat() })
            }
            if (valid == 0) return Snapshot(bands, null, 0.0)
            for (i in 0 until GRID) sum[i] /= valid
            val main = peak(sum)
            return Snapshot(bands, main.first, main.second.coerceIn(0.0, 1.0))
        }

        /** Lage (Kosinus) und Hoehe des Maximums, mit einer Parabel durch die drei hoechsten Stuetzstellen verfeinert. */
        private fun peak(curve: DoubleArray): Pair<Double, Double> {
            var best = 0
            for (i in 1 until GRID) if (curve[i] > curve[best]) best = i
            if (best == 0 || best == GRID - 1) return gridCosine(best) to curve[best]
            val y0 = curve[best - 1]; val y1 = curve[best]; val y2 = curve[best + 1]
            val den = y0 - 2 * y1 + y2
            val frac = if (abs(den) > 1e-12) (0.5 * (y0 - y2) / den).coerceIn(-0.5, 0.5) else 0.0
            return (gridCosine(best) + frac * 2.0 / (GRID - 1)) to (y1 - 0.25 * (y0 - y2) * frac)
        }
    }
}
