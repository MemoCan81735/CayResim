package app.cayresim.core.pure

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S-021: Lage des Handys. Die Erwartung kommt aus gedrehten Achsen (Rodrigues), nicht aus derselben Formel. */
class DeviceTiltTest {
    // Welt wie Android: x Osten, y Norden, z oben. Der Nutzer schaut nach Norden.
    private val east = Vec3(1.0, 0.0, 0.0); private val north = Vec3(0.0, 1.0, 0.0); private val upW = Vec3(0.0, 0.0, 1.0)
    private val back = Vec3(0.0, -1.0, 0.0)

    private fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z
    private fun cross(a: Vec3, b: Vec3) = Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

    /** Dreht [v] um die Achse [k] (Laenge 1) um [deg] nach der Rechte-Hand-Regel. */
    private fun rot(k: Vec3, deg: Double, v: Vec3): Vec3 {
        val t = Math.toRadians(deg)
        return v * cos(t) + cross(k, v) * sin(t) + k * (dot(k, v) * (1 - cos(t)))
    }

    /** Geraeteachsen in der Welt fuer ein quer und aufrecht gehaltenes Handy. */
    private class Frame(val x: Vec3, val y: Vec3, val z: Vec3)
    private fun upright(hold: SoundBands.Hold) =
        if (hold == SoundBands.Hold.TOP_LEFT) Frame(upW, east * -1.0, back) else Frame(upW * -1.0, east, back)
    private fun Frame.turned(k: Vec3, deg: Double) = Frame(rot(k, deg, x), rot(k, deg, y), rot(k, deg, z))
    /** Schwerkraft-Messwert: "oben" in Geraeteachsen, mal 9,81. */
    private fun Frame.gravity() = Vec3(dot(upW, x), dot(upW, y), dot(upW, z)) * 9.81

    /** Quaternion (Geraet in Welt) aus den Geraeteachsen in der Welt. */
    private fun Frame.pose(nanos: Long = 0): SweepMath.Pose {
        val m00 = x.x; val m01 = y.x; val m02 = z.x; val m10 = x.y; val m11 = y.y; val m12 = z.y; val m20 = x.z; val m21 = y.z; val m22 = z.z
        val tr = m00 + m11 + m22
        return when {
            tr > 0 -> { val s = sqrt(tr + 1.0) * 2; SweepMath.Pose(nanos, s / 4, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s) }
            m00 > m11 && m00 > m22 -> { val s = sqrt(1.0 + m00 - m11 - m22) * 2; SweepMath.Pose(nanos, (m21 - m12) / s, s / 4, (m01 + m10) / s, (m02 + m20) / s) }
            m11 > m22 -> { val s = sqrt(1.0 + m11 - m00 - m22) * 2; SweepMath.Pose(nanos, (m02 - m20) / s, (m01 + m10) / s, s / 4, (m12 + m21) / s) }
            else -> { val s = sqrt(1.0 + m22 - m00 - m11) * 2; SweepMath.Pose(nanos, (m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, s / 4) }
        }
    }

    // Bewegungen aus Sicht des Nutzers: rechte Seite hoch = Drehung um die Achse zum Nutzer; nach oben schauen = um
    // "rechts"; nach rechts drehen = um "oben" mit negativem Winkel.
    private fun Frame.rollRightUp(deg: Double) = turned(back, deg)
    private fun Frame.lookUp(deg: Double) = turned(east, deg)
    private fun Frame.turnRight(deg: Double) = turned(upW, -deg)

    @Test fun `S-021 Neigung quer und Kippen aus der Schwerkraft`() {
        for (hold in listOf(SoundBands.Hold.TOP_LEFT, SoundBands.Hold.TOP_RIGHT)) {
            val g0 = upright(hold).gravity()
            val flat = assertNotNull(DeviceTilt.tilt(g0.x, g0.y, g0.z, hold))
            assertEquals(0.0, flat.rollDeg, 0.01); assertEquals(0.0, flat.pitchDeg, 0.01)
            // erst 20 Grad nach oben schauen, dann 10 Grad um die Blickachse drehen (so liegt der Horizont schraeg im
            // Bild); und umgekehrte Vorzeichen
            for ((roll, pitch) in listOf(10.0 to 20.0, -10.0 to -20.0, 25.0 to 0.0, 0.0 to -35.0)) {
                val g = upright(hold).lookUp(pitch).turned(rot(east, pitch, back), roll).gravity()
                val t = assertNotNull(DeviceTilt.tilt(g.x, g.y, g.z, hold), "$hold $roll $pitch")
                assertEquals(roll, t.rollDeg, 0.5, "$hold Neigung quer"); assertEquals(pitch, t.pitchDeg, 0.5, "$hold Kippen")
            }
        }
        // flach auf dem Tisch, hochkant und Unsinn: keine Aussage
        assertNull(DeviceTilt.tilt(0.0, 0.0, 9.81, SoundBands.Hold.TOP_LEFT))
        assertNull(DeviceTilt.tilt(9.81, 0.0, 0.0, SoundBands.Hold.NOT_LANDSCAPE))
        assertNull(DeviceTilt.tilt(0.0, 0.0, 0.0, SoundBands.Hold.TOP_LEFT))
        assertNull(DeviceTilt.tilt(Double.NaN, 0.0, 0.0, SoundBands.Hold.TOP_LEFT))
        // 65 Grad nach oben: noch eine Aussage waere ungenau (Anteil in der Ebene 0,42)
        val steep = upright(SoundBands.Hold.TOP_LEFT).lookUp(65.0).gravity()
        assertNull(DeviceTilt.tilt(steep.x, steep.y, steep.z, SoundBands.Hold.TOP_LEFT))
    }

    @Test fun `S-021 Drehung seit dem Start und Drehgeschwindigkeit`() {
        for (hold in listOf(SoundBands.Hold.TOP_LEFT, SoundBands.Hold.TOP_RIGHT)) {
            val start = upright(hold)
            val right = assertNotNull(DeviceTilt.turn(start.pose(), start.turnRight(25.0).pose(), hold))
            assertEquals(25.0, right.yawDeg, 0.5, "$hold"); assertEquals(0.0, right.rollDeg, 0.5); assertEquals(0.0, right.pitchDeg, 0.5)
            val left = assertNotNull(DeviceTilt.turn(start.pose(), start.turnRight(-12.0).pose(), hold))
            assertEquals(-12.0, left.yawDeg, 0.5)
            val rolled = assertNotNull(DeviceTilt.turn(start.pose(), start.rollRightUp(15.0).pose(), hold))
            assertEquals(15.0, rolled.rollDeg, 0.5, "$hold"); assertEquals(0.0, rolled.yawDeg, 0.5)
            val up = assertNotNull(DeviceTilt.turn(start.pose(), start.lookUp(20.0).pose(), hold))
            assertEquals(20.0, up.pitchDeg, 0.5); assertEquals(0.0, up.yawDeg, 0.5); assertEquals(0.0, up.rollDeg, 0.5)
            // der Start muss nicht aufrecht sein: gegenueber einer schon gedrehten Lage zaehlt nur der Unterschied
            val from = start.turnRight(40.0)
            assertEquals(10.0, assertNotNull(DeviceTilt.turn(from.pose(), start.turnRight(50.0).pose(), hold)).yawDeg, 0.5)
        }
        assertNull(DeviceTilt.turn(upright(SoundBands.Hold.TOP_LEFT).pose(), upright(SoundBands.Hold.TOP_LEFT).pose(), SoundBands.Hold.NOT_LANDSCAPE))
        // 0,3 Grad in 10 ms sind 30 Grad je Sekunde
        val a = upright(SoundBands.Hold.TOP_LEFT)
        val rate = DeviceTilt.rateDegPerSecond(a.pose(1_000_000_000L), a.turnRight(0.3).pose(1_010_000_000L))
        assertEquals(30.0, rate, 1.5)
        assertEquals(0.0, DeviceTilt.rateDegPerSecond(a.pose(5), a.turnRight(3.0).pose(5)), 0.0)
        // q und -q sind dieselbe Lage
        val p = a.pose(0); val q = a.pose(10_000_000L).let { it.copy(w = -it.w, x = -it.x, y = -it.y, z = -it.z) }
        assertEquals(0.0, DeviceTilt.rateDegPerSecond(p, q), 1e-6)
    }

    @Test fun `S-021 bewegte Momente`() {
        val step = 10_000_000L
        // ruhige Hand: 0,2 m/s^2 Zittern, 3 Grad je Sekunde
        val calm = DeviceTilt.MotionGate()
        for (i in 0 until 300) {
            calm.accel(i * step, 9.81 + 0.2 * sin(i * 0.7), 0.1 * cos(i * 0.9), 0.0); calm.rate(i * step, 3.0)
            assertFalse(calm.moving(i * step), "ruhig bei $i")
        }
        // Tippen: ein Stoss von 3 m/s^2
        val tap = DeviceTilt.MotionGate()
        for (i in 0 until 100) tap.accel(i * step, 9.81, 0.0, 0.0)
        tap.accel(100 * step, 9.81, 0.0, 3.0)
        assertTrue(tap.moving(100 * step)); assertTrue(tap.moving(100 * step + 400_000_000L), "haelt 0,5 s")
        assertFalse(tap.moving(100 * step + 600_000_000L))
        // schnelle Drehung
        val spin = DeviceTilt.MotionGate()
        spin.rate(0, 29.0); assertFalse(spin.moving(0))
        spin.rate(step, 45.0); assertTrue(spin.moving(step))
        // langsames Schwenken mit 15 Grad je Sekunde dreht die Schwerkraft mit, ohne als Stoss zu zaehlen
        val sweep = DeviceTilt.MotionGate()
        for (i in 0 until 400) {
            val t = Math.toRadians(15.0 * i / 100.0)
            sweep.accel(i * step, 9.81 * cos(t), 9.81 * sin(t), 0.0); sweep.rate(i * step, 15.0)
            assertFalse(sweep.moving(i * step), "Schwenk bei $i")
        }
    }

    @Test fun `S-021 Punkt im Lage-Kreis`() {
        val hold = SoundBands.Hold.TOP_LEFT; val start = upright(hold)
        fun dotOf(f: Frame) = DeviceTilt.dot(assertNotNull(DeviceTilt.turn(start.pose(), f.pose(), hold)))
        val right = dotOf(start.turnRight(15.0)); assertEquals(1.0, right.first, 0.02); assertEquals(0.0, right.second, 0.02)
        val top = dotOf(start.rollRightUp(15.0)); assertEquals(0.0, top.first, 0.02); assertEquals(1.0, top.second, 0.02)
        val left = dotOf(start.turnRight(-15.0)); assertEquals(-1.0, left.first, 0.02)
        // weit ueber den Rand: bleibt am Rand des Felds
        val far = dotOf(start.turnRight(60.0)); assertTrue(hypot(far.first, far.second) <= 1.334, "$far"); assertTrue(far.first > 1.3)
        // Kippen allein bewegt den Punkt nicht
        val tipped = dotOf(start.lookUp(25.0)); assertTrue(abs(tipped.first) <= 0.02 && abs(tipped.second) <= 0.02, "$tipped")
        assertEquals(0.0 to 0.0, DeviceTilt.dot(DeviceTilt.Turn(0.0, 0.0, 0.0)))
    }

    @Test fun `S-021 Abdeckung des Rands`() {
        fun round(radius: Double, fromDeg: Int, toDeg: Int) = DeviceTilt.RimCoverage().apply {
            for (a in fromDeg..toDeg step 3) add(DeviceTilt.Turn(radius * cos(Math.toRadians(a.toDouble())), radius * sin(Math.toRadians(a.toDouble())), 7.0))
        }
        val full = round(15.0, 0, 359); assertEquals(12, full.count); assertTrue(full.complete)
        val small = round(5.0, 0, 359); assertEquals(0, small.count); assertFalse(small.complete)
        assertEquals(0, round(25.0, 0, 359).count, "weit ausserhalb zaehlt nicht")
        val half = round(15.0, 0, 180); assertTrue(half.count in 6..7, "halbe Runde: ${half.count}"); assertFalse(half.complete)
        // Abschnitt 0 liegt rechts, 3 oben
        assertEquals(1, DeviceTilt.RimCoverage().apply { add(DeviceTilt.Turn(15.0, 1.0, 0.0)) }.mask)
        assertEquals(1 shl 3, DeviceTilt.RimCoverage().apply { add(DeviceTilt.Turn(-0.5, 15.0, 0.0)) }.mask)
        full.reset(); assertEquals(0, full.mask)
    }

    @Test fun `S-021 Rechenzeit der Lage`() {
        // R27: Neigung, Drehung, Punkt, Abdeckung und Bewegung je Moment; bester von 15 nach 20 Aufwaermlaeufen
        val hold = SoundBands.Hold.TOP_LEFT; val start = upright(hold).pose()
        val poses = List(1000) { upright(hold).turnRight(it * 0.01).pose(it * 10_000_000L) }
        val gate = DeviceTilt.MotionGate(); val cover = DeviceTilt.RimCoverage()
        fun once(): Double {
            val t0 = System.nanoTime(); var sum = 0.0
            for (i in 1 until poses.size) {
                val turn = assertNotNull(DeviceTilt.turn(start, poses[i], hold))
                gate.accel(poses[i].nanos, 9.81, 0.01 * i, 0.0); gate.rate(poses[i].nanos, DeviceTilt.rateDegPerSecond(poses[i - 1], poses[i]))
                cover.add(turn); sum += DeviceTilt.dot(turn).first + (DeviceTilt.tilt(9.81, 0.5, 0.2, hold)?.rollDeg ?: 0.0)
            }
            check(sum.isFinite())
            return (System.nanoTime() - t0) / 1e6 / (poses.size - 1)
        }
        repeat(20) { once() }
        val runs = (0 until 15).map { once() }
        val best = runs.min()
        println("S-021 K14: Lage je Moment ${"%.4f".format(best)} ms (15 Laeufe, groesster ${"%.4f".format(runs.max())})")
        assertTrue(best <= 0.1, "Lage je Moment $best ms")
    }
}
