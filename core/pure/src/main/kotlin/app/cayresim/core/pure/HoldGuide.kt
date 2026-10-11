package app.cayresim.core.pure

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * S-016: Halte-Kreis bei der Nachtaufnahme. Wohin zeigt die Kamera gegenueber dem Start? Rechnet mit den Lagen des
 * Drehvektors (Geraet in Welt) wie die Schwenk-Messung ([SweepMath.Pose]).
 */
object HoldGuide {
    /** Gruen bis zu dieser Abweichung; 0,5 Grad sind beim S24+ etwa 10 px (Nachtserie 11.10.: etwa 20 px je Grad). */
    const val RADIUS_DEG = 0.5

    /** Abweichung der Blickrichtung im Geraetesystem des Starts: nach rechts (Geraet x) und nach oben (Geraet y). */
    data class Deviation(val rightDeg: Double, val upDeg: Double, val steady: Boolean) {
        val totalDeg: Double get() = hypot(rightDeg, upDeg)
    }

    fun deviation(start: SweepMath.Pose, now: SweepMath.Pose): Deviation {
        // Blickrichtung (Geraet -z) jetzt in der Welt, dann im Geraetesystem des Starts
        val u = SweepMath.rotateInverse(start, SweepMath.rotate(now, Vec3(0.0, 0.0, -1.0)))
        val right = Math.toDegrees(atan2(u.x, -u.z))
        val up = Math.toDegrees(atan2(u.y, -u.z))
        return Deviation(right, up, hypot(right, up) <= RADIUS_DEG)
    }

    /**
     * Auf den Bildschirm (nach rechts, nach unten) je nach Drehung der Anzeige in Grad (0, 90, 180, 270 wie
     * Display.getRotation): bei 90 liegt die Oberkante des Geraets links, bei 270 rechts.
     */
    fun toScreen(d: Deviation, displayRotation: Int): Pair<Double, Double> = when (((displayRotation % 360) + 360) % 360) {
        90 -> -d.upDeg to -d.rightDeg
        180 -> -d.rightDeg to d.upDeg
        270 -> d.upDeg to d.rightDeg
        else -> d.rightDeg to -d.upDeg
    }

    /** Kennzahlen fuer den Hinweis: Anteil der Zeit im Kreis (zeitgewichtet) und groesste Abweichung. */
    class Stats {
        private var lastNanos = Long.MIN_VALUE
        private var lastDeg = 0.0
        private var inside = 0L
        private var total = 0L
        var maxDeg = 0.0; private set
        var count = 0; private set
        val insideShare: Double get() = if (total > 0) inside.toDouble() / total else 0.0
        /** Gemessene Zeit in ns; 0 = noch keine Kennzahl (ein Wert oder gleiche Zeitstempel). */
        val totalNanos: Long get() = total

        /**
         * Lage zum Zeitpunkt [nanos] mit Abweichung [deg]; der Abschnitt seit dem letzten Wert zaehlt mit dessen
         * Abweichung. Werte mit aelterem Zeitstempel werden verworfen (Zweitpruefung S-016, H4).
         */
        fun add(nanos: Long, deg: Double) {
            if (lastNanos != Long.MIN_VALUE && nanos < lastNanos) return
            if (lastNanos != Long.MIN_VALUE && nanos > lastNanos) {
                val dt = nanos - lastNanos
                total += dt
                if (lastDeg <= RADIUS_DEG) inside += dt
            }
            lastNanos = nanos; lastDeg = deg; count++
            if (deg > maxDeg) maxDeg = deg
        }
    }
}
