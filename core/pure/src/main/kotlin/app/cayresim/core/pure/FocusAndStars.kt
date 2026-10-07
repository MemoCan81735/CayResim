package app.cayresim.core.pure

import kotlin.math.abs

/**
 * Kotlin-Referenzen fuer Phase 4: Fokus-Stacking (Schaerfekarte je Block) und
 * Sternausrichtung (Verschiebung schaetzen, dann mitteln). RGB-Bytes wie bei [Stacking].
 */
object FocusStacking {
    private fun luma(x: ByteArray, i: Int) =
        ((x[i].toInt() and 0xFF) * 54 + (x[i + 1].toInt() and 0xFF) * 183 + (x[i + 2].toInt() and 0xFF) * 19) shr 8

    /** Schaerfe eines Blocks: Summe des absoluten Laplace-Operators der Helligkeit. */
    fun blockSharpness(f: ByteArray, w: Int, h: Int, bx: Int, by: Int, block: Int): Long {
        var sum = 0L
        val x0 = maxOf(1, bx * block); val x1 = minOf(w - 1, (bx + 1) * block)
        val y0 = maxOf(1, by * block); val y1 = minOf(h - 1, (by + 1) * block)
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = luma(f, (y * w + x) * 3)
            val lap = 4 * c - luma(f, (y * w + x - 1) * 3) - luma(f, (y * w + x + 1) * 3) - luma(f, ((y - 1) * w + x) * 3) - luma(f, ((y + 1) * w + x) * 3)
            sum += abs(lap)
        }
        return sum
    }

    /** Je Block das schaerfste Bild; liefert die Wahl je Block und das zusammengesetzte Bild. */
    fun stack(frames: List<ByteArray>, w: Int, h: Int, block: Int = 16, beforeRow: (Int) -> Unit = {}): Pair<IntArray, ByteArray> {
        require(frames.isNotEmpty()) { "Mindestens ein Bild" }
        require(w > 2 && h > 2 && block >= 2) { "Bild zu klein" }
        require(frames.all { it.size == w * h * 3 }) { "Alle Bilder muessen gleich gross sein" }
        val bw = (w + block - 1) / block; val bh = (h + block - 1) / block
        val choice = IntArray(bw * bh)
        val out = ByteArray(w * h * 3)
        for (by in 0 until bh) {
            beforeRow(by)
            for (bx in 0 until bw) {
                var best = 0; var bestS = -1L
                for (k in frames.indices) { val s = blockSharpness(frames[k], w, h, bx, by, block); if (s > bestS) { bestS = s; best = k } }
                choice[by * bw + bx] = best
                val src = frames[best]
                for (y in by * block until minOf(h, (by + 1) * block)) {
                    val start = (y * w + bx * block) * 3; val end = (y * w + minOf(w, (bx + 1) * block)) * 3
                    System.arraycopy(src, start, out, start, end - start)
                }
            }
        }
        return choice to out
    }
}

object StarAlignment {
    private fun lumaAt(x: ByteArray, w: Int, px: Int, py: Int) = run {
        val i = (py * w + px) * 3
        ((x[i].toInt() and 0xFF) * 54 + (x[i + 1].toInt() and 0xFF) * 183 + (x[i + 2].toInt() and 0xFF) * 19) shr 8
    }

    /**
     * Verschiebung (dx, dy), die [img] auf [ref] legt: img(x + dx, y + dy) ~ ref(x, y).
     * Suche im Bereich +-[maxShift] ueber den mittleren absoluten Fehler auf jedem [step]-ten Pixel.
     */
    fun estimateShift(ref: ByteArray, img: ByteArray, w: Int, h: Int, maxShift: Int = 16, step: Int = 2): Pair<Int, Int> {
        require(ref.size == w * h * 3 && img.size == ref.size) { "Bilder muessen gleich gross sein" }
        require(maxShift >= 0 && w > 2 * maxShift && h > 2 * maxShift) { "Suchbereich zu gross fuer das Bild" }
        // 3x3-Weichzeichnung: einzelne Sterne treffen so auch bei Schrittweite 2 sicher ein Messpixel.
        val a = blurredLuma(ref, w, h); val b = blurredLuma(img, w, h)
        var best = 0 to 0; var bestErr = Long.MAX_VALUE
        for (dy in -maxShift..maxShift) for (dx in -maxShift..maxShift) {
            var err = 0L
            var y = maxShift
            while (y < h - maxShift) {
                var x = maxShift
                val row = y * w; val rowB = (y + dy) * w + dx
                while (x < w - maxShift) {
                    err += abs(a[row + x] - b[rowB + x])
                    x += step
                }
                y += step
            }
            if (err < bestErr || (err == bestErr && abs(dx) + abs(dy) < abs(best.first) + abs(best.second))) { bestErr = err; best = dx to dy }
        }
        return best
    }

    private fun blurredLuma(x: ByteArray, w: Int, h: Int): IntArray {
        val l = IntArray(w * h) { lumaAt(x, w, it % w, it / w) }
        val out = IntArray(w * h)
        for (y in 0 until h) for (xx in 0 until w) {
            var s = 0
            for (oy in -1..1) for (ox in -1..1) s += l[(y + oy).coerceIn(0, h - 1) * w + (xx + ox).coerceIn(0, w - 1)]
            out[y * w + xx] = s
        }
        return out
    }

    /** Verschiebt ein Bild; Raender werden mit dem naechsten Randpixel gefuellt. */
    fun shift(img: ByteArray, w: Int, h: Int, dx: Int, dy: Int): ByteArray {
        val out = ByteArray(img.size)
        for (y in 0 until h) {
            val sy = (y + dy).coerceIn(0, h - 1)
            for (x in 0 until w) {
                val sx = (x + dx).coerceIn(0, w - 1)
                val s = (sy * w + sx) * 3; val d = (y * w + x) * 3
                out[d] = img[s]; out[d + 1] = img[s + 1]; out[d + 2] = img[s + 2]
            }
        }
        return out
    }

    /** Richtet alle Bilder am ersten aus und mittelt sie (Astro-Stacking). */
    fun alignAndMean(frames: List<ByteArray>, w: Int, h: Int, maxShift: Int = 16): ByteArray {
        require(frames.isNotEmpty())
        val ref = frames.first()
        val aligned = frames.mapIndexed { i, f -> if (i == 0) f else { val (dx, dy) = estimateShift(ref, f, w, h, maxShift); shift(f, w, h, dx, dy) } }
        return Stacking.stack(aligned, w * h, median = false)
    }
}
