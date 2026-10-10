package app.cayresim.core.pure

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tonrechnung fuer den Mikrofon-Test (S-008, R28): Pegel, Kanalvergleich, Klatsch-Erkennung und Richtung aus der
 * Laufzeitdifferenz zweier Kanaele (GCC-PHAT). Abtastwerte als Float von -1 bis 1, 16-Bit-PCM als ShortArray
 * (verschraenkt, Kanal fuer Kanal je Frame).
 */
object AudioMath {
    /** Schallgeschwindigkeit in Luft bei etwa 20 Grad, m/s. */
    const val SPEED_OF_SOUND = 343.0

    /** Laufzeit von Kanal a gegenueber b: positiv heisst, a hoert spaeter. [peak]: Hoehe des Maximums, 0 bis 1. */
    data class Delay(val seconds: Double, val peak: Double)

    /** Kennzahlen einer Aufnahme; Pegel in dBFS (0 = Vollaussteuerung), -inf bei Stille. */
    data class RecordingStats(
        val frames: Int,
        val channels: Int,
        val levelDbfs: List<Double>,
        val peakDbfs: List<Double>,
        /** Anteil der Frames, in denen Kanal 0 und 1 exakt gleich sind (1 bei Mono). */
        val identicalShare: Double,
        /** Korrelation der Kanaele 0 und 1 ohne Versatz, -1 bis 1 (1 bei Mono). */
        val correlation: Double,
        /**
         * Korrelation der Aenderungen von Abtastwert zu Abtastwert (einfacher Hochpass), -1 bis 1 (1 bei Mono).
         * Tiefe Raumgeraeusche erreichen zwei Mikrofone gleich und heben [correlation] bis 0,86; hier bleiben zwei
         * Mikrofone bei 0 und ein einzelnes bei etwa 0,6 (S-009, Geraetetest S24+).
         */
        val diffCorrelation: Double = correlation,
    ) {
        /**
         * Zwei wirklich verschiedene Mikrofone: nicht doppeltes Mono, nicht ein Mikrofon auf beiden Kanaelen
         * (Korrelation der Aenderungen unter [DIFF_CORRELATION_LIMIT]) und keiner stumm.
         */
        val distinctChannels: Boolean
            get() = channels >= 2 && frames > 0 && identicalShare < IDENTICAL_LIMIT && kotlin.math.abs(diffCorrelation) < DIFF_CORRELATION_LIMIT &&
                levelDbfs.take(2).all { it > SILENT_DBFS }
    }

    /**
     * Eichung der Klatsch-Probe aus links und rechts (S-009): Laufzeit = Mitte + halbe Spanne * sin(Winkel).
     * [halfSpanSeconds] ist positiv, wenn rechts die groessere Laufzeit hat; das Vorzeichen des Geraets faellt so heraus.
     */
    data class Calibration(val centerSeconds: Double, val halfSpanSeconds: Double) {
        /** Wirksamer Mikrofonabstand: Strecke, die der Schall in der halben Spanne zuruecklegt. */
        val spacingMeters: Double get() = abs(halfSpanSeconds) * SPEED_OF_SOUND
    }

    enum class CalibrationFailure { TOO_FEW_CLAPS, SIDES_NOT_DISTINCT, IMPLAUSIBLE }

    sealed interface CalibrationResult {
        data class Ok(val calibration: Calibration) : CalibrationResult
        data class Failed(val reason: CalibrationFailure) : CalibrationResult
    }

    /** Ergebnis je Klatscher. [angleDegrees]: null, wenn die Laufzeit physikalisch nicht moeglich ist. */
    data class Clap(
        val sample: Int,
        val delaySeconds: Double,
        val angleDegrees: Double?,
        /** Aehnlichkeit der Kanaele beim besten Versatz, 0 bis 1. */
        val similarity: Double,
        /** Pegel Kanal a minus Kanal b in dB. */
        val levelDiffDb: Double,
        val peak: Double,
    )

    const val IDENTICAL_LIMIT = 0.999
    const val DIFF_CORRELATION_LIMIT = 0.3
    const val MIN_CALIBRATION_CLAPS = 3
    /** Kleinster Unterschied der Mediane links und rechts; darunter liegt die Mikrofonachse nicht quer (hochkant). */
    const val MIN_SIDE_DIFFERENCE_SECONDS = 0.0001
    const val MIN_CALIBRATED_SPACING = 0.03
    const val MAX_CALIBRATED_SPACING = 0.30
    const val SILENT_DBFS = -90.0

    fun dbfs(rms: Double): Double = if (rms <= 0.0) Double.NEGATIVE_INFINITY else 20 * log10(rms)

    /** Verschraenktes PCM in Kanaele, Werte -1 bis 1. Ein unvollstaendiger letzter Frame wird ignoriert. */
    fun deinterleave(pcm: ShortArray, channels: Int): List<FloatArray> {
        require(channels >= 1)
        val frames = pcm.size / channels
        return List(channels) { c -> FloatArray(frames) { f -> pcm[f * channels + c] / 32768f } }
    }

    fun analyze(pcm: ShortArray, channels: Int): RecordingStats {
        val ch = deinterleave(pcm, channels)
        val frames = ch[0].size
        val level = ch.map { x -> if (x.isEmpty()) Double.NEGATIVE_INFINITY else dbfs(sqrt(x.sumOf { it.toDouble() * it } / x.size)) }
        val peak = ch.map { x -> dbfs(x.maxOfOrNull { abs(it).toDouble() } ?: 0.0) }
        if (channels < 2 || frames == 0) return RecordingStats(frames, channels, level, peak, 1.0, 1.0)
        val a = ch[0]; val b = ch[1]
        var same = 0
        for (i in 0 until frames) if (pcm[i * channels] == pcm[i * channels + 1]) same++
        val da = FloatArray(maxOf(0, frames - 1)) { a[it + 1] - a[it] }
        val db = FloatArray(da.size) { b[it + 1] - b[it] }
        return RecordingStats(frames, channels, level, peak, same.toDouble() / frames, pearson(a, b, 0, 0, frames), pearson(da, db, 0, 0, da.size))
    }

    /**
     * Eichung aus den Laufzeiten der Klatscher links und rechts (Sekunden): je Seite der Median, robust gegen einzelne
     * Griffgeraeusche. NaN zaehlt nicht. Gruende ohne Eichung siehe [CalibrationFailure].
     */
    fun calibrate(left: List<Double>, right: List<Double>): CalibrationResult {
        val l = left.filter { it.isFinite() }; val r = right.filter { it.isFinite() }
        if (l.size < MIN_CALIBRATION_CLAPS || r.size < MIN_CALIBRATION_CLAPS) return CalibrationResult.Failed(CalibrationFailure.TOO_FEW_CLAPS)
        val ml = median(l); val mr = median(r)
        if (abs(mr - ml) < MIN_SIDE_DIFFERENCE_SECONDS) return CalibrationResult.Failed(CalibrationFailure.SIDES_NOT_DISTINCT)
        val c = Calibration((ml + mr) / 2, (mr - ml) / 2)
        if (c.spacingMeters !in MIN_CALIBRATED_SPACING..MAX_CALIBRATED_SPACING) return CalibrationResult.Failed(CalibrationFailure.IMPLAUSIBLE)
        return CalibrationResult.Ok(c)
    }

    /** Winkel nach der Eichung: links -90, vorne 0, rechts +90; null wie bei [angleDegrees], wenn mehr als 10 % ausserhalb. */
    fun calibratedAngle(delaySeconds: Double, c: Calibration): Double? {
        if (!delaySeconds.isFinite() || c.halfSpanSeconds == 0.0) return null
        val s = (delaySeconds - c.centerSeconds) / c.halfSpanSeconds
        if (abs(s) > 1.1) return null
        return Math.toDegrees(asin(s.coerceIn(-1.0, 1.0)))
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted(); val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /**
     * Laufzeitdifferenz von a gegenueber b mit GCC-PHAT, gesucht in +-[maxLagSeconds], verfeinert mit einer Parabel
     * durch das Maximum. null, wenn die Fenster ungleich lang oder kuerzer als 32 Werte sind.
     */
    fun gccPhat(a: FloatArray, b: FloatArray, sampleRate: Int, maxLagSeconds: Double): Delay? {
        if (a.size != b.size || a.size < 32 || sampleRate <= 0) return null
        var n = 1
        while (n < a.size * 2) n = n shl 1
        val ar = DoubleArray(n); val ai = DoubleArray(n); val br = DoubleArray(n); val bi = DoubleArray(n)
        val ma = a.average(); val mb = b.average()
        for (i in a.indices) { ar[i] = a[i] - ma; br[i] = b[i] - mb }
        fft(ar, ai, false); fft(br, bi, false)
        for (k in 0 until n) {
            // R = A * conj(B), auf Betrag 1 gebracht (Phasentransformation)
            val re = ar[k] * br[k] + ai[k] * bi[k]
            val im = ai[k] * br[k] - ar[k] * bi[k]
            val m = sqrt(re * re + im * im)
            if (m > 1e-20) { ar[k] = re / m; ai[k] = im / m } else { ar[k] = 0.0; ai[k] = 0.0 }
        }
        fft(ar, ai, true)
        val maxLag = (maxLagSeconds * sampleRate).toInt().coerceIn(1, n / 2 - 2)
        fun at(lag: Int) = ar[(lag + n) % n]
        var best = -maxLag
        for (lag in -maxLag..maxLag) if (at(lag) > at(best)) best = lag
        val y0 = at(best - 1); val y1 = at(best); val y2 = at(best + 1)
        val den = y0 - 2 * y1 + y2
        val frac = if (abs(den) > 1e-12 && best > -maxLag && best < maxLag) (0.5 * (y0 - y2) / den).coerceIn(-0.5, 0.5) else 0.0
        return Delay((best + frac) / sampleRate, y1.coerceIn(0.0, 1.0))
    }

    /**
     * Einfallswinkel zur Mikrofonachse aus der Laufzeit: 0 = senkrecht dazu (vorne oder hinten), +-90 = auf der Achse.
     * Bis 10 % ueber dem Maximum wird auf +-90 begrenzt (Rundung, Messfehler); darueber null (unplausibel).
     */
    fun angleDegrees(delaySeconds: Double, spacingMeters: Double): Double? {
        if (spacingMeters <= 0.0 || delaySeconds.isNaN()) return null
        val s = delaySeconds * SPEED_OF_SOUND / spacingMeters
        if (abs(s) > 1.1) return null
        return Math.toDegrees(asin(s.coerceIn(-1.0, 1.0)))
    }

    /**
     * Klatscher in einem Signal: Fenster von 5 ms, deren Energie mindestens 18 dB ueber dem Grundrauschen
     * (20-%-Quantil) und 15 dB ueber dem lautesten der 10 vorigen Fenster liegt. Langsam anschwellende Geraeusche
     * wie Sprache erfuellen das zweite nicht. Zwischen zwei Treffern mindestens 0,25 s. Liefert die Startstelle.
     */
    fun findClaps(x: FloatArray, sampleRate: Int, aboveFloorDb: Double = 18.0, riseDb: Double = 15.0, minGapSeconds: Double = 0.25): List<Int> {
        val w = sampleRate / 200
        if (w <= 0 || x.size < w * 12) return emptyList()
        val nw = x.size / w
        val e = DoubleArray(nw) { k -> var s = 0.0; for (i in k * w until (k + 1) * w) s += x[i].toDouble() * x[i]; s / w }
        val floor = e.sortedArray()[nw / 5].coerceAtLeast(1e-14)
        val above = Math.pow(10.0, aboveFloorDb / 10); val rise = Math.pow(10.0, riseDb / 10)
        val hits = mutableListOf<Int>()
        val gap = (minGapSeconds * sampleRate).toInt()
        for (k in 10 until nw) {
            if (e[k] < floor * above) continue
            var prev = 0.0
            for (j in k - 10 until k) prev = maxOf(prev, e[j])
            if (e[k] < rise * prev.coerceAtLeast(floor)) continue
            // genaue Startstelle: erster Wert ueber 30 % des oertlichen Maximums
            val from = (k - 1) * w; val to = minOf(x.size, (k + 2) * w)
            var pk = 0f
            for (i in from until to) pk = maxOf(pk, abs(x[i]))
            var start = from
            while (start < to && abs(x[start]) < 0.3f * pk) start++
            if (hits.isEmpty() || start - hits.last() >= gap) hits += start
        }
        return hits
    }

    /** Richtung je Klatscher aus zwei Kanaelen: Fenster von 2 ms vor bis 30 ms nach dem Start. */
    fun clapDirections(a: FloatArray, b: FloatArray, sampleRate: Int, spacingMeters: Double, starts: List<Int>): List<Clap> {
        val maxTau = spacingMeters / SPEED_OF_SOUND * 2
        val pre = sampleRate / 500; val len = sampleRate * 32 / 1000
        return starts.map { s ->
            val from = (s - pre).coerceAtLeast(0); val to = minOf(a.size, b.size, from + len)
            val wa = a.copyOfRange(from, to); val wb = b.copyOfRange(from, to)
            val d = gccPhat(wa, wb, sampleRate, maxTau)
            if (d == null) Clap(s, Double.NaN, null, 0.0, 0.0, 0.0)
            else {
                val lag = Math.round(d.seconds * sampleRate).toInt()
                val sim = pearson(wa, wb, maxOf(0, lag), maxOf(0, -lag), wa.size - abs(lag))
                val ra = sqrt(wa.sumOf { it.toDouble() * it } / wa.size); val rb = sqrt(wb.sumOf { it.toDouble() * it } / wb.size)
                val diff = if (ra > 0 && rb > 0) 20 * log10(ra / rb) else 0.0
                Clap(s, d.seconds, angleDegrees(d.seconds, spacingMeters), sim.coerceAtLeast(0.0), diff, d.peak)
            }
        }
    }

    /** Korrelation von a ab [oa] und b ab [ob] ueber [n] Werte. */
    private fun pearson(a: FloatArray, b: FloatArray, oa: Int, ob: Int, n: Int): Double {
        if (n <= 1) return 0.0
        var sa = 0.0; var sb = 0.0
        for (i in 0 until n) { sa += a[oa + i]; sb += b[ob + i] }
        val ma = sa / n; val mb = sb / n
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in 0 until n) { val x = a[oa + i] - ma; val y = b[ob + i] - mb; sab += x * y; saa += x * x; sbb += y * y }
        return if (saa <= 0 || sbb <= 0) 0.0 else sab / sqrt(saa * sbb)
    }

    /** Komplexe FFT an Ort und Stelle, Laenge eine Zweierpotenz; [inverse] teilt durch n. */
    internal fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        require(n == im.size && n > 0 && n and (n - 1) == 0) { "Laenge muss eine Zweierpotenz sein" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t; val u = im[i]; im[i] = im[j]; im[j] = u }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val p = i + k; val q = p + len / 2
                    val xr = re[q] * cr - im[q] * ci; val xi = re[q] * ci + im[q] * cr
                    re[q] = re[p] - xr; im[q] = im[p] - xi
                    re[p] += xr; im[p] += xi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}

/** WAV-Dateien mit 16-Bit-PCM (S-008). */
object Wav {
    class Decoded(val sampleRate: Int, val channels: Int, val samples: ShortArray)

    fun encode(pcm: ShortArray, sampleRate: Int, channels: Int): ByteArray {
        require(sampleRate > 0 && channels in 1..8)
        val data = pcm.size * 2
        val out = ByteArray(44 + data)
        fun ascii(o: Int, s: String) = s.forEachIndexed { i, c -> out[o + i] = c.code.toByte() }
        fun le32(o: Int, v: Int) { for (i in 0 until 4) out[o + i] = (v ushr (8 * i)).toByte() }
        fun le16(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v ushr 8).toByte() }
        ascii(0, "RIFF"); le32(4, 36 + data); ascii(8, "WAVE")
        ascii(12, "fmt "); le32(16, 16); le16(20, 1); le16(22, channels); le32(24, sampleRate)
        le32(28, sampleRate * channels * 2); le16(32, channels * 2); le16(34, 16)
        ascii(36, "data"); le32(40, data)
        for (i in pcm.indices) { val v = pcm[i].toInt(); out[44 + 2 * i] = v.toByte(); out[45 + 2 * i] = (v shr 8).toByte() }
        return out
    }

    /** Liest nur, was [encode] schreibt (PCM 16 Bit, Kopf 44 Byte); sonst null. */
    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size < 44) return null
        fun ascii(o: Int, n: Int) = String(CharArray(n) { (bytes[o + it].toInt() and 0xFF).toChar() })
        fun le32(o: Int) = (0 until 4).sumOf { (bytes[o + it].toInt() and 0xFF) shl (8 * it) }
        fun le16(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
        if (ascii(0, 4) != "RIFF" || ascii(8, 4) != "WAVE" || ascii(12, 4) != "fmt " || ascii(36, 4) != "data") return null
        if (le16(20) != 1 || le16(34) != 16) return null
        val channels = le16(22); val rate = le32(24); val data = minOf(le32(40), bytes.size - 44)
        if (channels <= 0 || rate <= 0) return null
        val s = ShortArray(data / 2) { i -> ((bytes[44 + 2 * i].toInt() and 0xFF) or (bytes[45 + 2 * i].toInt() shl 8)).toShort() }
        return Decoded(rate, channels, s)
    }
}
