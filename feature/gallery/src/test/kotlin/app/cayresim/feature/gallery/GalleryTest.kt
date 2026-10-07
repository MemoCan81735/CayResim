package app.cayresim.feature.gallery

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.cayresim.core.boundary.PhotoSnapshot
import app.cayresim.core.boundary.fake.FakeMediaBoundary
import app.cayresim.core.boundary.fake.FakeSeriesBoundary
import app.cayresim.core.boundary.fake.FakeProcessingBoundary
import app.cayresim.core.boundary.SeriesResult
import app.cayresim.feature.gallery.control.GalleryMessage
import app.cayresim.feature.gallery.control.SeriesItem
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.onRoot
import app.cayresim.core.designsystem.CayResimTheme
import app.cayresim.feature.gallery.control.GalleryUiState
import app.cayresim.feature.gallery.control.GalleryViewModel
import app.cayresim.feature.gallery.control.PhotoItem
import app.cayresim.feature.gallery.ui.GalleryContent
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GalleryTest {
    @get:Rule val compose = createComposeRule()
    private val series = FakeSeriesBoundary()
    private val proc = FakeProcessingBoundary()
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun viewmodel_neueste_zuerst_und_aktualisiert() = runTest {
        val media = FakeMediaBoundary()
        val vm = GalleryViewModel(media, series, proc)
        vm.uiState.test {
            var s = awaitItem()
            while (s.loading) s = awaitItem()
            assertEquals(0, s.photos.size)
            media.photos.value = listOf(PhotoSnapshot("a", 1), PhotoSnapshot("b", 3))
            assertEquals(listOf("b", "a"), awaitItem().photos.map { it.uri })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun viewmodel_begrenzt_auf_limit() = runTest {
        val media = FakeMediaBoundary().apply { photos.value = (1..500).map { PhotoSnapshot("u$it", it.toLong()) } }
        val vm = GalleryViewModel(media, series, proc)
        vm.uiState.test {
            var s = awaitItem()
            while (s.loading) s = awaitItem()
            assertEquals(GalleryViewModel.LIMIT, s.photos.size)
            assertEquals("u500", s.photos.first().uri)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun leere_galerie_zeigt_hinweis() {
        compose.setContent { CayResimTheme { GalleryContent(GalleryUiState(loading = false), {}, onOpen = {}) } }
        compose.onNodeWithTag("empty").assertIsDisplayed()
    }

    @Test fun waehrend_laden_kein_leer_hinweis() {
        compose.setContent { CayResimTheme { GalleryContent(GalleryUiState(loading = true), {}, onOpen = {}) } }
        compose.onAllNodesWithTag("empty").assertCountEquals(0)
    }

    @Test fun antippen_oeffnet_foto() {
        var opened: String? = null
        compose.setContent { CayResimTheme { GalleryContent(GalleryUiState(false, listOf(PhotoItem("content://p/1", 0))), {}, onOpen = { opened = it }) } }
        compose.onNodeWithTag("photo").performClick()
        assertEquals("content://p/1", opened)
    }

    private suspend fun seriesWith(n: Int): Long {
        val id = (series.create("Garten") as SeriesResult.Ok).series.id
        repeat(n) { series.addPhoto(id, "p$it", it.toLong()); proc.known += "p$it" }
        return id
    }

    @Test fun zeitraffer_guter_fall() = runTest {
        val id = seriesWith(5); val vm = GalleryViewModel(FakeMediaBoundary(), series, proc)
        vm.uiState.test {
            vm.onTimelapse(id)
            var s = awaitItem(); while (s.message == null) s = awaitItem()
            assertEquals(GalleryMessage.VIDEO_SAVED, s.message); kotlin.test.assertNotNull(s.lastVideoUri)
            assertEquals(listOf("p0", "p1", "p2", "p3", "p4"), proc.lastTimelapse)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun zeitraffer_zu_wenige_fotos() = runTest {
        val id = seriesWith(1); val vm = GalleryViewModel(FakeMediaBoundary(), series, proc)
        vm.uiState.test {
            vm.onTimelapse(id)
            var s = awaitItem(); while (s.message == null) s = awaitItem()
            assertEquals(GalleryMessage.VIDEO_TOO_FEW, s.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun zeitraffer_fehler_wird_gemeldet() = runTest {
        val id = seriesWith(3); proc.known.clear(); val vm = GalleryViewModel(FakeMediaBoundary(), series, proc)
        vm.uiState.test {
            vm.onTimelapse(id)
            var s = awaitItem(); while (s.message == null) s = awaitItem()
            assertEquals(GalleryMessage.VIDEO_FAILED, s.message); kotlin.test.assertNull(s.renderingSeriesId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun zeitraffer_unbekannte_serie() = runTest {
        val vm = GalleryViewModel(FakeMediaBoundary(), series, proc)
        vm.uiState.test {
            vm.onTimelapse(999)
            var s = awaitItem(); while (s.message == null) s = awaitItem()
            assertEquals(GalleryMessage.VIDEO_TOO_FEW, s.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun zeitraffer_knopf_sendet_serie() {
        var asked: Long? = null
        compose.setContent { CayResimTheme { GalleryContent(GalleryUiState(false, series = listOf(SeriesItem(4, "Hof", 9))), {}, { asked = it }) {} } }
        compose.onNodeWithTag("timelapse_4").performClick()
        assertEquals(4L, asked)
    }

    @Test fun bild_galerie_mit_serien() {
        compose.setContent { CayResimTheme(dark = true) { GalleryContent(GalleryUiState(false, listOf(PhotoItem("a", 0)), listOf(SeriesItem(1, "Garten", 12), SeriesItem(2, "Balkon", 1)), renderingSeriesId = 1), {}, {}) {} } }
        compose.onRoot().captureRoboImage("src/test/screenshots/gallery_series.png")
    }
}
