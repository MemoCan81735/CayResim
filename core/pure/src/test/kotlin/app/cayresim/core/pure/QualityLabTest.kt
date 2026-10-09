package app.cayresim.core.pure

import app.cayresim.core.pure.ImageQuality.Rect
import java.io.File
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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
            // Nachttest S24+ am 8. Oktober: Einzelbilder im Mittel bei Stufe 1 bis 2, nur 23 Bilder
            scenario("Sehr dunkel, freihand, 23 Bilder", 0.00045, shake = 10, passer = false, count = 23),
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
            // D: Entrauschen im Bild, nicht nur durch Mitteln (vorher etwa 13 % des Einzelbilds). Untergrenze 0,015:
            // das absichtliche Dithering der Ausgabe allein ergibt bei Helligkeit 40 schon etwa 0,008
            val grain = maxOf(0.1 * one.relNoise, 0.015)
            assertTrue(k.relNoise <= grain, "$s: Korn ${k.relNoise}, hoechstens $grain (Einzelbild ${one.relNoise})")
        }
        val shaky = "Dunkel, freihand"
        assertTrue(row(shaky, "Mittel ohne Ausrichtung").edge > 2 * row(shaky, "Nacht-Kern").edge, "Ausrichtung bringt keinen Vorteil")
        val passer = row("Dunkel, freihand, Passant", "Nacht-Kern")
        assertTrue(passer.ghost!! < 3.0, "Geisterbild ${passer.ghost} Stufen")
        // Nachttest S24+: Samsung lag bei Stufe 35, wir bei 20 (Aufhellung am Anschlag 16)
        for (s in listOf("Dunkel, freihand", "Sehr dunkel, freihand, 23 Bilder"))
            assertTrue(row(s, "Nacht-Kern").bright >= 30.0, "$s: Nacht-Kern zu dunkel (${row(s, "Nacht-Kern").bright})")
    }

    /** Farbige Szene in linearem Licht (3 Kanaele): fast schwarzer Raum, Stoffe in Rot, Gelb, Blau, eine graue und eine schwarze Flaeche. */
    private fun colorTruth(level: Double): DoubleArray {
        val t = DoubleArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val c = when {
                y in 10 until 60 && x in 10 until 60 -> doubleArrayOf(0.8, 0.08, 0.05).map { it * level * 4 }
                y in 10 until 60 && x in 70 until 120 -> doubleArrayOf(0.7, 0.6, 0.05).map { it * level * 4 }
                y in 10 until 60 && x in 130 until 180 -> doubleArrayOf(0.05, 0.15, 0.7).map { it * level * 4 }
                y in 80 until 130 && x in 10 until 90 -> List(3) { level * 2.5 }
                y in 80 until 130 && x in 100 until 180 -> List(3) { 0.0 }
                else -> List(3) { level * 0.2 }
            }
            for (k in 0 until 3) t[(y * w + x) * 3 + k] = c[k]
        }
        return t
    }

    private fun captureColor(t: DoubleArray, lab: Lab, electrons: Double = 20_000.0, read: Double = 2.0, offset: DoubleArray = DoubleArray(3)) = ByteArray(t.size) { i ->
        val e = t[i] * electrons
        val noisy = (e + lab.gauss() * sqrt(e + read * read)) / electrons + offset[i % 3]
        (NightTone.linearToSrgb(noisy.toFloat()) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
    }

    /** Mittlere lineare Farbe einer Flaeche und ihre Saettigung (max - min) / max, unabhaengig von der Helligkeit. */
    private fun linearPatch(rgb: ByteArray, r: Rect): Pair<DoubleArray, Double> {
        val m = DoubleArray(3); var n = 0
        for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) { for (k in 0 until 3) m[k] += NightTone.srgbToLinear((rgb[(y * w + x) * 3 + k].toInt() and 0xFF) / 255f); n++ }
        for (k in 0 until 3) m[k] /= n
        return m to (m.max() - m.min()) / m.max().coerceAtLeast(1e-9)
    }

    @Test fun `Testlabor Farben, Kontrast und Schwarz im Nacht-Kern`() {
        val truth = colorTruth(0.0006)
        val lab = Lab(4711)
        val night = core(List(36) { captureColor(truth, lab) })
        val old = NightTone.meanAndBrighten(Lab(4711).let { l -> List(36) { captureColor(truth, l) } }, w * h, w).rgb
        val gray = Rect(15, 85, 85, 125); val black = Rect(105, 85, 175, 125)
        val contrast = ImageQuality.mean(night, w, gray) - ImageQuality.mean(night, w, black)
        val oldContrast = ImageQuality.mean(old, w, gray) - ImageQuality.mean(old, w, black)
        val sb = StringBuilder("\n## Farbszene (36 Bilder, Stativ)\n\n| Messung | einfacher Mittelwert | Nacht-Kern |\n|---|---|---|\n")
        sb.append("| Kontrast Grau minus Schwarz | ${"%.1f".format(oldContrast)} | ${"%.1f".format(contrast)} |\n")
        sb.append("| Schwarz | ${"%.1f".format(ImageQuality.mean(old, w, black))} | ${"%.1f".format(ImageQuality.mean(night, w, black))} |\n")
        // A und B: echtes Schwarz und mehr Kontrast
        assertTrue(ImageQuality.mean(night, w, black) <= 6.0, "Schwarz ist grau: ${ImageQuality.mean(night, w, black)}")
        assertTrue(contrast >= 100.0, "zu flau: Grau minus Schwarz $contrast (einfacher Mittelwert $oldContrast)")
        // C: Farben bleiben wahr, Grau bleibt grau
        val patches = listOf("Rot" to Rect(15, 15, 55, 55), "Gelb" to Rect(75, 15, 115, 55), "Blau" to Rect(135, 15, 175, 55))
        for ((name, r) in patches) {
            val want = DoubleArray(3).also { m -> var n = 0
                for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) { for (k in 0 until 3) m[k] += truth[(y * w + x) * 3 + k]; n++ }
                for (k in 0 until 3) m[k] /= n }
            val wantSat = (want.max() - want.min()) / want.max()
            val (got, sat) = linearPatch(night, r)
            sb.append("| Saettigung $name (Wahrheit ${"%.3f".format(wantSat)}) | ${"%.3f".format(linearPatch(old, r).second)} | ${"%.3f".format(sat)} |\n")
            assertTrue(kotlin.math.abs(sat - wantSat) <= 0.05, "$name: Saettigung $sat statt $wantSat")
            assertEquals(want.indices.sortedBy { want[it] }, got.indices.sortedBy { got[it] }, "$name: Farbton gekippt")
        }
        val graySat = linearPatch(night, gray).second
        sb.append("| Saettigung Grau (Wahrheit 0) | ${"%.3f".format(linearPatch(old, gray).second)} | ${"%.3f".format(graySat)} |\n")
        assertTrue(graySat <= 0.03, "Farbstich im Grau: $graySat")
        // Ein Bericht fuer das CI (nur quality-report.md wird abgelegt), egal in welcher Reihenfolge die Tests laufen
        File("build/quality-report-color.md").apply { parentFile.mkdirs() }.writeText(sb.toString())
        File("build/quality-report.md").takeIf { it.exists() && "## Farbszene" !in it.readText() }?.appendText(sb.toString())
        println(sb)
    }

    @Test fun `Testlabor lichtloser Raum mit Tuer bleibt Nacht`() {
        // Nachttest S24+ am 9. Oktober: Samsung liess den Raum schwarz (Median 2) und zeigte die Tuer; wir hellten den
        // Rauschboden 64-fach zu blaeulichem Grau auf (Median 39). Blauer Boden wie am Geraet: kleiner Versatz im Blaukanal.
        val truth = DoubleArray(w * h * 3).also { t ->
            for (y in 30 until 120) for (x in 70 until 110) { t[(y * w + x) * 3] = 0.9 * 0.0006; t[(y * w + x) * 3 + 1] = 0.75 * 0.0006; t[(y * w + x) * 3 + 2] = 0.5 * 0.0006 }
        }
        val lab = Lab(4711); val offset = doubleArrayOf(0.0, 0.0, 0.00008)
        val merge = NightMerge(w, h).apply { repeat(36) { add(captureColor(truth, lab, offset = offset)) } }
        val rgb = merge.finish().rgb
        val room = Rect(5, 5, 60, 25); val door = Rect(75, 40, 105, 110)
        val roomL = ImageQuality.mean(rgb, w, room); val doorL = ImageQuality.mean(rgb, w, door)
        val (roomRgb, _) = linearPatch(rgb, room)
        val blueCast = NightTone.linearToSrgb(roomRgb[2].toFloat()) * 255 - NightTone.linearToSrgb(roomRgb[0].toFloat()) * 255
        File("build/quality-report-floor.md").apply { parentFile.mkdirs() }.writeText(
            "\n## Lichtloser Raum mit Tuer\n\nAnteil Boden ${"%.2f".format(merge.floorShare)}, Raum ${"%.1f".format(roomL)}, Tuer ${"%.1f".format(doorL)}, Blau minus Rot im Raum ${"%.1f".format(blueCast)}\n")
        File("build/quality-report.md").takeIf { it.exists() && "## Lichtloser Raum" !in it.readText() }
            ?.appendText(File("build/quality-report-floor.md").readText())
        assertTrue(merge.floorShare >= NightTone.FLOOR_SHARE, "Boden nicht erkannt: ${merge.floorShare}")
        assertTrue(roomL <= 8.0, "Raum aufgehellt: $roomL")
        assertTrue(doorL - roomL >= 15.0, "Tuer geht unter: Raum $roomL, Tuer $doorL")
        assertTrue(kotlin.math.abs(blueCast) <= 3.0, "Farbstich im Raum: $blueCast")
    }

    @Test fun `Testlabor heller gestreifter Vorhang brennt nicht aus`() {
        // Nachttest S24+ am 9. Oktober (Morgendaemmerung): unser Vorhang bei 249, Samsung bei 201 mit sichtbarer Struktur
        val scene = DoubleArray(w * h) { p ->
            val x = p % w; val y = p / w
            if (x in 10 until 60 && y in 10 until 134) 0.05 * (1 + 0.3 * kotlin.math.sign(kotlin.math.sin(x / 2.0))) else 0.002
        }
        val lab = Lab(1)
        val rgb = core(List(36) { capture(scene, 0, 0, lab) })
        val lum = DoubleArray(w * h) { ImageQuality.luma(rgb, it * 3).toDouble() }
        val p99 = lum.sorted()[(0.99 * (lum.size - 1)).toInt()]
        val cur = Rect(10, 10, 60, 134)
        val texture = ImageQuality.noise(rgb, w, cur) / ImageQuality.mean(rgb, w, cur)
        File("build/quality-report-highlight.md").apply { parentFile.mkdirs() }.writeText(
            "\n## Heller Vorhang\n\n99-%-Helligkeit ${"%.0f".format(p99)} (vorher 233, Samsung im Nachttest 201), Struktur ${"%.3f".format(texture)} (Wahrheit etwa 0,15)\n")
        File("build/quality-report.md").takeIf { it.exists() && "## Heller Vorhang" !in it.readText() }
            ?.appendText(File("build/quality-report-highlight.md").readText())
        assertTrue(p99 <= 215.0, "Lichter brennen aus: 99-%-Helligkeit $p99")
        assertTrue(texture >= 0.08, "Struktur im Vorhang verloren: $texture")
        assertTrue(ImageQuality.mean(rgb, w, Rect(100, 20, 180, 120)) >= 25.0, "Raum zu dunkel geworden")
    }

    // ---------- RAW-Weg: simulierter Bayer-Sensor (10 Bit, Schwarz 64, nicht abgeschnitten) ----------

    /** Aus einer linearen RGB-Wahrheit in Laborgroesse ein RGGB-Rohbild doppelter Kantenlaenge. */
    private fun captureRaw(truth: DoubleArray, lab: Lab, black: Int = 64, white: Int = 1023, electrons: Double = 20_000.0, read: Double = 40.0): ShortArray {
        val rw = w * 2; val rh = h * 2
        val colors = RawDevelop.Cfa.RGGB.colors
        return ShortArray(rw * rh) { i ->
            val x = i % rw; val y = i / rw
            val e = truth[((y / 2) * w + x / 2) * 3 + colors[(y % 2) * 2 + x % 2]] * electrons
            val noisy = (e + lab.gauss() * sqrt(e + read * read)) / electrons
            (black + noisy * (white - black)).let { kotlin.math.round(it).toInt() }.coerceIn(0, white).toShort()
        }
    }

    private fun rawCore(frames: List<ShortArray>): ByteArray = NightMerge(w, h).apply {
        frames.forEach { addLinear(RawDevelop.binToLinear(it, w * 2, h * 2, w * 2, RawDevelop.Cfa.RGGB, floatArrayOf(64f, 64f, 64f, 64f), 1023f, floatArrayOf(1f, 1f, 1f), RawDevelop.IDENTITY)) }
    }.finish().rgb

    @Test fun `Testlabor RAW-Weg lichtloser Raum mit Tuer bleibt Nacht`() {
        val truth = DoubleArray(w * h * 3).also { t ->
            for (y in 30 until 120) for (x in 70 until 110) { t[(y * w + x) * 3] = 0.9 * 0.0006; t[(y * w + x) * 3 + 1] = 0.75 * 0.0006; t[(y * w + x) * 3 + 2] = 0.5 * 0.0006 }
        }
        val lab = Lab(4711)
        val rgb = rawCore(List(36) { captureRaw(truth, lab) })
        val roomL = ImageQuality.mean(rgb, w, Rect(5, 5, 60, 25)); val doorL = ImageQuality.mean(rgb, w, Rect(75, 40, 105, 110))
        val (roomRgb, _) = linearPatch(rgb, Rect(5, 5, 60, 25))
        val cast = NightTone.linearToSrgb(roomRgb[2].toFloat()) * 255 - NightTone.linearToSrgb(roomRgb[0].toFloat()) * 255
        File("build/quality-report-raw.md").apply { parentFile.mkdirs() }.writeText(
            "\n## RAW-Weg (simulierter Sensor)\n\nLichtloser Raum: Raum ${"%.1f".format(roomL)}, Tuer ${"%.1f".format(doorL)}, Blau minus Rot ${"%.1f".format(cast)}\n")
        assertTrue(roomL <= 8.0, "RAW: Raum aufgehellt: $roomL")
        assertTrue(doorL - roomL >= 15.0, "RAW: Tuer geht unter: Raum $roomL, Tuer $doorL")
        assertTrue(kotlin.math.abs(cast) <= 3.0, "RAW: Farbstich im Raum: $cast")
    }

    @Test fun `Testlabor RAW-Weg dunkler Raum mit Restlicht wird aufgehellt und bleibt scharf`() {
        val gray = truth(0.0006)
        val truthRgb = DoubleArray(w * h * 3) { gray[it / 3] }
        val lab = Lab(99)
        val rgb = rawCore(List(36) { captureRaw(truthRgb, lab) })
        val bright = ImageQuality.mean(rgb, w, background)
        val edge = ImageQuality.edgeWidth(rgb, w, 80, 130, 108, 132)
        File("build/quality-report-raw.md").appendText("Dunkler Raum: Helligkeit ${"%.1f".format(bright)}, Kante ${"%.1f".format(edge)} px, Rauschen ${"%.3f".format(ImageQuality.relativeNoise(rgb, w, flat))}\n")
        File("build/quality-report.md").takeIf { it.exists() && "## RAW-Weg" !in it.readText() }?.appendText(File("build/quality-report-raw.md").readText())
        assertTrue(bright >= 30.0, "RAW: zu dunkel: $bright")
        assertTrue(edge <= 1.5, "RAW: Kante $edge px")
    }

    @Test fun `Testlabor Szenen mit Restlicht gelten nicht als lichtlos`() {
        for ((level, count) in listOf(0.0006 to 36, 0.00045 to 23, 0.004 to 36)) {
            val s = scenario("x", level, shake = 10, passer = false, count = count)
            val m = NightMerge(w, h).apply { s.frames.forEach { add(it) } }
            assertTrue(m.floorShare < NightTone.FLOOR_SHARE, "Restlicht $level als lichtlos erkannt: ${m.floorShare}")
        }
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
        File("build/quality-report-color.md").takeIf { it.exists() }?.let { sb.append(it.readText()) }
        File("build/quality-report-floor.md").takeIf { it.exists() }?.let { sb.append(it.readText()) }
        File("build/quality-report-highlight.md").takeIf { it.exists() }?.let { sb.append(it.readText()) }
        File("build/quality-report-raw.md").takeIf { it.exists() }?.let { sb.append(it.readText()) }
        f.writeText(sb.toString())
        println(sb)
    }
}
