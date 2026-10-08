package app.cayresim.core.pure

import kotlin.math.exp
import kotlin.math.pow

/**
 * Mitteln in hoher Genauigkeit und Aufhellen dunkler Serien (Bildqualitaets-Dossier, Stufe P0).
 *
 * Warum: Der Mittelwert von 20 dunklen 8-Bit-Bildern ist wieder dunkel; gerundet auf 8 Bit gehen
 * dazu die Zwischenwerte verloren, die das Mitteln gewonnen hat. Deshalb wird linear (Licht statt
 * Gammawerte) in Gleitkomma summiert, danach verstaerkt, die Lichter weich begrenzt und beim
 * Zurueckwandeln geordnet gedithert, damit keine Treppen entstehen.
 *
 * Nacht bleibt Nacht: Ziel sind dunkle, aber erkennbare Mitteltoene (wie Night Sight), keine
 * Tagesbilder. Helle Szenen bleiben unveraendert, weil die Verstaerkung dort unter 1 laege.
 */
object NightTone {
    /** Ziel fuer den Median der Helligkeit, linear (entspricht etwa sRGB 40 von 255). */
    const val TARGET_MEDIAN = 0.021f

    /** Hoechste Verstaerkung (4 Blenden); mehr macht bei 8-Bit-Eingang nur Rauschen sichtbar. */
    const val MAX_GAIN = 16f

    /** Ab hier werden Lichter weich zusammengedrueckt statt hart abgeschnitten (linear). */
    const val SHOULDER = 0.6f

    /** Unterhalb dieser Verstaerkung wird nichts veraendert. */
    const val MIN_GAIN = 1.05f

    private val decode = FloatArray(256) { srgbToLinear(it / 255f) }

    fun srgbToLinear(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    fun linearToSrgb(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
    }

    /** Summe vieler Bilder in linearem Licht; ein Bild nach dem anderen, ohne alle zu speichern. */
    class LinearSum(val pixels: Int) {
        val sum = FloatArray(pixels * 3)
        var count = 0
            private set

        fun add(frame: ByteArray) {
            require(frame.size == pixels * 3) { "Bild hat die falsche Groesse" }
            for (i in sum.indices) sum[i] += decode[frame[i].toInt() and 0xFF]
            count++
        }

        /** Mittelwert in linearem Licht. */
        fun mean(): FloatArray {
            require(count > 0) { "Mindestens ein Bild" }
            val inv = 1f / count
            return FloatArray(sum.size) { sum[it] * inv }
        }
    }

    data class Result(val rgb: ByteArray, val gain: Float)

    /** Mittelt [frames] linear und hellt bei Bedarf auf. */
    fun meanAndBrighten(frames: List<ByteArray>, pixels: Int, width: Int): Result {
        require(frames.isNotEmpty()) { "Mindestens ein Bild" }
        val acc = LinearSum(pixels)
        frames.forEach { acc.add(it) }
        return brighten(acc.mean(), width)
    }

    /**
     * Langzeit mit hellen Lichtspuren: Potenzmittel in linearem Licht, (Summe x^k / n)^(1/k).
     * Mit k = 3 (wie Googles Long Exposure, "soft gamma") verblassen bewegte Lichter nicht zu grauen
     * Schlieren; ruhige Flaechen bleiben wie beim normalen Mittel. k = 1 ist der normale Mittelwert.
     */
    fun softGammaMeanAndBrighten(frames: List<ByteArray>, pixels: Int, width: Int, k: Float = SOFT_GAMMA): Result {
        require(frames.isNotEmpty()) { "Mindestens ein Bild" }
        require(k >= 1f) { "k muss mindestens 1 sein" }
        val acc = FloatArray(pixels * 3)
        for (f in frames) {
            require(f.size == pixels * 3) { "Bild hat die falsche Groesse" }
            for (i in acc.indices) acc[i] += decode[f[i].toInt() and 0xFF].pow(k)
        }
        val inv = 1f / frames.size; val ik = 1f / k
        for (i in acc.indices) acc[i] = (acc[i] * inv).pow(ik)
        return brighten(acc, width)
    }

    const val SOFT_GAMMA = 3f

    /** Ein fertiges 8-Bit-Bild (z. B. Median) bei Bedarf aufhellen. */
    fun brightenBytes(rgb: ByteArray, width: Int): Result =
        brighten(FloatArray(rgb.size) { decode[rgb[it].toInt() and 0xFF] }, width)

    /** Verstaerkung, die den Median der Helligkeit auf [TARGET_MEDIAN] hebt, begrenzt auf 1 bis [MAX_GAIN]. */
    fun gainFor(linear: FloatArray): Float {
        val median = lumaPercentile(linear, 0.5f)
        if (median <= 0f) return MAX_GAIN
        return (TARGET_MEDIAN / median).coerceIn(1f, MAX_GAIN)
    }

    /** Perzentil der linearen Helligkeit ueber ein Histogramm (4096 Stufen, schnell und speicherarm). */
    fun lumaPercentile(linear: FloatArray, q: Float): Float {
        val bins = IntArray(BINS)
        val n = linear.size / 3
        if (n == 0) return 0f
        for (p in 0 until n) {
            val i = p * 3
            val y = 0.2126f * linear[i] + 0.7152f * linear[i + 1] + 0.0722f * linear[i + 2]
            bins[(y.coerceIn(0f, 1f) * (BINS - 1)).toInt()]++
        }
        val target = (q.coerceIn(0f, 1f) * (n - 1)).toLong()
        var seen = 0L
        for (b in bins.indices) {
            seen += bins[b]
            if (seen > target) return (b + 0.5f) / (BINS - 1)
        }
        return 1f
    }

    /** Weiche Schulter: bis [SHOULDER] linear, darueber laeuft der Wert gegen 1, ohne hart abzuschneiden. */
    fun shoulder(y: Float): Float {
        if (y <= SHOULDER) return y
        val room = 1f - SHOULDER
        return SHOULDER + room * (1f - exp(-(y - SHOULDER) / room))
    }

    /**
     * Verstaerken, Schulter, zurueck nach sRGB mit geordnetem 4x4-Dithering (deterministisch).
     * Bei kleiner Verstaerkung nur zurueckwandeln, ohne die Tonwerte zu aendern.
     */
    fun brighten(linear: FloatArray, width: Int): Result {
        require(width > 0 && linear.size % 3 == 0 && (linear.size / 3) % width == 0) { "Ungueltige Bildgroesse" }
        val gain = gainFor(linear)
        val apply = gain >= MIN_GAIN
        val out = ByteArray(linear.size)
        val pixels = linear.size / 3
        for (p in 0 until pixels) {
            val x = p % width; val y = p / width
            val dither = (BAYER[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f - 0.5f
            for (c in 0 until 3) {
                val i = p * 3 + c
                val v = if (apply) shoulder(linear[i] * gain) else linear[i]
                val s = linearToSrgb(v) * 255f + dither
                out[i] = (s + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return Result(out, if (apply) gain else 1f)
    }

    private const val BINS = 4096
    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
}
