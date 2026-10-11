import app.cayresim.core.pure.*
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileInputStream
import javax.imageio.ImageIO

/** S-015: Nachtserie offline durch den Nacht-Kern rechnen (nur Helligkeit, grau) und als JPEG schreiben. Aufruf ueber labor.sh. */
fun main(args: Array<String>) {
    val a = FileInputStream(args[0]).use { NightSeries.read(it) } ?: error("Archiv unlesbar")
    val w = Regex("\"width\": (\\d+)").find(a.meta)!!.groupValues[1].toInt()
    val h = Regex("\"height\": (\\d+)").find(a.meta)!!.groupValues[1].toInt()
    val m = NightMerge(w, h)
    val names = a.entries.keys.filter { it.startsWith("y-") }.sorted()
    for (n in names) {
        val y = a.entries.getValue(n)
        val rgb = ByteArray(w * h * 3) { y[it / 3] }
        m.add(rgb)
    }
    val r = m.finish()
    println("${args[2]}: Diagnose ${m.diagnosis}, Aufhellung ${r.gain}, verwendet ${m.used}, Boden ${m.noiseFloor}")
    // um 90 Grad drehen wie im Foto
    val img = BufferedImage(h, w, BufferedImage.TYPE_INT_RGB)
    for (yy in 0 until h) for (xx in 0 until w) {
        val p = (yy * w + xx) * 3
        val c = ((r.rgb[p].toInt() and 255) shl 16) or ((r.rgb[p + 1].toInt() and 255) shl 8) or (r.rgb[p + 2].toInt() and 255)
        img.setRGB(h - 1 - yy, xx, c)
    }
    ImageIO.write(img, "jpg", File(args[1]))
}
