package app.cayresim.core.pure

import kotlin.math.abs

/**
 * Nacht-Kern (Bildqualitaets-Dossier, Stufe N-P1, erste Ausbaustufe auf 8-Bit-Bildern).
 *
 * Messwerte des S24+ fuer Drittanbieter-Apps: hoechstens 1/10 s je Bild, ISO bis 3200. Deshalb viele
 * kurze Bilder statt weniger langer: feste Belichtung, jedes Bild beim Eintreffen ausrichten und
 * gewichtet linear aufsummieren. Der Speicher waechst nicht mit der Bildzahl.
 */
object NightPlan {
    /** Ab diesem Lichtwert (Belichtung in Sekunden mal ISO) gilt eine Szene als dunkel: z. B. 1/15 s bei ISO 300. */
    const val DARK_LEVEL = 20.0

    /** Etwas heller als die Automatik: sie ist im Dunkeln an ihrer Grenze und belichtet zu knapp. */
    const val BRIGHTER = 1.5

    /** Obergrenze je Bild freihand; das Geraet erlaubt Drittanbietern ohnehin hoechstens 1/10 s. */
    const val MAX_FRAME_NS = 100_000_000L

    /**
     * Die Automatik gilt als am Anschlag, wenn sie lange belichtet ([AE_LIMIT_NS]) oder ihre ISO fast am Maximum
     * steht ([AE_LIMIT_ISO_SHARE]). Dann belichtet sie zu knapp, und die Nachtserie nimmt die hoechste ISO.
     * Gemessen am S24+ (8. Oktober, zweimal): Automatik bei etwa 1/25 s und ISO 3200, der Plan ergab nur ISO 1919.
     */
    const val AE_LIMIT_NS = 50_000_000L
    const val AE_LIMIT_ISO_SHARE = 0.9

    /** Bildzahl fuer etwa 4 s Licht bei 1/10 s je Bild. */
    const val FRAMES = 36

    data class Exposure(val exposureNs: Long, val iso: Int)

    /** Lichtwert der Automatik; null, wenn unbekannt. */
    fun level(exposureNs: Long, iso: Int): Double = exposureNs / 1e9 * iso

    fun isDark(exposureNs: Long?, iso: Int?): Boolean? =
        if (exposureNs == null || iso == null || exposureNs <= 0 || iso <= 0) null else level(exposureNs, iso) >= DARK_LEVEL

    /**
     * Feste Belichtung fuer die Serie aus dem, was die Automatik gerade misst. Erst die Belichtungszeit
     * ausreizen (weniger Rauschen), dann ISO; in hellen Szenen die Zeit kuerzen statt ISO unter das Minimum.
     */
    fun plan(aeExposureNs: Long, aeIso: Int, maxExposureNs: Long, isoMin: Int, isoMax: Int): Exposure {
        require(aeExposureNs > 0 && aeIso > 0 && maxExposureNs > 0 && isoMin in 1..isoMax) { "Ungueltige Messwerte" }
        val target = aeExposureNs.toDouble() * aeIso * BRIGHTER
        val exp = minOf(maxExposureNs, MAX_FRAME_NS)
        if (aeExposureNs >= AE_LIMIT_NS || aeIso >= AE_LIMIT_ISO_SHARE * isoMax) return Exposure(exp, isoMax)
        val iso = Math.round(target / exp).toInt()
        return when {
            iso > isoMax -> Exposure(exp, isoMax)
            iso >= isoMin -> Exposure(exp, iso)
            else -> Exposure((target / isoMin).toLong().coerceAtLeast(1), isoMin)
        }
    }
}

/**
 * Robuste, streamende Zusammenfuehrung: das erste Bild ist die Referenz, jedes weitere wird global
 * verschoben (Suche auf verkleinerter Helligkeit) und kachelweise gewichtet. Kacheln, die deutlich
 * mehr von der Referenz abweichen als ueblich (Bewegung), zaehlen wenig; verwackelte Bilder werden verworfen.
 */
class NightMerge(val width: Int, val height: Int, private val tile: Int = 32) {
    init { require(width >= 32 && height >= 32 && tile >= 8) { "Bild zu klein" } }

    private val pixels = width * height
    private val sum = FloatArray(pixels * 3)
    private val tilesX = (width + tile - 1) / tile
    private val tilesY = (height + tile - 1) / tile
    private val weight = FloatArray(tilesX * tilesY)
    private var refLuma: ByteArray? = null
    private var refSmall: ByteArray? = null
    private var refSharpness = 0.0
    private val sw = width / SCALE
    private val sh = height / SCALE
    private val maxShift = minOf(8, (sw - 1) / 2 - 1, (sh - 1) / 2 - 1).coerceAtLeast(0)

    var used = 0; private set
    var dropped = 0; private set

    /** Fuegt ein Bild hinzu. false = verworfen (verwackelt). */
    fun add(frame: ByteArray): Boolean {
        require(frame.size == pixels * 3) { "Bild hat die falsche Groesse" }
        val small = downscale(frame)
        val ref = refSmall
        if (ref == null) {
            refSmall = small; refLuma = luma(frame); refSharpness = sharpness(small)
            for (i in sum.indices) sum[i] = LIN[frame[i].toInt() and 0xFF]
            weight.fill(1f); used = 1
            return true
        }
        // Verwackelte Bilder (deutlich weniger Kanten als die Referenz) verschlechtern das Ergebnis
        if (refSharpness > 0 && sharpness(small) < BLUR_LIMIT * refSharpness) { dropped++; return false }
        val (sdx, sdy) = if (maxShift > 0) StarAlignment.estimateShift(ref, small, sw, sh, maxShift) else 0 to 0
        val rl = refLuma!!
        // Feinausrichtung in voller Aufloesung um die Grobschaetzung herum (die Grobstufe trifft nur auf 4 Pixel genau)
        val (dx, dy) = refine(rl, frame, sdx * SCALE, sdy * SCALE)
        // Abweichung je Kachel nach dem Ausrichten
        val diff = FloatArray(weight.size)
        val count = IntArray(weight.size)
        for (y in 0 until height step 2) {
            val sy = (y + dy).coerceIn(0, height - 1)
            for (x in 0 until width step 2) {
                val sx = (x + dx).coerceIn(0, width - 1)
                val s = (sy * width + sx) * 3
                val l = lumaAt(frame, s)
                val t = (y / tile) * tilesX + x / tile
                diff[t] += abs(l - (rl[y * width + x].toInt() and 0xFF)); count[t]++
            }
        }
        for (t in diff.indices) diff[t] = if (count[t] == 0) 0f else diff[t] / count[t]
        val typical = median(diff).coerceAtLeast(0.5f)
        val w = FloatArray(weight.size) { t ->
            val d = diff[t]
            if (d <= ROBUST * typical) 1f else (ROBUST * typical / d).let { it * it }
        }
        for (y in 0 until height) {
            val sy = (y + dy).coerceIn(0, height - 1)
            val rowT = (y / tile) * tilesX
            for (x in 0 until width) {
                val sx = (x + dx).coerceIn(0, width - 1)
                val wt = w[rowT + x / tile]
                val s = (sy * width + sx) * 3; val d = (y * width + x) * 3
                sum[d] += wt * LIN[frame[s].toInt() and 0xFF]
                sum[d + 1] += wt * LIN[frame[s + 1].toInt() and 0xFF]
                sum[d + 2] += wt * LIN[frame[s + 2].toInt() and 0xFF]
            }
        }
        for (t in weight.indices) weight[t] += w[t]
        used++
        return true
    }

    /** Sucht im Umkreis von [SCALE] - 1 Pixeln die beste Verschiebung auf der vollen Helligkeit (jedes 2. Pixel). */
    private fun refine(ref: ByteArray, frame: ByteArray, cx: Int, cy: Int): Pair<Int, Int> {
        val r = SCALE - 1
        val m = minOf(width, height) / 8
        var best = cx to cy; var bestErr = Long.MAX_VALUE
        for (dy in cy - r..cy + r) for (dx in cx - r..cx + r) {
            var err = 0L
            var y = m
            while (y < height - m) {
                val sy = (y + dy).coerceIn(0, height - 1)
                var x = m
                while (x < width - m) {
                    val sx = (x + dx).coerceIn(0, width - 1)
                    err += abs(lumaAt(frame, (sy * width + sx) * 3) - (ref[y * width + x].toInt() and 0xFF))
                    x += 2
                }
                y += 2
            }
            if (err < bestErr) { bestErr = err; best = dx to dy }
        }
        return best
    }

    /** Gewichtetes Mittel in linearem Licht, dann Nacht-Look (Schwarzpunkt, Entrauschen, Kontrast, Aufhellen). */
    fun finish(): NightTone.Result {
        check(used > 0) { "Kein Bild" }
        val mean = FloatArray(sum.size)
        for (p in 0 until pixels) {
            val t = ((p / width) / tile) * tilesX + (p % width) / tile
            val inv = 1f / weight[t]
            mean[p * 3] = sum[p * 3] * inv; mean[p * 3 + 1] = sum[p * 3 + 1] * inv; mean[p * 3 + 2] = sum[p * 3 + 2] * inv
        }
        return NightTone.finishNight(mean, width, NightTone.maxGainFor(used))
    }

    /** Mittlere Zahl der Bilder, die je Kachel wirklich beigetragen haben. */
    fun effectiveFrames(): Float = weight.average().toFloat()

    private fun downscale(f: ByteArray): ByteArray {
        val out = ByteArray(sw * sh * 3)
        for (y in 0 until sh) for (x in 0 until sw) for (c in 0 until 3) {
            var s = 0
            for (oy in 0 until SCALE) for (ox in 0 until SCALE) s += f[((y * SCALE + oy) * width + x * SCALE + ox) * 3 + c].toInt() and 0xFF
            out[(y * sw + x) * 3 + c] = (s / (SCALE * SCALE)).toByte()
        }
        return out
    }

    private fun luma(f: ByteArray) = ByteArray(pixels) { lumaAt(f, it * 3).toByte() }

    private fun lumaAt(f: ByteArray, i: Int) =
        ((f[i].toInt() and 0xFF) * 54 + (f[i + 1].toInt() and 0xFF) * 183 + (f[i + 2].toInt() and 0xFF) * 19) shr 8

    /** Kantenenergie (Laplace) auf dem verkleinerten Bild. */
    private fun sharpness(s: ByteArray): Double {
        var e = 0.0
        for (y in 1 until sh - 1) for (x in 1 until sw - 1) {
            val c = lumaAt(s, (y * sw + x) * 3)
            val l = 4 * c - lumaAt(s, (y * sw + x - 1) * 3) - lumaAt(s, (y * sw + x + 1) * 3) -
                lumaAt(s, ((y - 1) * sw + x) * 3) - lumaAt(s, ((y + 1) * sw + x) * 3)
            e += l.toDouble() * l
        }
        return e / maxOf(1, (sw - 2) * (sh - 2))
    }

    private fun median(a: FloatArray): Float = a.sortedArray().let { it[it.size / 2] }

    companion object {
        const val SCALE = 4
        /** Kacheln bis zum Doppelten der ueblichen Abweichung gelten als Rauschen, darueber als Bewegung. */
        const val ROBUST = 2f
        /** Unter 50 % der Kantenenergie der Referenz gilt ein Bild als verwackelt. */
        const val BLUR_LIMIT = 0.5
        private val LIN = FloatArray(256) { NightTone.srgbToLinear(it / 255f) }
    }
}
