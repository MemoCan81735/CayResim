package app.cayresim.shell

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object CameraKey : NavKey
@Serializable data object GalleryKey : NavKey
@Serializable data object SettingsKey : NavKey
@Serializable data object GuideKey : NavKey
@Serializable data object SelfTestKey : NavKey
@Serializable data object MicTestKey : NavKey
@Serializable data object SweepKey : NavKey

/**
 * Navigation als eigener Zustand (Navigation 3): die AppControl besitzt den Back-Stack,
 * die UI zeigt ihn nur an. Die Kamera bleibt immer unten im Stapel.
 */
class AppControl(val backStack: NavBackStack<NavKey>) {
    fun open(key: NavKey) {
        if (backStack.lastOrNull() == key) return
        if (key == backStack.firstOrNull()) {
            while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
            return
        }
        backStack.remove(key)
        backStack.add(key)
    }

    /** true, wenn zurueckgegangen wurde; false heisst: App verlassen. */
    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }
}
