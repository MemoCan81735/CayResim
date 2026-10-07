package app.cayresim.feature.camera.ui

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
import app.cayresim.core.designsystem.CayResimTheme
import app.cayresim.feature.camera.control.CameraUiState
import app.cayresim.feature.camera.control.MessageKind
import app.cayresim.feature.camera.control.ModeOption
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
                onShutter = { events += "shutter" },
                onMessageShown = { events += "shown:$it" },
                onRequestPermission = { events += "permission" },
                onRetry = { events += "retry" },
                onOpenGallery = { events += "gallery" },
                onOpenSettings = { events += "settings" },
                onOpenLast = { events += "last:$it" },
                viewfinder = { Box(Modifier.fillMaxSize().background(Color(0xFF335544))) },
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

    @Test fun galerie_und_einstellungen() {
        show(running); compose.onNodeWithTag("open_gallery").performClick(); compose.onNodeWithTag("open_settings").performClick()
        assertEquals(listOf("gallery", "settings"), events)
    }
}
