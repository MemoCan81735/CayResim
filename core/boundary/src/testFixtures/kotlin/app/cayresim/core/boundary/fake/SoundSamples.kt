package app.cayresim.core.boundary.fake

import app.cayresim.core.pure.AudioMath
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Kuenstlicher Stereo-Ton aus einer Richtung fuer Tests (S-018; CLAUDE.md Abschnitt 7): 60 Toene zwischen 300 und
 * 5000 Hz mit zufaelliger Phase, dazu schwaches Rauschen je Kanal; verschraenkt, 16 Bit. [degrees]: Winkel zur
 * Mikrofonachse, positiv heisst wie in [AudioMath], Kanal 0 hoert spaeter. Fester Startwert, also bei jedem Lauf gleich.
 */
fun stereoTones(degrees: Double, seconds: Int = 3, sampleRate: Int = 48_000, spacingMeters: Double = 0.15, seed: Int = 18): ShortArray {
    val rnd = Random(seed)
    val tau = sin(degrees * PI / 180) * spacingMeters / AudioMath.SPEED_OF_SOUND
    val f = DoubleArray(60) { rnd.nextDouble(300.0, 5000.0) }; val ph = DoubleArray(60) { rnd.nextDouble(0.0, 2 * PI) }
    val n = sampleRate * seconds
    val out = ShortArray(n * 2)
    for (i in 0 until n) {
        val t = i.toDouble() / sampleRate
        var a = 0.0; var b = 0.0
        for (k in f.indices) { a += sin(2 * PI * f[k] * (t - tau) + ph[k]); b += sin(2 * PI * f[k] * t + ph[k]) }
        out[2 * i] = ((a / 60 + rnd.nextDouble(-0.002, 0.002)) * 32767 * 0.5).roundToInt().toShort()
        out[2 * i + 1] = ((b / 60 + rnd.nextDouble(-0.002, 0.002)) * 32767 * 0.5).roundToInt().toShort()
    }
    return out
}
