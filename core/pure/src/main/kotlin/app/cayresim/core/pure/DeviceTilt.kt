package app.cayresim.core.pure

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * S-021: Lage des quer gehaltenen Handys fuer das Schallbild. Achsen des Geraets wie bei Android: x zur rechten
 * Kante (hochkant gesehen), y zur Oberkante, z aus dem Bildschirm. Alle Winkel aus Sicht des Nutzers, der auf den
 * Bildschirm schaut; die Kamera schaut von ihm weg. Vorzeichen aus der Rechnung, der Geraetetest (K15) bestaetigt sie.
 */
object DeviceTilt {
    /** Innerer Rand des Lage-Kreises und der Bereich, in dem ein Punkt als "am Rand" zaehlt. */
    const val RIM_DEG = 15.0
    const val RIM_MIN_DEG = 10.0
    const val RIM_MAX_DEG = 20.0
    /** Abschnitte des Rings (je 30 Grad). */
    const val SECTORS = 12
    /** Ab hier gilt ein Moment als bewegt: Stoss (ohne Schwerkraft) in m/s^2, Drehgeschwindigkeit in Grad je Sekunde. */
    const val MOVING_ACCEL = 1.5
    const val MOVING_RATE_DEG = 30.0
    /** So lange nach dem letzten Stoss bleibt "bewegt": die Mittelung des Schallbilds (0,4 s) traegt ihn noch. */
    const val MOVING_HOLD_NANOS = 500_000_000L
    /** Zeitkonstante des Tiefpasses fuer die Schwerkraft. */
    const val GRAVITY_SECONDS = 0.2
    /** Kleinster Anteil der Schwerkraft in der Bildschirmebene; darunter liegt das Handy zu flach fuer eine Neigung quer. */
    const val MIN_UPRIGHT = 0.5

    /**
     * [rollDeg]: Neigung quer, positiv = rechte Seite hoeher; gemessen als Drehung um die Blickachse, also so, wie der
     * Horizont schraeg im Bild liegt. [pitchDeg]: Kippen, positiv = Kamera schaut nach oben.
     */
    data class Tilt(val rollDeg: Double, val pitchDeg: Double)

    /**
     * Lage gegenueber dem Start. [yawDeg]: Drehung, positiv = nach rechts gedreht. [rollDeg]: Schraegstellen, positiv =
     * rechte Seite hoeher. [pitchDeg]: Kippen, positiv = nach oben.
     */
    data class Turn(val yawDeg: Double, val rollDeg: Double, val pitchDeg: Double)

    /** "Rechts" und "oben" des Nutzers in Geraeteachsen; null, wenn nicht quer. */
    private fun axes(hold: SoundBands.Hold): Pair<Vec3, Vec3>? = when (hold) {
        SoundBands.Hold.TOP_LEFT -> Vec3(0.0, -1.0, 0.0) to Vec3(1.0, 0.0, 0.0)
        SoundBands.Hold.TOP_RIGHT -> Vec3(0.0, 1.0, 0.0) to Vec3(-1.0, 0.0, 0.0)
        SoundBands.Hold.NOT_LANDSCAPE -> null
    }

    private fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z

    /**
     * Neigung aus der Schwerkraft (Beschleunigung in Ruhe, zeigt im Geraet nach oben). null: nicht quer, kein gueltiger
     * Wert oder zu flach liegend (dann ist die Neigung quer nicht bestimmt).
     */
    fun tilt(ax: Double, ay: Double, az: Double, hold: SoundBands.Hold): Tilt? {
        val (right, up) = axes(hold) ?: return null
        val len = sqrt(ax * ax + ay * ay + az * az)
        if (!len.isFinite() || len < 1.0) return null
        val a = Vec3(ax / len, ay / len, az / len)
        val r = dot(a, right); val u = dot(a, up)
        if (hypot(r, u) < MIN_UPRIGHT) return null
        return Tilt(Math.toDegrees(atan2(r, u)), Math.toDegrees(asin((-a.z).coerceIn(-1.0, 1.0))))
    }

    /** Lage [now] gegenueber [start] (beide: Geraet in Welt, wie der Drehvektor); null, wenn nicht quer. */
    fun turn(start: SweepMath.Pose, now: SweepMath.Pose, hold: SoundBands.Hold): Turn? {
        val (right, up) = axes(hold) ?: return null
        // Blickrichtung (Geraet -z) und "rechts" von jetzt, ausgedrueckt im Geraetesystem des Starts
        val f = SweepMath.rotateInverse(start, SweepMath.rotate(now, Vec3(0.0, 0.0, -1.0)))
        val r = SweepMath.rotateInverse(start, SweepMath.rotate(now, right))
        return Turn(
            yawDeg = Math.toDegrees(atan2(dot(f, right), -f.z)),
            rollDeg = Math.toDegrees(atan2(dot(r, up), dot(r, right))),
            pitchDeg = Math.toDegrees(atan2(dot(f, up), -f.z)),
        )
    }

    /** Drehgeschwindigkeit zwischen zwei Lagen in Grad je Sekunde; 0 ohne Zeitabstand. */
    fun rateDegPerSecond(a: SweepMath.Pose, b: SweepMath.Pose): Double {
        val dt = (b.nanos - a.nanos) / 1e9
        if (dt <= 0.0) return 0.0
        // Drehung von a nach b: konjugiert(a) mal b; Winkel aus Vektorteil und Skalar (genau auch bei kleinen Winkeln)
        val w = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
        val x = a.w * b.x - a.x * b.w - a.y * b.z + a.z * b.y
        val y = a.w * b.y + a.x * b.z - a.y * b.w - a.z * b.x
        val z = a.w * b.z - a.x * b.y + a.y * b.x - a.z * b.w
        return Math.toDegrees(2.0 * atan2(sqrt(x * x + y * y + z * z), abs(w))) / dt
    }

    /**
     * Punkt im Lage-Kreis: x nach rechts (Drehung), y nach oben (Schraegstellen), 1 = innerer Rand ([RIM_DEG]). Kippen
     * dreht um die Mikrofonachse und zaehlt nicht. Weiter als [RIM_MAX_DEG] bleibt der Punkt am Rand des Felds.
     */
    fun dot(t: Turn): Pair<Double, Double> {
        val x = t.yawDeg / RIM_DEG; val y = t.rollDeg / RIM_DEG
        val len = hypot(x, y); val max = RIM_MAX_DEG / RIM_DEG
        return if (len > max) x * max / len to y * max / len else x to y
    }

    /** Welche Abschnitte des Rands der Punkt schon besucht hat. */
    class RimCoverage {
        /** Bit n gesetzt: Abschnitt n besucht; Abschnitt 0 beginnt rechts, weiter gegen den Uhrzeigersinn. */
        var mask = 0; private set
        val count: Int get() = Integer.bitCount(mask)
        val complete: Boolean get() = count == SECTORS

        fun add(t: Turn) {
            val radius = hypot(t.yawDeg, t.rollDeg)
            if (radius < RIM_MIN_DEG || radius > RIM_MAX_DEG) return
            val angle = (Math.toDegrees(atan2(t.rollDeg, t.yawDeg)) + 360.0) % 360.0
            mask = mask or (1 shl floor(angle / (360.0 / SECTORS)).toInt().coerceIn(0, SECTORS - 1))
        }

        fun reset() { mask = 0 }
    }

    /** Merkt Stoesse und schnelle Drehungen; [moving] bleibt danach [MOVING_HOLD_NANOS] lang wahr. */
    class MotionGate {
        private var gx = 0.0; private var gy = 0.0; private var gz = 0.0
        private var lastAccel = Long.MIN_VALUE
        private var until = Long.MIN_VALUE

        /** Beschleunigung mit Schwerkraft; die Schwerkraft wird als Tiefpass mitgefuehrt und abgezogen. */
        fun accel(nanos: Long, x: Double, y: Double, z: Double) {
            if (lastAccel == Long.MIN_VALUE) { gx = x; gy = y; gz = z; lastAccel = nanos; return }
            val dt = ((nanos - lastAccel) / 1e9).coerceAtLeast(0.0)
            lastAccel = nanos
            val linear = sqrt((x - gx) * (x - gx) + (y - gy) * (y - gy) + (z - gz) * (z - gz))
            if (linear > MOVING_ACCEL) mark(nanos)
            val alpha = 1.0 - exp(-dt / GRAVITY_SECONDS)
            gx += alpha * (x - gx); gy += alpha * (y - gy); gz += alpha * (z - gz)
        }

        fun rate(nanos: Long, degPerSecond: Double) { if (degPerSecond > MOVING_RATE_DEG) mark(nanos) }

        private fun mark(nanos: Long) { until = maxOf(until, nanos + MOVING_HOLD_NANOS) }

        fun moving(nanos: Long): Boolean = until != Long.MIN_VALUE && nanos <= until
    }
}
