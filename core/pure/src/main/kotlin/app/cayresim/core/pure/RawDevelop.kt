package app.cayresim.core.pure

/**
 * RAW-Weg (claude/foto-app-raw-weg.md, Stufe A): Bayer-Rohdaten in lineares RGB mit halber Kantenlaenge.
 *
 * Je 2x2-Block entsteht ein Pixel (Rauschen halbiert, keine Farbinterpolation noetig). Der Schwarzwert kommt aus
 * den Metadaten und wird ohne Abschneiden abgezogen: Rauschen unter Schwarz bleibt negativ und mittelt sich weg.
 * Danach Weissabgleich (Verstaerkung je Farbe) und Farbmatrix Kamera nach sRGB linear, beides aus der Aufnahme.
 */
object RawDevelop {
    /** Farbe je Position im 2x2-Block (oben links, oben rechts, unten links, unten rechts): 0 = Rot, 1 = Gruen, 2 = Blau. */
    enum class Cfa(val colors: IntArray) {
        RGGB(intArrayOf(0, 1, 1, 2)), GRBG(intArrayOf(1, 0, 2, 1)), GBRG(intArrayOf(1, 2, 0, 1)), BGGR(intArrayOf(2, 1, 1, 0))
    }

    /** Einheitsmatrix fuer Faelle ohne Farbmatrix. */
    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /**
     * [raw]: Werte zeilenweise, [rowStride] Werte je Zeile; [black] je Position im 2x2-Block; [gains] Rot, Gruen, Blau;
     * [matrix] 3x3 zeilenweise. Ergebnis: (width / 2) * (height / 2) Pixel, je 3 Floats, 1,0 = Weisswert.
     */
    fun binToLinear(
        raw: ShortArray, width: Int, height: Int, rowStride: Int, cfa: Cfa,
        black: FloatArray, white: Float, gains: FloatArray, matrix: FloatArray,
    ): FloatArray {
        require(width >= 2 && height >= 2 && rowStride >= width && raw.size >= rowStride * (height - 1) + width) { "Ungueltige Rohdaten" }
        require(black.size == 4 && gains.size == 3 && matrix.size == 9) { "Ungueltige Kalibrierung" }
        require(white > black.max()) { "Weisswert unter Schwarz" }
        val w = width / 2; val h = height / 2
        val out = FloatArray(w * h * 3)
        val scale = FloatArray(4) { 1f / (white - black[it]) }
        val cam = FloatArray(3); val count = IntArray(3)
        for (y in 0 until h) for (x in 0 until w) {
            cam.fill(0f); count.fill(0)
            for (i in 0 until 4) {
                val v = (raw[(2 * y + i / 2) * rowStride + 2 * x + i % 2].toInt() and 0xFFFF).toFloat()
                val c = cfa.colors[i]
                cam[c] += (v - black[i]) * scale[i]; count[c]++
            }
            for (c in 0 until 3) cam[c] = cam[c] / count[c] * gains[c]
            val o = (y * w + x) * 3
            for (r in 0 until 3) out[o + r] = matrix[r * 3] * cam[0] + matrix[r * 3 + 1] * cam[1] + matrix[r * 3 + 2] * cam[2]
        }
        return out
    }
}

/** Welcher Nachtweg auf einem Geraet genutzt wird. */
enum class NightPath { YUV, RAW }

/**
 * Entscheidung nach der Pruefung auf dem Geraet (Selbsttest): RAW nur, wenn ein echter RAW-Bildstrom schnell genug ist,
 * das Geraet unter Schwarz nicht abschneidet, die Kalibrierung vollstaendig ist und eine RAW-Probenacht gelang.
 */
object NightPathRule {
    /** 36 Bilder sollen in hoechstens etwa 4,5 s ankommen. */
    const val MIN_STREAM_FPS = 8f
    /** Mehr Nullen heisst: das Geraet schneidet unter Schwarz ab, RAW bringt dann kaum Gewinn. */
    const val MAX_ZERO_SHARE = 0.2f
    /** Probenacht mit [PROBE_FRAMES] Bildern muss in dieser Zeit fertig sein (Speichern eingeschlossen). */
    const val PROBE_FRAMES = 12
    const val MAX_PROBE_MS = 6_000L

    enum class Reason { RAW_OK, NO_RAW, SLOW_STREAM, CLIPPED, NO_CALIBRATION, PROBE_FAILED, PROBE_SLOW }

    data class Verdict(val path: NightPath, val reason: Reason)

    /**
     * Vorpruefung aus der Messung; null = Messung erlaubt RAW, die Probenacht entscheidet.
     * [black]: Schwarzwert je Position im 2x2-Muster. Alle 0 heisst (S-002): das Geraet hat Schwarz schon abgezogen und
     * kann Rauschen unter Schwarz nicht darstellen, unabhaengig vom Raumlicht. Die Nullen im Bild ([zeroShare])
     * zeigen das nur im Dunkeln (S24+: 61,9 % nachts, 0,0 % im hellen Raum).
     */
    fun precheck(raw: Boolean, streamFps: Float?, zeroShare: Float, calibrated: Boolean, black: List<Int> = emptyList()): Verdict? = when {
        !raw -> Verdict(NightPath.YUV, Reason.NO_RAW)
        streamFps == null || streamFps < MIN_STREAM_FPS -> Verdict(NightPath.YUV, Reason.SLOW_STREAM)
        zeroShare > MAX_ZERO_SHARE -> Verdict(NightPath.YUV, Reason.CLIPPED)
        // ohne Kalibrierung meldet der Adapter Schwarz als 0; das ist dann kein Beleg fuer Abschneiden
        !calibrated -> Verdict(NightPath.YUV, Reason.NO_CALIBRATION)
        black.isNotEmpty() && black.all { it == 0 } -> Verdict(NightPath.YUV, Reason.CLIPPED)
        else -> null
    }

    fun afterProbe(ok: Boolean, millis: Long): Verdict = when {
        !ok -> Verdict(NightPath.YUV, Reason.PROBE_FAILED)
        millis > MAX_PROBE_MS -> Verdict(NightPath.YUV, Reason.PROBE_SLOW)
        else -> Verdict(NightPath.RAW, Reason.RAW_OK)
    }
}
