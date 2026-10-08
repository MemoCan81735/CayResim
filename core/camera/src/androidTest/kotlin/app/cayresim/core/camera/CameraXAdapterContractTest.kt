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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
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
    @Test fun vertragZoomUndFokusVorStart() = run { CameraBoundaryContract.zoomAndFocusBeforeStartAreRejected(adapter) }
    @Test fun vertragZoomInDenGrenzen() = run { CameraBoundaryContract.zoomStaysWithinLimits(adapter) }
    @Test fun vertragFokusAusserhalb() = run { CameraBoundaryContract.focusOutsideTheImageIsRejected(adapter) }

    // ---------- Bildstrom fuer den Nacht-Kern (Befunde C2, H1, T2) ----------

    @Test fun bildstromLiefertHoechstensNBilderUndGibtFrei() = run {
        adapter.start()
        val got = withTimeout(20_000) { adapter.frames(5).toList() }
        assertTrue(got.size in 1..5, "${got.size} Bilder")
        assertTrue(got.all { it.rgb.size == it.width * it.height * 3 })
        assertEquals(0, adapter.pipelineUserCount, "Pipeline nicht freigegeben")
        val r = assertIs<CaptureResult.Saved>(adapter.capture()); adapter.delete(r.uri)
    }

    @Test fun bildstromEndetWennDieKameraStoppt() = run {
        adapter.start()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async { adapter.frames(1_000).count() }
        kotlinx.coroutines.delay(2_000)
        adapter.stop()
        val n = withTimeout(10_000) { job.await() }
        assertTrue(n < 1_000, "Strom lief nach dem Stopp weiter")
        assertEquals(0, adapter.pipelineUserCount)
    }

    @Test fun abbruchWaehrendDesStartsGibtDiePipelineFrei() = run {
        adapter.start()
        repeat(3) {
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { adapter.frames(50).collect { } }
            kotlinx.coroutines.delay(50L + it * 150L) // mal waehrend des Startens, mal danach
            job.cancel(); job.join()
        }
        kotlinx.coroutines.delay(500)
        assertEquals(0, adapter.pipelineUserCount, "Zaehler blieb nach Abbruch stehen")
    }

    /** Ultra HDR im normalen Modus, wenn die Kamera es kann; sonst normales JPEG ohne Ausfall. */
    @Test fun ultraHdrNurWennUnterstuetzt() = run {
        adapter.start()
        assertEquals(adapter.capabilities.value!!.ultraHdr, adapter.ultraHdrActive)
        val r = assertIs<CaptureResult.Saved>(adapter.capture())
        adapter.delete(r.uri)
    }

    /** Fokus auf die Bildmitte bricht nie ab und haengt nicht (Emulator: Fixfokus darf false liefern). */
    @Test fun fokusAufDieMitteHaengtNicht() = run {
        adapter.start()
        withTimeout(6_000) { adapter.focusAt(0.5f, 0.5f) }
    }

    /** Der Zoom bleibt nach einem Moduswechsel erhalten (CameraX setzt ihn beim Neubinden zurueck). */
    @Test fun zoomUeberstehtModuswechsel() = run {
        adapter.start()
        val z = adapter.state.value.zoom
        val target = (z.min + z.max) / 2
        adapter.setZoom(target)
        adapter.selectMode(PhotoMode.NORMAL)
        assertEquals(target, adapter.state.value.zoom.ratio, 0.05f)
    }

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

    /**
     * Fehler vom S24+ (Selbsttest v0.1.17): ohne Sucher dauerte jede Aufnahme etwa 10 s, weil die
     * Ersatz-Flaeche den Bildstrom staute und erst die Selbstheilung half. Jetzt: ohne Wartezeit-Umweg.
     */
    @Test fun ohneSucherKeinUmwegUeberDieSelbstheilung() = run {
        adapter.start()
        kotlinx.coroutines.delay(3_000) // Bildstrom laeuft eine Weile ohne Abnehmer
        repeat(3) {
            val t0 = System.currentTimeMillis()
            val r = adapter.capture()
            val ms = System.currentTimeMillis() - t0
            assertIs<CaptureResult.Saved>(r, "Aufnahme ${it + 1} war $r")
            assertTrue(ms < CameraXCameraAdapter.CAPTURE_TIMEOUT_MS, "Aufnahme ${it + 1} brauchte $ms ms")
            adapter.delete(r.uri)
        }
    }

    /** Der Sucher-Ersatz nimmt Bilder ab wie ein echter Sucher und laesst sich mehrfach gefahrlos freigeben. */
    @Test fun sucherErsatzLaesstSichAnlegenUndFreigeben() {
        val sink = assertNotNull(FallbackPreviewSink.create(android.util.Size(640, 480)))
        assertTrue(sink.surface.isValid)
        sink.release(); sink.release()
    }

    /** Wechsel durch alle Modi ohne Sucher, mehrfach: kein Haenger, kein Leck (Ablauf des Selbsttests). */
    @Test fun selbsttestAblaufOhneSucherMehrfach() = runBlocking {
        withTimeout(90_000) {
            repeat(2) {
                adapter.start()
                for (m in adapter.capabilities.value!!.modes) {
                    adapter.selectMode(m)
                    val t0 = System.currentTimeMillis()
                    val r = adapter.capture()
                    val ms = System.currentTimeMillis() - t0
                    assertIs<CaptureResult.Saved>(r, "Modus $m war $r")
                    assertTrue(ms < CameraXCameraAdapter.CAPTURE_TIMEOUT_MS, "Modus $m brauchte $ms ms")
                    adapter.delete(r.uri)
                }
                adapter.stop()
            }
            assertEquals(0, adapter.selfHealCount, "Ablauf des Selbsttests darf die Selbstheilung nie brauchen")
        }
    }

    /**
     * Fehler vom S24+ (Selbsttest v0.1.30, je Aufnahme etwa 10 s): Moduswechsel direkt vor dem Ausloesen
     * ohne Sucher. Der Ersatz ging an die verfallene Anfrage der vorigen Bindung.
     */
    @Test fun moduswechselDirektVorDemAusloesenOhneSucher() = run {
        adapter.start()
        repeat(3) {
            adapter.selectMode(PhotoMode.NORMAL)
            val r = adapter.capture()
            assertIs<CaptureResult.Saved>(r, "Aufnahme ${it + 1} war $r")
            adapter.delete(r.uri)
        }
        assertEquals(0, adapter.selfHealCount, "Selbstheilung darf hier nicht noetig sein")
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

    // ---------- Phase 4: manuelle Kamera ----------
    @Test fun manuelleFaehigkeitenSindBekannt() = run {
        adapter.start()
        assertNotNull(adapter.manualCapabilities.value, "Camera2-Faehigkeiten muessen lesbar sein")
    }

    @Test fun belichtungUndIsoKommenInDerAufnahmeAn() = run {
        adapter.start()
        val caps = assertNotNull(adapter.manualCapabilities.value)
        if (!caps.canExpose) return@run // Kamera ohne manuelle Belichtung: Rueckfall ist im Unit-Test abgedeckt
        val exp = 10_000_000L.coerceIn(caps.exposureRangeNanos!!); val iso = 400.coerceIn(caps.isoRange!!)
        assertTrue(adapter.setExposure(exp, iso))
        kotlinx.coroutines.delay(800)
        val r = assertIs<CaptureResult.Saved>(adapter.capture())
        val exif = context.contentResolver.openInputStream(android.net.Uri.parse(r.uri))!!.use { androidx.exifinterface.media.ExifInterface(it) }
        val t = exif.getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME, -1.0)
        val s = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1)
        adapter.delete(r.uri); adapter.setExposure(null, null)
        assertTrue(kotlin.math.abs(t - exp / 1e9) < exp / 1e9 * 0.25, "Belichtungszeit in EXIF $t statt ${exp / 1e9}")
        assertEquals(iso, s, "ISO in EXIF")
    }

    @Test fun rawErzeugtGueltigesDngUndJpeg() = run {
        adapter.start()
        if (adapter.manualCapabilities.value?.raw != true) { assertEquals(false, adapter.setRaw(true)); return@run }
        assertTrue(adapter.setRaw(true))
        val r = assertIs<CaptureResult.Saved>(adapter.capture())
        val raw = assertNotNull(r.rawUri, "DNG fehlt")
        val head = context.contentResolver.openInputStream(android.net.Uri.parse(raw))!!.use { it.readNBytes(4) }
        assertTrue(head.contentEquals(byteArrayOf(0x49, 0x49, 0x2A, 0x00)) || head.contentEquals(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A)), "Kein TIFF/DNG-Kopf")
        adapter.delete(r.uri); adapter.delete(raw); adapter.setRaw(false)
    }

    @Test fun fokusreiheBeiFixfokusWirdAbgelehnt() = run {
        adapter.start()
        val caps = assertNotNull(adapter.manualCapabilities.value)
        val r = adapter.focusBracket(4)
        if (caps.canFocus) assertIs<app.cayresim.core.boundary.BurstResult.Ok>(r)
        else assertEquals(app.cayresim.core.boundary.BurstResult.Failed(app.cayresim.core.boundary.BurstFailure.NOT_READY), r)
    }

    @Test fun ungueltigeManuelleWerteWerdenBegrenzt() = run {
        adapter.start()
        val caps = assertNotNull(adapter.manualCapabilities.value)
        if (!caps.canExpose) { assertEquals(false, adapter.setExposure(1, 1)); return@run }
        assertTrue(adapter.setExposure(Long.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(caps.exposureRangeNanos!!.last, adapter.manualState.value.exposureNanos)
        adapter.setExposure(null, null)
    }

    @Test fun loeschenUnbekannterUriLiefertFalse() = run {
        assertEquals(false, adapter.delete("content://media/external/images/media/999999999"))
        assertEquals(false, adapter.delete("kein-uri"))
    }
}
