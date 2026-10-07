package app.cayresim.feature.gallery

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.cayresim.core.boundary.PhotoSnapshot
import app.cayresim.core.boundary.fake.FakeMediaBoundary
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
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun viewmodel_neueste_zuerst_und_aktualisiert() = runTest {
        val media = FakeMediaBoundary()
        val vm = GalleryViewModel(media)
        vm.uiState.test {
            assertEquals(true, awaitItem().loading)
            assertEquals(0, awaitItem().photos.size)
            media.photos.value = listOf(PhotoSnapshot("a", 1), PhotoSnapshot("b", 3))
            assertEquals(listOf("b", "a"), awaitItem().photos.map { it.uri })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun viewmodel_begrenzt_auf_limit() = runTest {
        val media = FakeMediaBoundary().apply { photos.value = (1..500).map { PhotoSnapshot("u$it", it.toLong()) } }
        val vm = GalleryViewModel(media)
        vm.uiState.test {
            skipItems(1)
            assertEquals(GalleryViewModel.LIMIT, awaitItem().photos.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun leere_galerie_zeigt_hinweis() {
        compose.setContent { CayResimTheme { GalleryContent(GalleryUiState(loading = false), {}, {}) } }
        compose.onNodeWithTag("empty").assertIsDisplayed()
    }

    @Test fun waehrend_laden_kein_leer_hinweis() {
        compose.setContent { CayResimTheme { GalleryContent(GalleryUiState(loading = true), {}, {}) } }
        compose.onAllNodesWithTag("empty").assertCountEquals(0)
    }

    @Test fun antippen_oeffnet_foto() {
        var opened: String? = null
        compose.setContent { CayResimTheme { GalleryContent(GalleryUiState(false, listOf(PhotoItem("content://p/1", 0))), {}, { opened = it }) } }
        compose.onNodeWithTag("photo").performClick()
        assertEquals("content://p/1", opened)
    }
}
