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
     * steht ([AE_LIMIT_ISO_SHARE]). Dann belichtet sie zu knapp, und die Nachtserie nimmt die hoechste ISO, aber
     * hoechstens [MAX_BOOST]-mal so hell wie die Automatik (S-002).
     * Gemessen am S24+ (8. Oktober, zweimal): Automatik bei etwa 1/25 s und ISO 3200, der Plan ergab nur ISO 1919.
     */
    const val AE_LIMIT_NS = 50_000_000L
    const val AE_LIMIT_ISO_SHARE = 0.9

    /**
     * S-002: auch "am Anschlag" hoechstens so viel heller als die Automatik (Belichtung mal ISO). Abgeleitet aus dem
     * Nachtfall vom 8. Oktober (1/15 s bei ISO 1279 brauchte 3,75-fach); im beleuchteten Raum am 9. Oktober (Aufhellung
     * x1,0) ergab die alte Regel bis zu 10-fach, helle Stellen liefen voll.
     */
    const val MAX_BOOST = 4.0

    /** Bildzahl fuer etwa 4 s Licht bei 1/10 s je Bild. */
    const val FRAMES = 36

    /**
     * S-006: bei tiefer Dunkelheit doppelt so viele Bilder (7,2 s Licht; Samsung belichtete im Nachttest vom 10. Oktober
     * bis 8 s). Tief heisst: Lichtwert der Automatik ab [DEEP_DARK_LEVEL]. Gemessen am S24+: 1/25 s bei ISO 3200 (128,
     * Vorhang im fast lichtlosen Raum) und 1/15 s bei ISO 1279 (85, 8. Oktober) gegen 1/20 s bei ISO 640 (32, beleuchtetes
     * Wohnzimmer, 9. Oktober).
     */
    const val FRAMES_DEEP = 72
    const val DEEP_DARK_LEVEL = 80.0

    /** Bildzahl der Serie; ohne Messung wie bei tiefer Dunkelheit (dann gilt auch der Plan fuer volle Dunkelheit). */
    fun framesFor(aeExposureNs: Long?, aeIso: Int?): Int =
        if (aeExposureNs == null || aeIso == null || level(aeExposureNs, aeIso) >= DEEP_DARK_LEVEL) FRAMES_DEEP else FRAMES

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
        if (aeExposureNs >= AE_LIMIT_NS || aeIso >= AE_LIMIT_ISO_SHARE * isoMax) {
            val capped = Math.round(aeExposureNs.toDouble() * aeIso * MAX_BOOST / exp).toInt()
            return Exposure(exp, capped.coerceIn(isoMin, isoMax))
        }
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
    /** S-006: Summe der Quadrate je Kanal (nur 8 Bit), daraus die Streuung je Pixel fuer [Declip]. */
    private var sumSq: FloatArray? = null
    private val tilesX = (width + tile - 1) / tile
    private val tilesY = (height + tile - 1) / tile
    /** Summe der Gewichte je Pixel. */
    private val weight = FloatArray(pixels)
    private var refLuma: ByteArray? = null
    private var refSmall: ByteArray? = null
    private var refSharpness = 0.0
    private var refRobust = 0.0
    /** S-004: gemeinsame Ausrichtung (auch fuer Langzeit, Wegrechnen, Fokus-Stacking). */
    // Nacht bleibt beim schnellen einfachen Verfahren (Zeitbudget je Bild im Bildstrom, R27); bewegte Objekte
    // gewichten hier die Kacheln ab. Die robuste Suche nutzen die Serienmodi (FrameAlignment.alignInPlace).
    private val aligner = FrameAligner(width, height, robust = false)
    private val sw = aligner.sw
    private val sh = aligner.sh

    // Ueberblenden zwischen Kachelmitten: Tabellen je Spalte und Zeile einmal berechnen (S-001, R27)
    private val colTile = IntArray(width); private val colTile1 = IntArray(width); private val colA = FloatArray(width)
    private val rowTile = IntArray(height); private val rowTile1 = IntArray(height); private val rowA = FloatArray(height)

    init {
        fun fill(n: Int, tilesN: Int, t0: IntArray, t1: IntArray, a: FloatArray) {
            for (i in 0 until n) {
                val f = ((i + 0.5f) / tile - 0.5f).coerceIn(0f, (tilesN - 1).toFloat())
                t0[i] = f.toInt().coerceAtMost(tilesN - 1); t1[i] = minOf(t0[i] + 1, tilesN - 1); a[i] = f - t0[i]
            }
        }
        fill(width, tilesX, colTile, colTile1, colA); fill(height, tilesY, rowTile, rowTile1, rowA)
    }

    var used = 0; private set

    /** Groesster angenommener Versatz zum Bezugsbild in Pixeln (S-003: zeigt auf dem Geraet, wie stark gewackelt wurde). */
    var maxShake = 0; private set

    /** S-006: Zahl der ausgerichteten Bilder und davon, wo eine Verschiebung nicht klar besser passte als keine. */
    private var aligned = 0
    private var rejected = 0

    /** S-006: false = bei der Haelfte der Bilder oder mehr war das Wackeln im Rauschen nicht erkennbar ([maxShake] sagt dann nichts). */
    val shakeMeasurable: Boolean get() = aligned == 0 || rejected * 2 < aligned

    /** S-003: Kopien der ersten Bilder, solange der Bezug noch wechseln darf (hoechstens [REF_CANDIDATES] - 1, R19). */
    private val early = ArrayList<ByteArray>(REF_CANDIDATES - 1)
    private var seen = 0

    /** Anteil der Pixel mit Helligkeit 0 oder 1 im ersten Bild: hoch heisst "fast kein Licht" (siehe NightTone.FLOOR_SHARE). */
    var floorShare = 0f; private set
    var dropped = 0; private set

    /**
     * Fuegt ein Bild hinzu. false = verworfen (verwackelt).
     *
     * S-003: Bezug ist das schaerfste der ersten [REF_CANDIDATES] Bilder. Ist ein spaeteres davon deutlich schaerfer
     * ([REF_SWITCH]-fach, gemessen mit [robustSharpness]), beginnt die Summe neu mit ihm als Bezug, und die frueheren werden erneut geprueft (ein
     * verwackeltes erstes Bild liess sonst alle halb verwackelten durch). Nur fuer 8-Bit-Bilder; RAW behaelt das erste.
     */
    fun add(frame: ByteArray): Boolean {
        require(frame.size == pixels * 3) { "Bild hat die falsche Groesse" }
        seen++
        // Zweitpruefung S-003: robustes Mass, ein bewegtes helles Objekt darf den Bezug nicht uebernehmen
        if (seen in 2..REF_CANDIDATES && refSmall != null && robustSharpness(downscale(frame)) > REF_SWITCH * refRobust) {
            val before = early.toList()
            restart()
            addInternal(frame, null)
            before.forEach { addInternal(it, null) }
            // solange noch ein Wechsel moeglich ist, alle bisherigen Bilder behalten
            if (seen < REF_CANDIDATES) { early += frame.copyOf(); early += before }
            return true
        }
        val ok = addInternal(frame, null)
        if (seen < REF_CANDIDATES) early += frame.copyOf() else early.clear()
        return ok
    }

    /** Leert die Summe fuer einen neuen Bezug. */
    private fun restart() {
        sum.fill(0f); weight.fill(0f); sumSq?.fill(0f)
        refSmall = null; refLuma = null; refSharpness = 0.0; refRobust = 0.0
        used = 0; dropped = 0; maxShake = 0; floorShare = 0f; aligned = 0; rejected = 0
        early.clear()
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
            refSmall = small; refLuma = luma(frame); refSharpness = sharpness(small); refRobust = robustSharpness(small)
            // Bei RAW ist der Schwarzwert bekannt: kein Raten des Rauschbodens
            floorShare = if (linear != null) 0f else refLuma!!.count { (it.toInt() and 0xFF) <= 1 }.toFloat() / pixels
            for (i in sum.indices) sum[i] = linear?.get(i) ?: LIN[frame[i].toInt() and 0xFF]
            if (linear == null) { val q = sumSq ?: FloatArray(pixels * 3).also { sumSq = it }; for (i in q.indices) q[i] = sum[i] * sum[i] }
            weight.fill(1f); used = 1
            return true
        }
        // Verwackelte Bilder (deutlich weniger Kanten als die Referenz) verschlechtern das Ergebnis
        if (refSharpness > 0 && sharpness(small) < BLUR_LIMIT * refSharpness) { dropped++; return false }
        val rl = refLuma!!
        val (dx, dy) = aligner.shiftOf(ref, rl, small, frame)
        aligned++; if (aligner.lastRejected) rejected++
        maxShake = maxOf(maxShake, abs(dx), abs(dy))
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
        // S-001 K4: nur gesehene Kacheln mit genug Stichproben bestimmen, was "normales Rauschen" ist
        val sampled = diff.filterIndexed { t, _ -> count[t] >= MIN_TILE_SAMPLES }.toFloatArray()
        val typical = (if (sampled.isEmpty()) 0f else median(sampled)).coerceAtLeast(0.5f)
        val w = FloatArray(tiles) { t ->
            val d = diff[t]
            // zu wenige Stichproben sind kein Beleg fuer Bewegung
            if (count[t] < MIN_TILE_SAMPLES || d <= ROBUST * typical) 1f else (ROBUST * typical / d).let { it * it }
        }
        for (y in 0 until height) {
            val sy = y + dy
            if (sy < 0 || sy >= height) continue // vom Bild nie gesehen: zaehlt nicht
            val ty = rowTile[y]; val ty1 = rowTile1[y]; val ay = rowA[y]
            for (x in 0 until width) {
                val sx = x + dx
                if (sx < 0 || sx >= width) continue
                val tx = colTile[x]; val tx1 = colTile1[x]; val ax = colA[x]
                // bilinear zwischen den vier naechsten Kachelmitten
                val wt = (w[ty * tilesX + tx] * (1 - ax) + w[ty * tilesX + tx1] * ax) * (1 - ay) +
                    (w[ty1 * tilesX + tx] * (1 - ax) + w[ty1 * tilesX + tx1] * ax) * ay
                val s = (sy * width + sx) * 3; val p = y * width + x; val d = p * 3
                if (linear != null) {
                    sum[d] += wt * linear[s]; sum[d + 1] += wt * linear[s + 1]; sum[d + 2] += wt * linear[s + 2]
                } else {
                    val q = sumSq!!
                    val a = LIN[frame[s].toInt() and 0xFF]; val b = LIN[frame[s + 1].toInt() and 0xFF]; val c = LIN[frame[s + 2].toInt() and 0xFF]
                    sum[d] += wt * a; sum[d + 1] += wt * b; sum[d + 2] += wt * c
                    q[d] += wt * a * a; q[d + 1] += wt * b * b; q[d + 2] += wt * c * c
                }
                weight[p] += wt
            }
        }
        used++
        return true
    }

    /** Gewichtetes Mittel in linearem Licht, dann Nacht-Look (Schwarzpunkt, Entrauschen, Kontrast, Aufhellen). */
    fun finish(): NightTone.Result {
        check(used > 0) { "Kein Bild" }
        val mean = FloatArray(sum.size)
        for (p in 0 until pixels) {
            val inv = 1f / weight[p]
            mean[p * 3] = sum[p * 3] * inv; mean[p * 3 + 1] = sum[p * 3 + 1] * inv; mean[p * 3 + 2] = sum[p * 3 + 2] * inv
        }
        // S-006: 8 Bit, abgeschnittenes Rauschen zurueckrechnen; liegt das Signal des mittleren Pixels nicht ueber dem
        // Rauschen der Serie, ist die Szene lichtlos: Boden-Modus, auch wenn das Rauschen der Einzelbilder ueber Stufe 1 reicht
        val q = sumSq
        noiseFloor = false
        if (!linearInput && q != null && used >= MIN_DECLIP_FRAMES) {
            // Rauschen je Kanal fuer das ganze Bild: Median der Schaetzungen je Pixel, wo das Abschneiden wirkt
            // Stichprobe (jedes k-te Pixel, hoechstens etwa 65.536), ohne Listen von Objekten (Zweitpruefung S-006: 0,9 s und 30 MB)
            val step = maxOf(1, pixels / 65_536)
            val est = FloatArray((pixels + step - 1) / step)
            val sigma = FloatArray(3) { c ->
                var n = 0
                var p = 0
                while (p < pixels) { val i = p * 3 + c; Declip.noise(mean[i], q[i] / weight[p])?.let { est[n++] = it }; p += step }
                if (n < est.size / 20) 0f else NightTone.quantile(est.copyOf(n), 0.5f)
            }
            if (sigma.any { it > 0f }) {
                val sigmaL = kotlin.math.sqrt((0 until 3).sumOf { c -> ((LUMA[c] * sigma[c]) * (LUMA[c] * sigma[c])).toDouble() }).toFloat()
                // Signal des mittleren Pixels je Kanal zurueckgerechnet (die Helligkeit aus drei abgeschnittenen Kanaelen ist
                // selbst nicht abgeschnitten-normal), gegen das Rauschen des Mittels aus allen Bildern
                val medianSignal = (0 until 3).sumOf { c ->
                    val ch = FloatArray(pixels) { p -> mean[p * 3 + c] }
                    (LUMA[c] * Declip.signalFromMean(NightTone.quantile(ch, 0.5f), sigma[c])).toDouble()
                }.toFloat()
                noiseFloor = medianSignal <= FLOOR_SNR * sigmaL / kotlin.math.sqrt(NightTone.quantile(weight, 0.5f).coerceAtLeast(1f))
                if (noiseFloor) {
                    // Boden-Modus: der abgeschnittene Rauschanteil ohne Licht (0,4-mal das Rauschen) ist Schwarz. Nur
                    // abziehen, nicht je Pixel zurueckrechnen: das verstaerkte das Rauschen bis 6-fach (Labor 10.10.)
                    for (i in mean.indices) mean[i] -= Declip.ZERO_SIGNAL_MEAN * sigma[i % 3]
                }
            }
        }
        // RAW: Rauschen ist nicht abgeschnitten, einzelne Bilder sagen nichts; der Boden wird am Mittel gemessen
        val share = if (noiseFloor) maxOf(floorShare, NightTone.FLOOR_SHARE) else if (linearInput) {
            var n = 0
            for (p in 0 until pixels) if (0.2126f * mean[p * 3] + 0.7152f * mean[p * 3 + 1] + 0.0722f * mean[p * 3 + 2] <= NightTone.FLOOR_LINEAR) n++
            n.toFloat() / pixels
        } else floorShare
        // Hoehere Aufhellung nur im neu erkannten Boden-Modus (Zweitpruefung: nicht fuer RAW und den alten Ausloeser)
        return NightTone.finishNight(mean, width, NightTone.maxGainFor(used) * (if (noiseFloor) NightTone.FLOOR_GAIN_FACTOR else 1f), share,
            if (linearInput) NightTone.FLOOR_BLACK_RAW else NightTone.FLOOR_BLACK)
    }

    /** S-006: true = das Signal des mittleren Pixels liegt nicht ueber dem Rauschen der Serie (Boden-Modus). */
    var noiseFloor = false; private set

    /** Mittlere Zahl der Bilder, die je Pixel wirklich beigetragen haben. */
    fun effectiveFrames(): Float = weight.average().toFloat()

    /** Mittleres Gewicht in einem Bereich (fuer das Testlabor: Rand gegen Mitte). */
    fun weightIn(x0: Int, y0: Int, x1: Int, y1: Int): Float {
        var s = 0.0; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) { s += weight[y * width + x]; n++ }
        return if (n == 0) 0f else (s / n).toFloat()
    }

    private fun downscale(f: ByteArray) = aligner.downscale(f)

    private fun luma(f: ByteArray) = aligner.luma(f)

    private fun lumaAt(f: ByteArray, i: Int) = FrameAligner.lumaAt(f, i)

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

    /**
     * Schaerfe als Median der Kantenenergie je Feld von 4 x 4 Pixeln des verkleinerten Bilds (S-003). Bewegungsunschaerfe
     * senkt alle Felder, ein vorbeilaufendes helles Objekt hebt nur wenige (Python-Modell: Mittelwert 4-fach, Median 1,04-fach).
     */
    private fun robustSharpness(s: ByteArray): Double {
        val t = 4
        val tiles = ArrayList<Double>()
        var y0 = 1
        while (y0 + t <= sh - 1) {
            var x0 = 1
            while (x0 + t <= sw - 1) {
                var e = 0.0
                for (y in y0 until y0 + t) for (x in x0 until x0 + t) {
                    val c = lumaAt(s, (y * sw + x) * 3)
                    val l = 4 * c - lumaAt(s, (y * sw + x - 1) * 3) - lumaAt(s, (y * sw + x + 1) * 3) -
                        lumaAt(s, ((y - 1) * sw + x) * 3) - lumaAt(s, ((y + 1) * sw + x) * 3)
                    e += l.toDouble() * l
                }
                tiles += e / (t * t)
                x0 += t
            }
            y0 += t
        }
        if (tiles.isEmpty()) return 0.0
        tiles.sort()
        return tiles[tiles.size / 2]
    }

    private fun median(a: FloatArray): Float = a.sortedArray().let { it[it.size / 2] }

    companion object {
        const val SCALE = FrameAligner.SCALE
        /** Kacheln bis zum Doppelten der ueblichen Abweichung gelten als Rauschen, darueber als Bewegung. */
        const val ROBUST = 2f
        /** Unter 50 % der Kantenenergie der Referenz gilt ein Bild als verwackelt. */
        const val BLUR_LIMIT = 0.5
        const val SHIFT_GAIN = FrameAligner.SHIFT_GAIN
        /** S-006: Mindestzahl der Bilder fuer eine verlaessliche Streuung je Pixel. */
        const val MIN_DECLIP_FRAMES = 8
        /** S-006: Boden-Modus, wenn das mittlere Signal hoechstens so viel wie das Rauschen der Serie betraegt. */
        const val FLOOR_SNR = 1f
        private val LUMA = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
        /** S-003: so viele erste Bilder kommen als Bezug in Frage; 1 = immer das erste (Rueckweg). */
        const val REF_CANDIDATES = 3
        /** Ein Kandidat muss so viel schaerfer sein, damit der Bezug wechselt (Rauschen allein reicht nicht). */
        const val REF_SWITCH = 1.25
        /** Weniger Stichproben je Kachel (nur am Rand moeglich) reichen nicht als Beleg fuer Bewegung. */
        const val MIN_TILE_SAMPLES = 4
        /** Vorschau linearer Bilder: Median auf etwa sRGB 120, hoechstens 256-fach. */
        const val PREVIEW_MEDIAN = 0.18f
        const val PREVIEW_MAX_GAIN = 256f
        private val LIN = FloatArray(256) { NightTone.srgbToLinear(it / 255f) }
    }
}
