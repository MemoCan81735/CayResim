package app.cayresim.core.pure

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

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

    /** Hoechste Verstaerkung fuer ein Einzelbild (4 Blenden); mehr macht bei 8-Bit-Eingang nur Rauschen sichtbar. */
    const val MAX_GAIN = 16f

    /** Hoechste Verstaerkung ueberhaupt (6 Blenden), erst ab 36 gemittelten Bildern. */
    const val MAX_STACK_GAIN = 64f

    /**
     * Mittelt man n Bilder, sinkt das Rauschen um Wurzel(n); um so viel darf die Verstaerkung steigen, ohne dass
     * das Ergebnis mehr rauscht als ein Einzelbild mit [MAX_GAIN]. 1 Bild: 16, 23 Bilder: etwa 51, 36 Bilder: 64.
     */
    fun maxGainFor(frames: Int): Float =
        (MAX_GAIN * kotlin.math.sqrt(frames.coerceAtLeast(1).toFloat()) / 1.5f).coerceIn(MAX_GAIN, MAX_STACK_GAIN)

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

    /** Verstaerkung, die den Median der Helligkeit auf [TARGET_MEDIAN] hebt, begrenzt auf 1 bis [maxGain]. */
    fun gainFor(linear: FloatArray, maxGain: Float = MAX_GAIN): Float {
        val median = lumaPercentile(linear, 0.5f)
        if (median <= 0f) return maxGain
        return (TARGET_MEDIAN / median).coerceIn(1f, maxGain)
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
    fun brighten(linear: FloatArray, width: Int, maxGain: Float = MAX_GAIN): Result {
        require(width > 0 && linear.size % 3 == 0 && (linear.size / 3) % width == 0) { "Ungueltige Bildgroesse" }
        val gain = gainFor(linear, maxGain)
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

    // ---------- Nacht-Look (Nachttest S24+ vom 8. Oktober: flau, grauer Schleier, grobes Korn) ----------

    /** A: Schwarzpunkt aus dem dunkelsten halben Prozent, hoechstens ein Viertel des Medians (sonst kippen flache Szenen). */
    const val BLACK_QUANTILE = 0.005f
    const val BLACK_MAX_SHARE = 0.25f

    /** B: Lichter sollen bis hierhin reichen (linear, etwa sRGB 188); der Kontrast steigt dafuer hoechstens um [MAX_CONTRAST]. */
    const val WHITE_TARGET = 0.5f
    const val MAX_CONTRAST = 1.6f

    /**
     * Lichterschutz (Nachttest S24+ am 9. Oktober: Vorhang bei 249, Samsung 201): sind die hellsten 2 % zu hell,
     * wird der Kontrast um den Median bis auf [MIN_CONTRAST] gesenkt. Die 2 % sorgen dafuer, dass eine einzelne
     * kleine Lampe nicht das ganze Bild flau macht; sie darf weiter ausbrennen.
     */
    const val MIN_CONTRAST = 0.6f
    const val COMPRESS_QUANTILE = 0.98f

    /** C: Radius fuer das Glaetten des Farbrauschens (zweimal angewendet). */
    const val CHROMA_RADIUS = 3

    /**
     * Fast kein Licht (Nachttest S24+ am 9. Oktober, lichtloser Raum mit Tuer): bestehen mindestens [FLOOR_SHARE] der
     * Einzelbilder aus Werten 0 und 1, ist die Bildmitte nur der Rauschboden. Dann wird der Boden je Kanal zu Schwarz,
     * und aufgehellt wird nach den hellen Stellen ([FLOOR_HIGH_TARGET] fuer das 99-%-Quantil) statt nach dem Median.
     * Im Testlabor lag die Kueche-aehnliche Szene bei 67 %, der lichtlose Raum bei 98 bis 100 %.
     */
    const val FLOOR_SHARE = 0.85f
    const val FLOOR_HIGH_TARGET = 0.1f

    /** D: Kantenerhaltendes Glaetten der Helligkeit: Radius und Staerke (Vielfaches des gemessenen Rauschens). */
    const val LUMA_RADIUS = 3
    const val DENOISE_STRENGTH = 2.5f

    /**
     * Fertigstellen eines gemittelten Nachtbilds (linear): A Schwarzpunkt abziehen, C Farbrauschen glaetten,
     * D Helligkeit kantenerhaltend entrauschen (gefuehrter Filter auf der Wurzel, dort ist Photonenrauschen
     * etwa gleich stark), dann verstaerken, B Kontrastkurve um den Ziel-Median, Schulter, Dithering.
     * Die Farben werden nicht kuenstlich verstaerkt: im Testlabor blieben sie ohne das am naechsten an der Wahrheit.
     */
    fun finishNight(linear: FloatArray, width: Int, maxGain: Float = MAX_GAIN, floorShare: Float = 0f): Result {
        require(width > 0 && linear.size % 3 == 0 && (linear.size / 3) % width == 0) { "Ungueltige Bildgroesse" }
        val n = linear.size / 3
        val height = n / width
        val y = FloatArray(n) { lumaOf(linear, it * 3) }
        val floor = floorShare >= FLOOR_SHARE
        val medianY = quantile(y, 0.5f)
        // A: Schwarzpunkt je Farbkanal, sonst wird der leicht unterschiedliche Boden der Kanaele zum Farbstich
        val bp = FloatArray(3) { c ->
            val ch = FloatArray(n) { linear[it * 3 + c] }
            (if (floor) quantile(ch, 0.5f * floorShare) else minOf(quantile(ch, BLACK_QUANTILE), BLACK_MAX_SHARE * medianY)).coerceAtLeast(0f)
        }
        val rgb = FloatArray(linear.size) { (linear[it] - bp[it % 3]).coerceAtLeast(0f) }
        for (p in 0 until n) y[p] = lumaOf(rgb, p * 3)
        val chroma = Array(3) { c -> FloatArray(n) { rgb[it * 3 + c] - y[it] } }
        for (d in chroma) { boxBlur(d, width, height, CHROMA_RADIUS); boxBlur(d, width, height, CHROMA_RADIUS) }
        val base = guidedSelf(FloatArray(n) { kotlin.math.sqrt(y[it]) }, width, height, LUMA_RADIUS, DENOISE_STRENGTH)
        for (p in 0 until n) { val v = base[p].coerceAtLeast(0f); base[p] = v * v }
        val gain: Float
        val contrast: Float
        if (floor) {
            val hi = quantile(base, 0.99f)
            gain = if (hi <= 0f) maxGain else (FLOOR_HIGH_TARGET / hi).coerceIn(1f, maxGain)
            contrast = 1f
        } else {
            val median = quantile(base, 0.5f)
            gain = if (median <= 0f) maxGain else (TARGET_MEDIAN / median).coerceIn(1f, maxGain)
            val high = quantile(base, 0.995f) * gain
            fun toWhite(v: Float) = kotlin.math.ln(WHITE_TARGET / TARGET_MEDIAN) / kotlin.math.ln(v / TARGET_MEDIAN)
            contrast = when {
                high <= TARGET_MEDIAN * 1.01f -> 1f
                high < WHITE_TARGET -> toWhite(high).coerceIn(1f, MAX_CONTRAST)
                else -> {
                    val body = quantile(base, COMPRESS_QUANTILE) * gain
                    if (body > WHITE_TARGET) toWhite(body).coerceIn(MIN_CONTRAST, 1f) else 1f
                }
            }
        }
        val out = ByteArray(linear.size)
        for (p in 0 until n) {
            val x = p % width; val row = p / width
            val dither = (BAYER[(row and 3) * 4 + (x and 3)] + 0.5f) / 16f - 0.5f
            val b0 = base[p]
            val toned = shoulder(TARGET_MEDIAN * (b0 * gain / TARGET_MEDIAN).pow(contrast))
            for (c in 0 until 3) {
                val v = if (b0 > 1e-9f) (b0 + chroma[c][p]).coerceAtLeast(0f) * (toned / b0) else toned
                out[p * 3 + c] = (linearToSrgb(v) * 255f + dither + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return Result(out, gain)
    }

    private fun lumaOf(a: FloatArray, i: Int) = 0.2126f * a[i] + 0.7152f * a[i + 1] + 0.0722f * a[i + 2]

    /** Quantil ueber hoechstens 65.536 gleichmaessig verteilte Stichproben (genau genug, schnell, speicherarm). */
    internal fun quantile(values: FloatArray, q: Float): Float {
        if (values.isEmpty()) return 0f
        val stride = maxOf(1, values.size / 65_536)
        val sample = FloatArray((values.size + stride - 1) / stride) { values[it * stride] }
        sample.sort()
        return sample[(q.coerceIn(0f, 1f) * (sample.size - 1)).roundToInt()]
    }

    /** Kastenfilter in place, getrennt nach Zeilen und Spalten, Raender wiederholt. Laufzeit unabhaengig vom Radius. */
    internal fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int) {
        if (r <= 0) return
        require(a.size == w * h) { "Falsche Groesse" }
        val tmp = FloatArray(maxOf(w, h))
        val inv = 1.0 / (2 * r + 1)
        for (y in 0 until h) {
            val o = y * w
            var s = 0.0
            for (k in -r..r) s += a[o + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[x] = (s * inv).toFloat()
                s += a[o + (x + r + 1).coerceAtMost(w - 1)] - a[o + (x - r).coerceAtLeast(0)]
            }
            tmp.copyInto(a, o, 0, w)
        }
        for (x in 0 until w) {
            var s = 0.0
            for (k in -r..r) s += a[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                tmp[y] = (s * inv).toFloat()
                s += a[(y + r + 1).coerceAtMost(h - 1) * w + x] - a[(y - r).coerceAtLeast(0) * w + x]
            }
            for (y in 0 until h) a[y * w + x] = tmp[y]
        }
    }

    /**
     * Gefuehrter Filter mit dem Bild selbst als Fuehrung (He et al.): Flaechen werden geglaettet, Kanten bleiben,
     * weil dort die oertliche Streuung weit ueber dem Rauschen liegt. Rauschen geschaetzt aus dem Median der
     * Abweichung vom 3x3-Mittel. Liefert ein neues Feld.
     */
    internal fun guidedSelf(img: FloatArray, w: Int, h: Int, r: Int, strength: Float): FloatArray {
        val n = img.size
        val hp = img.copyOf(); boxBlur(hp, w, h, 1)
        for (i in 0 until n) hp[i] = kotlin.math.abs(img[i] - hp[i])
        val sigma = 1.4826f * quantile(hp, 0.5f)
        val eps = (strength * sigma).let { it * it }
        if (eps <= 0f) return img.copyOf()
        val mean = img.copyOf(); boxBlur(mean, w, h, r)
        val sq = FloatArray(n) { img[it] * img[it] }; boxBlur(sq, w, h, r)
        val a = FloatArray(n) { val v = (sq[it] - mean[it] * mean[it]).coerceAtLeast(0f); v / (v + eps) }
        for (i in 0 until n) mean[i] = mean[i] - a[i] * mean[i] // wird b
        boxBlur(a, w, h, r); boxBlur(mean, w, h, r)
        return FloatArray(n) { a[it] * img[it] + mean[it] }
    }

    private const val BINS = 4096
    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
}
