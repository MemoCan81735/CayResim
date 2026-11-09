package app.cayresim.core.pure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S-021: Urteil des Vergleichs ohne und mit Kamera, Richtung im Raum, Bildwinkel. */
class SoundCameraCheckTest {
    private fun phase(
        angle: Double? = 30.0, a: Double = -40.0, b: Double = -42.0, stereo: Boolean = true, frames: Int = 30,
        moved: Double? = 0.0, turn: Double? = 1.0,
    ) = SoundCameraCheck.Phase(frames, angle, 0.7, a, b, stereo, moved, turn, 2.0, -3.0)

    private fun verdict(off1: SoundCameraCheck.Phase? = phase(), on: SoundCameraCheck.Phase? = phase(), off2: SoundCameraCheck.Phase? = phase(), cameraFailed: Boolean = false) =
        SoundCameraCheck.verdict(off1, on, off2, cameraFailed)

    @Test fun `S-021 Urteil je Fall`() {

        assertEquals(SoundCameraCheck.Verdict.SAME, verdict())
        // Grenzfaelle: genau 5,0 Grad und 3,0 dB sind noch gleich, knapp darueber nicht
        assertEquals(SoundCameraCheck.Verdict.SAME, verdict(on = phase(angle = 35.0, a = -37.0, b = -45.0)))
        assertEquals(SoundCameraCheck.Verdict.CHANGED, verdict(on = phase(angle = 35.2)))
        assertEquals(SoundCameraCheck.Verdict.CHANGED, verdict(on = phase(a = -36.8)))
        assertEquals(SoundCameraCheck.Verdict.CHANGED, verdict(on = phase(b = -45.3)))
        // die Kamera schaltet auf ein Mikrofon
        assertEquals(SoundCameraCheck.Verdict.ONE_CHANNEL, verdict(on = phase(stereo = false, frames = 0, angle = null)))
        // Aufnahme scheitert oder ist stumm
        assertEquals(SoundCameraCheck.Verdict.NO_SOUND, verdict(on = null))
        assertEquals(SoundCameraCheck.Verdict.NO_SOUND, verdict(on = phase(a = -95.0, b = -100.0, angle = null)))
        assertEquals(SoundCameraCheck.Verdict.NO_SOUND, verdict(on = phase(frames = 0, angle = null)))
        // die Szene selbst hat sich geaendert
        assertEquals(SoundCameraCheck.Verdict.SCENE_UNSTEADY, verdict(off2 = phase(angle = 36.0)))
        assertEquals(SoundCameraCheck.Verdict.SCENE_UNSTEADY, verdict(off1 = phase(a = -36.5)))
        assertEquals(SoundCameraCheck.Verdict.SAME, verdict(off1 = phase(angle = 28.0), off2 = phase(angle = 33.0), on = phase(angle = 30.5)), "5 Grad zwischen den Phasen ohne Kamera gehen noch")
        // bewegt: in irgendeiner Phase
        assertEquals(SoundCameraCheck.Verdict.MOVED, verdict(off1 = phase(moved = 0.25)))
        assertEquals(SoundCameraCheck.Verdict.MOVED, verdict(on = phase(turn = 5.5)))
        assertEquals(SoundCameraCheck.Verdict.SAME, verdict(on = phase(moved = 0.2, turn = 5.0)), "Grenzen gehen noch")
        // ohne Lagesensor entfaellt "bewegt"
        assertEquals(SoundCameraCheck.Verdict.SAME, verdict(phase(moved = null, turn = null), phase(moved = null, turn = null), phase(moved = null, turn = null)))
        // keine Quelle ohne Kamera, oder die Aufnahme ohne Kamera scheiterte
        assertEquals(SoundCameraCheck.Verdict.NO_SOURCE, verdict(off1 = phase(angle = null)))
        assertEquals(SoundCameraCheck.Verdict.NO_SOURCE, verdict(off2 = null))
        // mit Kamera keine Richtung mehr, obwohl Ton da ist
        assertEquals(SoundCameraCheck.Verdict.CHANGED, verdict(on = phase(angle = null)))
        // Kamera lief nicht: das steht vor allem anderen
        assertEquals(SoundCameraCheck.Verdict.CAMERA_FAILED, verdict(on = null, cameraFailed = true))
        assertEquals(SoundCameraCheck.Verdict.CAMERA_FAILED, verdict(cameraFailed = true))
    }

    @Test fun `S-021 Phase aus Momenten`() {
        val acc = SoundCameraCheck.Accumulator()
        // 8 ruhige Momente mit Richtung, 2 bewegte (ohne Richtung gewertet), Pegel einmal ohne Ton
        repeat(8) { acc.add(28.0 + it % 2 * 4.0, 0.6, -40.0, -44.0, false, 1.0 + it * 0.2, 2.0, -4.0) }
        acc.add(80.0, 0.9, -20.0, -20.0, true, -4.0, 2.0, -4.0)
        acc.add(null, 0.0, Double.NEGATIVE_INFINITY, Double.NaN, true, 0.5, 2.0, -4.0)
        val p = acc.phase()
        assertEquals(10, p.frames); assertEquals(30.0, assertNotNull(p.angleDeg), 1e-9); assertEquals(0.6, p.share, 1e-9)
        assertEquals((8 * -40.0 - 20.0 - 120.0) / 10, p.levelADbfs, 1e-9); assertEquals((8 * -44.0 - 20.0 - 120.0) / 10, p.levelBDbfs, 1e-9)
        assertEquals(0.2, assertNotNull(p.movedShare), 1e-9); assertEquals(4.0, assertNotNull(p.turnDeg), 1e-9)
        assertEquals(2.0, assertNotNull(p.rollDeg), 1e-9); assertEquals(-4.0, assertNotNull(p.pitchDeg), 1e-9)
        assertTrue(p.stereo)
        // zu wenige Momente mit Richtung: keine Quelle
        val weak = SoundCameraCheck.Accumulator().apply {
            repeat(3) { add(30.0, 0.5, -50.0, -50.0, false, 0.0, 0.0, 0.0) }; repeat(7) { add(null, 0.0, -50.0, -50.0, false, 0.0, 0.0, 0.0) }
        }.phase()
        assertNull(weak.angleDeg)
        // ohne Lagesensor und ohne Momente
        val bare = SoundCameraCheck.Accumulator().apply { add(10.0, 0.5, -30.0, -30.0, null, null, null, null) }.phase(stereo = false)
        assertNull(bare.movedShare); assertNull(bare.turnDeg); assertNull(bare.rollDeg); assertEquals(false, bare.stereo)
        val empty = SoundCameraCheck.Accumulator().phase()
        assertEquals(0, empty.frames); assertTrue(empty.silent); assertNull(empty.angleDeg)
    }

    @Test fun `S-021 Richtung im Raum bleibt beim Drehen stehen`() {
        // Quelle fest bei +30 Grad im Raum; das Handy dreht in 5 Schritten nach rechts: die gemessene Richtung sinkt
        for (step in 0..5) {
            val yaw = step * 4.0
            val measured = 30.0 - yaw + if (step % 2 == 0) 1.5 else -1.5 // Messfehler des Schallbilds
            assertEquals(30.0, SoundCameraCheck.worldAngle(measured, yaw), 3.0, "Schritt $step")
        }
        assertEquals(10.0, 30.0 - 20.0, 0.0)
        assertEquals(-25.0, SoundCameraCheck.worldAngle(-10.0, -15.0), 1e-9)
    }
}

class ViewAngleTest {
    @Test fun `S-021 Bildwinkel aus Brennweite und Sensor`() {
        assertEquals(75.8, assertNotNull(ViewAngle.degrees(6.3, 9.8)), 0.1)
        assertEquals(60.5, assertNotNull(ViewAngle.degrees(6.3, 7.35)), 0.1)
        assertEquals(90.0, assertNotNull(ViewAngle.degrees(1.0, 2.0)), 1e-9)
        assertNull(ViewAngle.degrees(null, 9.8)); assertNull(ViewAngle.degrees(6.3, null))
        assertNull(ViewAngle.degrees(0.0, 9.8)); assertNull(ViewAngle.degrees(6.3, -1.0)); assertNull(ViewAngle.degrees(Double.NaN, 9.8))
    }
}
