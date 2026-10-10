package app.cayresim.core.pure

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NightPlanTest {
    private val s24Max = 100_000_000L // gemessen: 1/10 s

    @Test fun `Guter Fall dunkle Szene nutzt die volle Zeit und passende ISO`() {
        // Automatik: 1/15 s bei ISO 3200 (am Anschlag)
        val e = NightPlan.plan(66_666_666, 3200, s24Max, 25, 3200)
        assertEquals(100_000_000L, e.exposureNs)
        assertEquals(3200, e.iso) // 1,5-fach waere mehr als 3200: begrenzt
    }

    @Test fun `Guter Fall maessig dunkel ergibt mittlere ISO`() {
        val e = NightPlan.plan(33_333_333, 800, s24Max, 25, 3200)
        assertEquals(100_000_000L, e.exposureNs)
        assertEquals(400, e.iso) // 1/30*800*1,5 = 1/10 s * 400
    }

    @Test fun `Randfall helle Szene kuerzt die Zeit statt ISO unter das Minimum zu druecken`() {
        val e = NightPlan.plan(1_000_000, 25, s24Max, 25, 3200)
        assertEquals(25, e.iso)
        assertEquals(1_500_000L, e.exposureNs)
    }

    @Test fun `Randfall Geraet erlaubt mehr als 1 durch 10 s, Serie bleibt freihand bei 1 durch 10 s`() =
        assertEquals(100_000_000L, NightPlan.plan(66_666_666, 3200, 500_000_000, 25, 3200).exposureNs)

    @Test fun `Nachttest S24+ Automatik am Anschlag nimmt die volle ISO`() {
        // Gemessen am 8. Oktober: Automatik etwa 1/15 s bei ISO 1279, der alte Plan ergab nur ISO 1919
        val e = NightPlan.plan(66_666_666, 1279, s24Max, 25, 3200)
        assertEquals(100_000_000L, e.exposureNs); assertEquals(3200, e.iso)
    }

    @Test fun `Nachttest S24+ zweiter Versuch Automatik mit voller ISO bei 1 durch 25 s`() {
        // Hinweis am Geraet zweimal "1/10 s, ISO 1919": Automatik stand bei knapp 1/25 s und ISO 3200
        val e = NightPlan.plan(39_990_000, 3200, s24Max, 25, 3200)
        assertEquals(100_000_000L, e.exposureNs); assertEquals(3200, e.iso)
    }

    @Test fun `Nachttest S24+ beleuchteter Raum wird hoechstens 4-mal so hell wie die Automatik`() {
        // S-002 K1: 9. Oktober, Jeans im Wohnzimmer, Aufhellung x1,0 bei ISO 3200; der alte Plan ergab hier ISO 3200 (10-fach)
        val e = NightPlan.plan(50_000_000, 640, s24Max, 25, 3200)
        assertEquals(100_000_000L, e.exposureNs); assertEquals(1280, e.iso)
        assertTrue(NightPlan.level(e.exposureNs, e.iso) <= NightPlan.MAX_BOOST * NightPlan.level(50_000_000, 640) + 1e-9)
    }

    @Test fun `Randfall knapp unter dem Anschlag bleibt beim 1,5-fachen`() =
        assertEquals(1200, NightPlan.plan(40_000_000, 2000, s24Max, 25, 3200).iso)

    @Test fun `Dunkel oder nicht`() {
        assertEquals(true, NightPlan.isDark(66_666_666, 3200))
        assertEquals(false, NightPlan.isDark(10_000_000, 100))
        assertEquals(null, NightPlan.isDark(null, 100))
        assertEquals(null, NightPlan.isDark(0, 100))
    }

    @Test fun `Fehlerfall unsinnige Messwerte`() {
        assertFailsWith<IllegalArgumentException> { NightPlan.plan(0, 100, s24Max, 25, 3200) }
        assertFailsWith<IllegalArgumentException> { NightPlan.plan(1, 100, s24Max, 3200, 25) }
    }
}

class NightMergeTest {
    private val w = 128; private val h = 96

    /** Dunkle Szene mit Kanten (damit Ausrichtung und Schaerfe messbar sind), verschoben um dx, dy, mit Rauschen. */
    private fun scene(dx: Int, dy: Int, rng: Rng, noise: Int = 3, blobAt: Int? = null): ByteArray = ByteArray(w * h * 3) { i ->
        val p = i / 3; val x = p % w + dx; val y = p / w + dy
        var v = if (((x / 16) + (y / 16)) % 2 == 0) 4 else 14
        if (blobAt != null && p % w in blobAt until blobAt + 24 && p / w in 30 until 60) v = 120 // vorbeilaufendes Objekt
        (v + rng.nextInt(2 * noise + 1) - noise).coerceIn(0, 255).toByte()
    }

    private fun sd(b: ByteArray, x0: Int, y0: Int, size: Int): Double {
        val v = (y0 until y0 + size).flatMap { y -> (x0 until x0 + size).map { x -> b[(y * w + x) * 3 + 1].toInt() and 0xFF } }
        val m = v.average(); return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }

    @Test fun `Guter Fall viele Bilder senken das Rauschen und hellen auf`() {
        val rng = SeededRng(4711)
        val single = NightMerge(w, h).apply { add(scene(0, 0, rng)) }.finish()
        val m = NightMerge(w, h)
        repeat(24) { m.add(scene(0, 0, rng)) }
        val r = m.finish()
        assertEquals(24, m.used)
        assertTrue(r.gain > 1.5f, "Verstaerkung ${r.gain}")
        // innerhalb einer gleichmaessigen Flaeche: Rauschen deutlich kleiner als beim Einzelbild (gleiche Verstaerkung)
        assertTrue(sd(r.rgb, 2, 2, 12) < sd(single.rgb, 2, 2, 12) / 2.5, "Rauschen ${sd(single.rgb, 2, 2, 12)} -> ${sd(r.rgb, 2, 2, 12)}")
    }

    @Test fun `Guter Fall verwackelte Serie wird ausgerichtet, Kanten bleiben scharf`() {
        val rng = SeededRng(9)
        val shifts = listOf(0 to 0, 4 to 0, -8 to 4, 12 to -4, 0 to 8, -4 to -4, 8 to 8, -12 to 0)
        val m = NightMerge(w, h)
        shifts.forEach { (dx, dy) -> m.add(scene(dx, dy, rng)) }
        val r = m.finish()
        // Kante zwischen zwei Feldern bei x = 16: links dunkel, rechts hell, auch nach dem Mitteln
        val left = r.rgb[(40 * w + 12) * 3].toInt() and 0xFF; val right = r.rgb[(40 * w + 20) * 3].toInt() and 0xFF
        assertTrue(abs(left - right) > 15, "Kante verwischt: $left / $right")
    }

    @Test fun `Fehlerfall bewegtes Objekt hinterlaesst keinen Geist`() {
        val rng = SeededRng(5)
        val m = NightMerge(w, h)
        m.add(scene(0, 0, rng)) // Referenz ohne Objekt
        listOf(10, 40, 70, 100).forEach { m.add(scene(0, 0, rng, blobAt = it)) }
        repeat(6) { m.add(scene(0, 0, rng)) }
        val r = m.finish()
        val ghost = r.rgb[(45 * w + 50) * 3].toInt() and 0xFF
        val clean = NightMerge(w, h).apply { repeat(11) { add(scene(0, 0, SeededRng(5L + it))) } }.finish().rgb[(45 * w + 50) * 3].toInt() and 0xFF
        assertTrue(abs(ghost - clean) < 25, "Geist: $ghost statt etwa $clean")
    }

    @Test fun `Randfall starkes Wackeln wertet keine ungesehenen Kacheln ab`() {
        // S-001 K4 (Schutz gegen Rueckschritt, kein Rot-Nachweis): ruhige Szene, jedes zweite Bild um 12 Pixel verschoben;
        // nie gesehene Randkacheln duerfen den Vergleichswert nicht senken, die ruhige Mitte behaelt volles Gewicht.
        val rng = SeededRng(8)
        val m = NightMerge(w, h)
        repeat(20) { k -> m.add(scene(if (k % 2 == 0) 0 else 12, 0, rng)) }
        // Bildmitte: alle 20 Bilder sehen sie, keine Bewegung, also volles Gewicht
        assertTrue(m.weightIn(40, 30, 80, 60) >= 19.0f, "Mitte wurde abgewertet: ${m.weightIn(40, 30, 80, 60)}")
    }

    @Test fun `Fehlerfall verwackeltes Bild wird verworfen`() {
        val rng = SeededRng(2)
        val m = NightMerge(w, h)
        m.add(scene(0, 0, rng))
        val blurred = ByteArray(w * h * 3) { 9 } // keine Kanten
        assertFalse(m.add(blurred))
        assertEquals(1, m.dropped); assertEquals(1, m.used)
    }

    @Test fun `S-003 verwackeltes erstes Bild wird nicht zum Bezug`() {
        // erstes Bild ohne Kanten (verwackelt), danach scharfe: der Bezug wechselt, das erste Bild wird verworfen
        val rng = SeededRng(3)
        val m = NightMerge(w, h)
        m.add(ByteArray(w * h * 3) { 9 })
        repeat(5) { m.add(scene(0, 0, rng)) }
        assertEquals(1, m.dropped, "verwackeltes erstes Bild"); assertEquals(5, m.used)
    }

    @Test fun `S-003 Randfall Bezug wechselt nur in den ersten Bildern`() {
        val rng = SeededRng(4)
        val m = NightMerge(w, h)
        m.add(ByteArray(w * h * 3) { 9 }); m.add(ByteArray(w * h * 3) { 9 }); m.add(ByteArray(w * h * 3) { 9 })
        m.add(scene(0, 0, rng)) // viertes Bild: kein Wechsel mehr, Bezug bleibt das erste
        assertEquals(0, m.dropped); assertEquals(4, m.used)
    }

    /** Unregelmaessige Bloecke von 3 Pixeln (Zweitpruefung S-003: das Schachbrett ist fuer die Ausrichtung mehrdeutig). */
    private val blocks = kotlin.random.Random(17).let { r -> BooleanArray((h / 3 + 20) * (w / 3 + 20)) { r.nextBoolean() } }

    private fun blockScene(dx: Int, dy: Int, rng: Rng): ByteArray = ByteArray(w * h * 3) { i ->
        val p = i / 3; val x = p % w + dx + 24; val y = p / w + dy + 24
        val v = if (blocks[(y / 3) * (w / 3 + 20) + x / 3]) 30 else 4
        (v + rng.nextInt(7) - 3).coerceIn(0, 255).toByte()
    }

    @Test fun `S-003 Wackeln wird gemessen`() {
        // Python-Modell mit derselben Ausrichtung: Versatz exakt (4, 0), (8, 4), (12, 4), (0, 8)
        val rng = SeededRng(9)
        val m = NightMerge(w, h)
        listOf(0 to 0, 4 to 0, -8 to 4, 12 to -4, 0 to 8).forEach { (dx, dy) -> m.add(blockScene(dx, dy, rng)) }
        assertEquals(12, m.maxShake, "groesster Versatz zum Bezug in Pixeln")
        assertEquals(0, NightMerge(w, h).apply { repeat(3) { add(blockScene(0, 0, rng)) } }.maxShake)
    }

    @Test fun `S-006 Wackeln ist bei reinem Rauschen nicht messbar, bei Struktur schon`() {
        // Nachttest S24+ 10.10.: "Wackeln bis 0 px" bei fast reinem Rauschen war keine Messung
        val rng = SeededRng(12)
        // groesseres Bild wie auf dem Geraet: bei 128 x 96 Pixeln faellt das beste von 49 Rauschmustern zufaellig 3 % besser aus
        val bw = 640; val bh = 480
        val noise = NightMerge(bw, bh).apply { repeat(8) { add(ByteArray(bw * bh * 3) { (rng.nextInt(7)).toByte() }) } }
        assertFalse(noise.shakeMeasurable, "reines Rauschen")
        val shaky = NightMerge(w, h).apply { listOf(0 to 0, 4 to 0, -8 to 4, 12 to -4, 0 to 8).forEach { (dx, dy) -> add(blockScene(dx, dy, rng)) } }
        assertTrue(shaky.shakeMeasurable); assertEquals(12, shaky.maxShake)
    }

    @Test fun `S-006 Boden-Modus erkennt Dunkelheit auch bei Rauschen ueber Stufe 1`() {
        // vorher nur ueber den Anteil der Pixel auf Stufe 0 oder 1; bei ISO 3200 rauschen sie bis Stufe 5
        val rng = SeededRng(13)
        // Rauschen um 0 (Summe dreier Gleichverteilungen, fast normalverteilt, Streuung etwa 2,4 Stufen), bei 0 abgeschnitten
        fun frame() = ByteArray(w * h * 3) { (rng.nextInt(5) + rng.nextInt(5) + rng.nextInt(5) - 6).coerceAtLeast(0).toByte() }
        val m = NightMerge(w, h).apply { repeat(36) { add(frame()) } }
        val r = m.finish()
        assertTrue(m.floorShare < NightTone.FLOOR_SHARE, "Anteil 0/1 allein haette nicht gereicht: ${m.floorShare}")
        assertTrue(m.noiseFloor, "lichtlos nicht erkannt")
        // vorher Nebel auf Stufe 39 (Median-Ziel); jetzt bleibt reines Rauschen dunkles Korn (gemessen 12,9)
        assertTrue(r.rgb.map { it.toInt() and 0xFF }.average() <= 20.0, "Rauschen zu Nebel aufgehellt: ${r.rgb.map { it.toInt() and 0xFF }.average()}")
    }

    @Test fun `S-003 Fehlerfall vorbeilaufendes helles Objekt wird nicht zum Bezug`() {
        // Zweitpruefung: mit dem Mittelwert der Kantenenergie war das Bild mit Objekt 4-mal "schaerfer" und wurde Bezug
        val rng = SeededRng(5)
        val m = NightMerge(w, h)
        m.add(scene(0, 0, rng)); m.add(scene(0, 0, rng, blobAt = 40)); repeat(6) { m.add(scene(0, 0, rng)) }
        assertEquals(0, m.dropped, "klare Bilder bleiben"); assertEquals(8, m.used)
    }

    @Test fun `Aufhellung waechst mit der Bildzahl`() {
        assertEquals(16f, NightTone.maxGainFor(1)); assertEquals(16f, NightTone.maxGainFor(0))
        assertEquals(64f, NightTone.maxGainFor(36)); assertEquals(64f, NightTone.maxGainFor(100))
        assertTrue(NightTone.maxGainFor(23) in 50f..52f, "${NightTone.maxGainFor(23)}")
        // Fast schwarze Serie: ein Einzelbild bleibt bei 16, viele Bilder duerfen weiter aufhellen
        val dark = ByteArray(w * h * 3) { 2 }
        val one = NightMerge(w, h).apply { add(dark) }.finish()
        val many = NightMerge(w, h).apply { repeat(36) { add(dark) } }.finish()
        assertEquals(16f, one.gain); assertTrue(many.gain > 16f, "Verstaerkung ${many.gain}")
    }

    @Test fun `Randfall nur ein Bild ergibt ein gueltiges Ergebnis`() {
        val r = NightMerge(w, h).apply { add(scene(0, 0, SeededRng(1))) }.finish()
        assertEquals(w * h * 3, r.rgb.size)
    }

    @Test fun `Fehlerfall falsche Groessen und leer`() {
        assertFailsWith<IllegalArgumentException> { NightMerge(10, 10) }
        assertFailsWith<IllegalArgumentException> { NightMerge(w, h).add(ByteArray(3)) }
        assertFailsWith<IllegalStateException> { NightMerge(w, h).finish() }
    }
}
