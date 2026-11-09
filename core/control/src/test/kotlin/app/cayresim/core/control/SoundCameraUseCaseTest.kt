package app.cayresim.core.control

import app.cayresim.core.boundary.AudioSourceKey
import app.cayresim.core.boundary.CameraError
import app.cayresim.core.boundary.CameraStatus
import app.cayresim.core.boundary.MicFailure
import app.cayresim.core.boundary.MotionAvailability
import app.cayresim.core.boundary.MotionSample
import app.cayresim.core.boundary.MotionSensorBoundary
import app.cayresim.core.boundary.PhotoMode
import app.cayresim.core.boundary.fake.FakeCameraBoundary
import app.cayresim.core.boundary.fake.FakeMicrophoneBoundary
import app.cayresim.core.boundary.fake.FakeMotionSensorBoundary
import app.cayresim.core.boundary.fake.landscapeMotion
import app.cayresim.core.boundary.fake.stereoTones
import app.cayresim.core.pure.Clock
import app.cayresim.core.pure.SoundCameraCheck
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S-021: Messung des Schallbilds ohne und mit laufender Kamera, mit Fakes; Ton und Lage sind kuenstlich. */
@OptIn(ExperimentalCoroutinesApi::class)
class SoundCameraUseCaseTest {
    private val chunk = SoundImageUseCase.SAMPLE_RATE / 10
    private var now = 0L
    private val clock = Clock { now++ }
    private val pcm = stereoTones(30.0)

    private suspend fun here(): CoroutineDispatcher = currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher

    /** Kurze Phasen und schnelle Tonstuecke: der lokale Lauf rechnet mit echter Zeit. Je Phase 40 Tonstuecke wie auf dem Geraet. */
    private val phaseMillis = 400L

    private fun mic() = FakeMicrophoneBoundary().apply {
        streamChunkDelayMillis = 10
        streamSound = { _, i -> pcm.copyOfRange((i % 30) * chunk * 2, (i % 30 + 1) * chunk * 2) }
    }

    private fun still() = FakeMotionSensorBoundary(samples = (0..3).flatMap { landscapeMotion(it * 10_000_000L) })

    private suspend fun useCase(mic: FakeMicrophoneBoundary, camera: FakeCameraBoundary, sensors: MotionSensorBoundary = still()) =
        SoundCameraUseCase(SoundImageUseCase(mic, sensors, clock, here()), camera, clock, here())

    private suspend fun report(mic: FakeMicrophoneBoundary, camera: FakeCameraBoundary, sensors: MotionSensorBoundary = still()) =
        assertIs<SoundCameraEvent.Done>(useCase(mic, camera, sensors).run(phaseMillis).toList().last()).report

    @Test fun `S-021 sechs Phasen, Kamera in Phase 2 und 5`() = runTest {
        val camera = FakeCameraBoundary().apply { selectMode(PhotoMode.NIGHT) }
        val m = mic()
        // was gilt, wenn eine Aufnahme beginnt: laeuft die Kamera, und in welchem Modus?
        val atStart = mutableListOf<Pair<CameraStatus, PhotoMode>>()
        val chunks = mutableListOf<Int>()
        val base = m.streamSound
        m.streamSound = { r, i ->
            if (i == 0) { atStart += camera.state.value.status to camera.state.value.activeMode; chunks += 0 }
            chunks[chunks.size - 1] = i + 1
            base(r, i)
        }
        val events = useCase(m, camera).run(phaseMillis).toList()
        // auf dem Geraet: sechs Phasen zu 4 s, dazu zweimal der Start der Kamera (im Selbsttest unter 2 s)
        assertTrue(6 * SoundCameraUseCase.PHASE_MILLIS + 2 * 2_000 <= 30_000)
        assertEquals(10, SoundCameraUseCase.SETTLE_FRAMES, "etwa die erste Sekunde bei 10 Momenten je Sekunde")
        val progress = events.filterIsInstance<SoundCameraEvent.Phase>()
        assertEquals((0..5).toList(), progress.map { it.index }); assertTrue(progress.all { it.total == 6 })
        assertEquals(List(3) { AudioSourceKey.MIC } + List(3) { AudioSourceKey.CAMCORDER }, progress.map { it.source })
        assertEquals(listOf(false, true, false, false, true, false), progress.map { it.cameraOn })
        assertEquals(listOf(CameraStatus.IDLE, CameraStatus.RUNNING, CameraStatus.IDLE, CameraStatus.IDLE, CameraStatus.RUNNING, CameraStatus.IDLE), atStart.map { it.first })
        assertEquals(PhotoMode.NORMAL, atStart[1].second, "gemessen wird der normale Modus")
        assertEquals(progress.map { it.source }, m.streamRequests.map { it.source })
        assertEquals(2, camera.startCalls); assertEquals(2, camera.stopCalls); assertEquals(2, camera.headlessCalls)
        assertEquals(CameraStatus.IDLE, camera.state.value.status)
        assertEquals(PhotoMode.NIGHT, camera.state.value.requestedMode, "Modus von vorher wiederhergestellt")
        // lief die Kamera vorher schon, wird sie fuer die Phasen ohne Kamera gestoppt
        val busy = FakeCameraBoundary().apply { start() }
        val seen = mutableListOf<CameraStatus>()
        val m2 = mic(); val base2 = m2.streamSound
        m2.streamSound = { r, i -> if (i == 0) seen += busy.state.value.status; base2(r, i) }
        useCase(m2, busy).run(phaseMillis).toList()
        assertEquals(CameraStatus.IDLE, seen.first())
        assertEquals(0, m.open); assertEquals(1, m.maxOpen)

        val r = assertIs<SoundCameraEvent.Done>(events.last()).report
        assertEquals(6, r.phases.size)
        for ((n, p) in r.phases.withIndex()) {
            val s = assertNotNull(p.stats)
            // die ersten 10 Momente jeder Phase zaehlen nicht (das letzte Tonstueck kann im Abbruch der Phase liegen)
            assertTrue(s.frames in chunks[n] - 12..chunks[n] - 10 && s.frames >= 5, "gewertete Momente ${s.frames} von ${chunks[n]}")
            assertEquals(30.0, assertNotNull(s.angleDeg), 5.0); assertTrue(s.stereo)
            assertEquals(0.0, assertNotNull(s.movedShare), 0.0); assertEquals(0.0, assertNotNull(s.turnDeg), 0.5)
            assertEquals(0.0, assertNotNull(s.rollDeg), 0.5); assertEquals(0.0, assertNotNull(s.pitchDeg), 0.5)
            assertTrue(s.levelADbfs in -40.0..-10.0 && s.levelBDbfs in -40.0..-10.0, "$s")
            assertEquals(if (p.cameraOn) 30 else null, p.previewFps); assertNull(p.failure); assertEquals(0L, p.lostChunks)
        }
        assertEquals(listOf(AudioSourceKey.MIC to SoundCameraCheck.Verdict.SAME, AudioSourceKey.CAMCORDER to SoundCameraCheck.Verdict.SAME),
            r.verdicts.map { it.source to it.verdict })
        assertNull(r.cameraError); assertTrue(r.cameraFrames); assertTrue(r.motionAvailable)
        // die Dauer der ganzen Messung steht im Ergebnis (Uhr des Tests: 1 ms je Lesen)
        assertTrue(r.durationMillis > 0, "Dauer ${r.durationMillis}")
        assertEquals(75.8, assertNotNull(r.viewWideDeg), 0.1); assertEquals(60.5, assertNotNull(r.viewHighDeg), 0.1)
    }

    @Test fun `S-021 Fehler enden als Werte`() = runTest {
        // Kamera startet nicht: Urteil "Kamera scheitert", der Ton laeuft trotzdem durch alle Phasen
        val blocked = FakeCameraBoundary().apply { startError = CameraError.IN_USE }
        val a = report(mic(), blocked)
        assertEquals(listOf(SoundCameraCheck.Verdict.CAMERA_FAILED, SoundCameraCheck.Verdict.CAMERA_FAILED), a.verdicts.map { it.verdict })
        assertEquals(CameraError.IN_USE, a.cameraError); assertEquals(6, a.phases.count { it.stats != null })
        assertEquals(2, blocked.stopCalls, "auch eine gescheiterte Kamera wird freigegeben")
        // Kamera laeuft, liefert ohne Sucher aber keine Bilder
        val dark = FakeCameraBoundary().apply { headlessFps = null }
        val b = report(mic(), dark)
        assertEquals(SoundCameraCheck.Verdict.CAMERA_FAILED, b.verdicts.first().verdict); assertNull(b.cameraError); assertEquals(false, b.cameraFrames)
        // ohne Angabe zur Optik: kein Bildwinkel
        val c = report(mic(), FakeCameraBoundary().apply { lens = null })
        assertNull(c.viewWideDeg); assertNull(c.viewHighDeg); assertEquals(SoundCameraCheck.Verdict.SAME, c.verdicts.first().verdict)
        // Mikrofon scheitert fuer eine Tonquelle: Fehler als Wert, die andere laeuft
        val half = mic().apply { failures[AudioSourceKey.CAMCORDER] = MicFailure.INIT_FAILED }
        val d = report(half, FakeCameraBoundary())
        assertEquals(listOf(SoundCameraCheck.Verdict.SAME, SoundCameraCheck.Verdict.NO_SOUND), d.verdicts.map { it.verdict })
        assertTrue(d.phases.drop(3).all { it.stats == null && it.failure == SoundImageFailure.RECORD_FAILED })
        assertEquals(0, half.open)
        // Mikrofon scheitert nur in den Phasen mit Kamera: Fehler als Wert, die uebrigen Phasen laufen
        val camP = FakeCameraBoundary()
        val torn = mic()
        val whole = torn.streamSound
        torn.streamSound = { r, i -> if (camP.state.value.status == CameraStatus.RUNNING && i >= 3) null else whole(r, i) }
        val p = report(torn, camP)
        assertEquals(listOf(SoundCameraCheck.Verdict.NO_SOUND, SoundCameraCheck.Verdict.NO_SOUND), p.verdicts.map { it.verdict })
        assertEquals(listOf(true, false, true, true, false, true), p.phases.map { it.stats != null })
        assertEquals(SoundImageFailure.RECORD_FAILED, p.phases[1].failure); assertEquals(0, torn.open)
        // mit laufender Kamera kommt nur noch ein Mikrofon auf beiden Kanaelen
        val cam = FakeCameraBoundary()
        val mono = mic()
        val base = mono.streamSound
        mono.streamSound = { r, i ->
            val s = assertNotNull(base(r, i))
            if (cam.state.value.status == CameraStatus.RUNNING) ShortArray(s.size) { s[it - it % 2] } else s
        }
        val e = report(mono, cam)
        assertEquals(listOf(SoundCameraCheck.Verdict.ONE_CHANNEL, SoundCameraCheck.Verdict.ONE_CHANNEL), e.verdicts.map { it.verdict })
        assertEquals(SoundImageFailure.NO_STEREO, e.phases[1].failure); assertEquals(false, assertNotNull(e.phases[1].stats).stereo)
        // mit laufender Kamera ist der Ton 8 dB leiser: veraendert
        val cam2 = FakeCameraBoundary()
        val quiet = mic()
        quiet.streamSound = { r, i ->
            val s = assertNotNull(base(r, i))
            if (cam2.state.value.status == CameraStatus.RUNNING) ShortArray(s.size) { (s[it] * 0.4).toInt().toShort() } else s
        }
        assertEquals(SoundCameraCheck.Verdict.CHANGED, report(quiet, cam2).verdicts.first().verdict)
        // Lagesensor wirft oder fehlt: die Messung laeuft ohne Lage, "bewegt" entfaellt
        val failing = object : MotionSensorBoundary {
            override fun availability() = MotionAvailability(rotation = true, acceleration = true)
            override fun samples(periodMicros: Int): Flow<MotionSample> = flow { throw IllegalStateException("Sensor weg") }
        }
        val f = report(mic(), FakeCameraBoundary(), failing)
        assertEquals(false, f.motionAvailable); assertEquals(SoundCameraCheck.Verdict.SAME, f.verdicts.first().verdict)
        assertNull(assertNotNull(f.phases.first().stats).movedShare)
        val none = FakeMotionSensorBoundary(availability = MotionAvailability(rotation = false, acceleration = false))
        assertEquals(false, report(mic(), FakeCameraBoundary(), none).motionAvailable)
        // Handy waehrend der Messung 12 Grad gedreht: bewegt
        val turned = FakeMotionSensorBoundary(samples = (0..3).flatMap { landscapeMotion(it * 200_000_000L, yawRightDeg = it * 4.0) })
        assertEquals(SoundCameraCheck.Verdict.MOVED, report(mic(), FakeCameraBoundary(), turned).verdicts.first().verdict)
        // keine Berechtigung fuer das Mikrofon
        val denied = report(FakeMicrophoneBoundary(permission = false), FakeCameraBoundary())
        assertTrue(denied.phases.all { it.stats == null && it.failure == SoundImageFailure.NO_PERMISSION })
    }

    @Test fun `S-021 Abbruch gibt alles frei`() = runTest {
        // Abbruch in jeder Phase, in ihrer Mitte
        for (phase in 0..5) {
            val camera = FakeCameraBoundary(); val m = mic(); val sensors = still()
            val uc = useCase(m, camera, sensors)
            val job = launch { uc.run(phaseMillis).collect { } }
            advanceTimeBy(phase * phaseMillis + phaseMillis / 2)
            assertEquals(if (phase == 1 || phase == 4) CameraStatus.RUNNING else CameraStatus.IDLE, camera.state.value.status, "Phase $phase")
            assertEquals(1, m.open, "Phase $phase: Aufnahme laeuft")
            job.cancelAndJoin()
            assertEquals(CameraStatus.IDLE, camera.state.value.status, "Phase $phase")
            assertEquals(camera.startCalls, camera.stopCalls, "Phase $phase: jede gestartete Kamera gestoppt")
            assertEquals(0, m.open, "Phase $phase: Mikrofon frei"); assertEquals(0, sensors.active, "Phase $phase: Sensor abgemeldet")
            // danach laesst sich das Schallbild normal starten
            val frame = SoundImageUseCase(m, sensors, clock, here()).run().filterIsInstance<SoundImageEvent.Frame>().first()
            assertEquals(SoundHold.TOP_LEFT, frame.hold); assertEquals(0, m.open)
        }
    }
}
