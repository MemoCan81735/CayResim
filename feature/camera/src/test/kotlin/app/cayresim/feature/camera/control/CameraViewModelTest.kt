package app.cayresim.feature.camera.control

import app.cayresim.core.boundary.CameraError
import app.cayresim.core.boundary.CaptureFailure
import app.cayresim.core.boundary.CaptureResult
import app.cayresim.core.boundary.PhotoMode
import app.cayresim.core.boundary.fake.FakeCameraBoundary
import app.cayresim.core.control.TakePhotoUseCase
import app.cayresim.core.control.CaptureUseCase
import app.cayresim.core.boundary.fake.FakeProcessingBoundary
import app.cayresim.core.boundary.fake.FakeSeriesBoundary
import app.cayresim.core.pure.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {
    private lateinit var cam: FakeCameraBoundary
    private lateinit var vm: CameraViewModel
    private lateinit var series: FakeSeriesBoundary
    private lateinit var proc: FakeProcessingBoundary
    private lateinit var frames: app.cayresim.core.boundary.fake.FakeFrameBoundary
    private lateinit var manual: app.cayresim.core.boundary.fake.FakeManualCameraBoundary

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        cam = FakeCameraBoundary(setOf(PhotoMode.NIGHT, PhotoMode.HDR))
        series = FakeSeriesBoundary(); proc = FakeProcessingBoundary()
        val procSeesCamera = object : app.cayresim.core.boundary.ProcessingBoundary by proc {
            override suspend fun applyLook(uri: String, look: app.cayresim.core.boundary.Look) = proc.also { it.known += cam.saved }.applyLook(uri, look)
        }
        var t = 0L
        frames = app.cayresim.core.boundary.fake.FakeFrameBoundary(cam)
        manual = app.cayresim.core.boundary.fake.FakeManualCameraBoundary(cam)
        vm = CameraViewModel(cam, CaptureUseCase(TakePhotoUseCase(cam), cam, procSeesCamera, series, Clock { ++t }), series, proc,
            app.cayresim.core.control.StackPhotoUseCase(frames, proc), frames, manual,
            app.cayresim.core.control.FocusStackUseCase(manual, proc), app.cayresim.core.control.AstroUseCase(manual, frames, proc))
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun visibleAndGranted() { vm.onScreenStart(); vm.onPermissionResult(true) }

    // ---------- Guter Fall ----------
    @Test fun `Mit Erlaubnis und sichtbarem Screen laeuft die Kamera`() = runTest {
        visibleAndGranted()
        val s = vm.uiState.value
        assertEquals(ScreenStatus.RUNNING, s.status)
        assertEquals(PermissionStatus.GRANTED, s.permission)
        assertNotNull(s.previewToken)
        assertEquals(listOf(ModeOption.NORMAL, ModeOption.NIGHT, ModeOption.HDR), s.modes)
        assertTrue(s.canShoot)
    }

    @Test fun `Ausloesen zeigt Meldung und merkt das letzte Foto`() = runTest {
        visibleAndGranted(); vm.onShutter()
        val s = vm.uiState.value
        assertEquals(MessageKind.SAVED, s.message?.kind)
        assertEquals(cam.saved.last(), s.lastPhotoUri)
    }

    @Test fun `Moduswahl wirkt`() = runTest {
        visibleAndGranted(); vm.onModeSelected(ModeOption.NIGHT)
        assertEquals(ModeOption.NIGHT, vm.uiState.value.active); assertNull(vm.uiState.value.fallbackFrom)
    }

    @Test fun `Bestaetigte Meldung verschwindet`() = runTest {
        visibleAndGranted(); vm.onShutter()
        vm.onMessageShown(vm.uiState.value.message!!.id)
        assertNull(vm.uiState.value.message)
    }

    // ---------- Fehlerfall ----------
    @Test fun `Ohne Erlaubnis startet die Kamera nicht`() = runTest {
        vm.onScreenStart(); vm.onPermissionResult(false)
        assertEquals(PermissionStatus.DENIED, vm.uiState.value.permission)
        assertEquals(0, cam.startCalls)
        assertFalse(vm.uiState.value.canShoot)
    }

    @Test fun `Erlaubnis allein startet nicht, solange der Screen unsichtbar ist`() = runTest {
        vm.onPermissionResult(true)
        assertEquals(0, cam.startCalls)
        vm.onScreenStart(); assertEquals(1, cam.startCalls)
    }

    @Test fun `Belegte Kamera fuehrt zu ERROR und Neuversuch klappt`() = runTest {
        cam.startError = CameraError.IN_USE
        visibleAndGranted()
        assertEquals(ScreenStatus.ERROR, vm.uiState.value.status)
        cam.startError = null; vm.onScreenStart()
        assertEquals(ScreenStatus.RUNNING, vm.uiState.value.status)
    }

    @Test fun `Speicher voll ergibt eigene Meldung`() = runTest {
        visibleAndGranted(); cam.nextCapture = CaptureResult.Failed(CaptureFailure.STORAGE); vm.onShutter()
        assertEquals(MessageKind.FAILED_STORAGE, vm.uiState.value.message?.kind)
        assertNull(vm.uiState.value.lastPhotoUri)
    }

    @Test fun `Kamera geschlossen ergibt Kamera-Meldung`() = runTest {
        visibleAndGranted(); cam.nextCapture = CaptureResult.Failed(CaptureFailure.CAMERA_CLOSED); vm.onShutter()
        assertEquals(MessageKind.FAILED_CAMERA, vm.uiState.value.message?.kind)
    }

    @Test fun `Unbekannter Fehler ergibt allgemeine Meldung`() = runTest {
        visibleAndGranted(); cam.nextCapture = CaptureResult.Failed(CaptureFailure.UNKNOWN); vm.onShutter()
        assertEquals(MessageKind.FAILED_OTHER, vm.uiState.value.message?.kind)
    }

    @Test fun `Doppelklick wird still ignoriert`() = runTest {
        visibleAndGranted(); cam.nextCapture = CaptureResult.Failed(CaptureFailure.BUSY); vm.onShutter()
        assertNull(vm.uiState.value.message)
    }

    @Test fun `Ausloesen ohne Kamera meldet nicht bereit`() = runTest {
        vm.onShutter()
        assertEquals(MessageKind.FAILED_CAMERA, vm.uiState.value.message?.kind)
    }

    @Test fun `Nicht verfuegbarer Modus zeigt Rueckfall`() = runTest {
        visibleAndGranted(); vm.onModeSelected(ModeOption.BOKEH)
        val s = vm.uiState.value
        assertEquals(ModeOption.BOKEH, s.selected); assertEquals(ModeOption.NORMAL, s.active); assertEquals(ModeOption.BOKEH, s.fallbackFrom)
    }

    // ---------- Randfaelle ----------
    @Test fun `Unsichtbar werden stoppt die Kamera`() = runTest {
        visibleAndGranted(); vm.onScreenStop()
        assertEquals(ScreenStatus.IDLE, vm.uiState.value.status); assertNull(vm.uiState.value.previewToken)
        assertEquals(1, cam.stopCalls)
    }

    @Test fun `Mehrfaches Start und Stopp bleibt stabil`() = runTest {
        vm.onPermissionResult(true)
        repeat(50) { vm.onScreenStart(); vm.onScreenStop() }
        vm.onScreenStart()
        assertEquals(ScreenStatus.RUNNING, vm.uiState.value.status)
    }

    @Test fun `Veraltete Bestaetigung loescht keine neue Meldung`() = runTest {
        visibleAndGranted(); vm.onShutter(); val first = vm.uiState.value.message!!.id
        vm.onShutter(); val second = vm.uiState.value.message!!.id
        vm.onMessageShown(first)
        assertEquals(second, vm.uiState.value.message?.id)
        assertTrue(second > first)
    }

    @Test fun `Jeder Modus der Boundary hat eine UI-Entsprechung`() {
        PhotoMode.entries.forEach { ModeOption.valueOf(it.name) }
        assertEquals(PhotoMode.entries.size, ModeOption.entries.size)
    }

    // ---------- Phase 2: Look und Serie ----------
    @Test fun `Look wechselt reihum und kehrt zu NONE zurueck`() {
        val seen = (1..LookOption.entries.size).map { vm.onNextLook(); vm.uiState.value.look }
        assertEquals(LookOption.entries.drop(1) + LookOption.NONE, seen)
    }

    @Test fun `Foto mit Look ersetzt das Original`() = runTest {
        visibleAndGranted(); vm.onNextLook(); vm.onShutter()
        assertEquals(MessageKind.SAVED, vm.uiState.value.message?.kind)
        assertTrue(vm.uiState.value.lastPhotoUri!!.contains("look"))
    }

    @Test fun `GPU-Fehler meldet Foto ohne Look`() = runTest {
        visibleAndGranted(); proc.gpuFails = true; vm.onNextLook(); vm.onShutter()
        assertEquals(MessageKind.SAVED_WITHOUT_LOOK, vm.uiState.value.message?.kind)
    }

    @Test fun `Neue Serie wird angelegt und gewaehlt`() = runTest {
        vm.onCreateSeries("Garten")
        val s = vm.uiState.value
        assertEquals(MessageKind.SERIES_CREATED, s.message?.kind)
        assertEquals("Garten", s.series.single().name); assertEquals(s.series.single().id, s.selectedSeriesId)
    }

    @Test fun `Ungueltiger Serienname wird gemeldet`() = runTest {
        vm.onCreateSeries("   ")
        assertEquals(MessageKind.SERIES_INVALID, vm.uiState.value.message?.kind); assertNull(vm.uiState.value.selectedSeriesId)
    }

    @Test fun `Foto in Serie erhoeht den Zaehler`() = runTest {
        visibleAndGranted(); vm.onCreateSeries("S"); vm.onShutter(); vm.onShutter()
        assertEquals(2, vm.uiState.value.series.single().photoCount)
    }

    @Test fun `Geloeschte Serie ist nicht mehr gewaehlt`() = runTest {
        vm.onCreateSeries("S"); val id = vm.uiState.value.selectedSeriesId!!
        series.delete(id)
        assertNull(vm.uiState.value.selectedSeriesId)
    }

    @Test fun `Overlay-Deckkraft wird begrenzt`() {
        vm.onOverlayAlpha(2f); assertEquals(0.9f, vm.uiState.value.overlayAlpha)
        vm.onOverlayAlpha(-1f); assertEquals(0f, vm.uiState.value.overlayAlpha)
    }

    @Test fun `Ohne Serie gibt es kein Overlay`() = assertNull(vm.uiState.value.overlay)

    // ---------- Phase 3: Spezialaufnahmen ----------
    private fun special(o: SpecialOption) { while (vm.uiState.value.special != o) vm.onNextSpecial() }

    @Test fun `Menschen wegrechnen speichert das Ergebnis der Serie`() = runTest {
        visibleAndGranted(); special(SpecialOption.CLEAN_PLATE); vm.onShutter()
        assertEquals(MessageKind.STACK_SAVED, vm.uiState.value.message?.kind)
        assertEquals(app.cayresim.core.boundary.StackMode.MEDIAN, proc.stacked.single().second)
        assertEquals(SpecialStatus.IDLE, vm.uiState.value.specialStatus)
    }

    @Test fun `Langzeit nutzt den Mittelwert`() = runTest {
        visibleAndGranted(); special(SpecialOption.LONG_EXPOSURE); vm.onShutter()
        assertEquals(app.cayresim.core.boundary.StackMode.MEAN, proc.stacked.single().second)
    }

    @Test fun `Gekuerzte Serie wird gemeldet`() = runTest {
        visibleAndGranted(); frames.allowed = 3; special(SpecialOption.CLEAN_PLATE); vm.onShutter()
        assertEquals(MessageKind.STACK_SHORTENED, vm.uiState.value.message?.kind)
    }

    @Test fun `Serie ohne Kamera scheitert sauber`() = runTest {
        special(SpecialOption.CLEAN_PLATE); vm.onShutter()
        assertEquals(MessageKind.STACK_FAILED, vm.uiState.value.message?.kind)
        assertEquals(SpecialStatus.IDLE, vm.uiState.value.specialStatus)
    }

    @Test fun `Scharfer Ausloeser fotografiert bei jedem Signal`() = runTest {
        visibleAndGranted(); special(SpecialOption.TRIGGER_MOTION); vm.onShutter()
        assertEquals(SpecialStatus.ARMED, vm.uiState.value.specialStatus)
        assertEquals(app.cayresim.core.boundary.TriggerMode.MOTION, frames.lastTriggerMode)
        frames.fires.emit(Unit); frames.fires.emit(Unit)
        assertEquals(2, cam.saved.size); assertEquals(MessageKind.TRIGGER_FIRED, vm.uiState.value.message?.kind)
    }

    @Test fun `Zweites Tippen entschaerft den Ausloeser`() = runTest {
        visibleAndGranted(); special(SpecialOption.TRIGGER_STILL); vm.onShutter(); vm.onShutter()
        assertEquals(SpecialStatus.IDLE, vm.uiState.value.specialStatus)
        frames.fires.emit(Unit)
        assertEquals(0, cam.saved.size, "Entschaerfter Ausloeser darf nicht fotografieren")
    }

    @Test fun `Verlassen des Screens entschaerft`() = runTest {
        visibleAndGranted(); special(SpecialOption.TRIGGER_MOTION); vm.onShutter(); vm.onScreenStop()
        frames.fires.emit(Unit)
        assertEquals(0, cam.saved.size); assertEquals(SpecialStatus.IDLE, vm.uiState.value.specialStatus)
    }

    @Test fun `Moduswechsel entschaerft`() = runTest {
        visibleAndGranted(); special(SpecialOption.TRIGGER_MOTION); vm.onShutter(); vm.onNextSpecial()
        frames.fires.emit(Unit); assertEquals(0, cam.saved.size)
    }

    // ---------- Phase 4: Pro, Fokus-Stacking, Sterne ----------
    @Test fun `Pro-Regler setzen Belichtung, ISO und Fokus`() = runTest {
        visibleAndGranted(); special(SpecialOption.PRO)
        vm.onExposure(1f); vm.onIso(1f); vm.onFocus(0f)
        val m = manual.manualState.value
        assertEquals(2_000_000_000L, m.exposureNanos); assertEquals(3200, m.iso); assertEquals(10f, m.focusDiopters)
        assertEquals("2,0 s", vm.uiState.value.pro.exposureLabel); assertEquals("ISO 3200", vm.uiState.value.pro.isoLabel)
    }

    @Test fun `Auto setzt die Automatik zurueck`() = runTest {
        vm.onExposure(0.3f); vm.onExposure(null); vm.onFocus(0.5f); vm.onFocus(null)
        assertNull(manual.manualState.value.exposureNanos); assertNull(manual.manualState.value.focusDiopters)
        assertNull(vm.uiState.value.pro.exposure); assertEquals("Auto", vm.uiState.value.pro.exposureLabel)
    }

    @Test fun `Im Pro-Modus loest der Ausloeser ein normales Foto aus`() = runTest {
        visibleAndGranted(); special(SpecialOption.PRO); vm.onShutter()
        assertEquals(1, cam.saved.size); assertEquals(MessageKind.SAVED, vm.uiState.value.message?.kind)
    }

    @Test fun `RAW-Schalter`() = runTest {
        vm.onRaw(true); assertTrue(vm.uiState.value.pro.raw); vm.onRaw(false); assertFalse(vm.uiState.value.pro.raw)
    }

    @Test fun `Fokus-Stacking und Sterne speichern`() = runTest {
        visibleAndGranted(); special(SpecialOption.FOCUS_STACK); vm.onShutter()
        assertEquals(MessageKind.STACK_SAVED, vm.uiState.value.message?.kind)
        special(SpecialOption.ASTRO); vm.onShutter()
        assertEquals(listOf(app.cayresim.core.boundary.StackMode.FOCUS, app.cayresim.core.boundary.StackMode.STARS), proc.stacked.map { it.second })
    }

    @Test fun `Fokus-Stacking bei Fixfokus meldet das klar`() = runTest {
        manual = app.cayresim.core.boundary.fake.FakeManualCameraBoundary(cam, app.cayresim.core.boundary.ManualCapabilitiesSnapshot(null, null, null, false))
        vm = CameraViewModel(cam, CaptureUseCase(TakePhotoUseCase(cam), cam, proc, series, Clock { 1 }), series, proc,
            app.cayresim.core.control.StackPhotoUseCase(frames, proc), frames, manual,
            app.cayresim.core.control.FocusStackUseCase(manual, proc), app.cayresim.core.control.AstroUseCase(manual, frames, proc))
        visibleAndGranted(); special(SpecialOption.FOCUS_STACK); vm.onShutter()
        assertEquals(MessageKind.FIXED_FOCUS, vm.uiState.value.message?.kind)
        special(SpecialOption.ASTRO); vm.onShutter()
        assertEquals(MessageKind.NO_MANUAL, vm.uiState.value.message?.kind)
        assertFalse(vm.uiState.value.pro.canExpose)
    }

    @Test fun `Pro-Skala ist umkehrbar und begrenzt`() {
        val r = 100_000L..30_000_000_000L
        for (v in listOf(0f, 0.25f, 0.5f, 0.99f, 1f)) assertEquals(v, ProScale.toSlider(ProScale.fromSlider(v, r), r), 0.001f)
        assertEquals(r.first, ProScale.fromSlider(-1f, r)); assertEquals(r.last, ProScale.fromSlider(5f, r))
        assertEquals("1/250 s", ProScale.exposureText(4_000_000)); assertEquals("30 s", ProScale.exposureText(30_000_000_000))
    }
}
