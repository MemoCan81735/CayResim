package app.cayresim.core.control

import app.cayresim.core.boundary.CaptureFailure
import app.cayresim.core.boundary.CaptureResult
import app.cayresim.core.boundary.Look
import app.cayresim.core.boundary.SeriesResult
import app.cayresim.core.boundary.fake.FakeCameraBoundary
import app.cayresim.core.boundary.fake.FakeProcessingBoundary
import app.cayresim.core.boundary.fake.FakeSeriesBoundary
import app.cayresim.core.pure.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CaptureUseCaseTest {
    private val cam = FakeCameraBoundary()
    private val proc = FakeProcessingBoundary()
    private val series = FakeSeriesBoundary()
    private var t = 1000L
    private val uc = CaptureUseCase(TakePhotoUseCase(cam), cam, ProcessingWithCameraFiles(proc, cam), series, Clock { t++ })

    /** Der Processing-Fake kennt die Fotos, die der Kamera-Fake gespeichert hat. */
    private class ProcessingWithCameraFiles(val p: FakeProcessingBoundary, val c: FakeCameraBoundary) : app.cayresim.core.boundary.ProcessingBoundary by p {
        override suspend fun applyLook(uri: String, look: Look) = p.also { it.known += c.saved }.applyLook(uri, look)
    }

    @Test fun `Ohne Look und Serie wird nur fotografiert`() = runTest {
        cam.start()
        val r = assertIs<CaptureOutcome.Saved>(uc(Look.NONE, null))
        assertEquals(listOf(r.uri), cam.saved); assertEquals(0, proc.looksApplied.size)
    }

    @Test fun `Mit Look ersetzt das bearbeitete Foto das Original`() = runTest {
        cam.start()
        val r = assertIs<CaptureOutcome.Saved>(uc(Look.FILM, null))
        assertTrue(r.uri.startsWith("content://fake/look/")); assertEquals(0, cam.saved.size, "Original muss geloescht sein")
        assertEquals(Look.FILM, proc.looksApplied.single().second)
    }

    @Test fun `Mit Serie landet das Foto in der Serie`() = runTest {
        cam.start(); val id = (series.create("Garten") as SeriesResult.Ok).series.id
        val r = assertIs<CaptureOutcome.Saved>(uc(Look.NONE, id))
        assertEquals(listOf(r.uri), series.series().first().single().photoUris)
    }

    @Test fun `Look scheitert, Original bleibt erhalten`() = runTest {
        cam.start(); proc.gpuFails = true
        val r = assertIs<CaptureOutcome.Saved>(uc(Look.MONO, null))
        assertTrue(r.lookFailed); assertEquals(listOf(r.uri), cam.saved)
    }

    @Test fun `Serie scheitert, Foto bleibt erhalten`() = runTest {
        cam.start()
        val r = assertIs<CaptureOutcome.Saved>(uc(Look.NONE, 4711))
        assertTrue(r.seriesFailed); assertEquals(listOf(r.uri), cam.saved)
    }

    @Test fun `Speicher der Serie defekt wird gemeldet`() = runTest {
        cam.start(); val id = (series.create("S") as SeriesResult.Ok).series.id; series.failStorage = true
        assertTrue(assertIs<CaptureOutcome.Saved>(uc(Look.NONE, id)).seriesFailed)
    }

    @Test fun `Aufnahme scheitert, nichts weiter passiert`() = runTest {
        cam.start(); cam.nextCapture = CaptureResult.Failed(CaptureFailure.STORAGE)
        assertEquals(CaptureOutcome.Failed(CaptureFailure.STORAGE), uc(Look.FILM, 1))
        assertEquals(0, proc.looksApplied.size)
    }

    @Test fun `Kamera nicht bereit`() = runTest {
        assertEquals(CaptureOutcome.Failed(CaptureFailure.NOT_READY), uc(Look.NONE, null))
    }
}
