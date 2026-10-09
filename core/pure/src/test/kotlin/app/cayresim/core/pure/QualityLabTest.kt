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

    private data class Scenario(val name: String, val frames: List<ByteArray>, val clean: List<ByteArray>?, val read: Double = 2.0)

    private fun scenario(name: String, level: Double, shake: Int, passer: Boolean, count: Int = 36, seed: Long = 4711, read: Double = 2.0): Scenario {
        val lab = Lab(seed); val shifts = Random(seed + 1)
        val moves = List(count) { if (it == 0 || shake == 0) 0 to 0 else shifts.nextInt(-shake, shake + 1) to shifts.nextInt(-shake, shake + 1) }
        val frames = List(count) { k ->
            val px = if (passer && k in 4..16) 10 + (k - 4) * 12 else null
            capture(truth(level, px), moves[k].first, moves[k].second, lab, read = read)
        }
        val clean = if (passer) { val lab2 = Lab(seed); List(count) { k -> capture(truth(level), moves[k].first, moves[k].second, lab2, read = read) } } else null
        return Scenario(name, frames, clean, read)
    }

    private fun core(frames: List<ByteArray>) = NightMerge(w, h).apply { frames.forEach { add(it) } }.finish().rgb

    private data class Row(val scenario: String, val method: String, val relNoise: Double, val edge: Double, val ghost: Double?, val bright: Double, val edgeFit: Double)

    private fun measure(s: Scenario, method: String, rgb: ByteArray, cleanRgb: ByteArray?) = Row(
        s.name, method,
        ImageQuality.relativeNoise(rgb, w, flat),
        ImageQuality.edgeWidth(rgb, w, 80, 130, 108, 132),
        cleanRgb?.let { ImageQuality.mean(rgb, w, ghostArea) - ImageQuality.mean(it, w, ghostArea) },
        ImageQuality.mean(rgb, w, background),
        ImageQuality.edgeWidthFit(rgb, w, 80, 130, 108, 132),
    )

    @Test fun `Testlabor Nacht-Kern gegen Einzelbild und einfachen Mittelwert`() {
        val scenarios = listOf(
            scenario("Dunkel, Stativ", 0.0006, shake = 0, passer = false),
            scenario("Dunkel, freihand", 0.0006, shake = 10, passer = false),
            scenario("Dunkel, freihand, Passant", 0.0006, shake = 10, passer = true),
            scenario("Daemmerung, freihand", 0.004, shake = 6, passer = false),
            // Nachttest S24+ am 8. Oktober: Einzelbilder im Mittel bei Stufe 1 bis 2, nur 23 Bilder
            scenario("Sehr dunkel, freihand, 23 Bilder", 0.00045, shake = 10, passer = false, count = 23),
            // S-001 K1, K2: starkes Sensorrauschen (Leserauschen 40); vorher machte das Entrauschen die Kante 3,6 px breit
            scenario("Dunkel, starkes Rauschen", 0.0006, shake = 0, passer = false, read = 40.0),
            scenario("Starkes Rauschen, 1 Pixel Wackeln", 0.0006, shake = 1, passer = false, read = 40.0),
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
            // S-001: bei starkem Rauschen springt die 10-90-Messung auf Ausreisser (Python-Modell: bis 3 px fuer ein
            // scharfes Mittel), dort gilt die angepasste Kante: ideale Stufe 0,2, ohne Entrauschen 0,3 bis 0,7,
            // Entrauschen 2,5 (Befund 1) 2,4, Entrauschen 1,5 etwa 1,0
            if (scenarios.single { it.name == s }.read > 10) assertTrue(k.edgeFit <= 1.3, "$s: angepasste Kante ${k.edgeFit} px, zu weich (ideal 0,2)")
            else assertTrue(k.edge <= 1.5, "$s: Kante ${k.edge} px, zu weich (Wahrheit 0,8 px)")
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
        // S-001 K7: Bericht auch dann, wenn eine Grenze verletzt ist
        try {
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
        } finally { report("20-farbe", sb.toString()) }
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
        report("30-lichtlos", "\n## Lichtloser Raum mit Tuer\n\nAnteil Boden ${"%.2f".format(merge.floorShare)}, Raum ${"%.1f".format(roomL)}, Tuer ${"%.1f".format(doorL)}, Blau minus Rot im Raum ${"%.1f".format(blueCast)}\n")
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
        report("40-vorhang", "\n## Heller Vorhang\n\n99-%-Helligkeit ${"%.0f".format(p99)} (vorher 233, Samsung im Nachttest 201), Struktur ${"%.3f".format(texture)} (Wahrheit etwa 0,15)\n")
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
        report("50-raw-a", "\n## RAW-Weg (simulierter Sensor)\n\nLichtloser Raum: Raum ${"%.1f".format(roomL)}, Tuer ${"%.1f".format(doorL)}, Blau minus Rot ${"%.1f".format(cast)}\n")
        assertTrue(roomL <= 8.0, "RAW: Raum aufgehellt: $roomL")
        assertTrue(doorL - roomL >= 15.0, "RAW: Tuer geht unter: Raum $roomL, Tuer $doorL")
        assertTrue(kotlin.math.abs(cast) <= 3.0, "RAW: Farbstich im Raum: $cast")
    }

    @Test fun `Testlabor RAW-Weg dunkler Raum mit Restlicht wird aufgehellt und bleibt scharf`() {
        val gray = truth(0.0006)
        val truthRgb = DoubleArray(w * h * 3) { gray[it / 3] }
        val rgb = rawCore(Lab(99).let { l -> List(36) { captureRaw(truthRgb, l) } })
        // Vergleich mit dem 8-Bit-Weg bei gleich starkem Sensorrauschen (Leserauschen 40 statt 2 wie in den anderen Szenen)
        val yuv = core(Lab(99).let { l -> List(36) { capture(gray, 0, 0, l, read = 40.0) } })
        val bright = ImageQuality.mean(rgb, w, background)
        // S-001: angepasste Kante, weil die 10-90-Messung bei Leserauschen 40 auf Ausreisser springt
        val edge = ImageQuality.edgeWidthFit(rgb, w, 80, 130, 108, 132); val yuvEdge = ImageQuality.edgeWidthFit(yuv, w, 80, 130, 108, 132)
        val noise = ImageQuality.relativeNoise(rgb, w, flat); val yuvNoise = ImageQuality.relativeNoise(yuv, w, flat)
        report("51-raw-b", "\n## RAW-Weg, dunkler Raum\n\nRAW gegen 8 Bit bei gleichem Rauschen: Helligkeit ${"%.1f".format(bright)} / ${"%.1f".format(ImageQuality.mean(yuv, w, background))}, " +
            "angepasste Kante ${"%.2f".format(edge)} / ${"%.2f".format(yuvEdge)} px, Rauschen ${"%.3f".format(noise)} / ${"%.3f".format(yuvNoise)}\n")
        assertTrue(bright >= 30.0, "RAW: zu dunkel: $bright")
        // S-001: feste Grenze gegen die Wahrheit, nicht nur der Vergleich zweier Verfahren
        assertTrue(edge <= 1.3, "RAW: angepasste Kante $edge px (ideal 0,2)")
        assertTrue(yuvEdge <= 1.3, "8 Bit bei starkem Rauschen: angepasste Kante $yuvEdge px (ideal 0,2)")
        val single = rawCore(Lab(7).let { l -> listOf(captureRaw(truthRgb, l)) })
        val singleNoise = ImageQuality.relativeNoise(single, w, flat)
        assertTrue(noise <= 0.4 * singleNoise, "RAW: Rauschen $noise, Einzelbild $singleNoise")
    }

    // ---------- Nachttest S24+ am 9. Oktober (Auto bei Regen): Randstreifen und Blockkanten ----------

    @Test fun `Testlabor verwackelte Serie behaelt am Rand so viel Struktur wie in der Mitte`() {
        // unregelmaessiges Muster ueber das ganze Bild (ein regelmaessiges waere fuer die Ausrichtung mehrdeutig); Wackeln bis 10 Pixel
        val blocks = Random(23).let { r -> DoubleArray(((w + 2) / 3) * ((h + 2) / 3)) { if (r.nextBoolean()) 0.0008 else 0.0024 } }
        val scene = DoubleArray(w * h) { p -> val x = p % w; val y = p / w; blocks[(y / 3) * ((w + 2) / 3) + x / 3] }
        val lab = Lab(21); val shifts = Random(22)
        val merge = NightMerge(w, h)
        repeat(36) { k -> merge.add(capture(scene, if (k == 0) 0 else shifts.nextInt(-10, 11), if (k == 0) 0 else shifts.nextInt(-10, 11), lab)) }
        val rgb = merge.finish().rgb
        fun texture(r: Rect) = ImageQuality.noise(rgb, w, r) / ImageQuality.mean(rgb, w, r)
        val middle = texture(Rect(70, 50, 120, 94)); val right = texture(Rect(w - 12, 30, w - 1, 114)); val top = texture(Rect(40, 0, 150, 10))
        report("60-rand", "\n## Rand bei Wackeln\n\nStruktur Mitte ${"%.3f".format(middle)}, rechter Rand ${"%.3f".format(right)}, oberer Rand ${"%.3f".format(top)}; " +
            "Bilder je Pixel Mitte ${"%.1f".format(merge.weightIn(70, 50, 120, 94))}, Rand ${"%.1f".format(merge.weightIn(w - 12, 30, w - 1, 114))}\n")
        assertTrue(right >= 0.8 * middle, "rechter Rand verschmiert: $right gegen Mitte $middle")
        assertTrue(top >= 0.8 * middle, "oberer Rand verschmiert: $top gegen Mitte $middle")
        assertTrue(merge.weightIn(w - 12, 30, w - 1, 114) < merge.weightIn(70, 50, 120, 94), "Rand muss aus weniger Bildern bestehen als die Mitte")
    }

    @Test fun `Testlabor verwackelte Einzelbilder werden verworfen, auch wenn das erste verwackelt ist`() {
        // S-003 K1: Bewegungsunschaerfe von 8 Pixeln (waagrecht) in jedem dritten Bild, auch im ersten. Python-Modell:
        // alter Weg (Bezug = erstes Bild) nimmt alle 36, Struktur 75 %; Bezug = schaerfstes der ersten 3: 24 Bilder, 100 %
        val blocks = Random(23).let { r -> DoubleArray(((w + 2) / 3) * ((h + 2) / 3)) { if (r.nextBoolean()) 0.0008 else 0.0024 } }
        val scene = DoubleArray(w * h) { p -> val x = p % w; val y = p / w; blocks[(y / 3) * ((w + 2) / 3) + x / 3] }
        val smeared = DoubleArray(w * h) { p -> val x = p % w; val y = p / w; (0 until 8).sumOf { k -> scene[y * w + (x + k).coerceAtMost(w - 1)] } / 8 }
        val shaky = NightMerge(w, h); val lab = Lab(41)
        repeat(36) { k -> shaky.add(capture(if (k % 3 == 0) smeared else scene, 0, 0, lab)) }
        val calm = NightMerge(w, h); val lab2 = Lab(41)
        repeat(36) { calm.add(capture(scene, 0, 0, lab2)) }
        fun texture(rgb: ByteArray) = Rect(70, 50, 120, 94).let { ImageQuality.noise(rgb, w, it) / ImageQuality.mean(rgb, w, it) }
        val t = texture(shaky.finish().rgb); val t0 = texture(calm.finish().rgb)
        report("62-bewegungsunschaerfe", "\n## Bewegungsunschaerfe in jedem dritten Bild\n\nStruktur ${"%.3f".format(t)} gegen ${"%.3f".format(t0)} ohne Unschaerfe, " +
            "verworfen ${shaky.dropped} von 36\n")
        assertTrue(shaky.dropped >= 12, "verwackelte Bilder nicht verworfen: ${shaky.dropped}")
        assertTrue(t >= 0.9 * t0, "Struktur $t, hoechstens 10 % unter $t0")
    }

    // ---------- S-004: Ausrichtung fuer Langzeit und Menschen wegrechnen ----------

    /** Unregelmaessiges Muster wie im Randtest; [passerX]: helles Objekt (Passant) an dieser Stelle. */
    private fun blockTruth(passerX: Int? = null): DoubleArray {
        val blocks = Random(23).let { r -> DoubleArray(((w + 2) / 3) * ((h + 2) / 3)) { if (r.nextBoolean()) 0.0008 else 0.0024 } }
        return DoubleArray(w * h) { p ->
            val x = p % w; val y = p / w
            if (passerX != null && x in passerX until passerX + 20 && y in 60 until 100) 0.012 else blocks[(y / 3) * ((w + 2) / 3) + x / 3]
        }
    }

    private fun texture(rgb: ByteArray) = Rect(70, 50, 120, 94).let { ImageQuality.noise(rgb, w, it) / ImageQuality.mean(rgb, w, it) }

    /** 20 Bilder wie im Serienmodus; freihand bis 10 Pixel Versatz. */
    private fun series(seed: Long, shake: Int, passer: Boolean = false): List<ByteArray> {
        val lab = Lab(seed); val r = Random(seed + 1)
        return List(20) { k ->
            val dx = if (k == 0 || shake == 0) 0 else r.nextInt(-shake, shake + 1); val dy = if (k == 0 || shake == 0) 0 else r.nextInt(-shake, shake + 1)
            capture(blockTruth(if (passer && k in 4..16) 10 + (k - 4) * 12 else null), dx, dy, lab)
        }
    }

    @Test fun `Testlabor Langzeit freihand wird ausgerichtet und bleibt scharf`() {
        // S-004 K2: wie GlProcessingAdapter, StackMode.MEAN; ohne Ausrichtung verschmiert der Mittelwert das Muster
        fun mean(f: List<ByteArray>) = NightTone.softGammaMeanAndBrighten(f, w * h, w).rgb
        val calm = texture(mean(series(51, 0)))
        val plain = texture(mean(series(51, 10)))
        val shaky = series(51, 10).also { FrameAlignment.alignInPlace(it, w, h) }
        val aligned = texture(mean(shaky))
        report("70-langzeit", "\n## Langzeit freihand (20 Bilder, bis 10 Pixel)\n\nStruktur ausgerichtet ${"%.3f".format(aligned)}, ohne Ausrichtung ${"%.3f".format(plain)}, Stativ ${"%.3f".format(calm)}\n")
        assertTrue(aligned >= 0.9 * calm, "Langzeit freihand: Struktur $aligned gegen $calm auf dem Stativ")
        assertTrue(plain < 0.7 * calm, "Pruefe die Pruefung: ohne Ausrichtung muss die Szene verschmieren ($plain gegen $calm)")
    }

    @Test fun `Testlabor Menschen wegrechnen freihand entfernt den Passanten und bleibt scharf`() {
        // S-004 K3: wie GlProcessingAdapter, StackMode.MEDIAN (Median der Helligkeit, dann Aufhellen)
        fun median(f: List<ByteArray>) = ByteArray(w * h * 3).also { Stacking.lumaMedianRange(f, it, 0, w * h) }.let { NightTone.brightenBytes(it, w).rgb }
        val clean = median(series(61, 0))
        val shaky = series(61, 10, passer = true).also { FrameAlignment.alignInPlace(it, w, h) }
        val out = median(shaky)
        val path = Rect(20, 62, 180, 98)
        val ghost = ImageQuality.mean(out, w, path) - ImageQuality.mean(clean, w, path)
        val t = texture(out); val t0 = texture(clean)
        report("71-wegrechnen", "\n## Menschen wegrechnen freihand (20 Bilder, bis 10 Pixel)\n\nGeist ${"%.2f".format(ghost)} Stufen, Struktur ${"%.3f".format(t)} gegen ${"%.3f".format(t0)} auf dem Stativ\n")
        assertTrue(kotlin.math.abs(ghost) < 3.0, "Passant bleibt sichtbar: $ghost Stufen")
        assertTrue(t >= 0.9 * t0, "Wegrechnen freihand verschmiert: $t gegen $t0")
    }

    @Test fun `Testlabor bewegtes Objekt auf einer Kachelgrenze hinterlaesst weder Doppelbild noch Blockkante`() {
        val lab = Lab(31)
        val clean = truth(0.0006)
        // Passant laeuft genau ueber die Kachelgrenzen bei x = 64, 80, 96 (Vielfache von 8 und 16)
        val frames = List(36) { k -> capture(if (k in 6..20) truth(0.0006, 50 + (k - 6) * 4) else clean, 0, 0, lab) }
        val rgb = core(frames)
        val reference = core(Lab(31).let { l -> List(36) { capture(clean, 0, 0, l) } })
        val path = Rect(50, 72, 110, 98)
        val ghost = ImageQuality.meanAbsDiff(rgb, reference, w, path)
        // Blockkante: Sprung der Helligkeit zwischen den Spalten links und rechts einer Kachelgrenze, gemittelt
        fun seam(x: Int) = kotlin.math.abs(ImageQuality.mean(rgb, w, Rect(x - 2, 72, x, 98)) - ImageQuality.mean(rgb, w, Rect(x, 72, x + 2, 98)))
        val seams = listOf(64, 80, 96).map { seam(it) }
        report("61-kachelgrenze", "\n## Passant auf Kachelgrenzen\n\nGeist ${"%.2f".format(ghost)} Stufen, Kanten ${seams.joinToString { "%.2f".format(it) }}\n")
        assertTrue(ghost < 3.0, "Doppelbild: $ghost Stufen")
        assertTrue(seams.all { it < 2.0 }, "Blockkante an Kachelgrenze: $seams")
    }

    @Test fun `Testlabor Szenen mit Restlicht gelten nicht als lichtlos`() {
        val shares = listOf(0.0006 to 36, 0.00045 to 23, 0.004 to 36).map { (level, count) ->
            val s = scenario("x", level, shake = 10, passer = false, count = count)
            level to NightMerge(w, h).apply { s.frames.forEach { add(it) } }.floorShare
        }
        report("31-restlicht", "\n## Restlicht (Anteil Boden, lichtlos ab ${NightTone.FLOOR_SHARE})\n\n" +
            shares.joinToString { (level, share) -> "Licht $level: ${"%.2f".format(share)}" } + "\n")
        for ((level, share) in shares) assertTrue(share < NightTone.FLOOR_SHARE, "Restlicht $level als lichtlos erkannt: $share")
    }

    /**
     * S-001 K7: jeder Test schreibt seinen eigenen Teil, der Gesamtbericht wird jedes Mal aus allen Teilen neu
     * zusammengesetzt. So ist er vollstaendig, egal in welcher Reihenfolge die Tests laufen.
     */
    companion object {
        // Teile eines frueheren Laufs entfernen (einmal je Testlauf), damit kein alter Wert im Bericht steht
        init { File("build/quality-report-parts").deleteRecursively() }
    }

    private fun report(part: String, text: String) {
        val dir = File("build/quality-report-parts").apply { mkdirs() }
        File(dir, "$part.md").writeText(text)
        File("build/quality-report.md").writeText(dir.listFiles().orEmpty().sortedBy { it.name }.joinToString("") { it.readText() })
        println(text)
    }

    private fun writeReport(rows: List<Row>) {
        val sb = StringBuilder("# Testlabor Bildqualitaet\n\n")
        sb.append("Relatives Rauschen (kleiner ist besser), Kantenbreite in Pixeln (kleiner ist schaerfer), ")
        sb.append("Geisterbild in Helligkeitsstufen (nahe 0 ist gut), Helligkeit des dunklen Hintergrunds (0 bis 255).\n\n")
        sb.append("Angepasste Kante: weiche Stufe ans Profil angepasst, stabil auch bei starkem Rauschen (ideal 0,2).\n\n")
        sb.append("| Szene | Verfahren | Rauschen | Kante | angepasste Kante | Geist | Helligkeit |\n|---|---|---|---|---|---|---|\n")
        rows.forEach { r ->
            sb.append("| ${r.scenario} | ${r.method} | ${"%.3f".format(r.relNoise)} | ${"%.1f".format(r.edge)} | ${"%.2f".format(r.edgeFit)} | ${r.ghost?.let { "%.1f".format(it) } ?: ""} | ${"%.1f".format(r.bright)} |\n")
        }
        report("10-szenen", sb.toString())
    }
}
