package app.cayresim.core.pure

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
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
 * S-008: Tonrechnung fuer den Mikrofon-Test. Alle Signale sind kuenstlich (CLAUDE.md Abschnitt 7).
 * Bruchteil-Verzoegerungen entstehen durch 16-fache Ueberabtastung: Rauschen bei 768 kHz, um eine ganze Zahl
 * feiner Schritte verschoben, dann ueber 16 Werte gemittelt und jeder 16. Wert genommen.
 */
class AudioMathTest {
    private val sr = 48_000
    private val spacing = 0.15
    private val over = 16

    private fun gauss(rnd: Random): Double {
        val u = rnd.nextDouble().coerceAtLeast(1e-12); val v = rnd.nextDouble()
        return sqrt(-2 * ln(u)) * cos(2 * PI * v)
    }

    /** Klatscher als abklingender Rauschimpuls (10 ms), fein abgetastet. */
    private fun fineClap(rnd: Random, ms: Double = 10.0): DoubleArray {
        val n = (ms / 1000 * sr * over).toInt()
        return DoubleArray(n) { i -> gauss(rnd) * kotlin.math.exp(-i / (n / 4.0)) }
    }

    /** Legt den feinen Klatscher ab Abtastwert [at] in [out], um [shiftFine] feine Schritte verzoegert. */
    private fun place(out: FloatArray, at: Int, fine: DoubleArray, shiftFine: Int, gain: Double) {
        val len = fine.size / over + 2
        for (i in 0 until len) {
            var s = 0.0
            for (j in 0 until over) {
                val k = i * over + j - shiftFine
                if (k in fine.indices) s += fine[k]
            }
            val idx = at + i
            if (idx in out.indices) out[idx] += (s / over * gain).toFloat()
        }
    }

    private fun addNoise(x: FloatArray, rnd: Random, rms: Double) { for (i in x.indices) x[i] += (gauss(rnd) * rms).toFloat() }

    @Test fun `S-008 GCC-PHAT findet die Richtung synthetischer Klatscher`() {
        val rnd = Random(8)
        for (deg in listOf(-60.0, -30.0, 0.0, 30.0, 60.0)) {
            val tauTrue = spacing * sin(deg * PI / 180) / AudioMath.SPEED_OF_SOUND
            val shift = (tauTrue * sr * over).roundToInt()
            val tauFine = shift.toDouble() / (sr * over)
            val a = FloatArray(1920); val b = FloatArray(1920)
            val clap = fineClap(rnd)
            // a hoert spaeter, wenn tau > 0
            place(a, 100, clap, shift.coerceAtLeast(0), 0.5)
            place(b, 100, clap, (-shift).coerceAtLeast(0), 0.5)
            val noise = 0.5 * 10.0.pow(-50 / 20.0)
            addNoise(a, rnd, noise); addNoise(b, rnd, noise)
            val d = assertNotNull(AudioMath.gccPhat(a, b, sr, spacing / AudioMath.SPEED_OF_SOUND * 1.2))
            assertEquals(tauFine, d.seconds, 0.00002, "Laufzeit bei $deg Grad")
            val ang = assertNotNull(AudioMath.angleDegrees(d.seconds, spacing), "Winkel bei $deg Grad")
            val angTrue = Math.toDegrees(kotlin.math.asin((tauFine * AudioMath.SPEED_OF_SOUND / spacing).coerceIn(-1.0, 1.0)))
            assertEquals(angTrue, ang, 3.0, "Winkel bei $deg Grad")
        }
    }

    @Test fun `S-008 erkennt doppeltes Mono`() {
        val rnd = Random(3)
        val mono = ShortArray(9600) { (gauss(rnd) * 3000).toInt().coerceIn(-32768, 32767).toShort() }
        val same = ShortArray(mono.size * 2) { mono[it / 2] }
        val s = AudioMath.analyze(same, 2)
        assertTrue(s.identicalShare >= 0.999, "Anteil gleicher Werte ${s.identicalShare}")
        assertFalse(s.distinctChannels)
        // ein Abtastwert Versatz: nicht mehr gleich
        val shifted = ShortArray(mono.size * 2) { i -> if (i % 2 == 0) mono[i / 2] else mono[maxOf(0, i / 2 - 1)] }
        val t = AudioMath.analyze(shifted, 2)
        assertTrue(t.identicalShare < 0.999, "Anteil gleicher Werte ${t.identicalShare}")
        assertTrue(t.distinctChannels)
        // Mono-Aufnahme: ein Kanal, nie verschieden
        assertFalse(AudioMath.analyze(mono, 1).distinctChannels)
    }

    @Test fun `S-008 Grundrauschen und stummer Kanal`() {
        val rnd = Random(5)
        val rms = 10.0.pow(-60 / 20.0) * 32768
        val pcm = ShortArray(96_000) { i -> if (i % 2 == 0) (gauss(rnd) * rms).roundToInt().toShort() else 0 }
        val s = AudioMath.analyze(pcm, 2)
        assertEquals(-60.0, s.levelDbfs[0], 0.5)
        assertEquals(Double.NEGATIVE_INFINITY, s.levelDbfs[1])
        assertFalse(s.levelDbfs.any { it.isNaN() } || s.peakDbfs.any { it.isNaN() })
        assertFalse(s.distinctChannels, "stummer Kanal zaehlt nicht als zweiter Kanal")
    }

    @Test fun `S-008 findet Klatscher, nicht das Rauschen`() {
        val rnd = Random(11)
        val x = FloatArray(12 * sr)
        addNoise(x, rnd, 0.5 * 10.0.pow(-60 / 20.0))
        // Sprache-aehnlich: Rauschbloecke mit langsam ansteigender Huelle (150 ms), 30 dB unter dem Klatscher
        for (start in listOf(0.3, 2.1, 3.4, 6.2, 9.0)) {
            val s0 = (start * sr).toInt(); val len = (0.6 * sr).toInt(); val ramp = (0.15 * sr).toInt()
            for (i in 0 until len) {
                val env = when { i < ramp -> (1 - cos(PI * i / ramp)) / 2; i > len - ramp -> (1 - cos(PI * (len - i) / ramp)) / 2; else -> 1.0 }
                x[s0 + i] += (gauss(rnd) * env * 0.5 * 10.0.pow(-30 / 20.0) * (0.6 + 0.4 * sin(2 * PI * 4 * i / sr))).toFloat()
            }
        }
        val times = listOf(1.0, 1.4, 1.8, 4.7, 5.1, 5.5, 7.6, 8.0, 10.7)
        for (t in times) place(x, (t * sr).toInt(), fineClap(rnd), 0, 0.5)
        val hits = AudioMath.findClaps(x, sr)
        assertEquals(times.size, hits.size, "Treffer: ${hits.map { it.toDouble() / sr }}")
        hits.zip(times).forEach { (h, t) -> assertEquals(t, h.toDouble() / sr, 0.005) }
    }

    @Test fun `S-008 Randfaelle`() {
        val maxTau = spacing / AudioMath.SPEED_OF_SOUND
        assertNull(AudioMath.angleDegrees(maxTau * 1.5, spacing), "unplausible Laufzeit ergibt keinen Winkel")
        assertEquals(90.0, assertNotNull(AudioMath.angleDegrees(maxTau * 1.05, spacing)), 0.001)
        assertNull(AudioMath.angleDegrees(0.0, 0.0))
        assertNull(AudioMath.gccPhat(FloatArray(0), FloatArray(0), sr, maxTau))
        assertNull(AudioMath.gccPhat(FloatArray(10), FloatArray(10), sr, maxTau))
        assertNull(AudioMath.gccPhat(FloatArray(500), FloatArray(400), sr, maxTau), "ungleiche Laenge")
        // Stille: kein Absturz, kein Treffer
        val silent = FloatArray(4800)
        assertEquals(emptyList(), AudioMath.findClaps(silent, sr))
        assertEquals(emptyList(), AudioMath.findClaps(FloatArray(0), sr))
        val stats = AudioMath.analyze(ShortArray(0), 2)
        assertEquals(0, stats.frames)
        assertFalse(stats.distinctChannels)
        // unvollstaendiger letzter Frame wird ignoriert
        assertEquals(2, AudioMath.analyze(ShortArray(5), 2).frames)
        val dirs = AudioMath.clapDirections(FloatArray(100), FloatArray(100), sr, spacing, listOf(50))
        assertEquals(1, dirs.size); assertNull(dirs[0].angleDegrees)
    }

    @Test fun `S-008 Richtung je Klatscher aus einer Stereo-Aufnahme`() {
        val rnd = Random(21)
        val l = FloatArray(4 * sr); val r = FloatArray(4 * sr)
        addNoise(l, rnd, 1e-4); addNoise(r, rnd, 1e-4)
        val shifts = listOf(14, -14, 0) // in Abtastwerten: Klatscher links, rechts, vorne
        shifts.forEachIndexed { k, s ->
            val at = ((0.5 + k) * sr).toInt(); val clap = fineClap(rnd)
            place(l, at, clap, maxOf(0, s) * over, 0.4); place(r, at, clap, maxOf(0, -s) * over, 0.4)
        }
        val mono = FloatArray(l.size) { maxOf(abs(l[it]), abs(r[it])) }
        val hits = AudioMath.findClaps(mono, sr)
        assertEquals(3, hits.size)
        val d = AudioMath.clapDirections(l, r, sr, spacing, hits)
        shifts.zip(d).forEach { (s, c) ->
            assertEquals(s.toDouble() / sr, c.delaySeconds, 0.00002)
            assertTrue(c.similarity > 0.9, "Aehnlichkeit ${c.similarity}")
            assertEquals(0.0, c.levelDiffDb, 1.0)
        }
        // 14 Abtastwerte bei 15 cm: asin(14 / 48000 * 343 / 0,15) = 41,8 Grad
        assertEquals(41.8, assertNotNull(d[0].angleDegrees), 2.0)
        assertEquals(-41.8, assertNotNull(d[1].angleDegrees), 2.0)
        assertEquals(0.0, assertNotNull(d[2].angleDegrees), 2.0)
    }

    @Test fun `S-008 GCC-PHAT ist schnell genug`() {
        val rnd = Random(1)
        val a = FloatArray(1920) { gauss(rnd).toFloat() }; val b = FloatArray(1920) { gauss(rnd).toFloat() }
        repeat(20) { AudioMath.gccPhat(a, b, sr, 0.0006) }
        var best = Long.MAX_VALUE
        repeat(5) {
            val t0 = System.nanoTime(); AudioMath.gccPhat(a, b, sr, 0.0006); best = minOf(best, System.nanoTime() - t0)
        }
        println("GCC-PHAT 40 ms Fenster: ${best / 1000} us")
        assertTrue(best < 5_000_000, "GCC-PHAT dauerte ${best / 1_000_000.0} ms")
    }

    @Test fun `S-008 Pegel in dBFS`() {
        assertEquals(Double.NEGATIVE_INFINITY, AudioMath.dbfs(0.0))
        assertEquals(0.0, AudioMath.dbfs(1.0), 1e-9)
        assertEquals(-6.02, AudioMath.dbfs(0.5), 0.01)
        assertEquals(-20.0, 20 * log10(0.1), 1e-9)
    }
}

class WavTest {
    @Test fun `S-008 WAV-Kopf und Rundreise`() {
        val pcm = ShortArray(1000) { ((it * 37) % 65536 - 32768).toShort() }
        val bytes = Wav.encode(pcm, 48_000, 2)
        assertEquals(44 + pcm.size * 2, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        fun le32(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
            ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24)
        fun le16(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
        assertEquals(bytes.size - 8, le32(4))
        assertEquals(16, le32(16)); assertEquals(1, le16(20)); assertEquals(2, le16(22))
        assertEquals(48_000, le32(24)); assertEquals(48_000 * 4, le32(28)); assertEquals(4, le16(32)); assertEquals(16, le16(34))
        assertEquals(pcm.size * 2, le32(40))
        val back = assertNotNull(Wav.decode(bytes))
        assertEquals(48_000, back.sampleRate); assertEquals(2, back.channels)
        assertEquals(pcm.toList(), back.samples.toList())
    }

    @Test fun `S-008 WAV Randfaelle`() {
        assertEquals(44, Wav.encode(ShortArray(0), 44_100, 1).size)
        assertNull(Wav.decode(ByteArray(10)))
        assertNull(Wav.decode(ByteArray(44)), "falsche Kennung")
        val ok = Wav.encode(shortArrayOf(1, -1, 32767, -32768), 8_000, 1)
        assertEquals(listOf<Short>(1, -1, 32767, -32768), assertNotNull(Wav.decode(ok)).samples.toList())
    }
}
