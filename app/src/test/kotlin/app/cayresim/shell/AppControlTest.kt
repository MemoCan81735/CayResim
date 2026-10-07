package app.cayresim.shell

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppControlTest {
    private fun control() = AppControl(NavBackStack<NavKey>(CameraKey))

    @Test fun `Oeffnen und zurueck`() {
        val c = control(); c.open(GalleryKey)
        assertEquals(listOf(CameraKey, GalleryKey), c.backStack.toList())
        assertTrue(c.back()); assertEquals(listOf<NavKey>(CameraKey), c.backStack.toList())
    }

    @Test fun `Zurueck auf der Kamera verlaesst die App`() = assertFalse(control().back())

    @Test fun `Doppeltes Oeffnen stapelt nicht`() {
        val c = control(); c.open(SelfTestKey); c.open(SelfTestKey)
        assertEquals(2, c.backStack.size)
    }

    @Test fun `Erneutes Oeffnen holt den Screen nach oben statt ihn zu verdoppeln`() {
        val c = control(); c.open(GalleryKey); c.open(SelfTestKey); c.open(GalleryKey)
        assertEquals(listOf(CameraKey, SelfTestKey, GalleryKey), c.backStack.toList())
    }

    @Test fun `Viele Schritte bleiben konsistent`() {
        val c = control(); val keys = listOf(GalleryKey, SelfTestKey, CameraKey)
        repeat(1_000) { i -> if (i % 3 == 0) c.back() else c.open(keys[i % keys.size]) }
        assertTrue(c.backStack.size in 1..3)
        assertEquals(c.backStack.toSet().size, c.backStack.size)
    }
}

class AppControlRootTest {
    @Test fun `Kamera oeffnen kehrt zur Wurzel zurueck`() {
        val c = AppControl(NavBackStack<NavKey>(CameraKey)); c.open(GalleryKey); c.open(SelfTestKey); c.open(CameraKey)
        assertEquals(listOf<NavKey>(CameraKey), c.backStack.toList())
    }

    @Test fun `Zahnrad, Anleitung und zweimal zurueck fuehren zur Kamera`() {
        val c = AppControl(NavBackStack<NavKey>(CameraKey)); c.open(SettingsKey); c.open(GuideKey)
        assertEquals(listOf(CameraKey, SettingsKey, GuideKey), c.backStack.toList())
        assertTrue(c.back()); assertTrue(c.back())
        assertEquals(listOf<NavKey>(CameraKey), c.backStack.toList())
    }

    @Test fun `Von der Anleitung direkt zum Selbsttest`() {
        val c = AppControl(NavBackStack<NavKey>(CameraKey)); c.open(SettingsKey); c.open(GuideKey); c.back(); c.open(SelfTestKey)
        assertEquals(listOf(CameraKey, SettingsKey, SelfTestKey), c.backStack.toList())
    }
}
