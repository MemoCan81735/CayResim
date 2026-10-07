package app.cayresim.core.pure

/** Rechteck in Pixeln (links, oben, Breite, Hoehe). */
data class RectF(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Wie ein Bild der Groesse src in eine Flaeche dst gelegt wird, wenn es sie ganz fuellt
 * und mittig beschnitten wird (wie der Sucher). Das Geister-Overlay nutzt dieselbe Rechnung,
 * damit es deckungsgleich ueber dem Sucher liegt.
 */
fun fillCenter(srcW: Int, srcH: Int, dstW: Int, dstH: Int): RectF {
    require(srcW > 0 && srcH > 0 && dstW > 0 && dstH > 0) { "Groessen muessen positiv sein" }
    val scale = maxOf(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
    val w = srcW * scale; val h = srcH * scale
    return RectF((dstW - w) / 2f, (dstH - h) / 2f, w, h)
}

/** Plan fuer ein Zeitraffer-Video aus [photoCount] Fotos. */
data class TimelapsePlan(val photoCount: Int, val fps: Int, val frameDurationMicros: Long, val totalMicros: Long)

fun timelapsePlan(photoCount: Int, photosPerSecond: Int): TimelapsePlan {
    require(photoCount >= 1) { "Zeitraffer braucht mindestens ein Foto" }
    require(photosPerSecond in 1..60) { "1 bis 60 Fotos je Sekunde" }
    val per = 1_000_000L / photosPerSecond
    return TimelapsePlan(photoCount, photosPerSecond, per, per * photoCount)
}
