package app.cayresim.core.pure

/**
 * Median der ersten [count] Werte. Bei gerader Anzahl der untere Median,
 * damit das Ergebnis immer ein tatsaechlich vorkommender Wert ist (wichtig fuer Pixel).
 */
fun medianOf(values: IntArray, count: Int = values.size): Int {
    require(count in 1..values.size) { "count muss zwischen 1 und ${values.size} liegen: $count" }
    val copy = values.copyOf(count)
    copy.sort()
    return copy[(count - 1) / 2]
}

/** Gerundeter Mittelwert der ersten [count] Werte. */
fun meanOf(values: IntArray, count: Int = values.size): Int {
    require(count in 1..values.size) { "count muss zwischen 1 und ${values.size} liegen: $count" }
    var sum = 0L
    for (i in 0 until count) sum += values[i]
    return Math.round(sum.toDouble() / count).toInt()
}
