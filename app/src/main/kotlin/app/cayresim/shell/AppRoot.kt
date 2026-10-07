package app.cayresim.shell

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.cayresim.feature.camera.ui.CameraRoute
import app.cayresim.feature.gallery.ui.GalleryRoute
import app.cayresim.feature.settings.ui.SelfTestRoute

@Composable
fun AppRoot() {
    val backStack = rememberNavBackStack(CameraKey)
    val control = remember(backStack) { AppControl(backStack) }
    val activity = LocalActivity.current
    NavDisplay(
        backStack = backStack,
        onBack = { if (!control.back()) activity?.finish() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<CameraKey> {
                CameraRoute(onOpenGallery = { control.open(GalleryKey) }, onOpenSettings = { control.open(SelfTestKey) })
            }
            entry<GalleryKey> { GalleryRoute(onBack = { control.back() }) }
            entry<SelfTestKey> { SelfTestRoute(onBack = { control.back() }) }
        },
    )
}
