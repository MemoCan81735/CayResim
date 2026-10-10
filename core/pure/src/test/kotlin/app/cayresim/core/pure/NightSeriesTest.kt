package app.cayresim.core.pure

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S-011: Eintraege je Bild der Nacht-Zusammenfuehrung und Archiv der Serie. Alles kuenstlich (CLAUDE.md 7). */
class NightSeriesTest {
    private val w = 160
    private val h = 120

    /**
     * Grosse glatte Textur: Zufallswerte auf einem Gitter von 6 px, dazwischen bilinear. Glatt statt Bloecke, damit die
     * gemessene Schaerfe nicht davon abhaengt, wie die Verschiebung zur 4-fachen Verkleinerung liegt (sonst gelten
     * verschobene Bilder als verwackelt).
     */
    private fun texture(rnd: Random, bw: Int, bh: Int): IntArray {
        val g = 6
        val gw = bw / g + 2; val gh = bh / g + 2
        val grid = IntArray(gw * gh) { 40 + rnd.nextInt(170) }
        return IntArray(bw * bh) { i ->
            val x = i % bw; val y = i / bw
            val gx = x / g; val gy = y / g; val fx = (x % g) / g.toDouble(); val fy = (y % g) / g.toDouble()
            val a = grid[gy * gw + gx] * (1 - fx) + grid[gy * gw + gx + 1] * fx
            val b = grid[(gy + 1) * gw + gx] * (1 - fx) + grid[(gy + 1) * gw + gx + 1] * fx
            (a * (1 - fy) + b * fy).roundToInt()
        }
    }

    /** Ausschnitt der Textur ab (ox - sx, oy - sy): das Bild ist gegen den Bezug um (sx, sy) verschoben. */
    private fun frame(tex: IntArray, bw: Int, ox: Int, oy: Int, sx: Int, sy: Int, rnd: Random): ByteArray {
        val out = ByteArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val v = (tex[(y + oy - sy) * bw + x + ox - sx] + rnd.nextInt(-3, 4)).coerceIn(0, 255)
            val p = (y * w + x) * 3
            out[p] = v.toByte(); out[p + 1] = (v * 0.9).roundToInt().toByte(); out[p + 2] = (v * 0.8).roundToInt().toByte()
        }
        return out
    }

    private fun blur(f: ByteArray, r: Int): ByteArray {
        val out = ByteArray(f.size)
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 3) {
            var s = 0; var n = 0
            for (oy in -r..r) for (ox in -r..r) {
                val yy = (y + oy).coerceIn(0, h - 1); val xx = (x + ox).coerceIn(0, w - 1)
                s += f[(yy * w + xx) * 3 + c].toInt() and 0xFF; n++
            }
            out[(y * w + x) * 3 + c] = (s / n).toByte()
        }
        return out
    }

    @Test fun `S-011 Eintraege je Bild`() {
        val rnd = Random(11)
        val bw = w + 80; val bh = h + 80
        val tex = texture(rnd, bw, bh)
        val shifts = listOf(0 to 0, 3 to -2, -7 to 5, 12 to 9, -15 to -11, 4 to 0)
        val frames = shifts.map { (sx, sy) -> frame(tex, bw, 40, 40, sx, sy, rnd) }.toMutableList()
        frames += blur(frame(tex, bw, 40, 40, 2, 2, rnd), 4) // verwackelt
        val m = NightMerge(w, h)
        frames.forEach { m.add(it) }
        val r = m.records
        assertEquals(frames.size, r.size, "ein Eintrag je Bild")
        assertEquals((frames.indices).toList(), r.map { it.index })
        assertTrue(r[0].reference, "erstes Bild ist der Bezug")
        shifts.forEachIndexed { i, (sx, sy) -> if (i > 0) assertEquals(sx to sy, r[i].dx to r[i].dy, "Versatz Bild $i") }
        assertTrue(r.last().dropped, "verwackeltes Bild verworfen")
        assertFalse(r.dropLast(1).any { it.dropped })
        assertTrue(r.last().sharpness < 0.5 * r[0].sharpness, "Schaerfe ${r.last().sharpness} gegen ${r[0].sharpness}")
        r.forEach { assertTrue(it.meanLuma in 20f..230f && it.zeroShare in 0f..1f, "Werte $it") }
        assertEquals(m.dropped, r.count { it.dropped })
        assertEquals(m.maxShake, r.filter { !it.dropped }.maxOf { maxOf(kotlin.math.abs(it.dx), kotlin.math.abs(it.dy)) })
    }

    @Test fun `S-011 Eintraege bei Bezugswechsel`() {
        // erstes Bild weich, zweites scharf: Bezug wechselt, beide Eintraege bleiben mit ihrem Index erhalten
        val rnd = Random(12)
        val bw = w + 40; val bh = h + 40
        val tex = texture(rnd, bw, bh)
        val m = NightMerge(w, h)
        m.add(blur(frame(tex, bw, 20, 20, 0, 0, rnd), 2))
        m.add(frame(tex, bw, 20, 20, 0, 0, rnd))
        m.add(frame(tex, bw, 20, 20, 5, 3, rnd))
        val r = m.records
        assertEquals(listOf(0, 1, 2), r.map { it.index })
        assertTrue(r[1].reference && !r[0].reference, "Bezug ist das zweite Bild: $r")
        assertEquals(5 to 3, r[2].dx to r[2].dy)
    }

    @Test fun `S-011 Archiv Rundreise und Formatversion`() {
        val rgb = ByteArray(6 * 4 * 3) { (it * 7).toByte() }
        val luma = NightSeries.luma(rgb, 6 * 4)
        assertEquals(FrameAligner.lumaAt(rgb, 5 * 3), luma[5].toInt() and 0xFF)
        val meta = NightSeries.metaJson(
            NightSeries.Info(width = 6, height = 4, rotation = 90, exposureNs = 100_000_000, iso = 3200, meterExposureNs = 40_000_000,
                meterIso = 3200, durationMs = 9_700, used = 2, dropped = 0, gain = 15.7f, maxShake = 35, timestampsNs = listOf(10L, 20L)),
            listOf(NightMerge.FrameRecord(0, 0, 0, false, false, true, 12.5, 30f, 0.29f), NightMerge.FrameRecord(1, 3, -2, false, false, false, 11.0, 31f, 0.28f)),
        )
        val bytes = ByteArrayOutputStream().also { out ->
            NightSeries.ZipWriter(out).use { z ->
                z.put("y-000.bin", luma); z.put("rgb-first.bin", rgb); z.put(NightSeries.META, meta.toByteArray())
            }
        }.toByteArray()
        val a = assertNotNull(NightSeries.read(ByteArrayInputStream(bytes)))
        assertContentEquals(luma, a.entries.getValue("y-000.bin"))
        assertContentEquals(rgb, a.entries.getValue("rgb-first.bin"))
        assertEquals(1, a.format)
        assertTrue("\"maxShake\": 35" in a.meta && "\"dx\": 3" in a.meta && "\"timestampNs\": 20" in a.meta, a.meta)
        // unbekannte Formatversion oder fehlende Kenndaten: null (R26)
        val wrong = ByteArrayOutputStream().also { out -> NightSeries.ZipWriter(out).use { it.put(NightSeries.META, "{\"format\": 2}".toByteArray()) } }.toByteArray()
        assertNull(NightSeries.read(ByteArrayInputStream(wrong)))
        val noMeta = ByteArrayOutputStream().also { out -> NightSeries.ZipWriter(out).use { it.put("y-000.bin", luma) } }.toByteArray()
        assertNull(NightSeries.read(ByteArrayInputStream(noMeta)))
        assertNull(NightSeries.read(ByteArrayInputStream(ByteArray(10))))
    }

    @Test fun `S-011 Zeit und Groesse je Bild`() {
        // dunkle Szene wie auf dem S24+: Grundwert 0 bis 20 in Bloecken, Rauschen, unten bei 0 abgeschnitten
        val rnd = Random(13)
        val fw = 1440; val fh = 1080
        val blocks = IntArray((fw / 40 + 1) * (fh / 40 + 1)) { rnd.nextInt(0, 12) }
        val rgb = ByteArray(fw * fh * 3)
        for (i in 0 until fw * fh) {
            val b = blocks[(i / fw / 40) * (fw / 40 + 1) + (i % fw) / 40]
            for (c in 0 until 3) rgb[i * 3 + c] = (b + rnd.nextInt(-3, 4)).coerceIn(0, 255).toByte()
        }
        var best = Long.MAX_VALUE; var size = 0
        repeat(5) {
            val t0 = System.nanoTime()
            val out = ByteArrayOutputStream()
            NightSeries.ZipWriter(out).use { it.put("y-000.bin", NightSeries.luma(rgb, fw * fh)) }
            best = minOf(best, System.nanoTime() - t0); size = out.size()
        }
        println("Nachtserie je Bild: ${best / 1_000_000} ms, ${size / 1000} kB")
        assertTrue(best < 40_000_000, "je Bild ${best / 1e6} ms")
        assertTrue(size < 1_000_000, "je Bild $size Byte")
    }
}
