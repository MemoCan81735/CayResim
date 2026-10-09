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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
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
            app.cayresim.core.control.FocusStackUseCase(manual, proc), app.cayresim.core.control.AstroUseCase(manual, frames, proc),
            app.cayresim.core.control.NightUseCase(cam, manual, frames, proc))
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
        assertEquals(listOf(ModeOption.AUTO, ModeOption.NORMAL, ModeOption.NIGHT, ModeOption.HDR), s.modes)
        assertEquals(ModeOption.AUTO, s.selected, "Automatik ist Standard")
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

    // ---------- S-003 K5: Selbstausloeser 2 s ----------

    @Test fun `Selbstausloeser loest nach 2 s aus und zeigt den Countdown`() = runTest {
        visibleAndGranted(); vm.onToggleTimer()
        assertTrue(vm.uiState.value.timer)
        vm.onShutter()
        assertEquals(2, vm.uiState.value.countdown); assertTrue(cam.saved.isEmpty(), "noch nicht ausgeloest")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, vm.uiState.value.countdown); assertTrue(cam.saved.isEmpty())
        advanceTimeBy(1_000); runCurrent()
        assertNull(vm.uiState.value.countdown); assertEquals(1, cam.saved.size)
        assertEquals(MessageKind.SAVED, vm.uiState.value.message?.kind)
    }

    @Test fun `Selbstausloeser erneuter Druck bricht ab`() = runTest {
        visibleAndGranted(); vm.onToggleTimer(); vm.onShutter()
        advanceTimeBy(500); runCurrent()
        vm.onShutter()
        assertNull(vm.uiState.value.countdown)
        advanceTimeBy(5_000); runCurrent()
        assertTrue(cam.saved.isEmpty(), "abgebrochen")
    }

    @Test fun `Selbstausloeser Verlassen des Screens bricht ab, ohne Timer sofort`() = runTest {
        visibleAndGranted(); vm.onToggleTimer(); vm.onShutter()
        vm.onScreenStop()
        advanceTimeBy(5_000); runCurrent()
        assertTrue(cam.saved.isEmpty()); assertNull(vm.uiState.value.countdown)
        vm.onScreenStart(); vm.onToggleTimer(); assertFalse(vm.uiState.value.timer)
        vm.onShutter(); assertEquals(1, cam.saved.size, "ohne Timer sofort")
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
            app.cayresim.core.control.FocusStackUseCase(manual, proc), app.cayresim.core.control.AstroUseCase(manual, frames, proc),
            app.cayresim.core.control.NightUseCase(cam, manual, frames, proc))
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

    // ---------- Zoom, Fokus, Lautstaerketaste ----------

    private fun s24Zoom() { cam.zoomRange = 0.6f..10f; cam.zoomPresets = listOf(0.6f, 1f, 3f) }

    @Test fun `Guter Fall Schnellwahl 3x und Anzeige`() = runTest {
        s24Zoom(); visibleAndGranted(); vm.onZoomPreset(3f)
        val s = vm.uiState.value
        assertEquals(3f, s.zoomRatio); assertEquals("3x", s.zoomLabel)
        assertEquals(listOf("0,6x", "1x", "3x"), s.zoomPresets.map { it.label })
        assertEquals(listOf(false, false, true), s.zoomPresets.map { it.active })
    }

    @Test fun `Guter Fall Zwei-Finger-Zoom summiert die Gesten`() = runTest {
        s24Zoom(); visibleAndGranted()
        vm.onPinch(2f); vm.onPinch(1.2f)
        assertEquals(2.4f, vm.uiState.value.zoomRatio, 0.001f)
        assertEquals("2,4x", vm.uiState.value.zoomLabel)
        assertTrue(vm.uiState.value.zoomPresets.none { it.active }, "zwischen den Stufen ist keine hervorgehoben")
    }

    @Test fun `Randfall Zoom bleibt in den Grenzen`() = runTest {
        s24Zoom(); visibleAndGranted()
        repeat(20) { vm.onPinch(3f) }; assertEquals(10f, vm.uiState.value.zoomRatio)
        repeat(20) { vm.onPinch(0.2f) }; assertEquals(0.6f, vm.uiState.value.zoomRatio)
    }

    @Test fun `Fehlerfall unsinnige Geste aendert nichts`() = runTest {
        s24Zoom(); visibleAndGranted(); vm.onZoomPreset(3f)
        vm.onPinch(Float.NaN); vm.onPinch(0f); vm.onPinch(-2f)
        assertEquals(3f, vm.uiState.value.zoomRatio)
    }

    @Test fun `Randfall Kamera ohne Zoom zeigt keine Schnellwahl`() = runTest {
        visibleAndGranted(); assertEquals(emptyList(), vm.uiState.value.zoomPresets)
    }

    @Test fun `Antippen stellt auf den Punkt scharf`() = runTest {
        visibleAndGranted(); vm.onTapFocus(0.25f, 0.75f)
        assertEquals(0.25f to 0.75f, cam.lastFocus)
    }

    @Test fun `Fehlerfall Antippen ohne Kamera passiert nichts`() = runTest {
        vm.onTapFocus(0.5f, 0.5f); assertNull(cam.lastFocus)
    }

    @Test fun `Lautstaerketaste loest aus wie der Knopf`() = runTest {
        visibleAndGranted(); vm.onHardwareShutter()
        assertEquals(1, cam.saved.size); assertEquals(MessageKind.SAVED, vm.uiState.value.message?.kind)
    }

    @Test fun `Fehlerfall Lautstaerketaste ohne laufende Kamera loest nicht aus`() = runTest {
        vm.onHardwareShutter(); assertEquals(0, cam.saved.size); assertNull(vm.uiState.value.message)
    }

    // ---------- Nacht-Kern ----------

    @Test fun `Nacht im Dunkeln nutzt den eigenen Kern und meldet die Werte`() = runTest {
        visibleAndGranted(); vm.onModeSelected(ModeOption.NIGHT)
        cam.measure(app.cayresim.core.boundary.LightSnapshot(66_666_666, 3200))
        vm.onShutter()
        assertEquals(1, proc.nightRuns.size)
        assertEquals(0, cam.saved.size, "kein Foto ueber Samsungs Extension")
        val m = vm.uiState.value.message!!
        assertEquals(MessageKind.NIGHT_SAVED, m.kind)
        assertEquals(3200, m.night!!.iso); assertEquals(100_000_000L, m.night!!.exposureNs)
        // S-002: Messung der Automatik kommt bis in den Hinweis
        assertEquals(66_666_666L, m.night!!.meterExposureNs); assertEquals(3200, m.night!!.meterIso)
        assertEquals(SpecialStatus.IDLE, vm.uiState.value.specialStatus)
        assertNotNull(vm.uiState.value.lastPhotoUri)
    }

    @Test fun `Nacht mit gekuerzter Serie sagt es im Hinweis`() = runTest {
        visibleAndGranted(); vm.onModeSelected(ModeOption.NIGHT)
        cam.measure(app.cayresim.core.boundary.LightSnapshot(66_666_666, 3200))
        frames.allowed = 4 + 3 + 10
        vm.onShutter()
        val n = vm.uiState.value.message!!.night!!
        assertEquals(10, n.used); assertTrue(n.shortened)
    }

    @Test fun `Nacht bei hellem Licht nutzt Samsungs Modus`() = runTest {
        visibleAndGranted(); vm.onModeSelected(ModeOption.NIGHT)
        cam.measure(app.cayresim.core.boundary.LightSnapshot(5_000_000, 50))
        vm.onShutter()
        assertEquals(0, proc.nightRuns.size); assertEquals(1, cam.saved.size)
    }

    @Test fun `Normaler Modus im Dunkeln bleibt ein normales Foto`() = runTest {
        visibleAndGranted(); vm.onModeSelected(ModeOption.NORMAL); cam.measure(app.cayresim.core.boundary.LightSnapshot(66_666_666, 3200))
        vm.onShutter()
        assertEquals(0, proc.nightRuns.size); assertEquals(1, cam.saved.size)
    }

    @Test fun `Fehlerfall Nachtaufnahme scheitert mit Meldung`() = runTest {
        visibleAndGranted(); vm.onModeSelected(ModeOption.NIGHT); proc.gpuFails = true
        vm.onShutter()
        assertEquals(MessageKind.NIGHT_FAILED, vm.uiState.value.message?.kind)
        assertEquals(SpecialStatus.IDLE, vm.uiState.value.specialStatus)
    }

    // ---------- Automatik ----------

    @Test fun `Automatik im Dunkeln nimmt den Nacht-Kern und zeigt den Hinweis`() = runTest {
        visibleAndGranted(); cam.measure(app.cayresim.core.boundary.LightSnapshot(66_666_666, 3200))
        assertTrue(vm.uiState.value.autoNight)
        vm.onShutter()
        assertEquals(1, proc.nightRuns.size); assertEquals(MessageKind.NIGHT_SAVED, vm.uiState.value.message?.kind)
    }

    @Test fun `Automatik bei Tageslicht macht ein normales Foto`() = runTest {
        visibleAndGranted(); cam.measure(app.cayresim.core.boundary.LightSnapshot(5_000_000, 50))
        assertFalse(vm.uiState.value.autoNight)
        vm.onShutter()
        assertEquals(0, proc.nightRuns.size); assertEquals(1, cam.saved.size)
    }

    @Test fun `Randfall Automatik ohne Messung macht ein normales Foto`() = runTest {
        visibleAndGranted(); vm.onShutter()
        assertEquals(0, proc.nightRuns.size); assertEquals(1, cam.saved.size)
    }

    @Test fun `Eigene Moduswahl schaltet die Automatik ab, Auto schaltet sie wieder ein`() = runTest {
        visibleAndGranted(); cam.measure(app.cayresim.core.boundary.LightSnapshot(66_666_666, 3200))
        vm.onModeSelected(ModeOption.NORMAL)
        assertEquals(ModeOption.NORMAL, vm.uiState.value.selected); assertFalse(vm.uiState.value.autoNight)
        vm.onShutter(); assertEquals(0, proc.nightRuns.size)
        vm.onModeSelected(ModeOption.AUTO)
        assertEquals(ModeOption.AUTO, vm.uiState.value.selected)
        assertEquals(app.cayresim.core.boundary.PhotoMode.NORMAL, cam.state.value.requestedMode, "Automatik misst im normalen Modus")
    }

    @Test fun `Automatik mit Spezialaufnahme folgt der Spezialaufnahme`() = runTest {
        visibleAndGranted(); cam.measure(app.cayresim.core.boundary.LightSnapshot(66_666_666, 3200))
        vm.onNextSpecial() // Menschen wegrechnen
        assertFalse(vm.uiState.value.autoNight)
        vm.onShutter(); assertEquals(0, proc.nightRuns.size); assertEquals(1, proc.stacked.size)
    }

    // ---------- Befunde der Architekturpruefung ----------

    @Test fun `H2 doppelter Druck waehrend der Nachtaufnahme startet keine zweite`() = runTest {
        visibleAndGranted(); cam.measure(app.cayresim.core.boundary.LightSnapshot(66_666_666, 3200))
        proc.nightGate = kotlinx.coroutines.CompletableDeferred()
        vm.onShutter(); vm.onShutter(); vm.onHardwareShutter()
        assertEquals(1, proc.nightStarts)
        proc.nightGate!!.complete(Unit)
        assertEquals(SpecialStatus.IDLE, vm.uiState.value.specialStatus)
        assertEquals(1, proc.nightRuns.size)
    }

    @Test fun `C2 Verlassen des Screens bricht die Nachtaufnahme ab und gibt den Ausloeser frei`() = runTest {
        visibleAndGranted(); cam.measure(app.cayresim.core.boundary.LightSnapshot(66_666_666, 3200))
        proc.nightGate = kotlinx.coroutines.CompletableDeferred()
        vm.onShutter()
        assertEquals(SpecialStatus.COLLECTING, vm.uiState.value.specialStatus)
        vm.onScreenStop()
        assertEquals(SpecialStatus.IDLE, vm.uiState.value.specialStatus)
        assertNull(manual.manualState.value.exposureNanos, "Nachtbelichtung blieb stehen")
        vm.onScreenStart()
        assertEquals(0, proc.nightRuns.size)
    }

    @Test fun `L1 Lautstaerketaste ohne laufende Kamera bleibt Lautstaerke`() = runTest {
        assertFalse(vm.onHardwareShutter())
        visibleAndGranted(); assertTrue(vm.onHardwareShutter())
    }

    @Test fun `M8 Automatik setzt den Adapter beim Start auf den normalen Modus`() = runTest {
        cam.start(); cam.selectMode(app.cayresim.core.boundary.PhotoMode.HDR); cam.stop()
        visibleAndGranted()
        assertEquals(app.cayresim.core.boundary.PhotoMode.NORMAL, cam.state.value.requestedMode)
        assertEquals(ModeOption.AUTO, vm.uiState.value.selected)
    }
}
