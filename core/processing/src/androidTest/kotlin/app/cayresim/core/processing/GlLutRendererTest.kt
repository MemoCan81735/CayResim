package app.cayresim.core.processing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cayresim.core.pure.LookId
import app.cayresim.core.pure.Looks
import app.cayresim.core.pure.SeededRng
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** R20 und Testkonzept: GPU-Shader gegen die Kotlin-Referenz, hoechstens 1 Stufe Abweichung je Kanal. */
@RunWith(AndroidJUnit4::class)
class GlLutRendererTest {
    private val r = GlLutRenderer()
    @After fun tearDown() = r.release()

    /** Farbtafel: Graukeil, reine Farben, Zufallsfarben. */
    private fun chart(): Bitmap {
        val w = 64; val h = 48; val rng = SeededRng(21)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val c = when (y / 16) {
                0 -> { val v = x * 255 / (w - 1); Color.rgb(v, v, v) }
                1 -> Color.rgb(if (x % 3 == 0) 255 else 0, if (x % 3 == 1) 255 else 0, if (x % 3 == 2) 255 else x * 4)
                else -> Color.rgb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))
            }
            b.setPixel(x, y, c)
        }
        return b
    }

    @Test fun jederLookStimmtMitDerReferenzUeberein() {
        assertTrue(r.setUp(), "GLES 3 muss verfuegbar sein")
        val src = chart()
        for (id in LookId.entries) {
            val lut = Looks.lut(id)
            val out = assertNotNull(r.render(src, lut), "Rendern fehlgeschlagen fuer $id")
            var worst = 0
            for (y in 0 until src.height) for (x in 0 until src.width) {
                val s = src.getPixel(x, y); val o = out.getPixel(x, y)
                val e = lut.apply(Color.red(s), Color.green(s), Color.blue(s))
                worst = maxOf(worst, abs(Color.red(o) - e[0]), abs(Color.green(o) - e[1]), abs(Color.blue(o) - e[2]))
            }
            assertTrue(worst <= 1, "Look $id weicht um $worst Stufen von der Referenz ab")
        }
    }

    @Test fun ausrichtungBleibtErhalten() {
        assertTrue(r.setUp())
        val src = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK); setPixel(0, 0, Color.WHITE) }
        val out = assertNotNull(r.render(src, Looks.lut(LookId.NONE)))
        assertTrue(Color.red(out.getPixel(0, 0)) > 250 && Color.red(out.getPixel(3, 3)) < 5, "Bild darf nicht gespiegelt werden")
    }

    @Test fun mehrfachesRendernOhneLeck() {
        assertTrue(r.setUp())
        val src = chart()
        repeat(100) { assertNotNull(r.render(src, Looks.lut(LookId.FILM))).recycle() }
    }

    @Test fun zuGrossesBildWirdAbgelehntStattAbsturz() {
        assertTrue(r.setUp())
        val max = r.maxTextureSize()
        val src = Bitmap.createBitmap(max + 1, 1, Bitmap.Config.ARGB_8888)
        kotlin.test.assertNull(r.render(src, Looks.lut(LookId.NONE)))
    }

    @Test fun nachReleaseWiederVerwendbar() {
        assertTrue(r.setUp()); r.release(); assertTrue(r.setUp())
        assertNotNull(r.render(chart(), Looks.lut(LookId.MONO)))
    }
}
