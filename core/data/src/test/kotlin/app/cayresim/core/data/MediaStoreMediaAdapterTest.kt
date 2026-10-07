package app.cayresim.core.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Fehler- und Randfaelle des Galerie-Adapters ohne Geraet (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreMediaAdapterTest {
    private val adapter = MediaStoreMediaAdapter(ApplicationProvider.getApplicationContext(), Dispatchers.Unconfined)

    @Test fun `Limit 0 oder negativ liefert leere Liste`() {
        assertEquals(emptyList(), adapter.query(0)); assertEquals(emptyList(), adapter.query(-5))
    }

    @Test fun `Leere Galerie liefert leere Liste statt Fehler`() = runTest {
        assertEquals(emptyList(), adapter.recentPhotos(10).first())
    }
}
