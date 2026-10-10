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
import app.cayresim.feature.settings.ui.MicTestRoute
import app.cayresim.feature.settings.ui.SweepRoute
import app.cayresim.feature.settings.ui.SelfTestRoute
import app.cayresim.feature.settings.ui.SettingsContent
import app.cayresim.feature.settings.ui.GuideContent

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
                CameraRoute(
                    onOpenGallery = { control.open(GalleryKey) },
                    onOpenSettings = { control.open(SettingsKey) },
                    setShutterKeyListener = { (activity as? MainActivity)?.shutterKeys?.listener = it },
                )
            }
            entry<GalleryKey> { GalleryRoute(onBack = { control.back() }) }
            entry<SettingsKey> {
                SettingsContent(
                    onGuide = { control.open(GuideKey) },
                    onSelfTest = { control.open(SelfTestKey) },
                    onBack = { control.back() },
                    onMicTest = { control.open(MicTestKey) },
                )
            }
            entry<GuideKey> { GuideContent(onBack = { control.back() }) }
            entry<SelfTestKey> { SelfTestRoute(onBack = { control.back() }) }
            entry<MicTestKey> { MicTestRoute(onBack = { control.back() }, onSweep = { control.open(SweepKey) }) }
            entry<SweepKey> { SweepRoute(onBack = { control.back() }) }
        },
    )
}
