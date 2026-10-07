package app.cayresim.core.pure

/** Zeitquelle (R2): innere Schichten lesen nie die Systemuhr selbst. */
fun interface Clock {
    fun nowMillis(): Long
}

/** Zufallsquelle (R4): in Tests mit festem Startwert. */
fun interface Rng {
    /** Zahl in [0, bound). */
    fun nextInt(bound: Int): Int
}

/** Deterministischer Zufall (xorshift), fuer Tests und reproduzierbare Ablaeufe. */
class SeededRng(seed: Long) : Rng {
    private var state = if (seed == 0L) 0x2545F4914F6CDD1DL else seed
    override fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound muss positiv sein" }
        state = state xor (state shl 13)
        state = state xor (state ushr 7)
        state = state xor (state shl 17)
        return ((state ushr 1) % bound).toInt()
    }
}
