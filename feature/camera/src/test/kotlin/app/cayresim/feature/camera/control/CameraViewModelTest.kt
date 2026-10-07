package app.cayresim.feature.camera.control

import app.cayresim.core.boundary.CameraError
import app.cayresim.core.boundary.CaptureFailure
import app.cayresim.core.boundary.CaptureResult
import app.cayresim.core.boundary.PhotoMode
import app.cayresim.core.boundary.fake.FakeCameraBoundary
import app.cayresim.core.control.TakePhotoUseCase
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

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        cam = FakeCameraBoundary(setOf(PhotoMode.NIGHT, PhotoMode.HDR))
        vm = CameraViewModel(cam, TakePhotoUseCase(cam))
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
}
