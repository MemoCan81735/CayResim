package app.cayresim.core.control

import app.cayresim.core.boundary.AudioSourceKey
import app.cayresim.core.boundary.MicFailure
import app.cayresim.core.boundary.MotionAvailability
import app.cayresim.core.boundary.MotionKind
import app.cayresim.core.boundary.MotionSample
import app.cayresim.core.boundary.MotionSensorBoundary
import app.cayresim.core.boundary.fake.FakeMicrophoneBoundary
import app.cayresim.core.boundary.fake.FakeMotionSensorBoundary
import app.cayresim.core.boundary.fake.landscapeMotion
import app.cayresim.core.boundary.fake.stereoTones
import app.cayresim.core.pure.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    @Test fun `S-021 Lage je Momentaufnahme und neu ansetzen`() = runTest {
        val step = 10_000_000L
        // Quelle fest bei +30 Grad im Raum: das Handy hat sich in 1 s 20 Grad nach rechts gedreht, gemessen werden +10 Grad
        val turned = FakeMotionSensorBoundary(samples = (0..5).flatMap { landscapeMotion(it * 20 * step, yawRightDeg = it * 4.0, rollRightUpDeg = 10.0) })
        val f = SoundImageUseCase(mic(sound(10.0)), turned, clock, here()).run().filterIsInstance<SoundImageEvent.Frame>()
            .first { it.mainAngleDeg != null && it.bands.count { b -> b.angleDeg != null } >= 4 }
        val m = assertNotNull(f.motion)
        assertEquals(20.0, assertNotNull(m.yawDeg), 0.5); assertEquals(10.0, assertNotNull(m.rollDeg), 0.5); assertEquals(0.0, assertNotNull(m.pitchDeg), 0.5)
        assertEquals(false, m.moving)
        assertEquals(10.0, assertNotNull(f.mainAngleDeg), 5.0)
        assertEquals(30.0, assertNotNull(f.worldAngleDeg), 3.0)
        assertEquals(20.0 / 15.0, m.dotX, 0.05, "Punkt am Rand des Felds")
        // Gegenrichtung: 20 Grad nach links gedreht, dieselbe Quelle erscheint bei +50 Grad
        val turnedLeft = FakeMotionSensorBoundary(samples = (0..5).flatMap { landscapeMotion(it * 20 * step, yawRightDeg = it * -4.0) })
        val g = SoundImageUseCase(mic(sound(50.0)), turnedLeft, clock, here()).run().filterIsInstance<SoundImageEvent.Frame>()
            .first { it.mainAngleDeg != null && it.bands.count { b -> b.angleDeg != null } >= 4 }
        assertEquals(-20.0, assertNotNull(assertNotNull(g.motion).yawDeg), 0.5); assertEquals(30.0, assertNotNull(g.worldAngleDeg), 5.0)
        assertEquals(2, f.channelDbfs.size); assertTrue(f.channelDbfs.all { it in -40.0..-10.0 }, "${f.channelDbfs}")
        // Stoss: der Moment ist bewegt und zeigt keine Richtung, Pegel und Kurven bleiben
        val tapped = FakeMotionSensorBoundary(samples = (0..20).flatMap { landscapeMotion(it * step, shake = if (it == 20) 3f else 0f) })
        val moving = SoundImageUseCase(mic(sound(30.0)), tapped, clock, here()).run().filterIsInstance<SoundImageEvent.Frame>().take(12).toList().last()
        assertEquals(true, assertNotNull(moving.motion).moving)
        assertNull(moving.mainAngleDeg); assertNull(moving.worldAngleDeg); assertTrue(moving.bands.none { it.angleDeg != null })
        assertEquals(5, moving.bands.size); assertTrue(moving.bands.all { it.response.isNotEmpty() })
        // nur Schwerkraft, kein Drehvektor: Neigung ja, Drehung und Richtung im Raum nein
        val tiltOnly = SoundImageUseCase(mic(sound(30.0)), gravity(9.81f, 0f, 0f), clock, here()).run().filterIsInstance<SoundImageEvent.Frame>()
            .first { it.mainAngleDeg != null }
        assertEquals(0.0, assertNotNull(assertNotNull(tiltOnly.motion).rollDeg), 0.5); assertNull(tiltOnly.motion?.yawDeg); assertNull(tiltOnly.worldAngleDeg)
        // Sensorfehler oder kein Sensor: keine Lage, das Schallbild laeuft
        val none = FakeMotionSensorBoundary(availability = MotionAvailability(rotation = false, acceleration = false))
        assertNull(SoundImageUseCase(mic(sound(30.0)), none, clock, here()).run().filterIsInstance<SoundImageEvent.Frame>().first().motion)

        // Lage-Kreis: eine Runde am Rand in 2 s, im Takt der Zeitstempel; nach "Neu ansetzen" steht der Punkt in der Mitte
        val round = FakeMotionSensorBoundary(paced = true, samples = (0..300).flatMap {
            val a = Math.toRadians(minOf(it, 200) * 1.8)
            landscapeMotion(it * step, yawRightDeg = 15.0 * kotlin.math.cos(a), rollRightUpDeg = 15.0 * kotlin.math.sin(a))
        })
        // der Start liegt schon am Rand (15 Grad rechts): gemeinsamer Bezug aus einer aufrechten Lage
        val origin = TurnOrigin()
        SoundImageUseCase(mic(sound(30.0)), FakeMotionSensorBoundary(samples = landscapeMotion(0)), clock, here()).run(origin = origin)
            .filterIsInstance<SoundImageEvent.Frame>().first { it.motion?.yawDeg != null }
        val resets = MutableStateFlow(0)
        val slow = mic(sound(30.0)).apply { streamChunkDelayMillis = 100 }
        val frames = mutableListOf<SoundImageEvent.Frame>()
        SoundImageUseCase(slow, round, clock, here()).run(resets = resets, origin = origin).filterIsInstance<SoundImageEvent.Frame>().take(28).collect {
            frames += it
            if (frames.size == 22) resets.value = 1
        }
        val masks = frames.map { Integer.bitCount(assertNotNull(it.motion).coverageMask) }
        assertTrue(masks.zipWithNext().take(20).all { (a, b) -> b >= a }, "Abdeckung waechst: $masks")
        assertEquals(12, masks[21], "volle Runde nach 2 s: $masks")
        val atRim = assertNotNull(frames[21].motion); assertEquals(1.0, kotlin.math.hypot(atRim.dotX, atRim.dotY), 0.1)
        val after = assertNotNull(frames.last().motion)
        assertTrue(Integer.bitCount(after.coverageMask) == 0, "Ring nach neu ansetzen leer: ${after.coverageMask}")
        assertEquals(0.0, kotlin.math.hypot(after.dotX, after.dotY), 0.05, "Punkt in der Mitte")
        assertEquals(0.0, assertNotNull(after.yawDeg), 0.5)
    }
}
