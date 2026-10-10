package app.cayresim.core.pure

import kotlin.math.abs

/**
 * Ausrichtung ganzer Bilder als Verschiebung (S-004), gemeinsam fuer Nacht, Langzeit, Menschen wegrechnen und
 * Fokus-Stacking. Grobsuche auf dem 4-fach verkleinerten Bild (bis 8 Stufen, also etwa 32 Pixel), dann Feinsuche
 * +-3 Pixel in voller Aufloesung. Eine Verschiebung zaehlt nur, wenn sie klar besser passt als keine ([SHIFT_GAIN]).
 * Bilder als RGB-Bytes, 3 je Pixel.
 */
class FrameAligner(val width: Int, val height: Int, private val robust: Boolean = true) {
    val sw = width / SCALE
    val sh = height / SCALE
    private val maxShift = minOf(8, (sw - 1) / 2 - 1, (sh - 1) / 2 - 1).coerceAtLeast(0)
    private val pixels = width * height

    fun downscale(f: ByteArray): ByteArray {
        val out = ByteArray(sw * sh * 3)
        for (y in 0 until sh) for (x in 0 until sw) for (c in 0 until 3) {
            var s = 0
            for (oy in 0 until SCALE) for (ox in 0 until SCALE) s += f[((y * SCALE + oy) * width + x * SCALE + ox) * 3 + c].toInt() and 0xFF
            out[(y * sw + x) * 3 + c] = (s / (SCALE * SCALE)).toByte()
        }
        return out
    }

    fun luma(f: ByteArray) = ByteArray(pixels) { lumaAt(f, it * 3).toByte() }

    /**
     * Verschiebung (dx, dy), die [frame] auf den Bezug legt: frame(x + dx, y + dy) ~ Bezug(x, y).
     * [refSmall], [small]: verkleinerte Bilder ([downscale]); [refLuma]: Helligkeit des Bezugs ([luma]);
     * [hint]: Verschiebung des vorigen Bilds (die Hand bewegt sich stetig), null = keine.
     *
     * Zweitpruefung S-004: Die Grobstufe sieht feine Muster (Stoff, 3-Pixel-Bloecke) kaum, ein heller Passant
     * bestimmte dort allein das Ergebnis (bis 30 Pixel daneben). Deshalb werden die besten
     * Grobschaetzungen (begrenzt und einfach), keine Verschiebung und [hint] in voller Aufloesung verglichen.
     */
    /** S-006: true, wenn beim letzten [shiftOf] die beste Verschiebung nicht klar besser passte als keine (nur Rauschen). */
    var lastRejected = false; private set

    fun shiftOf(refSmall: ByteArray, refLuma: ByteArray, small: ByteArray, frame: ByteArray, hint: Pair<Int, Int>? = null): Pair<Int, Int> {
        lastRejected = false
        val centers = LinkedHashSet<Pair<Int, Int>>()
        if (maxShift > 0) {
            if (robust) coarse(refSmall, small).forEach { (dx, dy) -> centers += dx * SCALE to dy * SCALE }
            else coarsePlain(refSmall, small).let { (dx, dy) -> centers += dx * SCALE to dy * SCALE }
        }
        centers += 0 to 0
        if (hint != null && robust) centers += hint
        // Helligkeit einmal je Bild statt in jeder Pruefung (R27)
        val fl = luma(frame)
        if (!robust) {
            // Nacht (S-004, unveraendert wie vorher): beste Grobschaetzung, +-3 Pixel fein, einfacher Fehler
            val (cx, cy) = centers.first()
            var fine = cx to cy; var fineErr = Long.MAX_VALUE
            for (dy in cy - R..cy + R) for (dx in cx - R..cx + R) {
                val e = alignError(refLuma, fl, dx, dy, step = 2, cap = false)
                if (e < fineErr) { fineErr = e; fine = dx to dy }
            }
            val (bx, by) = fine
            lastRejected = (bx != 0 || by != 0) && fineErr > SHIFT_GAIN * alignError(refLuma, fl, 0, 0, step = 2, cap = false)
            return if (lastRejected) 0 to 0 else fine
        }
        // Vorauswahl auf jedem 4. Pixel, dann fein auf jedem 2. Pixel um den besten Kandidaten
        var best = 0 to 0; var bestErr = Long.MAX_VALUE
        for ((cx, cy) in centers) for (dy in cy - R..cy + R) for (dx in cx - R..cx + R) {
            val e = alignError(refLuma, fl, dx, dy, step = 4)
            if (e < bestErr) { bestErr = e; best = dx to dy }
        }
        var fine = best; var fineErr = Long.MAX_VALUE
        for (dy in best.second - 1..best.second + 1) for (dx in best.first - 1..best.first + 1) {
            val e = alignError(refLuma, fl, dx, dy, step = 2)
            if (e < fineErr) { fineErr = e; fine = dx to dy }
        }
        val (bx, by) = fine
        // Sehr verrauschte Bilder (RAW im Dunkeln): eine Verschiebung nur annehmen, wenn sie klar besser passt als keine
        lastRejected = (bx != 0 || by != 0) && fineErr > SHIFT_GAIN * alignError(refLuma, fl, 0, 0, step = 2)
        return if (lastRejected) 0 to 0 else fine
    }

    /** Grobsuche auf dem verkleinerten, leicht weichgezeichneten Bild: die besten Verschiebungen, mindestens 2 Stufen auseinander. */
    private fun coarse(refSmall: ByteArray, small: ByteArray): List<Pair<Int, Int>> {
        val m = maxShift
        val a = blurred(refSmall); val b = blurred(small)
        val found = ArrayList<Pair<Long, Pair<Int, Int>>>()
        val plain = ArrayList<Pair<Long, Pair<Int, Int>>>()
        val tw = (sw - 2 * m + SMALL_TILE - 1) / SMALL_TILE; val th = (sh - 2 * m + SMALL_TILE - 1) / SMALL_TILE
        val tiles = LongArray(tw * th)
        for (dy in -m..m) for (dx in -m..m) {
            tiles.fill(0)
            var y = m
            while (y < sh - m) {
                val row = y * sw; val rowB = (y + dy) * sw + dx; val t0 = ((y - m) shr SMALL_TILE_SHIFT) * tw
                var x = m
                while (x < sw - m) { tiles[t0 + ((x - m) shr SMALL_TILE_SHIFT)] += abs(a[row + x] - b[rowB + x]).toLong(); x += 2 }
                y += 2
            }
            found += capped(tiles) to (dx to dy)
            plain += tiles.sum() to (dx to dy)
        }
        // Beide Masse liefern Kandidaten: begrenzt (robust gegen Passanten), unbegrenzt (helle Lampe in sonst flacher Nacht)
        val out = ArrayList<Pair<Int, Int>>()
        for ((list, count) in listOf(found to CAPPED_CANDIDATES, plain to PLAIN_CANDIDATES)) {
            list.sortWith(compareBy<Pair<Long, Pair<Int, Int>>> { it.first }.thenBy { abs(it.second.first) + abs(it.second.second) })
            var n = 0
            for ((_, c) in list) {
                if (n == count) break
                if (out.none { abs(it.first - c.first) < 2 && abs(it.second - c.second) < 2 }) { out += c; n++ }
            }
        }
        return out
    }

    /**
     * Zweitpruefung S-004: Fehler je Feld, hoechstens [CAP]-mal der Median der Felder. Ein heller Passant zaehlt so bei
     * jeder Verschiebung gleich viel und kann die Schaetzung nicht wegziehen; Kanten in sonst flachen Szenen (Nacht)
     * bleiben wirksam, weil viele Felder an ihnen liegen. Ein reines Wegschneiden der schlechtesten Felder verlor genau diese.
     */
    private fun capped(tiles: LongArray): Long {
        val cap = (CAP * median(tiles)).toLong().coerceAtLeast(1)
        var s = 0L
        for (t in tiles) s += minOf(t, cap)
        return s
    }

    private val scratch = LongArray(maxOf(1, (width / TILE + 1) * (height / TILE + 1), (sw / SMALL_TILE + 1) * (sh / SMALL_TILE + 1)))

    /** Median ohne Sortieren (Quickselect auf einer Kopie), damit jede Pruefung nur linear Zeit braucht (R27). */
    private fun median(v: LongArray): Long {
        val a = scratch; val n = v.size
        System.arraycopy(v, 0, a, 0, n)
        val k = n / 2
        var lo = 0; var hi = n - 1
        while (lo < hi) {
            val pivot = a[(lo + hi) ushr 1]
            var i = lo; var j = hi
            while (i <= j) {
                while (a[i] < pivot) i++
                while (a[j] > pivot) j--
                if (i <= j) { val t = a[i]; a[i] = a[j]; a[j] = t; i++; j-- }
            }
            if (k <= j) hi = j else if (k >= i) lo = i else break
        }
        return a[k]
    }

    /** Grobsuche wie vor S-004 (Nacht): einfache Summe auf dem weichgezeichneten verkleinerten Bild. */
    private fun coarsePlain(refSmall: ByteArray, small: ByteArray): Pair<Int, Int> = StarAlignment.estimateShift(refSmall, small, sw, sh, maxShift)

    /** 3x3-Summe der Helligkeit (einzelne helle Punkte treffen so auch bei Schrittweite 2 ein Messpixel). */
    private fun blurred(s: ByteArray): IntArray {
        val l = IntArray(sw * sh) { lumaAt(s, it * 3) }
        return IntArray(sw * sh) { i ->
            val x = i % sw; val y = i / sw; var t = 0
            for (oy in -1..1) for (ox in -1..1) t += l[(y + oy).coerceIn(0, sh - 1) * sw + (x + ox).coerceIn(0, sw - 1)]
            t
        }
    }

    /** Helligkeitsabweichung bei Verschiebung (dx, dy), Rand ausgespart, jedes [step]-te Pixel, je Feld begrenzt ([capped]). */
    private fun alignError(ref: ByteArray, frameLuma: ByteArray, dx: Int, dy: Int, step: Int, cap: Boolean = true): Long {
        val m = minOf(width, height) / 8
        val tw = (width - 2 * m + TILE - 1) / TILE; val th = (height - 2 * m + TILE - 1) / TILE
        val tiles = LongArray(tw * th)
        var y = m
        while (y < height - m) {
            val sy = (y + dy).coerceIn(0, height - 1)
            val t0 = ((y - m) shr TILE_SHIFT) * tw
            val srow = sy * width; val rrow = y * width
            var x = m
            while (x < width - m) {
                val sx = (x + dx).coerceIn(0, width - 1)
                tiles[t0 + ((x - m) shr TILE_SHIFT)] += abs((frameLuma[srow + sx].toInt() and 0xFF) - (ref[rrow + x].toInt() and 0xFF)).toLong()
                x += step
            }
            y += step
        }
        return if (cap) capped(tiles) else tiles.sum()
    }

    companion object {
        const val SCALE = 4
        /** Eine Verschiebung muss den Fehler auf hoechstens 97 % von "keine Verschiebung" senken. */
        const val SHIFT_GAIN = 0.97
        /** Feldgroesse fuer den begrenzten Fehler: volle Aufloesung und verkleinertes Bild (gleiche Flaeche). */
        const val TILE = 16
        const val SMALL_TILE = 4
        private const val TILE_SHIFT = 4
        private const val SMALL_TILE_SHIFT = 2
        /** Ein Feld zaehlt hoechstens so viel wie das [CAP]-fache des mittleren Felds. */
        const val CAP = 3.0
        /** So viele Grobschaetzungen werden in voller Aufloesung geprueft: nach begrenztem und nach einfachem Fehler. */
        const val CAPPED_CANDIDATES = 2
        const val PLAIN_CANDIDATES = 1
        /** Suchradius um jeden Kandidaten in voller Aufloesung (die Grobstufe trifft nur auf 4 Pixel genau). */
        private const val R = SCALE - 1
        /** Kleinere Bilder werden nicht ausgerichtet (zu wenig Flaeche fuer eine sichere Schaetzung). */
        const val MIN_SIZE = 32

        fun lumaAt(f: ByteArray, i: Int) =
            ((f[i].toInt() and 0xFF) * 54 + (f[i + 1].toInt() and 0xFF) * 183 + (f[i + 2].toInt() and 0xFF) * 19) shr 8
    }
}

object FrameAlignment {
    /**
     * Richtet alle Bilder am ersten aus, direkt in den uebergebenen Puffern (die Serie gehoert der Verarbeitung, R19;
     * so entsteht nur ein Hilfspuffer statt einer zweiten Serie). Raender werden mit dem naechsten Randpixel gefuellt.
     * Liefert die Verschiebung je Bild; Bilder unter [FrameAligner.MIN_SIZE] Pixeln bleiben unveraendert.
     */
    fun alignInPlace(frames: List<ByteArray>, w: Int, h: Int, beforeFrame: (Int) -> Unit = {}): List<Pair<Int, Int>> {
        require(frames.all { it.size == w * h * 3 }) { "Alle Bilder muessen gleich gross sein" }
        if (frames.size < 2 || w < FrameAligner.MIN_SIZE || h < FrameAligner.MIN_SIZE) return List(frames.size) { 0 to 0 }
        val a = FrameAligner(w, h)
        val ref = frames.first(); val refSmall = a.downscale(ref); val refLuma = a.luma(ref)
        val tmp = ByteArray(w * h * 3)
        var last: Pair<Int, Int>? = null
        return frames.mapIndexed { i, f ->
            beforeFrame(i)
            if (i == 0) return@mapIndexed 0 to 0
            val (dx, dy) = a.shiftOf(refSmall, refLuma, a.downscale(f), f, last).also { last = it }
            if (dx != 0 || dy != 0) {
                System.arraycopy(f, 0, tmp, 0, f.size)
                for (y in 0 until h) {
                    val sy = (y + dy).coerceIn(0, h - 1)
                    for (x in 0 until w) {
                        val s = (sy * w + (x + dx).coerceIn(0, w - 1)) * 3; val d = (y * w + x) * 3
                        f[d] = tmp[s]; f[d + 1] = tmp[s + 1]; f[d + 2] = tmp[s + 2]
                    }
                }
            }
            dx to dy
        }
    }
}
