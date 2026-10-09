package app.cayresim.core.pure

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Prueft die Pruefung (CLAUDE.md): die Kantenmessung gegen bekannte Wahrheiten. */
class ImageQualityTest {
    private val w = 24; private val h = 50

    /** Senkrechte Stufe von 37 auf 62 bei x = 12, optional weichgezeichnet (Kasten mit Radius [blur]) und verrauscht. */
    private fun step(blur: Int, noise: Double, seed: Int): ByteArray {
        val r = Random(seed)
        val row = DoubleArray(w) { x -> if (x >= 12) 62.0 else 37.0 }
        val soft = DoubleArray(w) { x -> (-blur..blur).map { row[(x + it).coerceIn(0, w - 1)] }.average() }
        val out = ByteArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val v = (soft[x] + r.nextDouble(-1.0, 1.0) * noise * 1.732).toInt().coerceIn(0, 255).toByte()
            val i = (y * w + x) * 3; out[i] = v; out[i + 1] = v; out[i + 2] = v
        }
        return out
    }

    @Test fun `Angepasste Kantenbreite scharf bleibt scharf trotz starkem Rauschen`() {
        // Rauschen 10 Stufen je Pixel: im Profil (50 Zeilen) etwa 1,4 Stufen, wie im Laborfall starkes Rauschen
        for (seed in 1..6) {
            val img = step(0, 10.0, seed)
            val fit = ImageQuality.edgeWidthFit(img, w, 0, h, 0, w)
            assertTrue(fit <= 0.8, "Startwert $seed: scharfe Stufe als $fit px gemessen")
        }
    }

    @Test fun `Angepasste Kantenbreite erkennt Weichzeichnen`() {
        for (seed in 1..6) {
            val sharp = ImageQuality.edgeWidthFit(step(0, 10.0, seed), w, 0, h, 0, w)
            val soft = ImageQuality.edgeWidthFit(step(2, 10.0, seed), w, 0, h, 0, w)
            // Kasten 5 Pixel: 10-90-Breite der Wahrheit 4 Pixel
            assertTrue(soft >= 2.5 && soft >= sharp + 2.0, "Startwert $seed: weich $soft, scharf $sharp")
        }
    }
}
