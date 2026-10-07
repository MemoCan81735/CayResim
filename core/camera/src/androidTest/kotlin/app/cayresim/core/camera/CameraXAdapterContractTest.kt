package app.cayresim.core.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.cayresim.core.boundary.CameraStatus
import app.cayresim.core.boundary.CaptureResult
import app.cayresim.core.boundary.PhotoMode
import app.cayresim.core.boundary.contract.CameraBoundaryContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Testebene 3 und 5: derselbe Vertrag wie beim Fake, aber gegen die echte Emulator-Kamera. */
@RunWith(AndroidJUnit4::class)
class CameraXAdapterContractTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.CAMERA)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val adapter = CameraXCameraAdapter(context, Dispatchers.Main.immediate)

    @After fun tearDown() = runBlocking { adapter.stop() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    @Test fun vertragAufnahmeVorStart() = run { CameraBoundaryContract.captureBeforeStartIsNotReady(adapter) }
    @Test fun vertragStart() = run { CameraBoundaryContract.startRunsAndOffersNormal(adapter) }
    @Test fun vertragAufnahmeSpeichertUndLoescht() = run { CameraBoundaryContract.captureSavesAndDeletes(adapter) }
    @Test fun vertragStoppUndNeustart() = run { CameraBoundaryContract.stopReleasesAndRestartWorks(adapter) }
    @Test fun vertragDoppeltesStopp() = run { CameraBoundaryContract.stopTwiceIsHarmless(adapter) }
    @Test fun vertragModusUeberstehtNeustart() = run { CameraBoundaryContract.modeSurvivesRestart(adapter) }

    /** Der Emulator hat keine Extensions: jeder Extension-Modus muss sauber zurueckfallen (R14). */
    @Test fun jederExtensionModusFaelltZurueck() = run {
        adapter.start()
        val offered = adapter.state.value.offeredModes
        PhotoMode.entries.filter { it != PhotoMode.NORMAL && it !in offered }.forEach {
            CameraBoundaryContract.unavailableModeFallsBack(adapter, it)
        }
    }

    @Test fun suchlerBekommtEinenSurfaceRequest() = run {
        adapter.start()
        val handle = withTimeout(10_000) { adapter.state.first { it.preview != null } }.preview
        assertNotNull(handle)
        assertIs<androidx.camera.core.SurfaceRequest>(handle.token)
    }

    @Test fun mehrereAufnahmenHintereinander() = run {
        adapter.start()
        val uris = (1..5).map { (adapter.capture() as CaptureResult.Saved).uri }
        assertEquals(5, uris.toSet().size)
        uris.forEach { assertTrue(adapter.delete(it)) }
    }

    @Test fun moduswechselWaehrendLaufenderKamera() = run {
        adapter.start()
        repeat(10) { i -> adapter.selectMode(if (i % 2 == 0) PhotoMode.NIGHT else PhotoMode.NORMAL) }
        assertEquals(CameraStatus.RUNNING, adapter.state.value.status)
        val r = adapter.capture(); assertIs<CaptureResult.Saved>(r); adapter.delete(r.uri)
    }

    @Test fun faehigkeitenSindPlausibel() = run {
        adapter.start()
        val caps = assertNotNull(adapter.capabilities.value)
        assertEquals(PhotoMode.NORMAL, caps.modes.first())
        assertEquals(caps.modes, adapter.state.value.offeredModes)
    }

    @Test fun aufnahmeHaengtNieLaengerAlsDieZeitgrenze() = run {
        adapter.start()
        val t0 = System.currentTimeMillis()
        val r = adapter.capture()
        assertTrue(System.currentTimeMillis() - t0 < 2 * CameraXCameraAdapter.CAPTURE_TIMEOUT_MS + 5_000)
        if (r is CaptureResult.Saved) adapter.delete(r.uri)
    }

    /** Fehlerfall aus dem Selbsttest: Sucher-Flaeche geht verloren, die Aufnahme muss sich selbst heilen. */
    @Test fun aufnahmeNachVerlorenerFlaecheHeiltSich() = run {
        adapter.start()
        val req = withTimeout(10_000) { adapter.state.first { it.preview != null } }.preview!!.token as androidx.camera.core.SurfaceRequest
        val tex = android.graphics.SurfaceTexture(0).apply { setDefaultBufferSize(req.resolution.width, req.resolution.height) }
        val surface = android.view.Surface(tex)
        req.provideSurface(surface, androidx.core.content.ContextCompat.getMainExecutor(context)) { }
        kotlinx.coroutines.delay(500)
        surface.release(); tex.release() // Sucher verschwindet ohne Abmeldung
        val r = adapter.capture()
        assertIs<CaptureResult.Saved>(r, "Aufnahme muss nach Neubindung klappen, war $r")
        adapter.delete(r.uri)
    }

    // ---------- Phase 3: eigene Pipeline ----------
    @Test fun serieAusDemFrameStrom() = run {
        adapter.start()
        val r = assertIs<app.cayresim.core.boundary.BurstResult.Ok>(adapter.collect(5))
        assertTrue(r.burst.frames.size in 3..5)
        assertTrue(r.burst.width > 0 && r.burst.height > 0)
        r.burst.frames.forEach { assertEquals(r.burst.pixels * 3, it.size) }
        assertTrue(r.burst.frames.zipWithNext().any { (a, b) -> !a.contentEquals(b) } || r.burst.frames.size == 1, "Bilder muessen echte Kopien sein")
    }

    @Test fun serieOhneKameraIstNichtBereit() = run {
        assertEquals(app.cayresim.core.boundary.BurstResult.Failed(app.cayresim.core.boundary.BurstFailure.NOT_READY), adapter.collect(5))
    }

    @Test fun nachDerSerieFotografiertDieKameraNormalWeiter() = run {
        adapter.start(); adapter.collect(3)
        val r = adapter.capture(); assertIs<CaptureResult.Saved>(r); adapter.delete(r.uri)
        assertEquals(CameraStatus.RUNNING, adapter.state.value.status)
    }

    @Test fun ausloeserLaesstSichStartenUndBeenden() = run {
        adapter.start()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            adapter.trigger(app.cayresim.core.boundary.TriggerMode.MOTION).collect { }
        }
        kotlinx.coroutines.delay(2_000); job.cancel(); job.join()
        kotlinx.coroutines.delay(500)
        val r = adapter.capture(); assertIs<CaptureResult.Saved>(r, "Nach dem Ausloeser muss normal fotografiert werden, war $r"); adapter.delete(r.uri)
    }

    @Test fun loeschenUnbekannterUriLiefertFalse() = run {
        assertEquals(false, adapter.delete("content://media/external/images/media/999999999"))
        assertEquals(false, adapter.delete("kein-uri"))
    }
}
