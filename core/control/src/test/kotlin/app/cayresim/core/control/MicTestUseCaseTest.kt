package app.cayresim.core.control

import app.cayresim.core.boundary.AudioSourceKey
import app.cayresim.core.boundary.MicDirectionKey
import app.cayresim.core.boundary.MicFailure
import app.cayresim.core.boundary.MicRequest
import app.cayresim.core.boundary.fake.FakeAudioFileBoundary
import app.cayresim.core.boundary.fake.FakeMicrophoneBoundary
import app.cayresim.core.pure.Clock
import app.cayresim.core.pure.Wav
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MicTestUseCaseTest {
    /** Uhr, die bei jeder Aufnahme um deren Dauer weiterlaeuft. */
    private class RecordClock : Clock { var t = 1_000L; override fun nowMillis() = t }

    /** Zwei verschiedene Rauschkanaele (kein doppeltes Mono). */
    private fun stereoNoise(r: MicRequest, ch: Int): ShortArray {
        val rnd = Random(r.source.ordinal * 1_000 + r.direction.ordinal * 100 + (r.deviceId ?: 0) * 10 + r.millis)
        return ShortArray(r.sampleRate * r.millis / 1000 * ch) { (rnd.nextInt(-300, 300)).toShort() }
    }

    /** Doppeltes Mono: beide Kanaele gleich. */
    private fun dualMono(r: MicRequest, ch: Int): ShortArray {
        val rnd = Random(1); val frames = r.sampleRate * r.millis / 1000
        val m = ShortArray(frames) { rnd.nextInt(-300, 300).toShort() }
        return ShortArray(frames * ch) { m[it / ch] }
    }

    private fun setup(permission: Boolean = true): Triple<FakeMicrophoneBoundary, FakeAudioFileBoundary, RecordClock> {
        val clock = RecordClock()
        val mic = FakeMicrophoneBoundary(permission).apply {
            sound = ::stereoNoise
            onRecord = { clock.t += it.millis + 150L }
        }
        return Triple(mic, FakeAudioFileBoundary(), clock)
    }

    /** Der Dispatcher des Tests selbst, damit virtuelle Zeit und Abbruch wie im Test laufen. */
    private suspend fun here(): CoroutineDispatcher = currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher

    private suspend fun report(mic: FakeMicrophoneBoundary, files: FakeAudioFileBoundary, clock: Clock, claps: Boolean = true) =
        MicTestUseCase(mic, files, clock, here()).run(claps).filterIsInstance<MicTestEvent.Done>().first().report

    @Test fun `S-008 Durchlauf mit fehlender und fehlerhafter Quelle`() = runTest {
        val (mic, files, clock) = setup()
        mic.inventory = mic.inventory.copy(unprocessedSupported = false)
        mic.failures[AudioSourceKey.CAMCORDER] = MicFailure.INIT_FAILED
        val r = report(mic, files, clock, claps = false)
        val plan = MicTestUseCase.plan(mic.inventory)
        assertEquals(plan, r.sources.map { it.request }, "feste Reihenfolge")
        assertEquals(listOf(AudioSourceKey.MIC, AudioSourceKey.CAMCORDER, AudioSourceKey.VOICE_RECOGNITION, AudioSourceKey.UNPROCESSED), plan.take(4).map { it.source })
        assertEquals(MicDirectionKey.TOWARDS_USER, plan[4].direction); assertEquals(MicDirectionKey.AWAY_FROM_USER, plan[5].direction)
        assertEquals(listOf(1, 2), plan.drop(6).map { it.deviceId }, "nur eingebaute Mikrofone einzeln")
        assertEquals(SourceOutcome.Failed(MicFailure.NOT_OFFERED), r.sources[3].outcome)
        assertEquals(SourceOutcome.Failed(MicFailure.INIT_FAILED), r.sources[1].outcome)
        assertFalse(mic.requests.any { it.source == AudioSourceKey.UNPROCESSED }, "nicht angebotene Quelle wird nicht aufgenommen")
        val recorded = r.sources.map { it.outcome }.filterIsInstance<SourceOutcome.Recorded>()
        assertEquals(plan.size - 2, recorded.size, "Fehler bricht den Lauf nicht ab")
        assertTrue(recorded.all { it.stats.distinctChannels && it.fileUri != null })
        assertEquals(recorded.size, files.files.size)
        val first = assertNotNull(Wav.decode(files.files.values.first()))
        assertEquals(2, first.channels); assertEquals(48_000 * 2 * 2, first.samples.size)
        assertTrue(files.files.keys.first().endsWith("01-MIC-v1.wav"), files.files.keys.first())
        assertFalse(r.storageFailed); assertNull(r.failure)
    }

    @Test fun `S-008 Abbruch gibt das Mikrofon frei`() = runTest {
        val (mic, files, clock) = setup()
        mic.recordDelayMillis = 2_000
        val job = launch { MicTestUseCase(mic, files, clock, here()).run().toList() }
        advanceTimeBy(3_000) // mitten in der zweiten Aufnahme
        assertEquals(1, mic.open, "Aufnahme laeuft")
        job.cancel(); job.join()
        assertEquals(0, mic.open, "Mikrofon nach Abbruch freigegeben")
        assertEquals(1, mic.maxOpen, "nie zwei Aufnahmen gleichzeitig")
        assertEquals(2, mic.requests.size, "nach dem Abbruch keine weitere Aufnahme")
    }

    @Test fun `S-008 ohne Berechtigung`() = runTest {
        val (mic, files, clock) = setup(permission = false)
        val events = MicTestUseCase(mic, files, clock, here()).run().toList()
        val r = (events.single() as MicTestEvent.Done).report
        assertEquals(MicFailure.NO_PERMISSION, r.failure)
        assertTrue(mic.requests.isEmpty()); assertTrue(files.files.isEmpty())
    }

    @Test fun `S-008 Quelle fuer die Klatsch-Probe`() = runTest {
        // UNPROCESSED doppeltes Mono, VOICE_RECOGNITION echtes Stereo: die Probe nimmt VOICE_RECOGNITION
        val (mic, files, clock) = setup()
        mic.sound = { r, ch -> if (r.source == AudioSourceKey.UNPROCESSED) dualMono(r, ch) else stereoNoise(r, ch) }
        val r = report(mic, files, clock)
        assertEquals(MicRequest(AudioSourceKey.VOICE_RECOGNITION), r.clapSource)
        assertEquals(ClapPhase.entries, r.claps.map { it.phase })
        assertEquals(3, mic.requests.count { it.millis == 4_000 && it.source == AudioSourceKey.VOICE_RECOGNITION })
        // nirgends Stereo: keine Probe
        val (mic2, files2, clock2) = setup()
        mic2.sound = ::dualMono
        val r2 = report(mic2, files2, clock2)
        assertNull(r2.clapSource); assertTrue(r2.claps.isEmpty())
        assertFalse(mic2.requests.any { it.millis == 4_000 })
        // Mono-Geraet (ein Kanal) ebenso
        val (mic3, files3, clock3) = setup()
        mic3.channelsFor = { 1 }
        assertNull(report(mic3, files3, clock3).clapSource)
    }

    @Test fun `S-008 Klatsch-Probe liefert die Richtung`() = runTest {
        val (mic, files, clock) = setup()
        mic.sound = { r, ch ->
            if (r.millis != 4_000) stereoNoise(r, ch) else {
                val frames = r.sampleRate * r.millis / 1000; val pcm = ShortArray(frames * ch); val rnd = Random(4)
                val lag = 10 // Kanal 0 hoert 10 Abtastwerte spaeter
                for (at in listOf(24_000, 72_000, 120_000)) for (i in 0 until 480) {
                    val v = (rnd.nextDouble(-1.0, 1.0) * 12_000 * exp(-i / 120.0)).toInt().toShort()
                    pcm[(at + i + lag) * ch] = v; pcm[(at + i) * ch + 1] = v
                }
                pcm
            }
        }
        val r = report(mic, files, clock)
        val left = r.claps.first { it.phase == ClapPhase.LEFT }
        assertEquals(3, left.claps.size)
        left.claps.forEach { assertEquals(10.0 / 48_000, it.delaySeconds, 0.00002) }
        assertTrue(r.spacingMeasured, "Abstand aus den Positionen des Fakes")
        assertEquals(0.156, r.spacingMeters, 0.001)
        assertTrue(files.files.keys.any { it.endsWith("klatschen-LEFT-v1.wav") })
    }

    @Test fun `S-008 Dauer wird gemessen`() = runTest {
        val (mic, files, clock) = setup()
        val r = report(mic, files, clock, claps = false)
        val recorded = mic.requests.size
        assertEquals(recorded * 2_150L, r.sourcesMillis)
        assertTrue(r.sourcesMillis <= 30_000, "Lauf ${r.sourcesMillis} ms")
    }

    @Test fun `S-008 Speicher voll laesst den Lauf weiterlaufen`() = runTest {
        val (mic, files, clock) = setup()
        files.failSave = true
        val r = report(mic, files, clock, claps = false)
        assertTrue(r.storageFailed)
        assertTrue(r.sources.all { (it.outcome as? SourceOutcome.Recorded)?.fileUri == null })
        assertEquals(MicTestUseCase.plan(mic.inventory).size, r.sources.size)
        val (mic2, files2, clock2) = setup()
        files2.failFolder = true
        val r2 = report(mic2, files2, clock2, claps = false)
        assertNull(r2.folder); assertTrue(r2.storageFailed); assertTrue(files2.files.isEmpty())
    }

    @Test fun `S-008 Abstand ohne Positionen ist angenommen`() {
        val inv = FakeMicrophoneBoundary.defaultInventory()
        val none = inv.copy(microphones = inv.microphones.map { it.copy(positionMeters = null) })
        assertEquals(MicTestUseCase.DEFAULT_SPACING to false, MicTestUseCase.spacing(none))
        assertEquals("07-MIC-geraet1-v1.wav", MicTestUseCase.fileName(6, MicRequest(AudioSourceKey.MIC, deviceId = 1)))
        assertEquals("05-MIC-TOWARDS_USER-v1.wav", MicTestUseCase.fileName(4, MicRequest(AudioSourceKey.MIC, direction = MicDirectionKey.TOWARDS_USER)))
    }
}
