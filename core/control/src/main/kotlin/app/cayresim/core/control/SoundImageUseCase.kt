package app.cayresim.core.control

import app.cayresim.core.boundary.AudioSourceKey
import app.cayresim.core.boundary.ComputeDispatcher
import app.cayresim.core.boundary.MicChunk
import app.cayresim.core.boundary.MicFailure
import app.cayresim.core.boundary.MicRequest
import app.cayresim.core.boundary.MicStreamEvent
import app.cayresim.core.boundary.MicrophoneBoundary
import app.cayresim.core.boundary.MotionKind
import app.cayresim.core.boundary.MotionSensorBoundary
import app.cayresim.core.pure.AudioMath
import app.cayresim.core.pure.Clock
import app.cayresim.core.pure.DeviceTilt
import app.cayresim.core.pure.SoundBands
import app.cayresim.core.pure.SoundCameraCheck
import app.cayresim.core.pure.SweepMath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.exp
import kotlin.math.log10
import javax.inject.Inject

enum class SoundImageFailure { NO_PERMISSION, NO_STEREO, RECORD_FAILED }

/** Haltung des Handys; UNKNOWN: kein Sensor oder Sensorfehler, gerechnet wird dann wie mit der Oberkante links. */
enum class SoundHold { TOP_LEFT, TOP_RIGHT, NOT_LANDSCAPE, UNKNOWN }

/**
 * Ein Band im Schallbild, aus Sicht des Nutzers. [angleDeg]: negativ links, positiv rechts, null wenn das Band nicht
 * gilt. [response]: Kurve von links nach rechts an [SoundBands.GRID] Stellen (gleichmaessig im Sinus des Winkels).
 */
data class SoundImageBand(
    val lowHz: Int, val highHz: Int, val levelDbfs: Double, val share: Double, val angleDeg: Double?, val response: List<Float>,
)

/**
 * Messwerte des Laufs, damit Zeit und Verluste auf dem Geraet sichtbar sind (R27, Zweitpruefung S-018): Momentaufnahmen
 * und mittlere Rechenzeit je Momentaufnahme in der letzten vollen Sekunde, verworfene Tonstuecke seit dem Start.
 */
data class SoundImageStats(val framesPerSecond: Int = 0, val computeMillis: Double = 0.0, val lostChunks: Long = 0)

/**
 * S-021: Lage des Handys zur Momentaufnahme, nur Zahlen (R23). [rollDeg] (Neigung quer, rechte Seite hoeher positiv)
 * und [pitchDeg] (Kippen, nach oben positiv) aus der Schwerkraft; null, wenn nicht quer oder zu flach. [yawDeg]
 * (Drehung seit dem Start, nach rechts positiv) und [turnRollDeg] (Schraegstellen seit dem Start) aus dem Drehvektor;
 * null ohne Drehvektor. [moving]: Stoss oder schnelle Drehung in der letzten halben Sekunde. [dotX], [dotY]: Punkt im
 * Lage-Kreis (1 = innerer Rand, y nach oben); [coverageMask]: besuchte Abschnitte des Rands (Bit je Abschnitt).
 */
data class SoundMotion(
    val rollDeg: Double?, val pitchDeg: Double?, val yawDeg: Double?, val turnRollDeg: Double?, val moving: Boolean,
    val dotX: Double, val dotY: Double, val coverageMask: Int,
)

/**
 * S-021: gemeinsamer Bezug der Drehung fuer mehrere Laeufe hintereinander (Messung mit Kamera: sechs Phasen, eine
 * Startlage). Ohne ihn beginnt jeder Lauf bei seiner ersten Lage.
 */
class TurnOrigin { internal val pose = AtomicReference<SweepMath.Pose?>(null) }

/** Nur Zahlen, Schluessel und Enums (R23). */
sealed interface SoundImageEvent {
    /**
     * Momentaufnahme, etwa 10 je Sekunde. Nicht quer gehalten: keine Baender. [motion]: null ohne Lagesensor oder nach
     * einem Sensorfehler. [worldAngleDeg]: Hauptrichtung im Raum (bleibt stehen, wenn das Handy dreht); null ohne
     * Richtung oder Drehung. [channelDbfs]: Pegel der beiden Kanaele im letzten Tonstueck. In bewegten Momenten
     * tragen die Baender keine Richtung.
     */
    data class Frame(
        val hold: SoundHold, val bands: List<SoundImageBand>, val mainAngleDeg: Double?, val mainShare: Double, val stats: SoundImageStats,
        val motion: SoundMotion?, val worldAngleDeg: Double?, val channelDbfs: List<Double>,
    ) : SoundImageEvent
    /** Ende des Schallbilds; [micFailure] nennt den Grund der Aufnahme. */
    data class Failed(val reason: SoundImageFailure, val micFailure: MicFailure? = null) : SoundImageEvent
}

/**
 * Schallbild (S-018, Stufe 1 der akustischen Kamera F11): laufender Ton aus zwei Mikrofonen, je Frequenzband die
 * Richtung zwischen links und rechts; die Schwerkraft sagt, welche Schmalseite links ist. Laeuft, bis der Sammler
 * abbricht; dann geben Mikrofon und Sensor ihre Adapter frei (R17, R28, R29). Ein Sensorfehler beendet nur die
 * Haltungserkennung (R14). Rechnen auf dem ComputeDispatcher (R16, R18); die Anzeige braucht nur den neuesten Stand.
 *
 * S-021: Der Lagesensor liefert zusaetzlich Neigung, Drehung seit dem Start, bewegte Momente und den Lage-Kreis.
 * [run]: jede Aenderung von `resets` setzt Startlage und Ring neu ("Neu ansetzen").
 */
class SoundImageUseCase @Inject constructor(
    private val mic: MicrophoneBoundary,
    private val sensors: MotionSensorBoundary,
    private val clock: Clock,
    @ComputeDispatcher private val compute: CoroutineDispatcher,
) {
    fun run(
        source: AudioSourceKey = AudioSourceKey.MIC, resets: StateFlow<Int> = MutableStateFlow(0), origin: TurnOrigin = TurnOrigin(),
    ): Flow<SoundImageEvent> = channelFlow {
        if (!mic.hasPermission()) { send(SoundImageEvent.Failed(SoundImageFailure.NO_PERMISSION)); return@channelFlow }
        val halfSpan = MicTestUseCase.spacing(mic.inventory()).first / AudioMath.SPEED_OF_SOUND
        val hold = AtomicReference(SoundHold.UNKNOWN)
        val motion = AtomicReference<SoundMotion?>(null)
        coroutineScope {
            val sensorJob = launch(start = CoroutineStart.UNDISPATCHED) {
                // auch die Abfrage selbst darf das Schallbild nicht beenden (R14)
                val available = runCatching { sensors.availability() }.getOrNull() ?: return@launch
                if (!available.acceleration && !available.rotation) return@launch
                var x = 0.0; var y = 0.0; var z = 0.0; var seen = false; var lastAccel = 0L
                var last = SoundBands.Hold.NOT_LANDSCAPE
                var tilt: DeviceTilt.Tilt? = null; var turn: DeviceTilt.Turn? = null
                var previous: SweepMath.Pose? = null; var turnHold = SoundBands.Hold.NOT_LANDSCAPE
                var resetSeen = resets.value
                val gate = DeviceTilt.MotionGate(); val coverage = DeviceTilt.RimCoverage()
                sensors.samples(SENSOR_PERIOD_MICROS).catch { hold.set(SoundHold.UNKNOWN); motion.set(null) }.collect { s ->
                    when (s.kind) {
                        MotionKind.ACCELERATION -> {
                            // Tiefpass: ein Ruck beim Halten dreht das Bild nicht um
                            if (!seen) { x = s.a.toDouble(); y = s.b.toDouble(); z = s.c.toDouble(); seen = true }
                            else {
                                val alpha = 1.0 - exp(-((s.nanos - lastAccel) / 1e9).coerceAtLeast(0.0) / DeviceTilt.GRAVITY_SECONDS)
                                x += alpha * (s.a - x); y += alpha * (s.b - y); z += alpha * (s.c - z)
                            }
                            lastAccel = s.nanos
                            last = SoundBands.hold(x, y, z, last)
                            hold.set(holdOf(last))
                            tilt = DeviceTilt.tilt(x, y, z, last)
                            gate.accel(s.nanos, s.a.toDouble(), s.b.toDouble(), s.c.toDouble())
                        }
                        MotionKind.ROTATION -> {
                            val pose = SweepMath.Pose(s.nanos, s.a.toDouble(), s.b.toDouble(), s.c.toDouble(), s.d.toDouble())
                            // Drehgeschwindigkeit ueber mindestens 40 ms, nicht aus zwei Nachbarwerten (Zweitpruefung S-021)
                            val from = previous
                            if (from == null) previous = pose
                            else if (s.nanos - from.nanos >= DeviceTilt.RATE_SPAN_NANOS) {
                                gate.rate(s.nanos, DeviceTilt.rateDegPerSecond(from, pose)); previous = pose
                            }
                            // neu ansetzen: auf Wunsch, und wenn das Handy in die andere Querhaltung gedreht wurde
                            val wanted = resets.value
                            val flipped = last != SoundBands.Hold.NOT_LANDSCAPE && turnHold != SoundBands.Hold.NOT_LANDSCAPE && last != turnHold
                            if (wanted != resetSeen || flipped) { resetSeen = wanted; origin.pose.set(pose); coverage.reset() }
                            if (last != SoundBands.Hold.NOT_LANDSCAPE) turnHold = last
                            val start = origin.pose.get() ?: pose.also { origin.pose.set(it) }
                            // ohne Schwerkraft-Wert gilt die Oberkante links, wie bei der Richtung
                            turn = DeviceTilt.turn(start, pose, if (turnHold == SoundBands.Hold.NOT_LANDSCAPE) SoundBands.Hold.TOP_LEFT else turnHold)
                            turn?.let { coverage.add(it) }
                        }
                    }
                    val dot = turn?.let { DeviceTilt.dot(it) }
                    motion.set(SoundMotion(tilt?.rollDeg, tilt?.pitchDeg, turn?.yawDeg, turn?.rollDeg, gate.moving(s.nanos),
                        dot?.first ?: 0.0, dot?.second ?: 0.0, coverage.mask))
                }
            }
            var analyzer: SoundBands.Analyzer? = null
            val stereo = StereoCheck()
            val meter = Meter(clock.nowMillis())
            mic.stream(MicRequest(source, sampleRate = SAMPLE_RATE, channels = 2), CHUNK_MILLIS)
                .transformWhile { e ->
                    when (e) {
                        is MicStreamEvent.Failed -> {
                            val reason = if (e.reason == MicFailure.NO_PERMISSION) SoundImageFailure.NO_PERMISSION else SoundImageFailure.RECORD_FAILED
                            emit(SoundImageEvent.Failed(reason, e.reason)); false
                        }
                        is MicStreamEvent.Chunk -> {
                            val c = e.chunk
                            if (c.channels < 2 || stereo.isMono(c)) { emit(SoundImageEvent.Failed(SoundImageFailure.NO_STEREO)); false }
                            else {
                                val a = analyzer ?: SoundBands.Analyzer(c.sampleRate, halfSpan).also { analyzer = it }
                                val t0 = clock.nowMillis()
                                a.add(c.pcm, c.channels)
                                val snapshot = a.snapshot()
                                emit(frame(snapshot, hold.get(), meter.add(c.index, t0, clock.nowMillis()), motion.get(), channelLevels(c))); true
                            }
                        }
                    }
                }
                .collect { send(it) }
            sensorJob.cancelAndJoin()
        }
    }.flowOn(compute).buffer(Channel.CONFLATED)

    /** Zaehlt je voller Sekunde Momentaufnahmen und Rechenzeit, dazu Luecken in den Nummern der Tonstuecke. */
    private class Meter(private var windowStart: Long) {
        private var frames = 0; private var computeSum = 0L; private var next = 0L; private var lost = 0L
        private var shown = SoundImageStats()
        fun add(index: Long, startMillis: Long, endMillis: Long): SoundImageStats {
            if (index > next) lost += index - next
            next = index + 1
            frames++; computeSum += endMillis - startMillis
            if (endMillis - windowStart >= WINDOW_MILLIS) {
                shown = SoundImageStats(frames, computeSum.toDouble() / frames, lost)
                frames = 0; computeSum = 0; windowStart = endMillis
            } else if (shown.lostChunks != lost) shown = shown.copy(lostChunks = lost)
            return shown
        }
    }

    /**
     * Erkennt doppeltes Mono (ein Mikrofon auf beiden Kanaelen, S-008: VOICE_RECOGNITION am S24+): in der ersten
     * halben Sekunde mit Ton sind alle Frames gleich. Digitale Stille entscheidet nichts.
     */
    private class StereoCheck {
        private var loud = 0L; private var same = 0L; private var decided = false
        fun isMono(c: MicChunk): Boolean {
            if (decided) return false
            val frames = c.pcm.size / c.channels
            for (f in 0 until frames) {
                val a = c.pcm[f * c.channels]; val b = c.pcm[f * c.channels + 1]
                if (a.toInt() == 0 && b.toInt() == 0) continue
                loud++; if (a == b) same++
            }
            if (loud < c.sampleRate / 2) return false
            decided = true
            return same >= loud * AudioMath.IDENTICAL_LIMIT
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        /** Ein Stueck Ton je Momentaufnahme: 10 Bilder je Sekunde. */
        const val CHUNK_MILLIS = 100
        /** S-021: 100 Werte je Sekunde, damit Stoesse und die Drehgeschwindigkeit sichtbar sind (vorher 20 fuer die Haltung). */
        const val SENSOR_PERIOD_MICROS = 10_000
        /** Fenster der Messwerte. */
        const val WINDOW_MILLIS = 1_000L

        fun holdOf(h: SoundBands.Hold): SoundHold = when (h) {
            SoundBands.Hold.TOP_LEFT -> SoundHold.TOP_LEFT
            SoundBands.Hold.TOP_RIGHT -> SoundHold.TOP_RIGHT
            SoundBands.Hold.NOT_LANDSCAPE -> SoundHold.NOT_LANDSCAPE
        }

        /** Pegel der ersten beiden Kanaele eines Tonstuecks in dBFS; ohne Ton minus unendlich. */
        fun channelLevels(c: MicChunk): List<Double> {
            val frames = c.pcm.size / c.channels
            if (frames == 0 || c.channels < 2) return listOf(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
            var a = 0.0; var b = 0.0
            for (f in 0 until frames) {
                val va = c.pcm[f * c.channels] / 32768.0; val vb = c.pcm[f * c.channels + 1] / 32768.0
                a += va * va; b += vb * vb
            }
            return listOf(10.0 * log10(a / frames), 10.0 * log10(b / frames))
        }

        /**
         * Dreht die Rechnung (Kosinus zur Mikrofonachse) in die Sicht des Nutzers (links negativ, rechts positiv). In
         * bewegten Momenten (S-021) bleiben Pegel und Kurven, aber keine Richtung: ein Stoss saehe sonst wie eine Quelle aus.
         */
        fun frame(
            s: SoundBands.Snapshot, hold: SoundHold, stats: SoundImageStats = SoundImageStats(), motion: SoundMotion? = null,
            channelDbfs: List<Double> = emptyList(),
        ): SoundImageEvent.Frame {
            val sign = when (hold) {
                SoundHold.TOP_LEFT, SoundHold.UNKNOWN -> SoundBands.screenSign(SoundBands.Hold.TOP_LEFT)
                SoundHold.TOP_RIGHT -> SoundBands.screenSign(SoundBands.Hold.TOP_RIGHT)
                SoundHold.NOT_LANDSCAPE -> return SoundImageEvent.Frame(hold, emptyList(), null, 0.0, stats, motion, null, channelDbfs)
            }
            val calm = motion?.moving != true
            val bands = s.bands.map { b ->
                SoundImageBand(b.lowHz, b.highHz, b.levelDbfs, b.share, if (calm) b.cosine?.let { SoundBands.angleDegrees(it) * sign } else null,
                    if (sign < 0) b.response.asReversed().toList() else b.response)
            }
            val main = if (calm) s.mainCosine?.let { SoundBands.angleDegrees(it) * sign } else null
            val world = if (main != null) motion?.yawDeg?.let { SoundCameraCheck.worldAngle(main, it) } else null
            return SoundImageEvent.Frame(hold, bands, main, if (calm) s.mainShare else 0.0, stats, motion, world, channelDbfs)
        }
    }
}
