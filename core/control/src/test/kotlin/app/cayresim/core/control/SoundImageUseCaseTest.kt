package app.cayresim.core.control

import app.cayresim.core.boundary.AudioSourceKey
import app.cayresim.core.boundary.MicFailure
import app.cayresim.core.boundary.MotionAvailability
import app.cayresim.core.boundary.MotionKind
import app.cayresim.core.boundary.MotionSample
import app.cayresim.core.boundary.MotionSensorBoundary
import app.cayresim.core.boundary.fake.FakeMicrophoneBoundary
import app.cayresim.core.boundary.fake.FakeMotionSensorBoundary
import app.cayresim.core.boundary.fake.stereoTones
import app.cayresim.core.pure.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S-018: Schallbild mit Fakes; Ton und Lage sind kuenstlich (CLAUDE.md Abschnitt 7). */
class SoundImageUseCaseTest {
    private val sr = SoundImageUseCase.SAMPLE_RATE
    private val chunk = sr / 10

    /** Uhr, die bei jedem Lesen 1 ms weiterlaeuft. */
    private var now = 0L
    private val clock = Clock { now++ }

    private suspend fun here(): CoroutineDispatcher = currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher

    private fun sound(degrees: Double) = stereoTones(degrees)

    private fun mic(pcm: ShortArray) = FakeMicrophoneBoundary().apply {
        streamChunkDelayMillis = 1
        streamSound = { _, i -> pcm.copyOfRange((i % 30) * chunk * 2, (i % 30 + 1) * chunk * 2) }
    }

    private fun gravity(x: Float, y: Float, z: Float) =
        FakeMotionSensorBoundary(samples = List(5) { MotionSample(MotionKind.ACCELERATION, 1_000_000_000L + it * 50_000_000L, x, y, z) })

    @Test fun `S-018 Quelle rechts steht rechts, gedreht links`() = runTest {
        val pcm = sound(30.0)
        val left = SoundImageUseCase(mic(pcm), gravity(9.81f, 0f, 0f), clock, here()).run().filterIsInstance<SoundImageEvent.Frame>()
            .first { it.mainAngleDeg != null && it.bands.count { b -> b.angleDeg != null } >= 4 }
        assertEquals(SoundHold.TOP_LEFT, left.hold)
        assertEquals(30.0, assertNotNull(left.mainAngleDeg), 5.0)
        assertEquals(5, left.bands.size)
        // die Kurve zeigt nach derselben Seite wie der Winkel: Maximum in der rechten Haelfte
        val band = left.bands.first { it.angleDeg != null }
        assertTrue(band.response.indices.maxBy { band.response[it] } > band.response.size / 2)

        val right = SoundImageUseCase(mic(pcm), gravity(-9.81f, 0f, 0f), clock, here()).run().filterIsInstance<SoundImageEvent.Frame>()
            .first { it.mainAngleDeg != null && it.bands.count { b -> b.angleDeg != null } >= 4 }
        assertEquals(SoundHold.TOP_RIGHT, right.hold)
        assertEquals(-30.0, assertNotNull(right.mainAngleDeg), 5.0)
        val mirrored = right.bands.first { it.angleDeg != null }
        assertTrue(mirrored.response.indices.maxBy { mirrored.response[it] } < mirrored.response.size / 2)
    }

    @Test fun `S-018 hochkant zeigt keine Streifen`() = runTest {
        val frames = SoundImageUseCase(mic(sound(30.0)), gravity(0f, 9.81f, 0f), clock, here()).run().filterIsInstance<SoundImageEvent.Frame>().take(12).toList()
        val last = frames.last()
        assertEquals(SoundHold.NOT_LANDSCAPE, last.hold)
        assertTrue(last.bands.isEmpty()); assertNull(last.mainAngleDeg)
    }

    @Test fun `S-018 Fehlerfaelle enden als Werte`() = runTest {
        // keine Berechtigung
        val denied = SoundImageUseCase(FakeMicrophoneBoundary(permission = false), gravity(9.81f, 0f, 0f), clock, here()).run().toList()
        assertEquals(listOf<SoundImageEvent>(SoundImageEvent.Failed(SoundImageFailure.NO_PERMISSION)), denied)
        // Aufnahme scheitert sofort
        val broken = FakeMicrophoneBoundary().apply { failures[AudioSourceKey.MIC] = MicFailure.INIT_FAILED }
        assertEquals(SoundImageEvent.Failed(SoundImageFailure.RECORD_FAILED, MicFailure.INIT_FAILED),
            SoundImageUseCase(broken, gravity(9.81f, 0f, 0f), clock, here()).run().toList().last())
        assertEquals(0, broken.open)
        // Aufnahme reisst nach 3 Stuecken ab
        val pcm = sound(30.0)
        val torn = mic(pcm).apply { streamSound = { _, i -> if (i < 3) pcm.copyOfRange(0, chunk * 2) else null } }
        val events = SoundImageUseCase(torn, gravity(9.81f, 0f, 0f), clock, here()).run().toList()
        assertEquals(SoundImageEvent.Failed(SoundImageFailure.RECORD_FAILED, MicFailure.READ_FAILED), events.last())
        assertEquals(0, torn.open)
        // nur ein Mikrofon: beide Kanaele gleich
        val mono = FakeMicrophoneBoundary().apply {
            streamChunkDelayMillis = 1
            streamSound = { _, i -> ShortArray(chunk * 2) { k -> (((k / 2 + i * chunk) * 37) % 2000 - 1000).toShort() } }
        }
        assertEquals(SoundImageEvent.Failed(SoundImageFailure.NO_STEREO), SoundImageUseCase(mono, gravity(9.81f, 0f, 0f), clock, here()).run().toList().last())
        // nur ein Kanal geliefert
        val single = FakeMicrophoneBoundary().apply { streamChunkDelayMillis = 1; channelsFor = { 1 } }
        assertEquals(SoundImageEvent.Failed(SoundImageFailure.NO_STEREO), SoundImageUseCase(single, gravity(9.81f, 0f, 0f), clock, here()).run().toList().last())
        // digitale Stille ist kein Mono: es laeuft weiter, ohne Streifen
        val silent = FakeMicrophoneBoundary().apply { streamChunkDelayMillis = 1 }
        val quiet = SoundImageUseCase(silent, gravity(9.81f, 0f, 0f), clock, here()).run().take(15).toList()
        assertTrue(quiet.all { it is SoundImageEvent.Frame && it.bands.none { b -> b.angleDeg != null } })
        // Sensorfehler: das Schallbild laeuft weiter, Haltung unbekannt, gerechnet wie Oberkante links (R14)
        val failing = object : MotionSensorBoundary {
            override fun availability() = MotionAvailability(rotation = true, acceleration = true)
            override fun samples(periodMicros: Int): Flow<MotionSample> = flow { throw IllegalStateException("Sensor weg") }
        }
        val frame = SoundImageUseCase(mic(pcm), failing, clock, here()).run().filterIsInstance<SoundImageEvent.Frame>().first { it.mainAngleDeg != null }
        assertEquals(SoundHold.UNKNOWN, frame.hold)
        assertEquals(30.0, assertNotNull(frame.mainAngleDeg), 5.0)
        // ohne Beschleunigungssensor ebenso
        val none = FakeMotionSensorBoundary(availability = MotionAvailability(rotation = false, acceleration = false))
        assertEquals(SoundHold.UNKNOWN, assertIs<SoundImageEvent.Frame>(SoundImageUseCase(mic(pcm), none, clock, here()).run().first()).hold)
        assertEquals(0, none.registrations)
    }

    @Test fun `S-018 Abbruch gibt Mikrofon und Sensor frei`() = runTest {
        val m = mic(sound(30.0)); val s = gravity(9.81f, 0f, 0f)
        val frames = SoundImageUseCase(m, s, clock, here()).run(AudioSourceKey.CAMCORDER).take(6).toList()
        assertEquals(6, frames.size)
        assertEquals(0, m.open); assertEquals(1, m.maxOpen); assertEquals(0, s.active); assertEquals(1, s.registrations)
        // Tonstrom der Boundary: Stuecke von 100 ms, Stereo, gewaehlte Quelle
        val request = m.streamRequests.single()
        assertEquals(AudioSourceKey.CAMCORDER, request.source); assertEquals(2, request.channels); assertEquals(sr, request.sampleRate)
        assertEquals(listOf(100), m.streamChunkMillis)
        assertTrue(m.requests.isEmpty(), "Einzelaufnahme statt Strom")
    }

    @Test fun `S-018 Messwerte zeigen Takt, Rechenzeit und verlorene Stuecke`() = runTest {
        // Uhr: 3 ms Rechnen je Momentaufnahme, 100 ms je Stueck; nach dem 12. Stueck fehlen zwei Nummern
        var t = 0L; var reads = 0
        val stepping = Clock { reads++; if (reads % 2 == 0) t += 97 else if (reads > 1) t += 3; t }
        val m = mic(sound(30.0)).apply { streamIndex = { if (it < 12) it.toLong() else it + 2L } }
        val frames = SoundImageUseCase(m, gravity(9.81f, 0f, 0f), stepping, here()).run().filterIsInstance<SoundImageEvent.Frame>().take(25).toList()
        assertEquals(SoundImageStats(), frames.first().stats, "vor der ersten vollen Sekunde nichts")
        val full = frames.last().stats
        assertEquals(10, full.framesPerSecond); assertEquals(3.0, full.computeMillis, 0.01); assertEquals(2L, full.lostChunks)
        // der Verlust erscheint sofort, nicht erst mit der naechsten vollen Sekunde: schon bei 10 Bildern je Sekunde aus dem ersten Fenster
        val firstLoss = frames.first { it.stats.lostChunks > 0 }
        assertEquals(2L, firstLoss.stats.lostChunks); assertEquals(10, firstLoss.stats.framesPerSecond)
        assertTrue(frames.takeWhile { it !== firstLoss }.all { it.stats.lostChunks == 0L })
    }
}
