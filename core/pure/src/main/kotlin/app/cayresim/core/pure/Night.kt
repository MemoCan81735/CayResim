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
 *
 * Gewichte je Pixel (Nachttest S24+ am 9. Oktober, Auto bei Regen): Die Kachelgewichte werden bilinear zwischen
 * den Kachelmitten ueberblendet, damit keine Blockkanten entstehen (Befund M9), und Pixel, die ein verschobenes
 * Bild nie gesehen hat, zaehlen fuer dieses Bild nicht (vorher: Streifen durch wiederholte Randpixel).
 * Kacheln von 8 Pixeln: mit Ueberblenden allein zieht eine grosse "bewegte" Kachel ihr niedriges Gewicht in die
 * Nachbarn und das Doppelbild wird staerker; im Python-Modell waren 8 Pixel ohne Rauschnachteil (Geist 1,4, Kanten 0,3).
 */
class NightMerge(val width: Int, val height: Int, private val tile: Int = 8) {
    init { require(width >= 32 && height >= 32 && tile >= 8) { "Bild zu klein" } }

    private val pixels = width * height
    private val sum = FloatArray(pixels * 3)
    private val tilesX = (width + tile - 1) / tile
    private val tilesY = (height + tile - 1) / tile
    /** Summe der Gewichte je Pixel. */
    private val weight = FloatArray(pixels)
    private var refLuma: ByteArray? = null
    private var refSmall: ByteArray? = null
    private var refSharpness = 0.0
    private val sw = width / SCALE
    private val sh = height / SCALE
    private val maxShift = minOf(8, (sw - 1) / 2 - 1, (sh - 1) / 2 - 1).coerceAtLeast(0)

    var used = 0; private set

    /** Anteil der Pixel mit Helligkeit 0 oder 1 im ersten Bild: hoch heisst "fast kein Licht" (siehe NightTone.FLOOR_SHARE). */
    var floorShare = 0f; private set
    var dropped = 0; private set

    /** Fuegt ein Bild hinzu. false = verworfen (verwackelt). */
    fun add(frame: ByteArray): Boolean {
        require(frame.size == pixels * 3) { "Bild hat die falsche Groesse" }
        return addInternal(frame, null)
    }

    /** Verstaerkung fuer die 8-Bit-Vorschau linearer Bilder (aus dem ersten Bild, fuer alle gleich). */
    private var previewGain = 0f

    /** True, sobald lineare Bilder (RAW-Weg) zusammengefuehrt werden; der Schwarzwert ist dann bekannt. */
    var linearInput = false; private set

    /**
     * RAW-Weg: lineares Bild (3 Floats je Pixel, 1,0 = Weiss, auch leicht negativ). Ausgerichtet wird auf einer
     * hellen 8-Bit-Vorschau, aufsummiert wird das lineare Bild selbst, ohne Rundung und ohne Abschneiden.
     */
    fun addLinear(linear: FloatArray): Boolean {
        require(linear.size == pixels * 3) { "Bild hat die falsche Groesse" }
        if (previewGain == 0f) {
            val y = FloatArray(pixels) { 0.2126f * linear[it * 3] + 0.7152f * linear[it * 3 + 1] + 0.0722f * linear[it * 3 + 2] }
            val m = NightTone.quantile(y, 0.5f)
            previewGain = if (m <= 0f) PREVIEW_MAX_GAIN else (PREVIEW_MEDIAN / m).coerceIn(1f, PREVIEW_MAX_GAIN)
            linearInput = true
        }
        val g = previewGain
        val preview = ByteArray(linear.size) { (NightTone.linearToSrgb(linear[it] * g) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte() }
        return addInternal(preview, linear)
    }

    private fun addInternal(frame: ByteArray, linear: FloatArray?): Boolean {
        val small = downscale(frame)
        val ref = refSmall
        if (ref == null) {
            refSmall = small; refLuma = luma(frame); refSharpness = sharpness(small)
            // Bei RAW ist der Schwarzwert bekannt: kein Raten des Rauschbodens
            floorShare = if (linear != null) 0f else refLuma!!.count { (it.toInt() and 0xFF) <= 1 }.toFloat() / pixels
            for (i in sum.indices) sum[i] = linear?.get(i) ?: LIN[frame[i].toInt() and 0xFF]
            weight.fill(1f); used = 1
            return true
        }
        // Verwackelte Bilder (deutlich weniger Kanten als die Referenz) verschlechtern das Ergebnis
        if (refSharpness > 0 && sharpness(small) < BLUR_LIMIT * refSharpness) { dropped++; return false }
        val (sdx, sdy) = if (maxShift > 0) StarAlignment.estimateShift(ref, small, sw, sh, maxShift) else 0 to 0
        val rl = refLuma!!
        // Feinausrichtung in voller Aufloesung um die Grobschaetzung herum (die Grobstufe trifft nur auf 4 Pixel genau)
        val (bx, by) = refine(rl, frame, sdx * SCALE, sdy * SCALE)
        // Sehr verrauschte Bilder (RAW im Dunkeln): eine Verschiebung nur annehmen, wenn sie klar besser passt als keine
        val (dx, dy) = if ((bx != 0 || by != 0) && alignError(rl, frame, bx, by) > SHIFT_GAIN * alignError(rl, frame, 0, 0)) 0 to 0 else bx to by
        // Abweichung je Kachel nach dem Ausrichten
        val tiles = tilesX * tilesY
        val diff = FloatArray(tiles)
        val count = IntArray(tiles)
        for (y in 0 until height step 2) {
            val sy = y + dy
            if (sy < 0 || sy >= height) continue
            for (x in 0 until width step 2) {
                val sx = x + dx
                if (sx < 0 || sx >= width) continue
                val s = (sy * width + sx) * 3
                val l = lumaAt(frame, s)
                val t = (y / tile) * tilesX + x / tile
                diff[t] += abs(l - (rl[y * width + x].toInt() and 0xFF)); count[t]++
            }
        }
        for (t in diff.indices) diff[t] = if (count[t] == 0) 0f else diff[t] / count[t]
        val typical = median(diff).coerceAtLeast(0.5f)
        val w = FloatArray(tiles) { t ->
            val d = diff[t]
            if (d <= ROBUST * typical) 1f else (ROBUST * typical / d).let { it * it }
        }
        for (y in 0 until height) {
            val sy = y + dy
            if (sy < 0 || sy >= height) continue // vom Bild nie gesehen: zaehlt nicht
            val fy = ((y + 0.5f) / tile - 0.5f).coerceIn(0f, (tilesY - 1).toFloat())
            val ty = fy.toInt().coerceAtMost(tilesY - 1); val ty1 = minOf(ty + 1, tilesY - 1); val ay = fy - ty
            for (x in 0 until width) {
                val sx = x + dx
                if (sx < 0 || sx >= width) continue
                val fx = ((x + 0.5f) / tile - 0.5f).coerceIn(0f, (tilesX - 1).toFloat())
                val tx = fx.toInt().coerceAtMost(tilesX - 1); val tx1 = minOf(tx + 1, tilesX - 1); val ax = fx - tx
                // bilinear zwischen den vier naechsten Kachelmitten
                val wt = (w[ty * tilesX + tx] * (1 - ax) + w[ty * tilesX + tx1] * ax) * (1 - ay) +
                    (w[ty1 * tilesX + tx] * (1 - ax) + w[ty1 * tilesX + tx1] * ax) * ay
                val s = (sy * width + sx) * 3; val p = y * width + x; val d = p * 3
                if (linear != null) {
                    sum[d] += wt * linear[s]; sum[d + 1] += wt * linear[s + 1]; sum[d + 2] += wt * linear[s + 2]
                } else {
                    sum[d] += wt * LIN[frame[s].toInt() and 0xFF]
                    sum[d + 1] += wt * LIN[frame[s + 1].toInt() and 0xFF]
                    sum[d + 2] += wt * LIN[frame[s + 2].toInt() and 0xFF]
                }
                weight[p] += wt
            }
        }
        used++
        return true
    }

    /** Sucht im Umkreis von [SCALE] - 1 Pixeln die beste Verschiebung auf der vollen Helligkeit (jedes 2. Pixel). */
    private fun refine(ref: ByteArray, frame: ByteArray, cx: Int, cy: Int): Pair<Int, Int> {
        val r = SCALE - 1
        var best = cx to cy; var bestErr = Long.MAX_VALUE
        for (dy in cy - r..cy + r) for (dx in cx - r..cx + r) {
            val err = alignError(ref, frame, dx, dy)
            if (err < bestErr) { bestErr = err; best = dx to dy }
        }
        return best
    }

    /** Summe der Helligkeitsabweichung bei Verschiebung (dx, dy), Rand ausgespart, jedes 2. Pixel. */
    private fun alignError(ref: ByteArray, frame: ByteArray, dx: Int, dy: Int): Long {
        val m = minOf(width, height) / 8
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
        return err
    }

    /** Gewichtetes Mittel in linearem Licht, dann Nacht-Look (Schwarzpunkt, Entrauschen, Kontrast, Aufhellen). */
    fun finish(): NightTone.Result {
        check(used > 0) { "Kein Bild" }
        val mean = FloatArray(sum.size)
        for (p in 0 until pixels) {
            val inv = 1f / weight[p]
            mean[p * 3] = sum[p * 3] * inv; mean[p * 3 + 1] = sum[p * 3 + 1] * inv; mean[p * 3 + 2] = sum[p * 3 + 2] * inv
        }
        // RAW: Rauschen ist nicht abgeschnitten, einzelne Bilder sagen nichts; der Boden wird am Mittel gemessen
        val share = if (linearInput) {
            var n = 0
            for (p in 0 until pixels) if (0.2126f * mean[p * 3] + 0.7152f * mean[p * 3 + 1] + 0.0722f * mean[p * 3 + 2] <= NightTone.FLOOR_LINEAR) n++
            n.toFloat() / pixels
        } else floorShare
        return NightTone.finishNight(mean, width, NightTone.maxGainFor(used), share,
            if (linearInput) NightTone.FLOOR_BLACK_RAW else NightTone.FLOOR_BLACK)
    }

    /** Mittlere Zahl der Bilder, die je Pixel wirklich beigetragen haben. */
    fun effectiveFrames(): Float = weight.average().toFloat()

    /** Mittleres Gewicht in einem Bereich (fuer das Testlabor: Rand gegen Mitte). */
    fun weightIn(x0: Int, y0: Int, x1: Int, y1: Int): Float {
        var s = 0.0; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) { s += weight[y * width + x]; n++ }
        return if (n == 0) 0f else (s / n).toFloat()
    }

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
        /** Eine Verschiebung muss den Fehler auf hoechstens 97 % von "keine Verschiebung" senken. */
        const val SHIFT_GAIN = 0.97
        /** Vorschau linearer Bilder: Median auf etwa sRGB 120, hoechstens 256-fach. */
        const val PREVIEW_MEDIAN = 0.18f
        const val PREVIEW_MAX_GAIN = 256f
        private val LIN = FloatArray(256) { NightTone.srgbToLinear(it / 255f) }
    }
}
