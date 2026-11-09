package app.cayresim.core.pure

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S-018: Schallbild je Frequenzband. Alle Signale sind kuenstlich (CLAUDE.md Abschnitt 7). Eine Quelle ist Rauschen
 * mit genauer Laufzeit zwischen den Kanaelen: im Spektrum auf das Band begrenzt und fuer Kanal 0 mit der Phase der
 * Laufzeit gedreht (Kanal 0 = s(t - tau), Kanal 1 = s(t); positiv heisst wie in [AudioMath], Kanal 0 hoert spaeter).
 */
class SoundBandsTest {
    private val sr = 48_000
    private val halfSpan = 0.15 / AudioMath.SPEED_OF_SOUND
    /** 2,73 s */
    private val n = 1 shl 17

    private fun gauss(rnd: Random): Double {
        val u = rnd.nextDouble().coerceAtLeast(1e-12); val v = rnd.nextDouble()
        return sqrt(-2 * ln(u)) * cos(2 * PI * v)
    }

    private fun rms(x: DoubleArray) = sqrt(x.sumOf { it * it } / x.size)

    /** Bandbegrenztes Rauschen aus der Richtung mit Kosinus [cosine] zur Mikrofonachse; liefert (Kanal 0, Kanal 1). */
    private fun source(rnd: Random, lowHz: Double, highHz: Double, cosine: Double, level: Double): Pair<DoubleArray, DoubleArray> {
        val tau = cosine * halfSpan
        val re = DoubleArray(n) { gauss(rnd) }; val im = DoubleArray(n)
        AudioMath.fft(re, im, false)
        val ar = DoubleArray(n); val ai = DoubleArray(n)
        for (k in 0 until n) {
            val f = (if (k <= n / 2) k else k - n) * sr.toDouble() / n
            if (abs(f) < lowHz || abs(f) >= highHz) { re[k] = 0.0; im[k] = 0.0; continue }
            val c = cos(-2 * PI * f * tau); val s = sin(-2 * PI * f * tau)
            ar[k] = re[k] * c - im[k] * s; ai[k] = re[k] * s + im[k] * c
        }
        AudioMath.fft(re, im, true); AudioMath.fft(ar, ai, true)
        val g = level / rms(re)
        return DoubleArray(n) { ar[it] * g } to DoubleArray(n) { re[it] * g }
    }

    private fun noise(rnd: Random, level: Double) = DoubleArray(n) { gauss(rnd) * level } to DoubleArray(n) { gauss(rnd) * level }

    private fun mix(vararg parts: Pair<DoubleArray, DoubleArray>): Pair<DoubleArray, DoubleArray> {
        val a = DoubleArray(n); val b = DoubleArray(n)
        for (p in parts) for (i in 0 until n) { a[i] += p.first[i]; b[i] += p.second[i] }
        return a to b
    }

    private fun pcm(s: Pair<DoubleArray, DoubleArray>, from: Int = 0, to: Int = n) = ShortArray((to - from) * 2) { k ->
        val x = if (k % 2 == 0) s.first[from + k / 2] else s.second[from + k / 2]
        (x * 32767).roundToInt().coerceIn(-32768, 32767).toShort()
    }

    /** Wie auf dem Geraet: Stuecke von 100 ms einrechnen. */
    private fun feed(a: SoundBands.Analyzer, s: Pair<DoubleArray, DoubleArray>, seconds: Double = n.toDouble() / sr) {
        val chunk = sr / 10
        var at = 0; val end = minOf(n, (seconds * sr).toInt())
        while (at + chunk <= end) { a.add(pcm(s, at, at + chunk), 2); at += chunk }
    }

    private fun analyzer() = SoundBands.Analyzer(sr, halfSpan)
    private fun deg(cosine: Double?) = SoundBands.angleDegrees(assertNotNull(cosine))
    private fun cosOf(degrees: Double) = sin(degrees * PI / 180)

    @Test fun `S-018 Rauschen aus 30 Grad steht in allen Baendern`() {
        val rnd = Random(18)
        val a = analyzer()
        feed(a, mix(source(rnd, 200.0, 6400.0, cosOf(30.0), 0.03), noise(rnd, 0.0095)), 2.0)
        val s = a.snapshot()
        assertEquals(5, s.bands.size)
        for (b in s.bands) {
            assertTrue(b.valid, "Band ${b.lowHz} ungueltig: Anteil ${b.share}")
            assertEquals(30.0, deg(b.cosine), 6.0, "Band ${b.lowHz}")
            assertTrue(b.share >= 0.8, "Band ${b.lowHz} Anteil ${b.share}")
            assertEquals(SoundBands.GRID, b.response.size)
        }
        assertEquals(30.0, deg(s.mainCosine), 3.0, "Hauptrichtung")
        assertEquals(listOf(200, 400, 800, 1600, 3200), s.bands.map { it.lowHz })
        assertEquals(6400, s.bands.last().highHz)
    }

    @Test fun `S-018 zwei Quellen stehen je Band an ihrer Stelle`() {
        val rnd = Random(19)
        val a = analyzer()
        feed(a, mix(source(rnd, 200.0, 800.0, cosOf(-40.0), 0.03), source(rnd, 1600.0, 6400.0, cosOf(35.0), 0.03), noise(rnd, 0.003)), 2.0)
        val s = a.snapshot()
        for (i in 0..1) assertEquals(-40.0, deg(s.bands[i].cosine), 8.0, "Band ${s.bands[i].lowHz}")
        for (i in 3..4) assertEquals(35.0, deg(s.bands[i].cosine), 8.0, "Band ${s.bands[i].lowHz}")
        // das Band dazwischen hat nur Stoerrauschen und den Rand der Nachbarn (Fenster): deutlich leiser
        println("S-018 K2 Pegel je Band: ${s.bands.joinToString { "%.1f".format(it.levelDbfs) }}")
        assertTrue(s.bands[2].levelDbfs <= minOf(s.bands[1].levelDbfs, s.bands[3].levelDbfs) - 15.0, "Band 800: ${s.bands.map { it.levelDbfs }}")
    }

    @Test fun `S-018 Fehlerfall unabhaengiges Rauschen zeigt nichts`() {
        val rnd = Random(20)
        var shots = 0
        val worst = DoubleArray(5)
        // 8 Neustarts: gerade die ersten Momentaufnahmen nach dem Start mitteln ueber wenige Rahmen
        repeat(8) {
            val a = analyzer()
            repeat(3) {
                val s = noise(rnd, 0.03)
                var at = 0; val chunk = sr / 10
                while (at + chunk <= n) {
                    a.add(pcm(s, at, at + chunk), 2); at += chunk
                    val snap = a.snapshot(); shots++
                    snap.bands.forEachIndexed { i, b ->
                        worst[i] = maxOf(worst[i], b.share)
                        assertFalse(b.valid, "Band ${b.lowHz} gueltig bei reinem Rauschen (Anteil ${b.share}, Aufnahme $shots)")
                    }
                    assertNull(snap.mainCosine)
                }
            }
        }
        println("S-018 K3: $shots Momentaufnahmen, groesster Anteil je Band bei unabhaengigem Rauschen ${worst.joinToString { "%.3f".format(it) }} (Grenze ${SoundBands.MIN_SHARE})")
        assertTrue(shots >= 600, "nur $shots Momentaufnahmen")
    }

    @Test fun `S-018 reiner Ton steht im richtigen Band`() {
        val f = 2500.0; val tau = cosOf(20.0) * halfSpan
        val tone = DoubleArray(n) { 0.05 * sin(2 * PI * f * (it.toDouble() / sr - tau)) } to DoubleArray(n) { 0.05 * sin(2 * PI * f * it / sr) }
        val a = analyzer()
        feed(a, mix(tone, noise(Random(21), 0.0005)), 2.0)
        val s = a.snapshot()
        val band = s.bands[3]
        assertTrue(band.valid, "Band 1600 ungueltig: ${band.share}")
        // ein Ton passt zu mehreren Laufzeiten: eines der Maxima der Kurve muss die Wahrheit treffen
        val maxima = (1 until SoundBands.GRID - 1).filter { i ->
            band.response[i] >= band.response[i - 1] && band.response[i] >= band.response[i + 1] && band.response[i] >= 0.6f * band.share
        }.map { SoundBands.angleDegrees(SoundBands.gridCosine(it)) }
        assertTrue(maxima.any { abs(it - 20.0) <= 5.0 }, "Maxima $maxima")
        for (i in listOf(0, 1, 2, 4)) assertFalse(s.bands[i].valid, "Band ${s.bands[i].lowHz} gueltig: ${s.bands[i].share}")
    }

    @Test fun `S-018 Randfall Stille`() {
        val a = analyzer()
        val empty = a.snapshot()
        assertTrue(empty.bands.none { it.valid }); assertNull(empty.mainCosine)
        a.add(ShortArray(sr * 2), 2)
        val silent = a.snapshot()
        for (b in silent.bands) {
            assertFalse(b.valid); assertFalse(b.share.isNaN()); assertFalse(b.levelDbfs.isNaN())
            assertTrue(b.response.none { it.isNaN() })
        }
        // sehr leise, aber gerichtet: unter der Pegelgrenze kein Streifen
        val quiet = analyzer()
        feed(quiet, source(Random(22), 200.0, 6400.0, 0.5, 0.00005), 2.0)
        assertTrue(quiet.snapshot().bands.none { it.valid }, "Pegel ${quiet.snapshot().bands.map { it.levelDbfs }}")
        // Mono und leere Stuecke werden ignoriert
        quiet.add(ShortArray(100), 1); quiet.add(ShortArray(0), 2)
    }

    @Test fun `S-018 Pegel je Band`() {
        val tone = DoubleArray(n) { 0.5 * sin(2 * PI * 1000.0 * it / sr) }
        val a = analyzer()
        feed(a, tone to tone, 2.0)
        // halbe Vollaussteuerung: Effektivwert 0,354 = -9,0 dBFS
        assertEquals(-9.0, a.snapshot().bands[2].levelDbfs, 0.5)
        assertEquals(0f, SoundBands.brightness(-80.0)); assertEquals(1f, SoundBands.brightness(-30.0))
        assertEquals(0.5f, SoundBands.brightness(-57.5), 0.01f)
    }

    @Test fun `S-018 folgt einem Sprung der Quelle`() {
        val rnd = Random(23)
        val a = analyzer()
        feed(a, mix(source(rnd, 200.0, 6400.0, cosOf(-40.0), 0.03), noise(rnd, 0.003)))
        assertEquals(-40.0, deg(a.snapshot().mainCosine), 5.0)
        feed(a, mix(source(rnd, 200.0, 6400.0, cosOf(40.0), 0.03), noise(rnd, 0.003)), 1.5)
        assertEquals(40.0, deg(a.snapshot().mainCosine), 5.0)
    }

    @Test fun `S-018 Rechnung haelt das Zeitbudget`() {
        val rnd = Random(24)
        val s = mix(source(rnd, 200.0, 6400.0, 0.3, 0.03), noise(rnd, 0.003))
        val a = analyzer()
        feed(a, s, 1.0) // Aufwaermen
        val chunk = pcm(s, sr, sr + sr / 10)
        val runs = (0 until 5).map { val t0 = System.nanoTime(); a.add(chunk, 2); a.snapshot(); (System.nanoTime() - t0) / 1e6 }
        println("S-018 K8: 100 ms Ton und Momentaufnahme in ${"%.2f".format(runs.min())} ms (5 Laeufe: ${runs.joinToString { "%.2f".format(it) }})")
        assertTrue(runs.min() <= 5.0, "zu langsam: ${runs.min()} ms")
    }

    @Test fun `S-018 Hall zieht nur tiefe Baender zur Mitte`() {
        val rnd = Random(25)
        // Raumschall: 32 unabhaengige Wellen aus zufaelligen Richtungen (Kosinus zur Achse gleichverteilt), zusammen so laut wie die Quelle
        val waves = Array(32) { source(rnd, 200.0, 6400.0, rnd.nextDouble(-1.0, 1.0), 0.03 / sqrt(32.0)) }
        val a = analyzer()
        feed(a, mix(source(rnd, 200.0, 6400.0, cosOf(40.0), 0.03), *waves, noise(rnd, 0.001)), 2.5)
        val s = a.snapshot()
        println("S-018 K9 Hall gleich laut, Quelle 40 Grad: " + s.bands.joinToString { b ->
            "${b.lowHz} Hz: ${b.cosine?.let { "%.0f".format(SoundBands.angleDegrees(it)) } ?: "kein"} Grad, Anteil ${"%.2f".format(b.share)}"
        } + "; Hauptrichtung ${s.mainCosine?.let { "%.0f".format(SoundBands.angleDegrees(it)) }}")
        val high = s.bands.drop(2).filter { it.valid }
        assertTrue(high.size >= 2, "zu wenige hohe Baender gueltig: ${s.bands.map { it.share }}")
        for (b in high) assertEquals(40.0, deg(b.cosine), 10.0, "Band ${b.lowHz}")
        // feste Grenze gegen die Wahrheit auch fuer die Hauptrichtung
        assertEquals(40.0, deg(s.mainCosine), 12.0, "Hauptrichtung im Hall")
    }

    @Test fun `S-018 Haltung aus der Schwerkraft`() {
        assertEquals(SoundBands.Hold.TOP_LEFT, SoundBands.hold(9.81, 0.0, 0.0))
        assertEquals(SoundBands.Hold.TOP_RIGHT, SoundBands.hold(-9.81, 0.0, 0.0))
        // quer, Bildschirm 45 Grad nach hinten geneigt
        assertEquals(SoundBands.Hold.TOP_LEFT, SoundBands.hold(6.9, 0.0, 6.9))
        assertEquals(SoundBands.Hold.NOT_LANDSCAPE, SoundBands.hold(0.0, 9.81, 0.0))
        assertEquals(SoundBands.Hold.NOT_LANDSCAPE, SoundBands.hold(0.0, 0.0, 9.81))
        assertEquals(SoundBands.Hold.NOT_LANDSCAPE, SoundBands.hold(0.0, 0.0, 0.0))
        assertEquals(SoundBands.Hold.NOT_LANDSCAPE, SoundBands.hold(Double.NaN, 0.0, 0.0))
        // Oberkante links: die Unterkante mit dem unteren Mikrofon zeigt nach rechts
        assertEquals(1, SoundBands.screenSign(SoundBands.Hold.TOP_LEFT)); assertEquals(-1, SoundBands.screenSign(SoundBands.Hold.TOP_RIGHT))
        assertEquals(0, SoundBands.screenSign(SoundBands.Hold.NOT_LANDSCAPE))
        assertEquals(30.0, SoundBands.angleDegrees(0.5), 1e-9); assertEquals(90.0, SoundBands.angleDegrees(1.2), 1e-9)
        assertEquals(-1.0, SoundBands.gridCosine(0), 1e-12); assertEquals(1.0, SoundBands.gridCosine(SoundBands.GRID - 1), 1e-12)
    }
}
