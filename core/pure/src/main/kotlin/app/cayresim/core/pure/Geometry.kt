package app.cayresim.core.pure

/** Punkt in normalisierten Koordinaten, beide Achsen von 0 bis 1 (R21). */
data class NormPoint(val x: Float, val y: Float) {
    init {
        require(x in 0f..1f && y in 0f..1f) { "NormPoint ausserhalb von 0..1: ($x, $y)" }
    }
}

/** Pixel einer Flaeche der Groesse width x height in normalisierte Koordinaten, an den Raendern begrenzt. */
fun viewToNormalized(px: Float, py: Float, width: Int, height: Int): NormPoint {
    require(width > 0 && height > 0) { "Flaeche muss groesser als 0 sein" }
    return NormPoint((px / width).coerceIn(0f, 1f), (py / height).coerceIn(0f, 1f))
}

/** Normalisierte Koordinaten zurueck in Pixel einer Flaeche. */
fun normalizedToView(p: NormPoint, width: Int, height: Int): Pair<Float, Float> {
    require(width > 0 && height > 0) { "Flaeche muss groesser als 0 sein" }
    return p.x * width to p.y * height
}

/** Dreht einen normalisierten Punkt um 0, 90, 180 oder 270 Grad im Uhrzeigersinn um die Bildmitte. */
fun rotateNormalized(p: NormPoint, degrees: Int): NormPoint = when (Math.floorMod(degrees, 360)) {
    0 -> p
    90 -> NormPoint(1f - p.y, p.x)
    180 -> NormPoint(1f - p.x, 1f - p.y)
    270 -> NormPoint(p.y, 1f - p.x)
    else -> throw IllegalArgumentException("Nur Vielfache von 90 Grad: $degrees")
}

/** Spiegelt horizontal (Frontkamera). */
fun mirrorNormalized(p: NormPoint): NormPoint = NormPoint(1f - p.x, p.y)
