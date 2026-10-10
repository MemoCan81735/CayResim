package app.cayresim.core.control

import app.cayresim.core.boundary.MicFailure
import app.cayresim.core.boundary.MicRequest
import app.cayresim.core.boundary.MotionAvailability
import app.cayresim.core.boundary.MotionKind
import app.cayresim.core.boundary.MotionSample
import app.cayresim.core.boundary.fake.FakeAudioFileBoundary
import app.cayresim.core.boundary.fake.FakeMicrophoneBoundary
import app.cayresim.core.boundary.fake.FakeMotionSensorBoundary
import app.cayresim.core.pure.AudioMath
import app.cayresim.core.pure.SweepMath
import app.cayresim.core.pure.Vec3
import app.cayresim.core.pure.Wav
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S-010 K6: Schwenk-Messung mit Fakes; Ton und Lage sind kuenstlich (CLAUDE.md Abschnitt 7). */
class SweepUseCaseTest {
    private val start = 1_000_000_000L
    private val sr = 48_000
    private val source = Vec3(0.5, 0.8, 0.2).normalized()

    private suspend fun here(): CoroutineDispatcher = currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher

    /** Quaternion (w, x, y, z) einer Drehung um eine Weltachse. */
    private fun axisAngle(ax: Double, ay: Double, az: Double, deg: Double): DoubleArray {
        val h = deg * PI / 360; val s = sin(h)
        return doubleArrayOf(cos(h), ax * s, ay * s, az * s)
    }

    private fun mul(p: DoubleArray, q: DoubleArray) = doubleArrayOf(
        p[0] * q[0] - p[1] * q[1] - p[2] * q[2] - p[3] * q[3],
        p[0] * q[1] + p[1] * q[0] + p[2] * q[3] - p[3] * q[2],
        p[0] * q[2] - p[1] * q[3] + p[2] * q[0] + p[3] * q[1],
        p[0] * q[3] + p[1] * q[2] - p[2] * q[1] + p[3] * q[0],
    )

    /** Schwenk: Gieren +-50 Grad um z, Nicken +-35 Grad um x; Lage je 10 ms, dazu Beschleunigung mit zwei Klopfern. */
    private fun motion(): List<MotionSample> {
        val out = mutableListOf<MotionSample>()
        for (i in -10..2_510) {
            val t = i / 100.0
            val q = mul(axisAngle(0.0, 0.0, 1.0, 50 * sin(2 * PI * t / 9)), axisAngle(1.0, 0.0, 0.0, 35 * sin(2 * PI * t / 6)))
            val nanos = start + i * 10_000_000L
            out += MotionSample(MotionKind.ROTATION, nanos, q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat())
            val tap = i == 100 || i == 160
            out += MotionSample(MotionKind.ACCELERATION, nanos, 0f, 0f, if (tap) 16f else 9.81f)
        }
        return out
    }

    /** Ton passend zur Lage: Kanal 0 hoert um 15 cm / c * (Achse . Quelle) spaeter; Klopfer bei 1,0 und 1,6 s. */
    private fun sweepSound(samples: List<MotionSample>): (MicRequest, Int) -> ShortArray = { r, ch ->
        val n = r.sampleRate * r.millis / 1000
        val rnd = Random(12)
        val src = DoubleArray(n + 200) { rnd.nextDouble(-3000.0, 3000.0) }
        val poses = samples.filter { it.kind == MotionKind.ROTATION }.map { SweepMath.Pose(it.nanos, it.a.toDouble(), it.b.toDouble(), it.c.toDouble(), it.d.toDouble()) }
        val pcm = ShortArray(n * ch)
        for (i in 0 until n) {
            val pose = poses[10 + i / 480]
            val lag = Math.round(0.15 / AudioMath.SPEED_OF_SOUND * SweepMath.rotate(pose, SweepMath.MIC_AXIS_DEVICE).dot(source) * r.sampleRate).toInt()
            pcm[ch * i] = src[i + 100 - lag].toInt().toShort(); pcm[ch * i + 1] = src[i + 100].toInt().toShort()
        }
        for (at in listOf(r.sampleRate, r.sampleRate * 16 / 10)) for (i in 0 until 240) {
            // Klopfer auf das Gehaeuse: fast Vollaussteuerung, klingt in etwa 2 ms ab
            val v = (30_000 * kotlin.math.exp(-i / 80.0) * if (i % 2 == 0) 1 else -1).toInt().toShort()
            pcm[ch * (at + i)] = v; pcm[ch * (at + i) + 1] = v
        }
        pcm
    }

    private fun setup(): Triple<FakeMicrophoneBoundary, FakeMotionSensorBoundary, FakeAudioFileBoundary> {
        val samples = motion()
        val sensors = FakeMotionSensorBoundary(samples = samples)
        val mic = FakeMicrophoneBoundary().apply { sound = sweepSound(samples); startBootNanos = start }
        return Triple(mic, sensors, FakeAudioFileBoundary())
    }

    private suspend fun report(mic: FakeMicrophoneBoundary, sensors: FakeMotionSensorBoundary, files: FakeAudioFileBoundary) =
        SweepUseCase(mic, sensors, files, here()).run().filterIsInstance<SweepEvent.Done>().first().report

    @Test fun `S-010 Ton und Lage laufen gleichzeitig und werden gespeichert`() = runTest {
        val (mic, sensors, files) = setup()
        var activeDuringRecord = -1
        mic.onRecord = { activeDuringRecord = sensors.active }
        val r = report(mic, sensors, files)
        assertEquals(1, activeDuringRecord, "Sensor laeuft waehrend der Aufnahme")
        assertEquals(0, sensors.active, "Sensor danach abgemeldet"); assertEquals(0, mic.open)
        assertEquals(SweepUseCase.TOTAL_SECONDS * 1000, mic.requests.single().millis)
        assertNull(r.failure)
        val a = assertNotNull(r.analysis)
        val e = assertNotNull(a.estimate as? SweepMath.Estimate.Ok, "Ergebnis ${a.estimate}")
        val err = acos(e.direction.dot(source).coerceIn(-1.0, 1.0)) * 180 / PI
        assertTrue(err < 10, "Richtung $err Grad daneben")
        assertEquals(0.0, assertNotNull(a.syncSeconds), 0.012)
        assertTrue(r.timeExact)
        // eine WAV mit Lage und Kenndaten
        val (name, bytes) = files.files.entries.single()
        assertTrue(name.endsWith("schwenk-v1.wav"), name)
        assertEquals(2, assertNotNull(Wav.decode(bytes)).channels)
        val chunks = Wav.chunks(bytes)
        val lage = String(chunks.getValue("lage")).lines()
        assertEquals("format=v1", lage.first())
        assertEquals(sensors.samples.size, lage.count { it.startsWith("ROTATION,") || it.startsWith("ACCELERATION,") })
        val meta = String(chunks.getValue("meta"))
        assertTrue("\"format\": 1" in meta && "\"startBootNanos\": $start" in meta && "\"sampleRate\": $sr" in meta, meta)
        assertTrue("\"rotationSource\": \"GAME\"" in meta && "NaN" !in meta && "Infinity" !in meta, meta)
        assertFalse(r.storageFailed)
    }

    @Test fun `S-012 Signalstaerke in meta`() = runTest {
        val (mic, sensors, files) = setup()
        val r = report(mic, sensors, files)
        val a = assertNotNull(r.analysis)
        assertTrue(a.peakMedian > 0.5, "Median ${a.peakMedian}")
        val meta = String(Wav.chunks(files.files.values.single()).getValue("meta"))
        val m = assertNotNull(Regex("\"peakMedian\": ([0-9.eE+-]+)").find(meta), meta)
        assertEquals(a.peakMedian, m.groupValues[1].toDouble(), 1e-6)
        assertTrue("\"format\": 1" in meta, meta)
    }

    @Test fun `S-014 Ablauf mit Abdeckung`() = runTest {
        // ersetzt "S-010 Ansagen in fester Reihenfolge": statt zwei Ansagen ein Fortschritt im festen Ablauf
        val (mic, sensors, files) = setup()
        sensors.paced = true
        mic.recordDelayMillis = SweepUseCase.TOTAL_SECONDS * 1000L
        val events = SweepUseCase(mic, sensors, files, here()).run().toList()
        val progress = events.filterIsInstance<SweepEvent.Progress>()
        assertTrue(progress.size >= SweepUseCase.TOTAL_SECONDS * 4 - 2, "Fortschritt mindestens alle 250 ms: ${progress.size}")
        // Bewegungen in fester Reihenfolge, jede kommt vor
        val moves = progress.map { it.move }.fold(mutableListOf<app.cayresim.core.pure.SweepGuide.Move>()) { l, m -> if (l.lastOrNull() != m) l += m; l }
        assertEquals(app.cayresim.core.pure.SweepGuide.Move.entries, moves)
        val first = progress.first()
        assertEquals(app.cayresim.core.pure.SweepGuide.Move.TAP, first.move); assertEquals(3, first.secondsLeft)
        assertEquals(app.cayresim.core.pure.SweepGuide.Move.YAW, first.next)
        // Abdeckung erst nach der Klopfphase, steigt; der kuenstliche Schwenk (ohne Rollen) erreicht etwa 0,048
        assertTrue(progress.filter { it.move == app.cayresim.core.pure.SweepGuide.Move.TAP }.all { it.coverage == 0.0 })
        val early = progress.first { it.move == app.cayresim.core.pure.SweepGuide.Move.ROLL }.coverage
        val last = progress.last().coverage
        assertTrue(last > 0.03 && last > early, "Abdeckung ${progress.map { "%.3f".format(it.coverage) }}")
        val i = events.indexOfFirst { it is SweepEvent.Analyzing }
        assertTrue(i > 0 && events.subList(i, events.size).none { it is SweepEvent.Progress }, "nach der Auswertung kein Fortschritt mehr")
        assertTrue(events.last() is SweepEvent.Done)
        val meta = String(Wav.chunks(files.files.values.single()).getValue("meta"))
        assertTrue("\"guide\": \"v1\"" in meta, meta)
    }

    @Test fun `S-010 Abbruch gibt Mikrofon und Sensor frei`() = runTest {
        val (mic, sensors, files) = setup()
        mic.recordDelayMillis = SweepUseCase.TOTAL_SECONDS * 1000L
        val job = launch { SweepUseCase(mic, sensors, files, here()).run().toList() }
        advanceTimeBy(5_000)
        assertEquals(1, mic.open, "Aufnahme laeuft"); assertEquals(1, sensors.active, "Sensor laeuft")
        job.cancel(); job.join()
        assertEquals(0, mic.open, "Mikrofon frei"); assertEquals(0, sensors.active, "Sensor abgemeldet")
        assertTrue(files.files.isEmpty(), "nach Abbruch nichts gespeichert")
    }

    @Test fun `S-010 Abbruch waehrend der Auswertung speichert nichts`() = runTest {
        val (mic, sensors, files) = setup()
        val job = launch { SweepUseCase(mic, sensors, files, here()).run().collect { if (it is SweepEvent.Analyzing) cancel() } }
        job.join()
        assertTrue(files.files.isEmpty(), "nach Abbruch nichts gespeichert")
        assertEquals(0, sensors.active); assertEquals(0, mic.open)
    }

    @Test fun `S-010 Speicher voll laesst die Auswertung stehen`() = runTest {
        val (mic, sensors, files) = setup()
        files.failSave = true
        val r = report(mic, sensors, files)
        assertTrue(r.storageFailed); assertNull(r.fileUri)
        assertTrue(assertNotNull(r.analysis).estimate is SweepMath.Estimate.Ok)
        val (mic2, sensors2, files2) = setup()
        files2.failFolder = true
        val r2 = report(mic2, sensors2, files2)
        assertNull(r2.folder); assertTrue(r2.storageFailed); assertNotNull(r2.analysis)
    }

    @Test fun `S-010 ohne Berechtigung, ohne Sensor, Aufnahme scheitert`() = runTest {
        val (mic, sensors, files) = setup()
        mic.permission = false
        assertEquals(SweepRunFailure.NO_PERMISSION, report(mic, sensors, files).failure)
        assertTrue(mic.requests.isEmpty()); assertEquals(0, sensors.registrations)
        val (mic2, sensors2, files2) = setup()
        sensors2.availability = MotionAvailability(rotation = false, acceleration = true)
        assertEquals(SweepRunFailure.NO_SENSOR, report(mic2, sensors2, files2).failure)
        assertTrue(mic2.requests.isEmpty())
        val (mic3, sensors3, files3) = setup()
        mic3.failures[app.cayresim.core.boundary.AudioSourceKey.MIC] = MicFailure.INIT_FAILED
        val r3 = report(mic3, sensors3, files3)
        assertEquals(SweepRunFailure.RECORD_FAILED, r3.failure); assertEquals(MicFailure.INIT_FAILED, r3.micFailure)
        assertEquals(0, sensors3.active); assertNull(r3.analysis)
        // ohne Zeitbezug der Aufnahme: grob aus dem ersten Lagewert, so markiert
        val (mic4, sensors4, files4) = setup()
        mic4.startBootNanos = null; mic4.timeExact = false
        val r4 = report(mic4, sensors4, files4)
        assertFalse(r4.timeExact); assertNotNull(r4.analysis)
    }
}
