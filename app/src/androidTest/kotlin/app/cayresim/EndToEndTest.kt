package app.cayresim

import android.Manifest
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.cayresim.shell.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import leakcanary.DetectLeaksAfterTestSuccess
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * Testebene 5 und 6 auf dem Emulator: die ganze App mit echter Emulator-Kamera,
 * echten Adaptern und Speicherleck-Pruefung nach jedem Test.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    private val hilt = HiltAndroidRule(this)
    private val permission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(DetectLeaksAfterTestSuccess()).around(hilt).around(permission).around(compose)

    private val resolver get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    private fun appPhotoCount(): Int = resolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/CayResim%"), null,
    )?.use { it.count } ?: 0

    private fun deleteAppPhotos() {
        resolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/CayResim%"))
    }

    @Before fun setUp() { hilt.inject(); deleteAppPhotos() }
    @After fun tearDown() = deleteAppPhotos()

    private fun waitForViewfinder() = compose.waitUntil(20_000) {
        compose.onAllNodes(hasTestTag("viewfinder")).fetchSemanticsNodes().isNotEmpty()
    }

    private fun shootAndWait() {
        val before = appPhotoCount()
        compose.onNodeWithTag("shutter").performClick()
        compose.waitUntil(20_000) { appPhotoCount() > before }
    }

    @Test fun sucherStartetUndAusloesenSpeichert() {
        waitForViewfinder()
        shootAndWait()
        compose.onNodeWithText("Foto gespeichert").assertIsDisplayed()
        compose.onNodeWithTag("open_last").assertIsDisplayed()
    }

    @Test fun ohneExtensionFaelltNachtZurueckUndFotografiertTrotzdem() {
        waitForViewfinder()
        val night = compose.onAllNodes(hasTestTag("mode_NIGHT")).fetchSemanticsNodes()
        if (night.isNotEmpty()) compose.onNodeWithTag("mode_NIGHT").performClick()
        shootAndWait()
    }

    @Test fun drehenMittenImBetriebBehaeltLaufendeKamera() {
        waitForViewfinder()
        compose.activityRule.scenario.recreate()
        waitForViewfinder()
        shootAndWait()
    }

    @Test fun hintergrundUndZurueckStartetKameraNeu() {
        waitForViewfinder()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForViewfinder()
        shootAndWait()
    }

    @Test fun schnellesMehrfachAusloesenStuerztNichtAb() {
        waitForViewfinder()
        val before = appPhotoCount()
        repeat(10) { compose.onNodeWithTag("shutter").performClick() }
        compose.waitUntil(30_000) { appPhotoCount() > before }
        compose.onNodeWithTag("shutter").assertIsDisplayed()
    }

    @Test fun selbsttestLaeuftAufDemEmulatorGruen() {
        waitForViewfinder()
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("selftest_start").performClick()
        compose.waitUntil(60_000) { compose.onAllNodes(hasTestTag("selftest_summary")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Alles grün", substring = true).assertIsDisplayed()
        assertTrue(appPhotoCount() == 0, "Selbsttest muss seine Fotos loeschen")
    }

    @Test fun galerieZeigtAufgenommenesFoto() {
        waitForViewfinder()
        shootAndWait()
        compose.onNodeWithTag("open_gallery").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("photo")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("back").performClick()
        waitForViewfinder()
    }
}
