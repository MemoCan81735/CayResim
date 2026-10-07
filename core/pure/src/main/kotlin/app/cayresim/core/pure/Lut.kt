package app.cayresim.core.pure

/**
 * 3D-Farbtabelle (LUT) mit [size] Stuetzstellen je Achse, Werte 0..1, Reihenfolge r schnell, dann g, dann b.
 * Kotlin-Referenz zum GPU-Shader (R20): beide rechnen trilinear.
 */
class Lut3D(val size: Int, val data: FloatArray) {
    init {
        require(size >= 2) { "LUT braucht mindestens 2 Stuetzstellen" }
        require(data.size == size * size * size * 3) { "LUT-Daten passen nicht zur Groesse $size" }
    }

    private fun idx(r: Int, g: Int, b: Int) = ((b * size + g) * size + r) * 3

    /** Wendet die LUT auf einen 8-Bit-Farbwert an, Ergebnis gerundet auf 8 Bit. */
    fun apply(r: Int, g: Int, b: Int): IntArray {
        val out = applyFloat(r / 255f, g / 255f, b / 255f)
        return IntArray(3) { (out[it] * 255f + 0.5f).toInt().coerceIn(0, 255) }
    }

    fun applyFloat(rf: Float, gf: Float, bf: Float): FloatArray {
        val m = size - 1
        val x = rf.coerceIn(0f, 1f) * m; val y = gf.coerceIn(0f, 1f) * m; val z = bf.coerceIn(0f, 1f) * m
        val x0 = minOf(x.toInt(), m - 1); val y0 = minOf(y.toInt(), m - 1); val z0 = minOf(z.toInt(), m - 1)
        val dx = x - x0; val dy = y - y0; val dz = z - z0
        val out = FloatArray(3)
        for (c in 0..2) {
            fun v(i: Int, j: Int, k: Int) = data[idx(x0 + i, y0 + j, z0 + k) + c]
            val c00 = v(0, 0, 0) * (1 - dx) + v(1, 0, 0) * dx
            val c10 = v(0, 1, 0) * (1 - dx) + v(1, 1, 0) * dx
            val c01 = v(0, 0, 1) * (1 - dx) + v(1, 0, 1) * dx
            val c11 = v(0, 1, 1) * (1 - dx) + v(1, 1, 1) * dx
            out[c] = (c00 * (1 - dy) + c10 * dy) * (1 - dz) + (c01 * (1 - dy) + c11 * dy) * dz
        }
        return out
    }

    companion object {
        /** Tastet eine Farbfunktion an size^3 Stellen ab. */
        fun from(size: Int, f: (Float, Float, Float) -> FloatArray): Lut3D {
            val d = FloatArray(size * size * size * 3)
            val m = (size - 1).toFloat()
            var i = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                val o = f(r / m, g / m, b / m)
                d[i++] = o[0].coerceIn(0f, 1f); d[i++] = o[1].coerceIn(0f, 1f); d[i++] = o[2].coerceIn(0f, 1f)
            }
            return Lut3D(size, d)
        }

        fun identity(size: Int = 17) = from(size) { r, g, b -> floatArrayOf(r, g, b) }
    }
}

/** Eingebaute Looks. Neue Looks bekommen hier eine Formel; die Kennung bleibt fuer gespeicherte Werte stabil (R26). */
enum class LookId { NONE, WARM, COOL, FILM, MONO }

object Looks {
    const val SIZE = 17

    fun lut(id: LookId): Lut3D = when (id) {
        LookId.NONE -> Lut3D.identity(SIZE)
        LookId.WARM -> Lut3D.from(SIZE) { r, g, b -> floatArrayOf(r * 1.06f + 0.02f, g * 1.01f, b * 0.90f) }
        LookId.COOL -> Lut3D.from(SIZE) { r, g, b -> floatArrayOf(r * 0.92f, g * 0.99f, b * 1.06f + 0.02f) }
        LookId.FILM -> Lut3D.from(SIZE) { r, g, b ->
            val l = luma(r, g, b)
            // sanfte S-Kurve plus Schatten leicht blaugruen, Lichter leicht orange
            fun s(v: Float) = v * v * (3 - 2 * v) * 0.85f + v * 0.15f
            floatArrayOf(s(r) + 0.03f * l, s(g) + 0.01f, s(b) + 0.04f * (1 - l) - 0.02f * l)
        }
        LookId.MONO -> Lut3D.from(SIZE) { r, g, b -> val l = luma(r, g, b); floatArrayOf(l, l, l) }
    }

    fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b
}
