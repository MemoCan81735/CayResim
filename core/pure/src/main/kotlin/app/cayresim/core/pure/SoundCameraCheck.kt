package app.cayresim.core.pure

import kotlin.math.abs
import kotlin.math.atan

/** S-021: Bildwinkel einer Kamera aus Brennweite und Sensorgroesse (ohne Zuschnitt des Sucherbilds). */
object ViewAngle {
    /** Winkel in Grad ueber die Kante [sizeMm]; null bei fehlenden oder unsinnigen Werten. */
    fun degrees(focalLengthMm: Double?, sizeMm: Double?): Double? {
        if (focalLengthMm == null || sizeMm == null) return null
        if (!focalLengthMm.isFinite() || !sizeMm.isFinite() || focalLengthMm <= 0.0 || sizeMm <= 0.0) return null
        return Math.toDegrees(2.0 * atan(sizeMm / (2.0 * focalLengthMm)))
    }
}

/**
 * S-021: Vergleich des Schallbilds ohne und mit laufender Kamera. Je Tonquelle drei Phasen (ohne, mit, ohne); die
 * beiden Phasen ohne Kamera zeigen, ob die Szene selbst ruhig war.
 */
object SoundCameraCheck {
    /** Groesste Abweichung der Richtung und des Pegels je Kanal fuer "gleich". Startwerte, nach dem Geraetetest pruefen. */
    const val ANGLE_LIMIT_DEG = 5.0
    const val LEVEL_LIMIT_DB = 3.0
    /** Mehr bewegte Momente oder mehr Drehung seit dem Start: die Messung gilt nicht. */
    const val MOVED_SHARE_LIMIT = 0.2
    const val TURN_LIMIT_DEG = 5.0
    /** So viele der ruhigen Momente muessen eine Richtung haben, damit die Phase eine Quelle hat. */
    const val MIN_DIRECTED_SHARE = 0.5
    /** Beide Kanaele darunter: stumm. */
    const val SILENT_DBFS = -90.0
    private const val EPS = 1e-9

    /**
     * Eine Phase. [frames]: gewertete Momente. [angleDeg]: mittlere Hauptrichtung, null ohne Quelle. [movedShare],
     * [turnDeg] (groesste Drehung seit dem Start der Messung), [rollDeg], [pitchDeg]: null ohne Lagesensor.
     */
    data class Phase(
        val frames: Int, val angleDeg: Double?, val share: Double, val levelADbfs: Double, val levelBDbfs: Double, val stereo: Boolean,
        val movedShare: Double?, val turnDeg: Double?, val rollDeg: Double?, val pitchDeg: Double?,
    ) {
        val silent: Boolean get() = frames == 0 || (levelADbfs < SILENT_DBFS && levelBDbfs < SILENT_DBFS)
    }

    enum class Verdict { SAME, CHANGED, ONE_CHANNEL, NO_SOUND, SCENE_UNSTEADY, MOVED, NO_SOURCE, CAMERA_FAILED }

    /** Sammelt die Momente einer Phase. */
    class Accumulator {
        private var frames = 0; private var moved = 0; private var motionSeen = false
        private var angleSum = 0.0; private var shareSum = 0.0; private var directed = 0
        private var aSum = 0.0; private var bSum = 0.0
        private var turn: Double? = null
        private var rollSum = 0.0; private var pitchSum = 0.0; private var tilts = 0

        /** [moving], [yawDeg], [rollDeg], [pitchDeg]: null ohne Lagesensor. Pegel ohne Ton (minus unendlich) zaehlen als -120. */
        fun add(
            angleDeg: Double?, share: Double, levelADbfs: Double, levelBDbfs: Double,
            moving: Boolean?, yawDeg: Double?, rollDeg: Double?, pitchDeg: Double?,
        ) {
            frames++
            if (moving != null) { motionSeen = true; if (moving) moved++ }
            if (angleDeg != null && moving != true) { angleSum += angleDeg; shareSum += share; directed++ }
            aSum += finite(levelADbfs); bSum += finite(levelBDbfs)
            if (yawDeg != null) turn = maxOf(turn ?: 0.0, abs(yawDeg))
            if (rollDeg != null && pitchDeg != null) { rollSum += rollDeg; pitchSum += pitchDeg; tilts++ }
        }

        private fun finite(db: Double) = if (db.isNaN() || db < -120.0) -120.0 else db

        fun phase(stereo: Boolean = true): Phase {
            val calm = frames - moved
            val hasSource = directed > 0 && directed >= MIN_DIRECTED_SHARE * calm
            return Phase(
                frames, if (hasSource) angleSum / directed else null, if (directed > 0) shareSum / directed else 0.0,
                if (frames > 0) aSum / frames else -120.0, if (frames > 0) bSum / frames else -120.0, stereo,
                if (motionSeen && frames > 0) moved.toDouble() / frames else null, turn,
                if (tilts > 0) rollSum / tilts else null, if (tilts > 0) pitchSum / tilts else null,
            )
        }
    }

    /** Richtung im Raum: bleibt stehen, wenn sich das Handy dreht (Quelle rechts, Handy dreht nach rechts: Richtung sinkt). */
    fun worldAngle(angleDeg: Double, yawDeg: Double): Double = angleDeg + yawDeg

    /**
     * Genau ein Urteil. [off1], [on], [off2]: Phasen ohne, mit, ohne Kamera; null, wenn die Aufnahme scheiterte.
     * [cameraFailed]: die Kamera lief in der mittleren Phase nicht.
     */
    fun verdict(off1: Phase?, on: Phase?, off2: Phase?, cameraFailed: Boolean): Verdict {
        if (cameraFailed) return Verdict.CAMERA_FAILED
        if (on == null) return Verdict.NO_SOUND
        if (!on.stereo) return Verdict.ONE_CHANNEL
        if (on.silent) return Verdict.NO_SOUND
        if (listOfNotNull(off1, on, off2).any { p -> (p.movedShare ?: 0.0) > MOVED_SHARE_LIMIT + EPS || (p.turnDeg ?: 0.0) > TURN_LIMIT_DEG + EPS }) return Verdict.MOVED
        val a1 = off1?.angleDeg; val a2 = off2?.angleDeg
        if (off1 == null || off2 == null || a1 == null || a2 == null) return Verdict.NO_SOURCE
        if (abs(a1 - a2) > ANGLE_LIMIT_DEG + EPS || abs(off1.levelADbfs - off2.levelADbfs) > LEVEL_LIMIT_DB + EPS ||
            abs(off1.levelBDbfs - off2.levelBDbfs) > LEVEL_LIMIT_DB + EPS) return Verdict.SCENE_UNSTEADY
        val angle = on.angleDeg ?: return Verdict.CHANGED
        val same = abs(angle - (a1 + a2) / 2) <= ANGLE_LIMIT_DEG + EPS &&
            abs(on.levelADbfs - (off1.levelADbfs + off2.levelADbfs) / 2) <= LEVEL_LIMIT_DB + EPS &&
            abs(on.levelBDbfs - (off1.levelBDbfs + off2.levelBDbfs) / 2) <= LEVEL_LIMIT_DB + EPS
        return if (same) Verdict.SAME else Verdict.CHANGED
    }
}
