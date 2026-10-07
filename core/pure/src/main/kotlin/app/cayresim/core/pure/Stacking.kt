package app.cayresim.core.pure

/**
 * Bildstapel als RGB-Bytes (3 Bytes je Pixel, Zeile fuer Zeile). Kotlin-Referenz fuer
 * "Menschen wegrechnen" (Median) und "Langzeitbelichtung" (Mittelwert), kachelweise rechenbar,
 * damit lange Rechnungen zwischen den Kacheln abgebrochen werden koennen (R17).
 */
object Stacking {
    /** Median je Kanal fuer die Pixel [fromPixel, toPixel). Bei gerader Anzahl der untere Median. */
    fun medianRange(frames: List<ByteArray>, out: ByteArray, fromPixel: Int, toPixel: Int) {
        val n = frames.size
        require(n >= 1) { "Mindestens ein Bild" }
        val buf = IntArray(n)
        for (i in fromPixel * 3 until toPixel * 3) {
            for (k in 0 until n) buf[k] = frames[k][i].toInt() and 0xFF
            insertionSort(buf, n)
            out[i] = buf[(n - 1) / 2].toByte()
        }
    }

    /** Gerundeter Mittelwert je Kanal fuer die Pixel [fromPixel, toPixel). */
    fun meanRange(frames: List<ByteArray>, out: ByteArray, fromPixel: Int, toPixel: Int) {
        val n = frames.size
        require(n >= 1) { "Mindestens ein Bild" }
        for (i in fromPixel * 3 until toPixel * 3) {
            var sum = 0
            for (k in 0 until n) sum += frames[k][i].toInt() and 0xFF
            out[i] = ((sum * 2 + n) / (2 * n)).toByte()
        }
    }

    /** Ganzer Stapel in Kacheln; [beforeTile] darf werfen (Abbruch). */
    fun stack(frames: List<ByteArray>, pixels: Int, median: Boolean, tilePixels: Int = 65_536, beforeTile: (Int) -> Unit = {}): ByteArray {
        require(frames.isNotEmpty()) { "Mindestens ein Bild" }
        require(frames.all { it.size == pixels * 3 }) { "Alle Bilder muessen gleich gross sein" }
        val out = ByteArray(pixels * 3)
        var p = 0; var tile = 0
        while (p < pixels) {
            beforeTile(tile++)
            val end = minOf(pixels, p + tilePixels)
            if (median) medianRange(frames, out, p, end) else meanRange(frames, out, p, end)
            p = end
        }
        return out
    }

    private fun insertionSort(a: IntArray, n: Int) {
        for (i in 1 until n) {
            val v = a[i]; var j = i - 1
            while (j >= 0 && a[j] > v) { a[j + 1] = a[j]; j-- }
            a[j + 1] = v
        }
    }

    /**
     * Bewegungsmass zwischen zwei Bildern: mittlere absolute Helligkeitsdifferenz (0..255)
     * auf jedem [step]-ten Pixel. Grundlage fuer den intelligenten Ausloeser.
     */
    fun motionScore(a: ByteArray, b: ByteArray, pixels: Int, step: Int = 7): Float {
        require(a.size == b.size && a.size == pixels * 3) { "Bilder muessen gleich gross sein" }
        require(step >= 1)
        var sum = 0L; var count = 0
        var p = 0
        while (p < pixels) {
            val i = p * 3
            sum += kotlin.math.abs(luma(a, i) - luma(b, i)); count++
            p += step
        }
        return if (count == 0) 0f else sum.toFloat() / count
    }

    private fun luma(x: ByteArray, i: Int): Int =
        ((x[i].toInt() and 0xFF) * 54 + (x[i + 1].toInt() and 0xFF) * 183 + (x[i + 2].toInt() and 0xFF) * 19) shr 8
}
