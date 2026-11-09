package app.cayresim.core.boundary.fake

import app.cayresim.core.boundary.MotionKind
import app.cayresim.core.boundary.MotionSample
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kuenstliche Lage eines quer und aufrecht gehaltenen Handys mit der Oberkante links (S-021; CLAUDE.md Abschnitt 7):
 * ein Beschleunigungswert (Schwerkraft, dazu [shake] in m/s^2 als Stoss aus dem Bildschirm) und ein Drehvektor.
 * Der Nutzer schaut nach Norden. [rollRightUpDeg]: rechte Seite hoeher (Drehung um die Blickachse), danach
 * [yawRightDeg]: nach rechts gedreht (um die Senkrechte). Rechnet mit gedrehten Achsen, nicht mit den Formeln der App.
 */
fun landscapeMotion(nanos: Long, yawRightDeg: Double = 0.0, rollRightUpDeg: Double = 0.0, shake: Float = 0f): List<MotionSample> {
    val up = doubleArrayOf(0.0, 0.0, 1.0); val back = doubleArrayOf(0.0, -1.0, 0.0)
    fun turn(v: DoubleArray) = rotate(up, -yawRightDeg, rotate(back, rollRightUpDeg, v))
    // Geraeteachsen in der Welt (x Osten, y Norden, z oben): x zeigt nach oben, y nach Westen, z zum Nutzer
    val x = turn(up); val y = turn(doubleArrayOf(-1.0, 0.0, 0.0)); val z = turn(back)
    val g = 9.81
    val accel = MotionSample(MotionKind.ACCELERATION, nanos, (x[2] * g).toFloat(), (y[2] * g).toFloat(), (z[2] * g).toFloat() + shake)
    val q = quaternion(x, y, z)
    return listOf(accel, MotionSample(MotionKind.ROTATION, nanos, q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat()))
}

/** Dreht [v] um die Achse [k] (Laenge 1) nach der Rechte-Hand-Regel. */
private fun rotate(k: DoubleArray, deg: Double, v: DoubleArray): DoubleArray {
    val t = Math.toRadians(deg); val c = cos(t); val s = sin(t)
    val d = k[0] * v[0] + k[1] * v[1] + k[2] * v[2]
    val cx = k[1] * v[2] - k[2] * v[1]; val cy = k[2] * v[0] - k[0] * v[2]; val cz = k[0] * v[1] - k[1] * v[0]
    return doubleArrayOf(v[0] * c + cx * s + k[0] * d * (1 - c), v[1] * c + cy * s + k[1] * d * (1 - c), v[2] * c + cz * s + k[2] * d * (1 - c))
}

/** Quaternion w, x, y, z (Geraet in Welt) aus den Geraeteachsen in der Welt. */
private fun quaternion(x: DoubleArray, y: DoubleArray, z: DoubleArray): DoubleArray {
    val m00 = x[0]; val m01 = y[0]; val m02 = z[0]; val m10 = x[1]; val m11 = y[1]; val m12 = z[1]; val m20 = x[2]; val m21 = y[2]; val m22 = z[2]
    val tr = m00 + m11 + m22
    return when {
        tr > 0 -> { val s = sqrt(tr + 1.0) * 2; doubleArrayOf(s / 4, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s) }
        m00 > m11 && m00 > m22 -> { val s = sqrt(1.0 + m00 - m11 - m22) * 2; doubleArrayOf((m21 - m12) / s, s / 4, (m01 + m10) / s, (m02 + m20) / s) }
        m11 > m22 -> { val s = sqrt(1.0 + m11 - m00 - m22) * 2; doubleArrayOf((m02 - m20) / s, (m01 + m10) / s, s / 4, (m12 + m21) / s) }
        else -> { val s = sqrt(1.0 + m22 - m00 - m11) * 2; doubleArrayOf((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, s / 4) }
    }
}
