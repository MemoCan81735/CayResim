package app.cayresim.feature.camera.ui

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.geometry.Offset
import app.cayresim.feature.camera.control.ZoomPresetUi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.cayresim.core.designsystem.CayResimTheme
import app.cayresim.feature.camera.control.CameraUiState
import app.cayresim.feature.camera.control.MessageKind
import app.cayresim.feature.camera.control.ModeOption
import app.cayresim.feature.camera.control.LookOption
import app.cayresim.feature.camera.control.SeriesOption
import app.cayresim.feature.camera.control.SpecialOption
import app.cayresim.feature.camera.control.SpecialStatus
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import app.cayresim.feature.camera.control.PermissionStatus
import app.cayresim.feature.camera.control.ScreenStatus
import app.cayresim.feature.camera.control.UserMessage
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/** Testebene 4: Screenshots aller Zustaende (Baselines im Repository) und Bedienung per Klick. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CameraContentTest {
    @get:Rule val compose = createComposeRule()

    private val running = CameraUiState(
        permission = PermissionStatus.GRANTED, status = ScreenStatus.RUNNING,
        modes = listOf(ModeOption.NORMAL, ModeOption.NIGHT, ModeOption.HDR, ModeOption.BOKEH),
        previewToken = "fake",
    )

    private val events = mutableListOf<String>()

    private fun show(state: CameraUiState, dark: Boolean = true) = compose.setContent {
        CayResimTheme(dark = dark) {
            CameraContent(
                state = state,
                onModeSelected = { events += "mode:$it" },
                onNextLook = { events += "look" },
                onNextSpecial = { events += "special" },
                onSeriesSelected = { events += "series:$it" },
                onCreateSeries = { events += "create:$it" },
                onOverlayAlpha = { },
                onShutter = { events += "shutter" },
                onMessageShown = { events += "shown:$it" },
                onRequestPermission = { events += "permission" },
                onRetry = { events += "retry" },
                onOpenGallery = { events += "gallery" },
                onOpenSettings = { events += "settings" },
                onOpenLast = { events += "last:$it" },
                onZoomPreset = { events += "zoom:$it" },
                onPinch = { events += "pinch" },
                onTapFocus = { x, y -> events += "focus:$x,$y" },
                viewfinder = { _, _ -> Box(Modifier.fillMaxSize().background(Color(0xFF335544))) },
            )
        }
    }

    // ---------- Screenshots ----------
    @Test fun bild_laeuft() { show(running); compose.onRoot().captureRoboImage("src/test/screenshots/camera_running.png") }
    @Test fun bild_hell() { show(running, dark = false); compose.onRoot().captureRoboImage("src/test/screenshots/camera_running_light.png") }
    @Test fun bild_rueckfall() {
        show(running.copy(selected = ModeOption.NIGHT, fallbackFrom = ModeOption.NIGHT))
        compose.onRoot().captureRoboImage("src/test/screenshots/camera_fallback.png")
    }
    @Test fun bild_keine_erlaubnis() {
        show(CameraUiState(permission = PermissionStatus.DENIED))
        compose.onRoot().captureRoboImage("src/test/screenshots/camera_denied.png")
    }
    @Test fun bild_kamerafehler() {
        show(CameraUiState(permission = PermissionStatus.GRANTED, status = ScreenStatus.ERROR))
        compose.onRoot().captureRoboImage("src/test/screenshots/camera_error.png")
    }
    @Test fun bild_aufnahme_laeuft() {
        show(running.copy(capturing = true))
        compose.onRoot().captureRoboImage("src/test/screenshots/camera_capturing.png")
    }
    @Test @Config(qualifiers = "+land") fun bild_quer() { show(running); compose.onRoot().captureRoboImage("src/test/screenshots/camera_landscape.png") }
    @Test @Config(fontScale = 2.0f) fun bild_grosse_schrift() {
        show(running.copy(fallbackFrom = ModeOption.HDR)); compose.onRoot().captureRoboImage("src/test/screenshots/camera_fontscale.png")
    }

    // ---------- Bedienung ----------
    @Test fun ausloeser_sendet_ereignis() {
        show(running); compose.onNodeWithTag("shutter").performClick()
        assertEquals(listOf("shutter"), events)
    }

    @Test fun ausloeser_reagiert_nicht_ohne_laufende_kamera() {
        show(running.copy(status = ScreenStatus.STARTING)); compose.onNodeWithTag("shutter").performClick()
        assertEquals(emptyList(), events)
    }

    @Test fun ausloeser_reagiert_nicht_waehrend_aufnahme() {
        show(running.copy(capturing = true)); compose.onNodeWithTag("shutter").performClick()
        assertEquals(emptyList(), events)
    }

    @Test fun modus_chip_sendet_modus() {
        show(running); compose.onNodeWithTag("mode_NIGHT").performClick()
        assertEquals(listOf("mode:NIGHT"), events)
    }

    @Test fun erlaubnis_knopf_fragt_erneut() {
        show(CameraUiState(permission = PermissionStatus.DENIED))
        compose.onNodeWithTag("permission").assertIsDisplayed()
    }

    @Test fun rueckfall_hinweis_sichtbar() {
        show(running.copy(fallbackFrom = ModeOption.NIGHT)); compose.onNodeWithTag("fallback").assertIsDisplayed()
    }

    @Test fun meldung_wird_bestaetigt() {
        show(running.copy(message = UserMessage(7, MessageKind.SAVED)))
        compose.waitUntil(5_000) { "shown:7" in events }
    }

    @Test fun letztes_foto_knopf_oeffnet_uri() {
        show(running.copy(lastPhotoUri = "content://x/1")); compose.onNodeWithTag("open_last").performClick()
        assertEquals(listOf("last:content://x/1"), events)
    }

    // Fehler vom S24+: nach dem ersten Foto waren die Einstellungen weg
    @Test fun einstellungen_bleiben_nach_foto_erreichbar() {
        show(running.copy(lastPhotoUri = "content://x/1"))
        compose.onNodeWithTag("open_last").assertExists()
        compose.onNodeWithTag("open_settings").performClick()
        assertEquals(listOf("settings"), events)
    }

    @Test fun look_knopf_sendet_ereignis() {
        show(running); compose.onNodeWithTag("look").performClick()
        assertEquals(listOf("look"), events)
    }

    @Test fun serie_waehlen_und_anlegen() {
        show(running.copy(series = listOf(SeriesOption(3, "Garten", 12))))
        compose.onNodeWithTag("series").performClick()
        compose.onNodeWithTag("series_3").performClick()
        compose.onNodeWithTag("series").performClick()
        compose.onNodeWithTag("series_name").performTextInput("Balkon")
        compose.onNodeWithTag("series_create").performClick()
        assertEquals(listOf("series:3", "create:Balkon"), events)
    }

    @Test fun bild_serie_mit_look() {
        show(running.copy(look = LookOption.FILM, series = listOf(SeriesOption(1, "Garten", 12)), selectedSeriesId = 1))
        compose.onRoot().captureRoboImage("src/test/screenshots/camera_series_look.png")
    }

    @Test fun bild_serienwahl() {
        show(running.copy(series = listOf(SeriesOption(1, "Garten", 12), SeriesOption(2, "Balkon", 3))))
        compose.onNodeWithTag("series").performClick()
        compose.onRoot().captureRoboImage("src/test/screenshots/camera_series_picker.png")
    }

    @Test fun ausloeser_unter_der_serienwahl_ist_gesperrt() {
        show(running.copy(series = listOf(SeriesOption(1, "Garten", 2))))
        compose.onNodeWithTag("series").performClick()
        compose.onNodeWithTag("shutter").performClick()
        assertEquals(emptyList(), events, "Ausloeser darf durch das Panel nicht erreichbar sein")
    }

    @Test fun bild_ausloeser_scharf() {
        show(running.copy(special = SpecialOption.TRIGGER_MOTION, specialStatus = SpecialStatus.ARMED))
        compose.onRoot().captureRoboImage("src/test/screenshots/camera_trigger_armed.png")
    }

    @Test fun ausloeser_gesperrt_waehrend_serie() {
        show(running.copy(special = SpecialOption.CLEAN_PLATE, specialStatus = SpecialStatus.COLLECTING))
        compose.onNodeWithTag("shutter").performClick()
        compose.onNodeWithTag("special_hint").assertIsDisplayed()
        assertEquals(emptyList(), events)
    }

    @Test fun scharfer_ausloeser_laesst_sich_beenden() {
        show(running.copy(special = SpecialOption.TRIGGER_STILL, specialStatus = SpecialStatus.ARMED))
        compose.onNodeWithTag("shutter").performClick()
        assertEquals(listOf("shutter"), events)
    }

    @Test fun bild_pro_panel() {
        show(running.copy(special = SpecialOption.PRO, pro = app.cayresim.feature.camera.control.ProUi(true, true, true, 0.4f, 0.2f, null, true, "1/60 s", "ISO 400")))
        compose.onRoot().captureRoboImage("src/test/screenshots/camera_pro.png")
    }

    @Test fun pro_ohne_faehigkeiten_zeigt_hinweis() {
        show(running.copy(special = SpecialOption.PRO))
        compose.onNodeWithTag("pro_panel").assertIsDisplayed()
        compose.onAllNodesWithTag("pro_exposure").assertCountEquals(0)
    }

    @Test fun galerie_und_einstellungen() {
        show(running); compose.onNodeWithTag("open_gallery").performClick(); compose.onNodeWithTag("open_settings").performClick()
        assertEquals(listOf("gallery", "settings"), events)
    }

    // ---------- Zoom, Fokus, Lautstaerketaste ----------

    private val zoomed = running.copy(zoomRatio = 2.4f, zoomPresets = listOf(
        ZoomPresetUi(0.6f, "0,6x", false), ZoomPresetUi(1f, "1x", false), ZoomPresetUi(3f, "3x", false)))

    @Test fun bild_zoom_zwischen_den_stufen() {
        show(zoomed); compose.onRoot().captureRoboImage("src/test/screenshots/camera_zoom.png")
    }

    @Test fun zoom_stufe_zeigt_genauen_wert_und_sendet() {
        show(zoomed)
        compose.onNodeWithText("2,4x").assertIsDisplayed() // 1x-Stufe zeigt den aktuellen Wert
        compose.onNodeWithTag("zoom_3x").performClick(); compose.onNodeWithTag("zoom_0,6x").performClick()
        assertEquals(listOf("zoom:3.0", "zoom:0.6"), events)
    }

    @Test fun ohne_zoom_stufen_keine_leiste() {
        show(running); compose.onAllNodesWithTag("zoom_row").assertCountEquals(0)
    }

    @Test fun bild_fokusring() {
        compose.setContent { CayResimTheme { Box(Modifier.fillMaxSize().background(Color(0xFF335544))) { FocusRing(Offset(500f, 900f)) } } }
        compose.onNodeWithTag("focus_ring").assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/camera_focus_ring.png")
    }

    @Test fun nacht_hinweis_zeigt_die_werte() {
        show(running.copy(message = UserMessage(3, MessageKind.NIGHT_SAVED,
            app.cayresim.feature.camera.control.NightInfo(100_000_000, 3200, 34, 2, 6f))))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1/10 s, ISO 3200, 34 Bilder, 2 verworfen, Aufhellung x6,0", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("gekürzt", substring = true).assertCountEquals(0)
    }

    @Test fun nacht_hinweis_zeigt_die_dauer() {
        show(running.copy(message = UserMessage(6, MessageKind.NIGHT_SAVED,
            app.cayresim.feature.camera.control.NightInfo(100_000_000, 3200, 36, 0, 12f, durationMs = 4_240))))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Aufhellung x12,0, Dauer 4,2 s", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun nacht_hinweis_zeigt_die_automatik() {
        show(running.copy(message = UserMessage(7, MessageKind.NIGHT_SAVED,
            app.cayresim.feature.camera.control.NightInfo(100_000_000, 1280, 36, 0, 1f, meterExposureNs = 50_000_000, meterIso = 640))))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1/10 s, ISO 1280 (Automatik 1/20 s, ISO 640), 36 Bilder", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun nacht_hinweis_zeigt_das_wackeln() {
        show(running.copy(message = UserMessage(8, MessageKind.NIGHT_SAVED,
            app.cayresim.feature.camera.control.NightInfo(100_000_000, 3200, 33, 3, 12f, shakePx = 12))))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("33 Bilder, 3 verworfen, Aufhellung x12,0, Wackeln bis 12 px", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun selbstausloeser_zeigt_countdown_und_schalter() {
        show(running.copy(timer = true, countdown = 2))
        compose.onNodeWithTag("countdown").assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithTag("timer").assertIsDisplayed()
        compose.onNodeWithText("Timer 2 s").assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/camera_countdown.png")
    }

    @Test fun selbstausloeser_aus_ohne_countdown() {
        show(running)
        compose.onAllNodesWithTag("countdown").assertCountEquals(0)
        compose.onNodeWithText("Timer aus").assertIsDisplayed()
    }

    @Test fun nacht_hinweis_nennt_den_raw_weg() {
        show(running.copy(message = UserMessage(5, MessageKind.NIGHT_SAVED,
            app.cayresim.feature.camera.control.NightInfo(100_000_000, 3200, 36, 0, 12f, raw = true))))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("RAW, 1/10 s, ISO 3200, 36 Bilder", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun nacht_hinweis_meldet_gekuerzte_serie() {
        show(running.copy(message = UserMessage(4, MessageKind.NIGHT_SAVED,
            app.cayresim.feature.camera.control.NightInfo(100_000_000, 3200, 23, 0, 16f, shortened = true))))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("23 Bilder, 0 verworfen, Aufhellung x16,0, Serie gekürzt", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
