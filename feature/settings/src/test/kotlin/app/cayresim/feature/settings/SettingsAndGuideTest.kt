package app.cayresim.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import app.cayresim.core.designsystem.CayResimTheme
import app.cayresim.feature.settings.ui.GuideContent
import app.cayresim.feature.settings.ui.SettingsContent
import app.cayresim.feature.settings.ui.guideSections
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsAndGuideTest {
    @get:Rule val compose = createComposeRule()
    private val events = mutableListOf<String>()

    private fun settings() = compose.setContent {
        CayResimTheme { SettingsContent(onGuide = { events += "guide" }, onSelfTest = { events += "selftest" }, onBack = { events += "back" }) }
    }

    @Test fun bild_einstellungen() { settings(); compose.onRoot().captureRoboImage("src/test/screenshots/settings.png") }

    @Test fun einstellungen_oeffnen_anleitung_und_selbsttest() {
        settings()
        compose.onNodeWithTag("settings_guide").performClick()
        compose.onNodeWithTag("settings_selftest").performClick()
        compose.onNodeWithTag("back").performClick()
        assertEquals(listOf("guide", "selftest", "back"), events)
    }

    @Test fun bild_anleitung_anfang_dunkel() {
        compose.setContent { CayResimTheme(dark = true) { GuideContent {} } }
        compose.onRoot().captureRoboImage("src/test/screenshots/guide_top.png")
    }

    @Test fun bild_anleitung_spezialaufnahmen() {
        compose.setContent { CayResimTheme(dark = false) { GuideContent {} } }
        compose.onNodeWithTag("guide").performScrollToNode(hasTestTag("guide_${R.string.guide_special_title}"))
        compose.onRoot().captureRoboImage("src/test/screenshots/guide_special.png")
    }

    @Test fun jeder_abschnitt_ist_erreichbar_und_sichtbar() {
        compose.setContent { CayResimTheme { GuideContent {} } }
        guideSections.forEach { (title, _) ->
            compose.onNodeWithTag("guide").performScrollToNode(hasTestTag("guide_$title"))
            compose.onNodeWithTag("guide_$title").assertIsDisplayed()
        }
    }

    @Test fun zurueck_aus_der_anleitung() {
        compose.setContent { CayResimTheme { GuideContent { events += "back" } } }
        compose.onNodeWithTag("back").performClick()
        assertEquals(listOf("back"), events)
    }

    /** Randfall: Hausregel, keine Gedankenstriche im Text; jeder Abschnitt hat echten Inhalt. */
    @Test fun texte_ohne_gedankenstriche_und_nicht_leer() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val all = guideSections.flatMap { listOf(it.first, it.second) } + R.string.guide_intro
        all.forEach { id ->
            val text = ctx.getString(id)
            assertTrue(text.isNotBlank(), "Text $id ist leer")
            assertFalse('–' in text || '—' in text, "Gedankenstrich in: $text")
        }
        assertEquals(guideSections.size, guideSections.map { it.first }.toSet().size, "Abschnitte doppelt")
        // Fehler aus der Sichtpruefung: Zeilenumbrueche und Anfuehrungszeichen gingen in der Ressource verloren
        assertTrue("\n• Nacht" in ctx.getString(R.string.guide_modes_body), "Aufzaehlung ohne Zeilenumbruch")
        assertTrue("\"Ohne Look\"" in ctx.getString(R.string.guide_look_body), "Anfuehrungszeichen fehlen")
    }
}
