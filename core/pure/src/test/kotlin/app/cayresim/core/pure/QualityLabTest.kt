package app.cayresim.core.pure

import app.cayresim.core.pure.ImageQuality.Rect
import java.io.File
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Testlabor Bildqualitaet: kuenstliche Nachtserien mit bekannter Wahrheit.
 *
 * Rauschmodell wie ein echter Sensor: Photonenrauschen (waechst mit dem Licht) plus Ausleserauschen,
 * danach wie das Handy: Gamma und 8 Bit. Dazu Verwackeln und ein Passant. Gemessen werden Rauschen,
 * Kantenbreite (Schaerfe), Geisterbild und Helligkeit, verglichen mit einem Einzelbild und einem
 * Mittelwert ohne Ausrichtung. Der Bericht landet in build/quality-report.md (im CI unter ci-logs-fast).
 */
class QualityLabTest {
    private val w = 192; private val h = 144
    private val flat = Rect(8, 8, 72, 60)
    private val ghostArea = Rect(20, 72, 100, 100)
    private val background = Rect(8, 104, 100, 140)

    /** Szene in linearem Licht: dunkler Raum, hellere Flaeche rechts (Kante bei x = 120), Lampe oben rechts. */
    private fun truth(level: Double, passerX: Int? = null): DoubleArray = DoubleArray(w * h) { p ->
        val x = p % w; val y = p / w
        var v = level
        if (x >= 120 && y in 64 until 140) v = level * 4
        val dx = x - 160; val dy = y - 30
        if (dx * dx + dy * dy < 64) v = 0.5
        if (passerX != null && x in passerX until passerX + 20 && y in 70 until 100) v = level * 12
        v
    }

    private class Lab(seed: Long) {
        val rnd = Random(seed)
        fun gauss(): Double { // Box-Muller
            val u = rnd.nextDouble().coerceAtLeast(1e-12); val v = rnd.nextDouble()
            return sqrt(-2 * ln(u)) * kotlin.math.cos(2 * Math.PI * v)
        }
    }

    /** Ein Bild wie vom Handy: verschoben, verrauscht (Photonen + Auslesen), Gamma, 8 Bit. */
    private fun capture(scene: DoubleArray, dx: Int, dy: Int, lab: Lab, electrons: Double = 20_000.0, read: Double = 2.0): ByteArray {
        val out = ByteArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = (x + dx).coerceIn(0, w - 1); val sy = (y + dy).coerceIn(0, h - 1)
            val e = scene[sy * w + sx] * electrons
            val noisy = (e + lab.gauss() * sqrt(e + read * read)) / electrons
            val v = (NightTone.linearToSrgb(noisy.toFloat()) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            val i = (y * w + x) * 3; out[i] = v; out[i + 1] = v; out[i + 2] = v
        }
        return out
    }

    private data class Scenario(val name: String, val frames: List<ByteArray>, val clean: List<ByteArray>?)

    private fun scenario(name: String, level: Double, shake: Int, passer: Boolean, count: Int = 36, seed: Long = 4711): Scenario {
        val lab = Lab(seed); val shifts = Random(seed + 1)
        val moves = List(count) { if (it == 0 || shake == 0) 0 to 0 else shifts.nextInt(-shake, shake + 1) to shifts.nextInt(-shake, shake + 1) }
        val frames = List(count) { k ->
            val px = if (passer && k in 4..16) 10 + (k - 4) * 12 else null
            capture(truth(level, px), moves[k].first, moves[k].second, lab)
        }
        val clean = if (passer) { val lab2 = Lab(seed); List(count) { k -> capture(truth(level), moves[k].first, moves[k].second, lab2) } } else null
        return Scenario(name, frames, clean)
    }

    private fun core(frames: List<ByteArray>) = NightMerge(w, h).apply { frames.forEach { add(it) } }.finish().rgb

    private data class Row(val scenario: String, val method: String, val relNoise: Double, val edge: Double, val ghost: Double?, val bright: Double)

    private fun measure(s: Scenario, method: String, rgb: ByteArray, cleanRgb: ByteArray?) = Row(
        s.name, method,
        ImageQuality.relativeNoise(rgb, w, flat),
        ImageQuality.edgeWidth(rgb, w, 80, 130, 108, 132),
        cleanRgb?.let { ImageQuality.mean(rgb, w, ghostArea) - ImageQuality.mean(it, w, ghostArea) },
        ImageQuality.mean(rgb, w, background),
    )

    @Test fun `Testlabor Nacht-Kern gegen Einzelbild und einfachen Mittelwert`() {
        val scenarios = listOf(
            scenario("Dunkel, Stativ", 0.0006, shake = 0, passer = false),
            scenario("Dunkel, freihand", 0.0006, shake = 10, passer = false),
            scenario("Dunkel, freihand, Passant", 0.0006, shake = 10, passer = true),
            scenario("Daemmerung, freihand", 0.004, shake = 6, passer = false),
        )
        val rows = mutableListOf<Row>()
        for (s in scenarios) {
            val single = NightTone.brightenBytes(s.frames.first(), w).rgb
            val mean = NightTone.meanAndBrighten(s.frames, w * h, w).rgb
            val night = core(s.frames)
            val cleanMean = s.clean?.let { NightTone.meanAndBrighten(it, w * h, w).rgb }
            val cleanNight = s.clean?.let { core(it) }
            rows += measure(s, "Einzelbild", single, null)
            rows += measure(s, "Mittel ohne Ausrichtung", mean, cleanMean)
            rows += measure(s, "Nacht-Kern", night, cleanNight)
        }
        writeReport(rows)

        fun row(s: String, m: String) = rows.single { it.scenario == s && it.method == m }
        for (s in scenarios.map { it.name }) {
            val one = row(s, "Einzelbild"); val k = row(s, "Nacht-Kern")
            // Grenzwerte: werden sie verletzt, ist eine Aenderung eine Verschlechterung
            assertTrue(k.relNoise <= 0.4 * one.relNoise, "$s: Rauschen ${k.relNoise} statt hoechstens 40 % von ${one.relNoise}")
            assertTrue(k.edge <= 1.5, "$s: Kante ${k.edge} px, zu weich (Wahrheit 0,8 px)")
        }
        val shaky = "Dunkel, freihand"
        assertTrue(row(shaky, "Mittel ohne Ausrichtung").edge > 2 * row(shaky, "Nacht-Kern").edge, "Ausrichtung bringt keinen Vorteil")
        val passer = row("Dunkel, freihand, Passant", "Nacht-Kern")
        assertTrue(passer.ghost!! < 3.0, "Geisterbild ${passer.ghost} Stufen")
        assertTrue(row("Dunkel, freihand", "Nacht-Kern").bright >= 10.0, "Nacht-Kern zu dunkel")
    }

    private fun writeReport(rows: List<Row>) {
        val f = File("build/quality-report.md"); f.parentFile.mkdirs()
        val sb = StringBuilder("# Testlabor Bildqualitaet\n\n")
        sb.append("Relatives Rauschen (kleiner ist besser), Kantenbreite in Pixeln (kleiner ist schaerfer), ")
        sb.append("Geisterbild in Helligkeitsstufen (nahe 0 ist gut), Helligkeit des dunklen Hintergrunds (0 bis 255).\n\n")
        sb.append("| Szene | Verfahren | Rauschen | Kante | Geist | Helligkeit |\n|---|---|---|---|---|---|\n")
        rows.forEach { r ->
            sb.append("| ${r.scenario} | ${r.method} | ${"%.3f".format(r.relNoise)} | ${"%.1f".format(r.edge)} | ${r.ghost?.let { "%.1f".format(it) } ?: ""} | ${"%.1f".format(r.bright)} |\n")
        }
        f.writeText(sb.toString())
        println(sb)
    }
}
